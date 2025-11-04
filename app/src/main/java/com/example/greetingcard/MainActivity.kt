package com.gtm.vpointer

import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Confi

guration
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi

class MainActivity : ComponentActivity() {
    private var isPointerViewAttached = false
    private var isShow = false
    private lateinit var pointerImageView: ImageView
    private lateinit var udpReceiver: UdpReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestOverlayPermission()

        udpReceiver = UdpReceiver { abs_x, abs_y, show_int, downing_int, _ ->
            runOnUiThread {
                if (show_int == 1) {
                    if (!isShow) {
                        isShow = true
                        showPointer()
                    }
                    updatePointerPosition(abs_x, abs_y)
                    if (downing_int == 1) {
                        pointerImageView.scaleX = 0.75f
                        pointerImageView.scaleY = 0.75f
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
        }
        udpReceiver.startReceiving()
        createFloatingPointer()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createFloatingPointer() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        pointerImageView = ImageView(this)
        pointerImageView.setImageBitmap(BitmapFactory.decodeResource(resources, R.drawable.pointer_arrow))
        pointerImageView.alpha = 0f // Make it transparent initially
        pointerImageView.pivotX = 0f
        pointerImageView.pivotY = 0f

        params.x = 0
        params.y = 0

        try {
            windowManager.addView(pointerImageView, params)
            isPointerViewAttached = true
        } catch (e: Exception) {
            Log.e("MainActivity", "无法添加悬浮窗: ${e.message}")
        }
    }

    private fun updatePointerPosition(abs_x: Int, abs_y: Int) {
        if (!isPointerViewAttached) return
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val params = pointerImageView.layoutParams as WindowManager.LayoutParams
        params.x = abs_x
        params.y = abs_y
        windowManager.updateViewLayout(pointerImageView, params)
    }

    // Animate to opaque
    private fun showPointer() {
        if (isPointerViewAttached) {
            val animator = ObjectAnimator.ofFloat(pointerImageView, "alpha", pointerImageView.alpha, 1f)
            animator.duration = 200
            animator.start()
        }
        sendDeviceOrientation()
    }

    // Animate to transparent
    private fun removePointer() {
        if (isPointerViewAttached) {
            val animator = ObjectAnimator.ofFloat(pointerImageView, "alpha", pointerImageView.alpha, 0f)
            animator.duration = 200
            animator.start()
            isShow = false
        }
        sendDeviceOrientation()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sendDeviceOrientation()
    }

    private fun sendDeviceOrientation() {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation
        } else {
            windowManager.defaultDisplay.rotation
        }
        val orientation: Byte = when (rotation) {
            Surface.ROTATION_0 -> 0x00
            Surface.ROTATION_90 -> 0x01
            Surface.ROTATION_180 -> 0x02
            Surface.ROTATION_270 -> 0x03
            else -> 0x00
        }
        udpReceiver.sendOrientation(orientation)
    }


    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 1234
    }
}