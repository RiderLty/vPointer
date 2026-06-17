package com.gtm.vpointer

import android.animation.ObjectAnimator
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class PointerService : Service() {

    // 抽象出渲染器：内置屏用 WindowManager 覆盖层，外接屏用 Presentation
    private var renderer: PointerRenderer? = null

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
        } else if (renderer == null) {
            android.util.Log.d("PointerService", "renderer is null, creating new one")
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

        // 目标显示器不存在，回退到内置屏覆盖层
        if (display == null) {
            android.util.Log.w("PointerService", "Target display not found, falling back to DEFAULT_DISPLAY")
            targetDisplayId = Display.DEFAULT_DISPLAY
            renderer = OverlayRenderer(this)
            isShow = false
            return
        }

        // 内置屏：用 WindowManager 覆盖层（已验证可用）。
        // 外接屏：普通应用无法把覆盖层窗口加到副屏（系统会静默重定位回内置屏），
        //         必须使用 Presentation 才能在指定 Display 上绘制。
        renderer = if (display.displayId == Display.DEFAULT_DISPLAY) {
            android.util.Log.d("PointerService", "Using OverlayRenderer for default display")
            OverlayRenderer(this)
        } else {
            android.util.Log.d("PointerService", "Using PresentationRenderer for display ${display.displayId}")
            PresentationRenderer(this, display)
        }
        isShow = false
    }

    private fun removeExistingPointer() {
        renderer?.destroy()
        renderer = null
        isShow = false
    }

    private fun startDisplayListener() {
        if (displayListener != null) return
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
        if (::orientationEventListener.isInitialized) return
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
            renderer?.setPosition(abs_x, abs_y)
            if (downing_int == 1) {
                renderer?.setScale(0.95f)
                sendDeviceOrientation(getDeviceRotation())
            } else {
                renderer?.setScale(1.0f)
            }
        } else {
            if (isShow) {
                removePointer()
            }
        }
    }

    private fun showPointer() {
        renderer?.show()
        sendDeviceOrientation(getDeviceRotation())
        isShow = true
    }

    private fun removePointer() {
        renderer?.hide()
        isShow = false
    }

    private fun getDeviceRotation(): Int {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
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

    // ---- 渲染器抽象 ----

    private interface PointerRenderer {
        fun show()
        fun hide()
        fun setPosition(x: Int, y: Int)
        fun setScale(scale: Float)
        fun destroy()
    }

    /** 创建光标 ImageView，左上角为锚点 */
    private fun createPointerImageView(): ImageView {
        return ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeResource(resources, R.drawable.pointer_arrow))
            alpha = 0f
            pivotX = 0f
            pivotY = 0f
        }
    }

    private fun fade(view: View, to: Float) {
        val animator = ObjectAnimator.ofFloat(view, "alpha", view.alpha, to)
        animator.duration = 200
        animator.start()
    }

    /**
     * 计算目标显示器相对内置屏的密度缩放比例。
     * 光标位图是固定像素尺寸，在低 DPI 大屏上会显得过大；按密度比例缩放后，
     * 光标在外接屏上的物理大小与内置屏（手机）保持一致，无需用户手动调整。
     */
    private fun densityScaleFor(display: Display): Float {
        val internalDpi = resources.displayMetrics.densityDpi
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val targetDpi = metrics.densityDpi
        if (internalDpi <= 0 || targetDpi <= 0) return 1f
        return targetDpi.toFloat() / internalDpi.toFloat()
    }

    /** 内置屏：WindowManager 覆盖层 */
    private inner class OverlayRenderer(context: Context) : PointerRenderer {
        private val windowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val imageView = createPointerImageView()
        private val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
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
        private var attached = false

        override fun show() {
            if (!attached) {
                try {
                    windowManager.addView(imageView, params)
                    attached = true
                } catch (e: Exception) {
                    android.util.Log.e("PointerService", "Overlay addView failed", e)
                }
            }
            fade(imageView, 1f)
        }

        override fun hide() {
            fade(imageView, 0f)
        }

        override fun setPosition(x: Int, y: Int) {
            if (!attached) return
            params.x = x
            params.y = y
            try {
                windowManager.updateViewLayout(imageView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun setScale(scale: Float) {
            imageView.scaleX = scale
            imageView.scaleY = scale
        }

        override fun destroy() {
            if (attached) {
                try {
                    windowManager.removeView(imageView)
                } catch (e: Exception) {
                    // ignore
                }
                attached = false
            }
        }
    }

    /** 外接屏：Presentation（绑定到指定 Display） */
    private inner class PresentationRenderer(
        context: Context,
        display: Display
    ) : PointerRenderer {
        // 按目标显示器密度缩放，使光标物理大小与内置屏一致
        private val baseScale = densityScaleFor(display)
        private val imageView = createPointerImageView()
        private val container = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                imageView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                )
            )
        }
        private val presentation = Presentation(context, display).apply {
            setCancelable(false)
            setContentView(container)
            window?.apply {
                // 不要调用 setType 覆盖窗口类型：Presentation 内部已用
                // TYPE_PRESENTATION(2037) 创建了绑定到目标 Display 的 window context，
                // 强行改成 TYPE_APPLICATION_OVERLAY(2038) 会导致 window type mismatch 异常。
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
            }
        }
        private var shown = false

        override fun show() {
            if (!shown) {
                try {
                    presentation.show()
                    shown = true
                    android.util.Log.d(
                        "PointerService",
                        "Presentation shown on display ${presentation.display?.displayId}, baseScale=$baseScale"
                    )
                } catch (e: Exception) {
                    android.util.Log.e("PointerService", "Presentation show failed", e)
                }
            }
            imageView.scaleX = baseScale
            imageView.scaleY = baseScale
            fade(imageView, 1f)
        }

        override fun hide() {
            fade(imageView, 0f)
        }

        override fun setPosition(x: Int, y: Int) {
            imageView.translationX = x.toFloat()
            imageView.translationY = y.toFloat()
        }

        override fun setScale(scale: Float) {
            // 按压系数叠加到密度基准缩放上
            imageView.scaleX = baseScale * scale
            imageView.scaleY = baseScale * scale
        }

        override fun destroy() {
            try {
                presentation.dismiss()
            } catch (e: Exception) {
                // ignore
            }
            shown = false
        }
    }
}
