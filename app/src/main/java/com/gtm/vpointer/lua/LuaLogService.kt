package com.gtm.vpointer.lua

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import com.gtm.vpointer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Socket

/**
 * Lua 悬浮日志前台服务。
 *
 * 拓扑（与 pico-hid-mapper 的既有约定一致，**协议不改**）：
 *
 *     设备端 WS 服务 (默认 ws://127.0.0.1:8000/ws)
 *        │  二进制帧，payload[0] = 帧类型
 *        │    0x01 文本日志（UTF-8，\n 分行）   ← 只处理这个
 *        │    0x02 陀螺仪 32B                   → 丢弃
 *        │    0x03 光标 9B (vmouse_t)           → 丢弃
 *        ▼
 *     本服务：连接 → 过滤 → 400 行环形缓冲 → 60ms 防抖 → 悬浮文字窗
 *
 * 连接方式与 vPointer 一致：出站 Socket 先按目标 IP 的**子网**匹配网卡，
 * 命中则 Network.bindSocket / 源地址绑定（见 [NetBinder]），默认的 127.0.0.1 走 loopback。
 * 日志是**纯广播、不缓存、不补发**，所以必须长连 + 1s 重连。
 */
class LuaLogService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var store: LuaLogStore
    private var overlay: LuaOverlay? = null

    private var wsJob: Job? = null
    private var socket: Socket? = null
    private var ws: MiniWebSocket? = null

    /** 环形缓冲：只在 synchronized(buffer) 内读写 */
    private val buffer = ArrayDeque<String>()

    private var renderPending = false
    private var lastNotifMs = 0L
    private var statusText = "等待启动"
    private var received = 0

    private var host = TARGET_HOST
    private var port = TARGET_PORT
    private var rules: List<LuaLogFilter.Rule> = emptyList()
    private var displayId = Display.DEFAULT_DISPLAY

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate() {
        super.onCreate()
        store = LuaLogStore(this)
        ensureForeground("等待启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 首行必须是 ensureForeground：用 startForegroundService 拉起时，
        // 5 秒内不 startForeground 会抛 ServiceStartNotAllowedException / ANR。
        ensureForeground(statusText)

        when (intent?.action) {
            ACTION_TOGGLE -> {
                val ov = overlay
                if (ov != null) ov.setHidden(!store.hidden) else store.hidden = !store.hidden
                refreshNotification()
                return START_STICKY
            }
            ACTION_EDIT -> {
                toggleEditMode()
                return START_STICKY
            }
            ACTION_CLEAR -> {
                clearBuffer()
                return START_STICKY
            }
            ACTION_UPDATE_STYLE -> {
                // 字号/不透明度/行数是「运行中实时生效」的：滑块一动就下发，
                // 不重建窗口、不断连接，避免调样式把日志流弄丢。
                if (intent.hasExtra(EXTRA_FONT_SP)) {
                    store.fontSp = intent.getIntExtra(EXTRA_FONT_SP, store.fontSp)
                }
                if (intent.hasExtra(EXTRA_OPACITY)) {
                    store.opacity = intent.getIntExtra(EXTRA_OPACITY, store.opacity)
                }
                if (intent.hasExtra(EXTRA_LINES)) {
                    store.lines = intent.getIntExtra(EXTRA_LINES, store.lines)
                }
                overlay?.updateStyle(store.fontSp, store.opacity)
                // 行数变化只影响「取最后 N 行」，立刻重绘一次让改动可见
                renderNow()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (intent != null) {
            // 目标固定为 ESP 网卡那一侧的日志服务（见 TARGET_HOST/TARGET_PORT），
            // 界面不再传主机/端口；只接收显示相关的设置项。
            host = TARGET_HOST
            port = TARGET_PORT
            val filterRaw = intent.getStringExtra(EXTRA_FILTER) ?: store.filter
            store.filter = filterRaw
            if (intent.hasExtra(EXTRA_LINES)) store.lines = intent.getIntExtra(EXTRA_LINES, store.lines)
            if (intent.hasExtra(EXTRA_FONT_SP)) store.fontSp = intent.getIntExtra(EXTRA_FONT_SP, store.fontSp)
            if (intent.hasExtra(EXTRA_OPACITY)) store.opacity = intent.getIntExtra(EXTRA_OPACITY, store.opacity)
            displayId = intent.getIntExtra(EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
        } else {
            // START_STICKY 被系统重启时 intent 为 null：全部从持久化配置恢复
            host = TARGET_HOST
            port = TARGET_PORT
            displayId = Display.DEFAULT_DISPLAY
        }
        rules = LuaLogFilter.compile(store.filter)

        if (overlay == null) showOverlay()
        startWs()
        publish(STATUS_CONNECTING, "连接 $host:$port …")
        return START_STICKY
    }

    override fun onDestroy() {
        wsJob?.cancel()
        wsJob = null
        runCatching { ws?.close() }
        ws = null
        runCatching { socket?.close() }
        socket = null
        overlay?.destroy()
        overlay = null
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        sendStatusBroadcast(STATUS_STOPPED, "悬浮日志已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------- 悬浮窗

    private fun showOverlay() {
        val display = resolveDisplay(displayId)
        overlay = LuaOverlay(
            context = this,
            display = display,
            store = store,
            onToggleHidden = {
                overlay?.setHidden(!store.hidden)
                refreshNotification()
            },
            onClear = { clearBuffer() },
            onToggleEdit = { toggleEditMode() }
        ).also { it.show() }
        Log.d(TAG, "overlay shown on display ${display.displayId} (${display.name})")
    }

    private fun resolveDisplay(id: Int): Display {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.getDisplay(id) ?: dm.getDisplay(Display.DEFAULT_DISPLAY)
            ?: throw IllegalStateException("no display")
    }

    // ---------------------------------------------------------------- WS 连接

    private fun startWs() {
        if (wsJob?.isActive == true) return
        wsJob = scope.launch {
            while (isActive) {
                var nicMissing = false
                try {
                    val bound = NetBinder(this@LuaLogService).open(host, port)
                    socket = bound.socket
                    val client = MiniWebSocket(LuaLogStore.DEFAULT_PATH)
                    ws = client
                    client.connect(bound.socket, "$host:$port")
                    Log.d(TAG, "ws connected $host:$port via ${bound.via}")
                    publish(STATUS_RUNNING, "已连接 $host:$port · 出口 ${bound.via}")
                    client.readFrame(object : MiniWebSocket.Listener {
                        override fun onOpen() = Unit
                        override fun onBinary(data: ByteArray) = handleFrame(data)
                        override fun onText(text: String) = handleText(text)
                        override fun onClosed() = Unit
                    })
                    // readFrame 返回 = 对端关闭或本地 close
                    if (isActive) publish(STATUS_CONNECTING, "连接已断开，重连中 …")
                } catch (e: NetBinder.TargetUnavailableException) {
                    // 网卡不在：这不是「连接失败」，按端口转发那套语义**暂停**，
                    // 等 ESP 网卡回来再连，而不是每秒往默认网卡打。
                    nicMissing = true
                    if (isActive) {
                        publish(
                            STATUS_WAITING,
                            "等待 ESP 网卡…（未找到子网包含 $host 的网卡，已暂停连接）"
                        )
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "ws error: ${e.javaClass.simpleName}: ${e.message}")
                        publish(STATUS_ERROR, "连接失败：${e.message ?: e.javaClass.simpleName}（1s 后重试）")
                    }
                } finally {
                    runCatching { ws?.close() }
                    ws = null
                    runCatching { socket?.close() }
                    socket = null
                }
                if (isActive) delay(if (nicMissing) NIC_RESCAN_MS else RECONNECT_MS)
            }
        }
    }

    /** 帧首字节分流：只认 0x01 文本日志，陀螺仪/光标帧直接丢 */
    private fun handleFrame(data: ByteArray) {
        if (data.isEmpty()) return
        when (data[0].toInt() and 0xFF) {
            0x01 -> handleText(String(data, 1, data.size - 1, Charsets.UTF_8))
            else -> Unit
        }
    }

    private fun handleText(text: String) {
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // 过滤发生在解析阶段：不符合的行不进缓冲，零内存零渲染成本
            if (!LuaLogFilter.matches(line, rules)) continue
            push(line)
        }
    }

    private fun push(line: String) {
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_BUF) buffer.removeFirst()
        }
        received++
        scheduleRender()
    }

    private fun scheduleRender() {
        if (renderPending) return
        renderPending = true
        main.postDelayed({
            renderPending = false
            renderNow()
        }, RENDER_DEBOUNCE_MS)
    }

    private fun renderNow() {
        val lines = synchronized(buffer) { buffer.takeLast(store.lines) }
        overlay?.setText(lines.joinToString("\n"))
        refreshNotification()
    }

    private fun clearBuffer() {
        synchronized(buffer) { buffer.clear() }
        received = 0
        overlay?.clearText()
        refreshNotification()
    }

    // ---------------------------------------------------------------- 状态上报 / 通知

    private fun publish(status: String, message: String) {
        statusText = message
        sendStatusBroadcast(status, message)
        main.post { refreshNotification() }
    }

    private fun sendStatusBroadcast(status: String, message: String) {
        Log.d(TAG, "status=$status msg=$message")
        val intent = Intent(ACTION_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    /** 编辑（拖动）模式切换：页面按钮、通知栏 action、悬浮条上的「拖/完」三处共用 */
    private fun toggleEditMode() {
        val ov = overlay ?: return
        ov.setEditMode(!ov.isEditMode())
        sendEditState(ov.isEditMode())
        refreshNotification()
    }

    private fun sendEditState(on: Boolean) {
        val intent = Intent(ACTION_EDIT_STATE).apply {
            putExtra(EXTRA_EDIT, on)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun refreshNotification() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotifMs < NOTIF_REFRESH_MS) return
        lastNotifMs = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun summary(): String {
        val filterLabel = if (rules.isEmpty()) "全部" else store.filter
        val mode = when {
            overlay?.isEditMode() == true -> "编辑位置"
            store.hidden -> "已隐藏"
            else -> "穿透"
        }
        val count = synchronized(buffer) { buffer.size }
        return "筛选:$filterLabel · 缓存 $count 行 · $mode"
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Lua 悬浮日志", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Lua 日志悬浮窗前台服务" }
            )
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            Notification.Builder(this)
        }
        val icon = Icon.createWithResource(this, R.drawable.pointer_arrow)
        return builder
            .setContentTitle("vPointer · Lua 日志")
            .setContentText("$statusText | ${summary()}")
            .setSmallIcon(R.drawable.pointer_arrow)
            .setOngoing(true)
            .addAction(notificationAction(icon, "编辑位置", ACTION_EDIT, 11))
            .addAction(notificationAction(icon, "清空", ACTION_CLEAR, 12))
            .addAction(notificationAction(icon, "停止", ACTION_STOP, 13))
            .build()
    }

    private fun notificationAction(
        icon: Icon,
        title: String,
        action: String,
        code: Int
    ): Notification.Action =
        Notification.Action.Builder(icon, title, actionIntent(action, code)).build()

    private fun actionIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this,
            code,
            Intent(this, LuaLogService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun ensureForeground(text: String) {
        statusText = text
        startForeground(NOTIF_ID, buildNotification())
    }

    companion object {
        private const val TAG = "LuaLogService"

        const val ACTION_STATUS = "com.gtm.vpointer.LUA_LOG_STATUS"
        const val ACTION_EDIT_STATE = "com.gtm.vpointer.LUA_EDIT_STATE"
        const val ACTION_TOGGLE = "com.gtm.vpointer.LUA_TOGGLE"
        const val ACTION_EDIT = "com.gtm.vpointer.LUA_EDIT"
        const val ACTION_CLEAR = "com.gtm.vpointer.LUA_CLEAR"
        const val ACTION_UPDATE_STYLE = "com.gtm.vpointer.LUA_STYLE"
        const val ACTION_STOP = "com.gtm.vpointer.LUA_STOP"

        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_FILTER = "lua_filter"
        const val EXTRA_LINES = "lua_lines"
        const val EXTRA_FONT_SP = "lua_font_sp"
        const val EXTRA_OPACITY = "lua_opacity"
        const val EXTRA_DISPLAY_ID = "lua_display_id"
        const val EXTRA_EDIT = "lua_edit"

        /**
         * 日志 WS 服务所在设备与端口：与「端口转发」指向同一块 ESP 网卡
         * （按「子网包含 192.168.73.1」识别），所以界面不需要填主机/端口。
         *
         * 端口是 **80**：这台 pico-hid-mapper（USB HID 映射器）的 HTTP 面板与 /ws
         * 日志通道在同一个 Web 服务器上（实测 GET / 返回配置面板，WS 握手 101）。
         * 不是手机本机的 8000（那是另一套本机服务，此设备上没有）。
         */
        const val TARGET_HOST = "192.168.73.1"
        const val TARGET_PORT = 80

        const val STATUS_CONNECTING = "connecting"
        const val STATUS_RUNNING = "running"
        const val STATUS_ERROR = "error"
        const val STATUS_WAITING = "waiting"
        const val STATUS_STOPPED = "stopped"

        /** 环形缓冲上限：日志瞬时可达上百行/秒，必须丢最旧 */
        private const val MAX_BUF = 400
        /** 渲染防抖：约 16fps 上限，避免 setText 把主线程打满 */
        private const val RENDER_DEBOUNCE_MS = 60L
        private const val NOTIF_REFRESH_MS = 1500L
        private const val RECONNECT_MS = 1000L
        /** 网卡缺失时的重试节奏：与端口转发的 2 秒轮询一致，别用 1 秒去打空网卡 */
        private const val NIC_RESCAN_MS = 2000L
        private const val NOTIF_ID = 3
        private const val CHANNEL = "vpointer_lua"
    }
}
