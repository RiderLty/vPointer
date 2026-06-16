package com.gtm.vpointer

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display

data class DisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isInternal: Boolean,
    val state: Int
)

class DisplayManagerHelper(private val context: Context) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var listener: DisplayManager.DisplayListener? = null

    fun getAllDisplays(): List<DisplayInfo> {
        return displayManager.displays.map { display ->
            val mode = display.mode
            DisplayInfo(
                displayId = display.displayId,
                name = display.name ?: "Unknown Display",
                width = mode.physicalWidth,
                height = mode.physicalHeight,
                isInternal = display.displayId == Display.DEFAULT_DISPLAY,
                state = display.state
            )
        }
    }

    fun getDisplayById(displayId: Int): Display? {
        return displayManager.getDisplay(displayId)
    }

    fun registerDisplayListener(onChanged: () -> Unit) {
        listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                onChanged()
            }

            override fun onDisplayRemoved(displayId: Int) {
                onChanged()
            }

            override fun onDisplayChanged(displayId: Int) {
                onChanged()
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
    }

    fun unregisterDisplayListener() {
        listener?.let {
            displayManager.unregisterDisplayListener(it)
            listener = null
        }
    }
}
