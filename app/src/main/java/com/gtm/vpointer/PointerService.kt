package com.gtm.vpointer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.ImageView
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class PointerService : Service() {

    private var windowManager: WindowManager? = null
    private var pointerImageView: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    private var isPointerViewAttached = false
    private var isShow = false

    private val socket = DatagramSocket(6533)
    private val clients = mutableSetOf<Pair<InetAddress, Int>>()
    private lateinit var orientationEventListener: OrientationEventListener
    private var lastRotation = -1

    private var targetDisplayId = Display.DEFAULT_DISPLAY
    private lateinit var displayManagerHelper: DisplayManagerHelper
    private var displayListener: DisplayManager.DisplayListener? = null

    override fun onCreate() {
        super.onCreate()
        displayManagerHelper = DisplayManagerHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val displayId = intent?.getIntExtra(MainActivity.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
            ?: Display.DEFAULT_DISPLAY
        android.util.Log.d("PointerService", "onStartCommand called with displayId: $displayId")

        // 如果显示器 ID 变化，重新创建窗口
        if (displayId != targetDisplayId) {
            android.util.Log.d("PointerService", "Display changed from $targetDisplayId to $displayId, recreating pointer")
            removeExistingPointer()
            targetDisplayId = displayId
            createFloatingPointer()
        } else if (pointerImageView == null) {
            android.util.Log.d("PointerService", "pointerImageView is null, creating new one")
            createFloatingPointer()
        }

        startUdpReceiver()
        startOrientationListener()
        startDisplayListener()

        android.util.Log.d("PointerService", "onStartCommand completed, returning START_STICKY")
        return START_STICKY
    }

    private fun createFloatingPointer() {
        android.util.Log.d("PointerService", "createFloatingPointer called, targetDisplayId: $targetDisplayId")
        val display = displayManagerHelper.getDisplayById(targetDisplayId)
        android.util.Log.d("PointerService", "Display found: ${display?.displayId}, name: ${display?.name}")

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        // 如果目标显示器不存在，回退到默认显示器
        if (display == null) {
            android.util.Log.w("PointerService", "Target display not found, falling back to DEFAULT_DISPLAY")
            targetDisplayId = Display.DEFAULT_DISPLAY
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } else {
            try {
                android.util.Log.d("PointerService", "Creating window context for display: ${display.displayId}")
                // createDisplayContext 只调整资源指标，其 WindowManager 不持有绑定到目标
                // 显示器的 window token，addView 的覆盖层窗口仍会落到默认显示器上。
                // 必须使用 createWindowContext 才能把非 Activity 窗口正确添加到指定显示器。
                val windowContext = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        createWindowContext(display, overlayType, null)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                        createDisplayContext(display).createWindowContext(overlayType, null)
                    else ->
                        createDisplayContext(display)
                }
                android.util.Log.d("PointerService", "Window context created successfully")
                windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                android.util.Log.d("PointerService", "WindowManager obtained from window context")
            } catch (e: Exception) {
                android.util.Log.e("PointerService", "Failed to create window context", e)
                targetDisplayId = Display.DEFAULT_DISPLAY
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        pointerImageView = ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeResource(resources, R.drawable.pointer_arrow))
            alpha = 0f
            pivotX = 0f
            pivotY = 0f
        }

        isPointerViewAttached = false
        isShow = false
    }

    private fun removeExistingPointer() {
        if (isPointerViewAttached) {
            try {
                windowManager?.removeView(pointerImageView)
            } catch (e: Exception) {
                // Ignore if view is not attached
            }
            isPointerViewAttached = false
        }
        pointerImageView = null
        params = null
        windowManager = null
    }

    private fun startDisplayListener() {
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                // 新显示器插入，不需要处理
            }

            override fun onDisplayRemoved(displayId: Int) {
                // 如果目标显示器被移除，回退到主屏幕
                if (displayId == targetDisplayId) {
                    Handler(Looper.getMainLooper()).post {
                        removeExistingPointer()
                        targetDisplayId = Display.DEFAULT_DISPLAY
                        createFloatingPointer()
                    }
                }
            }

            override fun onDisplayChanged(displayId: Int) {
                // 显示器属性变化，不需要处理
            }
        }
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    private fun startUdpReceiver() {
        GlobalScope.launch {
            val buffer = ByteArray(1024)
            while (true) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    clients.add(Pair(packet.address, packet.port))
                    val data = String(packet.data, 0, packet.length)
                    val values = data.split(",")
                    if (values.size == 5) {
                        val abs_x = values[0].toInt()
                        val abs_y = values[1].toInt()
                        val show_int = values[2].toInt()
                        val downing_int = values[3].toInt()

                        Handler(Looper.getMainLooper()).post {
                            handlePointer(abs_x, abs_y, show_int, downing_int)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun startOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = getDeviceRotation()
                if (rotation != lastRotation) {
                    lastRotation = rotation
                    sendDeviceOrientation(rotation)
                }
            }
        }
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
    }

    private fun handlePointer(abs_x: Int, abs_y: Int, show_int: Int, downing_int: Int) {
        if (show_int == 1) {
            if (!isShow) {
                showPointer()
            }
            updatePointerPosition(abs_x, abs_y)
            if (downing_int == 1) {
                pointerImageView?.scaleX = 0.95f
                pointerImageView?.scaleY = 0.95f
                sendDeviceOrientation(getDeviceRotation())
            } else {
                pointerImageView?.scaleX = 1.0f
                pointerImageView?.scaleY = 1.0f
            }
        } else {
            if (isShow) {
                removePointer()
            }
        }
    }

    private fun showPointer() {
        if (!isPointerViewAttached && pointerImageView != null && params != null) {
            try {
                windowManager?.addView(pointerImageView, params)
                isPointerViewAttached = true
                sendDeviceOrientation(getDeviceRotation())
                // 诊断：addView 之后查看窗口真正落在了哪块屏幕上
                pointerImageView?.post {
                    val actual = pointerImageView?.display
                    android.util.Log.d(
                        "PointerService",
                        "DIAG after addView -> targetDisplayId=$targetDisplayId, " +
                                "view.display.id=${actual?.displayId}, view.display.name=${actual?.name}"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("PointerService", "addView failed", e)
                e.printStackTrace()
            }
        }
        pointerImageView?.let { imageView ->
            val animator = ObjectAnimator.ofFloat(imageView, "alpha", imageView.alpha, 1f)
            animator.duration = 200
            animator.start()
        }
        isShow = true
    }

    private fun removePointer() {
        pointerImageView?.let { imageView ->
            val animator = ObjectAnimator.ofFloat(imageView, "alpha", imageView.alpha, 0f)
            animator.duration = 200
            animator.start()
        }
        isShow = false
    }

    private fun updatePointerPosition(x: Int, y: Int) {
        if (isPointerViewAttached && params != null) {
            params?.x = x
            params?.y = y
            try {
                windowManager?.updateViewLayout(pointerImageView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getDeviceRotation(): Int {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay
        }
        return display?.rotation ?: Surface.ROTATION_0
    }

    private fun sendDeviceOrientation(rotation: Int) {
        val orientationByte: Byte = when (rotation) {
            Surface.ROTATION_0 -> 0x00
            Surface.ROTATION_90 -> 0x01
            Surface.ROTATION_180 -> 0x02
            Surface.ROTATION_270 -> 0x03
            else -> 0x00
        }
        GlobalScope.launch {
            clients.forEach { (address, port) ->
                try {
                    val data = byteArrayOf(orientationByte)
                    val packet = DatagramPacket(data, data.size, address, port)
                    socket.send(packet)
                } catch (e: Exception) {
                    // Ignore send errors
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeExistingPointer()
        socket.close()
        if (::orientationEventListener.isInitialized) {
            orientationEventListener.disable()
        }
        displayListener?.let {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.unregisterDisplayListener(it)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
