package com.gtm.vpointer

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
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
     *
     * 还有一个陷阱：第三方录屏/投屏 app（如 MediaProjection 采集类）创建的
     * 虚拟屏同样能携带 FLAG_PRESENTATION（实测 TB322FC 上 com.example.ndiscreensender
     * 创建的 "VortexVisualCapture" 虚拟屏就带该标志），单靠 flag 无法区分。
     * 故再用 getDeviceProductInfo() 复核：物理屏有 EDID 派生信息（非 null），
     * 虚拟屏没有物理设备，恒为 null（dumpsys display 可验证）。该 API 需要
     * API 31+；更低版本退回反射 Display.getType()（隐藏 API，尽力而为），
     * 反射被 ROM 拦截时保守放行，避免误杀真外接屏。
     */
    fun getAllDisplays(): List<DisplayInfo> {
        return displayManager.displays
            .filter {
                it.displayId == Display.DEFAULT_DISPLAY ||
                    (it.flags and Display.FLAG_PRESENTATION) != 0 && isPhysicalDisplay(it)
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

    /**
     * 物理屏判定：有 EDID 派生产品信息的是物理屏；虚拟屏（录屏/投屏创建的）
     * 没有物理设备，getDeviceProductInfo() 恒为 null。
     */
    private fun isPhysicalDisplay(display: Display): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return display.getDeviceProductInfo() != null
        }
        // API < 31：getType() 是隐藏 API，反射尝试；TYPE_VIRTUAL 字面值 5。
        return try {
            display.javaClass.getMethod("getType").invoke(display) != 5
        } catch (e: Exception) {
            true // 反射被拦截时保守放行，回退到仅按 FLAG_PRESENTATION 判定的旧行为
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
