package com.gtm.vpointer

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display

data class DisplayInfo(
    val displayId: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val isInternal: Boolean,
    /** Display.TYPE_INTERNAL / TYPE_EXTERNAL / TYPE_HDMI / TYPE_WIFI / TYPE_OVERLAY / TYPE_VIRTUAL */
    val type: Int,
    val state: Int,
    val densityDpi: Int,
    val refreshRate: Float
)

class DisplayManagerHelper(private val context: Context) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var listener: DisplayManager.DisplayListener? = null

    /**
     * 列出可用于光标渲染的显示器，已排除虚拟显示器。
     *
     * 录屏软件（MediaProjection / createVirtualDisplay）会创建 TYPE_VIRTUAL 虚拟显示器：
     * - 公开（public）的会出现在 getDisplays() 里，若不排除会被误认为外接屏；
     * - 私有（private，如系统录屏）的不会出现在 getDisplays()，但 DisplayListener
     *   的 onDisplayAdded 仍会回调——所以判定外接屏必须走本枚举（按 type 过滤），
     *   不能只看 onDisplayAdded 的 displayId 是否非默认屏，否则系统录屏会"不显示
     *   在列表里却触发方向恒为 0"。
     */
    fun getAllDisplays(): List<DisplayInfo> {
        return displayManager.displays
            .filter { it.type != Display.TYPE_VIRTUAL }
            .map { display ->
            val mode = display.mode
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics)
            DisplayInfo(
                displayId = display.displayId,
                name = display.name ?: "Unknown Display",
                width = mode.physicalWidth,
                height = mode.physicalHeight,
                isInternal = display.displayId == Display.DEFAULT_DISPLAY,
                type = display.type,
                state = display.state,
                densityDpi = metrics.densityDpi,
                refreshRate = display.refreshRate
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
