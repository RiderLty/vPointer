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

    private lateinit var windowManager: WindowManager
    private lateinit var pointerImageView: ImageView
    private lateinit var params: WindowManager.LayoutParams

    private var isPointerViewAttached = false
    private var isShow = false

    private val socket = DatagramSocket(6533)
    private val clients = mutableSetOf<Pair<InetAddress, Int>>()
    private lateinit var orientationEventListener: OrientationEventListener
    private var lastRotation = -1

    override fun onCreate() {
        super.onCreate()
        createFloatingPointer()
        startUdpReceiver()
        startOrientationListener()
    }

    private fun createFloatingPointer() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0

        pointerImageView = ImageView(this)
        pointerImageView.setImageBitmap(BitmapFactory.decodeResource(resources, R.drawable.pointer_arrow))
        pointerImageView.alpha = 0f
        pointerImageView.pivotX = 0f
        pointerImageView.pivotY = 0f
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
                pointerImageView.scaleX = 0.95f
                pointerImageView.scaleY = 0.95f
                sendDeviceOrientation(getDeviceRotation())
            } else {
                pointerImageView.scaleX = 1.0f
                pointerImageView.scaleY = 1.0f
            }
        } else {
            if (isShow) {
                removePointer()
            }
        }
    }

    private fun showPointer() {
        if (!isPointerViewAttached) {
            windowManager.addView(pointerImageView, params)
            isPointerViewAttached = true
            sendDeviceOrientation(getDeviceRotation())
        }
        val animator = ObjectAnimator.ofFloat(pointerImageView, "alpha", pointerImageView.alpha, 1f)
        animator.duration = 200
        animator.start()
        isShow = true
    }

    private fun removePointer() {
        val animator = ObjectAnimator.ofFloat(pointerImageView, "alpha", pointerImageView.alpha, 0f)
        animator.duration = 200
        animator.start()
        isShow = false
    }

    private fun updatePointerPosition(x: Int, y: Int) {
        if (isPointerViewAttached) {
            params.x = x
            params.y = y
            windowManager.updateViewLayout(pointerImageView, params)
        }
    }

    private fun getDeviceRotation(): Int {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
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
        if (isPointerViewAttached) {
            windowManager.removeView(pointerImageView)
        }
        socket.close()
        orientationEventListener.disable()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
