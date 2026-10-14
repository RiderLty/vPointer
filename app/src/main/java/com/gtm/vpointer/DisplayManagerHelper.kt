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
    val state: Int,
    val densityDpi: Int,
    val refreshRate: Float
)

class DisplayManagerHelper(private val context: Context) {

    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var listener: DisplayManager.DisplayListener? = null

    /**
     * 列出可用于光标渲染的显示器：内置屏 + 带 FLAG_PRESENTATION 的屏。
     *
     * 注意：不能用 Display.getType() 区分虚拟显示器——它不是公开 SDK API
     * （android.jar 里没有，编译报 Unresolved reference）。改用公开的
     * FLAG_PRESENTATION 标志（API 17+）：HDMI/DP/无线投屏等真实外接屏都带
     * （Presentation 能在其上渲染的前提），而录屏软件用 MediaProjection /
     * createVirtualDisplay 创建的虚拟屏不带。
     *
     * 另一个平台陷阱：系统录屏创建的私有（private）虚拟显示器不会出现在
     * getDisplays() 里，但 DisplayListener 的 onDisplayAdded 照常回调——所以
     * 判定外接屏接入必须走本枚举复查，不能只看回调的 displayId 是否非默认屏，
     * 否则系统录屏会"不显示在列表里却触发方向恒为 0"。
     */
    fun getAllDisplays(): List<DisplayInfo> {
        return displayManager.displays
            .filter {
                it.displayId == Display.DEFAULT_DISPLAY ||
                    (it.flags and Display.FLAG_PRESENTATION) != 0
            }
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
