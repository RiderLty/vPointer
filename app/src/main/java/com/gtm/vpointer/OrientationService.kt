package com.gtm.vpointer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import android.os.Build

class OrientationService : Service() {

    private lateinit var orientationEventListener: OrientationEventListener
    private var lastRotation = -1

    override fun onCreate() {
        super.onCreate()

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

    override fun onDestroy() {
        super.onDestroy()
        orientationEventListener.disable()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun getDeviceRotation(): Int {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        }
        return display?.rotation ?: Surface.ROTATION_0
    }

    private fun sendDeviceOrientation(rotation: Int) {
        val orientation: Byte = when (rotation) {
            Surface.ROTATION_0 -> 0x00
            Surface.ROTATION_90 -> 0x01
            Surface.ROTATION_180 -> 0x02
            Surface.ROTATION_270 -> 0x03
            else -> 0x00
        }
        UdpReceiver.sendOrientation(orientation)
    }
}
