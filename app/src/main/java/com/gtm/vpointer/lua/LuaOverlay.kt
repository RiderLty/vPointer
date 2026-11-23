package com.gtm.vpointer.lua

import android.animation.ObjectAnimator
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Lua 日志的悬浮渲染器。两块窗口，**不能合并**：
 *
 *   窗口 A 文字窗：日志文字，正常态带 FLAG_NOT_TOUCHABLE → 手指完全穿透到下层游戏
 *   窗口 B 按钮条：「隐/显」「清」「拖/完」三个按钮，**故意不带** NOT_TOUCHABLE → 可点
 *
 * `FLAG_NOT_TOUCHABLE` 是**窗口级**而非 View 级，按钮塞进同一个窗口就同样点不到，
 * 所以沿用 lua-overlay 的双窗方案；渲染骨架照 [com.gtm.vpointer.PointerService] 的
 * PointerRenderer：内置屏走覆盖层，外接屏走 Presentation（+4px 偏移绕开不认 flag 的
 * DecorView），按钮条始终挂内置屏。
 *
 * 已验证的两个平台细节（都踩过）：
 *
 * 1. **编辑模式的手势捕获**：文字窗在覆盖层路径下恒为 MATCH_PARENT 全屏窗口，
 *    日志框只是窗口内带 margin 的子 View。拖动改 margin、窗口不动，手指永远不会
 *    「跑出窗口」把手势漏给下层 App（早期 WRAP_CONTENT 小窗口边拖边移动自己会漏）。
 * 2. **全屏编辑窗口会挡住应用自己的按钮**，所以按钮条上必须有「拖/完」这个永远点得到的出口。
 *
 * 已知实现要点（透明度相关，照 lua-overlay 的做法）：
 *   - 日志框底色**不透明**黑，透明度用 `textView.alpha`（view alpha 一定生效）；
 *   - 文字窗 `LayoutParams.alpha` 必须显式设成 1f，否则窗口默认的 0.8 会把差异乘掉。
 */
class LuaOverlay(
    private val context: Context,
    private val display: Display,
    private val store: LuaLogStore,
    private val onToggleHidden: () -> Unit,
    private val onClear: () -> Unit,
    private val onToggleEdit: () -> Unit
) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dm = context.resources.displayMetrics

    private val isExternal = display.displayId != Display.DEFAULT_DISPLAY

    private var x = store.x
    private var y = store.y
    private var fontSp = store.fontSp
    private var opacity = store.opacity

    private var hidden = store.hidden
    private var editMode = false
    /** 编辑模式下的占位提示是否是我们写进去的（退出时只清自己写的） */
    private var editHintShown = false

    private val textView: TextView = buildTextView()

    /** 覆盖层路径的窗口根 View（全屏透明），承载文字并负责接手势 */
    private var root: FrameLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var toggleLabel: TextView? = null
    private var editLabel: TextView? = null
    private var barView: LinearLayout? = null
    private var barParams: WindowManager.LayoutParams? = null
    private var presentation: Presentation? = null

    // ---------------------------------------------------------------- 生命周期

    fun show() {
        if (isExternal) showOnPresentation() else showAsOverlay()
        showBar()
        applyTextVisibility()
        // 与虚拟光标一致：显示时淡入，而不是硬蹦出来（目标值 = 当前不透明度）
        textView.alpha = 0f
        fade(textView, alphaOf(opacity))
    }

    fun destroy() {
        barView?.let { v -> runCatching { wm.removeView(v) } }
        barView = null
        barParams = null

        val p = presentation
        presentation = null
        if (p != null) {
            runCatching { p.dismiss() }
        } else {
            root?.let { r -> runCatching { wm.removeView(r) } }
            root = null
            overlayParams = null
        }
    }

    fun setText(text: String) {
        textView.text = text
        if (!hidden) applyTextVisibility()
        positionBar()
    }

    fun clearText() {
        textView.text = ""
        positionBar()
    }

    fun setHidden(value: Boolean) {
        hidden = value
        store.hidden = value
        if (hidden && !editMode) {
            // 隐藏用淡出，200ms，和 PointerService 的光标淡入淡出一致
            fade(textView, 0f)
            textView.postDelayed({ if (hidden && !editMode) textView.visibility = View.GONE }, FADE_MS)
        } else {
            textView.visibility = View.VISIBLE
            if (textView.alpha < alphaOf(opacity)) fade(textView, alphaOf(opacity))
        }
        toggleLabel?.text = if (hidden) "显" else "隐"
        positionBar()
    }

    fun isHidden(): Boolean = hidden

    /** 编辑位置模式：去掉穿透、允许拖动日志框（页面按钮 / 通知栏 / 悬浮条三处共用） */
    fun setEditMode(value: Boolean) {
        if (editMode == value) return
        editMode = value
        if (editMode) {
            // 空日志时框体是 0 尺寸，给一个可抓取的最小区域，否则根本拖不动
            textView.minWidth = dp(200)
            textView.minHeight = dp(72)
            if (textView.text.isNullOrEmpty()) {
                editHintShown = true
                textView.text = EDIT_HINT
            }
        } else {
            textView.minWidth = 0
            textView.minHeight = 0
            if (editHintShown) {
                editHintShown = false
                if (textView.text.toString() == EDIT_HINT) textView.text = ""
            }
        }
        rebuildTextFlags()
        applyTextVisibility()
        editLabel?.text = if (editMode) "完" else "拖"
        positionBar()
    }

    fun isEditMode(): Boolean = editMode

    // ---------------------------------------------------------------- 内容

    private fun buildTextView(): TextView = TextView(context).apply {
        setTextColor(TEXT_COLOR)
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat())
        // 与 lua-overlay 一致：不透明黑底 + view alpha 控透明度（见 logBackground 注释）
        background = logBackground()
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setLineSpacing(0f, 1.08f)
        maxWidth = (dm.widthPixels * 0.80f).toInt()
        gravity = Gravity.START or Gravity.TOP
        includeFontPadding = false
        isClickable = false
        isFocusable = false
        alpha = alphaOf(opacity)
        text = ""
    }

    /**
     * 背景：**不透明**黑（圆角保留）。
     *
     * 透明度不能用颜色 alpha —— 实测 `setColor(Color.argb(opacity, …))` 与
     * `Drawable.setAlpha()` 在这个覆盖层窗口里都被吃掉（0/190/255 渲染一致）。
     * 照 lua-overlay 的做法：底色不透明，透明度交给 `textView.alpha`（view alpha 一定生效），
     * 同时把窗口自身的 `lp.alpha` 显式压到 1f，否则窗口默认的 0.8 会把差异乘没。
     */
    private fun logBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(6).toFloat()
        setColor(Color.BLACK)
        if (editMode) {
            setStroke(dp(1).coerceAtLeast(1), Color.argb(0xCC, 0xFF, 0xB3, 0x00))
        }
    }

    private fun textWindowFlags(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // 编辑模式下窗口要能接住触摸才能拖动；其余时刻一律穿透
        if (!editMode) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    private fun boxParams(): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = x
            topMargin = y
        }

    private fun showAsOverlay() {
        if (overlayParams != null) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            textWindowFlags(),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // ★ 关键：窗口自身 alpha 必须是 1f。实测这个覆盖层窗口默认带 alpha≈0.8，
            // 不压平的话 view alpha 的差异会被乘掉，调不透明度看起来完全没反应。
            alpha = 1f
        }
        overlayParams = lp
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(textView, boxParams())
            isClickable = false
            isFocusable = false
        }
        root = container
        attachDrag(container, forPresentation = false)
        runCatching { wm.addView(container, lp) }
        textView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionBar() }
    }

    private fun showOnPresentation() {
        if (presentation != null) return
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                textView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                )
            )
            isClickable = false
            isFocusable = false
        }
        val p = Presentation(context, display).apply {
            setCancelable(false)
            setContentView(container)
            window?.apply {
                // 不要 setType：Presentation 内部已用 TYPE_PRESENTATION 建好绑定目标 Display 的
                // window context，改成 TYPE_APPLICATION_OVERLAY 会抛 window type mismatch。
                addFlags(presentationFlags())
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                // 外接屏上窗口必须 WRAP_CONTENT 且跟随坐标移动，不能全屏——
                // 全屏 Presentation 即使带 FLAG_NOT_TOUCHABLE 也会在部分 ROM 上拦截外接屏触摸。
                setLayout(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                val lp = attributes
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = x + CURSOR_OFFSET_PX
                lp.y = y + CURSOR_OFFSET_PX
                attributes = lp
            }
        }
        presentation = p
        attachDrag(container, forPresentation = true)
        runCatching {
            p.show()
            p.window?.decorView?.apply {
                isClickable = false
                isFocusable = false
            }
        }
        textView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionBar() }
    }

    private fun presentationFlags(): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!editMode) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    private fun rebuildTextFlags() {
        textView.background = logBackground()
        overlayParams?.let { lp ->
            lp.flags = textWindowFlags()
            root?.let { r -> runCatching { wm.updateViewLayout(r, lp) } }
        }
        presentation?.window?.let { window ->
            val lp = window.attributes
            lp.flags = presentationFlags()
            runCatching { window.attributes = lp }
        }
    }

    private fun applyTextVisibility() {
        // 编辑模式下强制可见：隐藏状态是 GONE，窗口根本接不到触摸，也拖不动
        textView.visibility = if (!hidden || editMode) View.VISIBLE else View.GONE
        if (textView.visibility == View.VISIBLE) textView.alpha = alphaOf(opacity)
    }

    // ---------------------------------------------------------------- 按钮条

    private fun showBar() {
        if (barView != null) return
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val toggle = barButton(if (hidden) "显" else "隐") { onToggleHidden() }
        val clear = barButton("清") { onClear() }
        // 第三个键：拖动/完成。编辑模式下全屏窗口会挡住页面里的按钮，
        // 所以必须有一个「永远点得到」的出口，否则用户无法退出拖动模式。
        val editBtn = barButton(if (editMode) "完" else "拖") { onToggleEdit() }
        toggleLabel = toggle
        editLabel = editBtn
        bar.addView(toggle)
        bar.addView(clear)
        bar.addView(editBtn)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            // 按钮条必须可点：只有 NOT_FOCUSABLE + NOT_TOUCH_MODAL，绝不加 NOT_TOUCHABLE
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = this@LuaOverlay.x
            y = this@LuaOverlay.y
            // 与文字窗同理：压平窗口 alpha，让按钮条的 0.75 是真实观感
            alpha = 1f
        }
        barView = bar
        barParams = lp
        runCatching { wm.addView(bar, lp) }
        bar.alpha = 0f
        fade(bar, BAR_ALPHA)
        bar.post { positionBar() }
    }

    /**
     * 悬浮按钮样式对齐虚拟光标（PointerService.OverlayRenderer）：
     *   - 整体半透明（0.75），和光标的 cursorAlpha 一致，不抢戏也不糊住下层
     *   - 按下缩到 0.92（光标按下是 0.95），松开回弹，给物理反馈
     *   - 出现时淡入 200ms（光标用 ObjectAnimator 200ms 淡入淡出）
     */
    private fun barButton(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(BUTTON_TEXT_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(9).toFloat()
                setColor(BUTTON_BG_COLOR)
                setStroke(dp(1).coerceAtLeast(1), BUTTON_STROKE_COLOR)
            }
            alpha = 0.75f
            isClickable = true
            isFocusable = false
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate()
                        .scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(80).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate()
                        .scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                false // 不消费事件，交给 OnClickListener 正常触发
            }
            setOnClickListener { onClick() }
        }

    /** 淡入/淡出，时长与 PointerService.fade() 一致 */
    private fun fade(view: View, to: Float) {
        ObjectAnimator.ofFloat(view, "alpha", view.alpha, to).apply {
            duration = FADE_MS
            start()
        }
    }

    /** 按钮条贴日志框右缘；右侧放不下就翻到左边，永远不出屏 */
    private fun positionBar() {
        val bar = barView ?: return
        val lp = barParams ?: return
        val textW = if (hidden) 0 else textView.width
        val barW = bar.width
        var bx = x + textW + dp(6)
        val limit = dm.widthPixels - dp(4)
        if (bx + barW > limit) bx = x - barW - dp(6)
        if (bx < dp(4)) bx = dp(4)
        if (lp.x == bx && lp.y == y) return
        lp.x = bx
        lp.y = y
        runCatching { wm.updateViewLayout(bar, lp) }
    }

    // ---------------------------------------------------------------- 拖动（仅编辑模式）

    /**
     * [forPresentation]=true 时移动整个窗口（外接屏本来就靠 attributes 定位）；
     * 覆盖层路径则只改文字 View 的 margin —— 窗口不动，手势不会跑丢。
     */
    private fun attachDrag(view: View, forPresentation: Boolean) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        view.setOnTouchListener { _, event ->
            if (!editMode) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = x
                    startY = y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    x = startX + (event.rawX - downRawX).toInt()
                    y = startY + (event.rawY - downRawY).toInt()
                    // 只挪子 View（改 margin，无 binder IPC）。按钮条的 updateViewLayout
                    // 放到松手时做——每个 MOVE 都发一次 IPC 会拖慢输入队列，
                    // 快滑时系统会超时取消手势（实测拖到一半就停）。
                    applyPosition(forPresentation)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 只有松手才落盘 + 重新贴合按钮条
                    store.x = x
                    store.y = y
                    positionBar()
                    true
                }
                else -> false
            }
        }
    }

    private fun applyPosition(forPresentation: Boolean) {
        clampPosition()
        if (forPresentation || presentation != null) {
            val window = presentation?.window
            if (window != null) {
                val lp = window.attributes
                lp.x = x + CURSOR_OFFSET_PX
                lp.y = y + CURSOR_OFFSET_PX
                lp.flags = presentationFlags()
                runCatching { window.attributes = lp }
            }
            return
        }
        val lp = textView.layoutParams as? FrameLayout.LayoutParams
        if (lp != null) {
            lp.leftMargin = x
            lp.topMargin = y
            textView.layoutParams = lp
        }
    }

    private fun clampPosition() {
        val maxX = (dm.widthPixels - dp(24)).coerceAtLeast(dp(24))
        if (x < 0) x = 0
        if (x > maxX) x = maxX
        if (y < 0) y = 0
    }

    /** 字号 / 不透明度实时生效：不动窗口、不断连接 */
    fun updateStyle(newFontSp: Int, newOpacity: Int) {
        fontSp = newFontSp
        opacity = newOpacity
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp.toFloat())
        textView.background = logBackground()
        if (textView.visibility == View.VISIBLE) {
            textView.animate().cancel()
            textView.alpha = alphaOf(opacity)
        }
        positionBar()
    }

    /** 不透明度 → view alpha；下限 20 与 lua-overlay 的 clampAlpha 一致，避免完全看不见 */
    private fun alphaOf(opacity255: Int): Float =
        opacity255.coerceIn(20, 255) / 255f

    private fun dp(v: Int): Int = (v * dm.density).roundToInt()

    private companion object {
        /** 与外接屏光标一致：4px 固定偏移，让触点落在窗口外 */
        const val CURSOR_OFFSET_PX = 4
        val TEXT_COLOR = 0xFF6BFF87.toInt()
        /** 编辑模式下没有日志时的占位文字（同时充当可抓取区域） */
        const val EDIT_HINT = "拖动调整位置 · 再点一次「编辑位置」退出"

        // ---- 视觉参数，对齐 PointerService 的光标覆盖层 ----
        const val BOX_ALPHA = 1f
        /** 按钮条整体透明度：等于光标默认的 cursorAlpha(0.75) */
        const val BAR_ALPHA = 0.75f
        /** 按下缩放：光标按压缩到 0.95，这里用 0.92 让 30dp 的小按钮更明显 */
        const val PRESS_SCALE = 0.92f
        /** 淡入淡出时长，与 PointerService.fade() 的 200ms 一致 */
        const val FADE_MS = 200L

        val BUTTON_BG_COLOR = Color.argb(0xB3, 0x00, 0x00, 0x00)
        val BUTTON_TEXT_COLOR = 0xFFFFFFFF.toInt()
        val BUTTON_STROKE_COLOR = Color.argb(0x40, 0xFF, 0xFF, 0xFF)
    }
}
