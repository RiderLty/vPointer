package com.gtm.vpointer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gtm.vpointer.lua.LuaLogService
import com.gtm.vpointer.lua.LuaLogStore
import com.gtm.vpointer.lua.NetBinder
import com.gtm.vpointer.ui.screen.DisplaySelectScreen
import com.gtm.vpointer.ui.screen.ForwardStatus
import com.gtm.vpointer.ui.screen.LuaLogScreen
import com.gtm.vpointer.ui.screen.LuaLogStatus
import com.gtm.vpointer.ui.screen.PortForwardScreen
import com.gtm.vpointer.ui.screen.ServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var displayManagerHelper: DisplayManagerHelper
    private var displays by mutableStateOf<List<DisplayInfo>>(emptyList())
    private var selectedDisplayId by mutableStateOf<Int?>(null)
    private var serviceState by mutableStateOf(ServiceState.IDLE)
    private var serviceMessage by mutableStateOf("")

    // 端口转发相关状态
    private var mainPage by mutableStateOf(0)
    private var forwardListenPort by mutableStateOf("8000")
    private var forwardRunning by mutableStateOf(false)
    private var forwardStatusText by mutableStateOf("")
    private var forwardStatusLevel by mutableStateOf(ForwardStatus.INFO)
    private var forwardNics by mutableStateOf<List<NicInfo>>(emptyList())

    // Lua 悬浮日志相关状态（配置持久化在 LuaLogStore / SharedPreferences）
    // 目标地址固定为 ESP 网卡那一侧（LuaLogService.TARGET_HOST/PORT），界面不再提供输入框
    private val luaLogStore by lazy { LuaLogStore(this) }
    private var luaFilter by mutableStateOf(LuaLogStore.DEFAULT_FILTER)
    private var luaLines by mutableStateOf(LuaLogStore.DEFAULT_LINES.toFloat())
    private var luaFontSp by mutableStateOf(LuaLogStore.DEFAULT_FONT_SP.toFloat())
    private var luaOpacity by mutableStateOf(LuaLogStore.DEFAULT_OPACITY.toFloat())
    private var luaRunning by mutableStateOf(false)
    private var luaEditMode by mutableStateOf(false)
    private var luaStatusText by mutableStateOf("")
    private var luaStatusLevel by mutableStateOf(LuaLogStatus.INFO)
    private var luaNics by mutableStateOf<List<NicInfo>>(emptyList())
    /** 样式下发节流用：合并滑块的高频 onValueChange */
    private var luaStyleJob: Job? = null

    // 光标样式相关状态
    private val cursorIconStore by lazy { CursorIconStore(this) }
    private var selectedCursorId by mutableStateOf(CursorPresets.DEFAULT_ID)
    private var customCursorBitmap by mutableStateOf<Bitmap?>(null)
    private var cursorColor by mutableStateOf(CursorIconStore.NO_COLOR)
    // 实验性开关：实心光标（不可穿透触摸 + 不透明）
    private var solidCursor by mutableStateOf(false)

    // 系统图片选择器：选一张 PNG 作为自定义光标
    private val pickCursorImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) onCustomImagePicked(uri)
        }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(PointerService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(PointerService.EXTRA_MESSAGE) ?: ""
            android.util.Log.d("MainActivity", "Status received: $status - $message")
            serviceMessage = message
            serviceState = when (status) {
                PointerService.STATUS_RUNNING -> ServiceState.RUNNING
                PointerService.STATUS_ERROR -> ServiceState.ERROR
                PointerService.STATUS_STOPPED -> ServiceState.IDLE
                else -> serviceState
            }
        }
    }

    private val forwardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(PortForwardService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(PortForwardService.EXTRA_MESSAGE) ?: ""
            android.util.Log.d("MainActivity", "Forward status: $status - $message")
            when (status) {
                PortForwardService.STATUS_STARTED,
                PortForwardService.STATUS_IFACE_UP -> {
                    forwardRunning = true
                    forwardStatusText = message
                    forwardStatusLevel = ForwardStatus.OK
                }
                PortForwardService.STATUS_IFACE_DOWN -> {
                    forwardRunning = true
                    forwardStatusText = message
                    forwardStatusLevel = ForwardStatus.WARN
                }
                PortForwardService.STATUS_ERROR -> {
                    forwardRunning = false
                    forwardStatusText = message
                    forwardStatusLevel = ForwardStatus.ERROR
                }
                PortForwardService.STATUS_STOPPED -> {
                    forwardRunning = false
                    forwardStatusText = message
                    forwardStatusLevel = ForwardStatus.INFO
                    forwardNics = emptyList()
                }
            }
        }
    }

    private val nicsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val nics = intent.getSerializableExtra(PortForwardService.EXTRA_NICS) as? List<NicInfo>
                ?: emptyList()
            forwardNics = nics
        }
    }

    private var lastLuaStatus = ""

    private val luaStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(LuaLogService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(LuaLogService.EXTRA_MESSAGE) ?: ""
            android.util.Log.d("MainActivity", "Lua status: $status - $message")
            // 状态跃迁时刷一次网卡列表，避免「状态说没网卡、列表还显示 eth0」的自相矛盾
            if (status != lastLuaStatus) {
                lastLuaStatus = status
                if (status != LuaLogService.STATUS_STOPPED) refreshLuaNics()
            }
            when (status) {
                LuaLogService.STATUS_RUNNING -> {
                    luaRunning = true
                    luaStatusLevel = LuaLogStatus.OK
                    luaStatusText = message
                }
                LuaLogService.STATUS_CONNECTING -> {
                    // 连上之前就属于「运行中」（服务已在前台跑），只是连接态在抖动
                    luaRunning = true
                    luaStatusLevel = LuaLogStatus.WARN
                    luaStatusText = message
                }
                LuaLogService.STATUS_WAITING -> {
                    // 网卡不在：暂停连接（与端口转发「拔出只暂停上游」同义）
                    luaRunning = true
                    luaStatusLevel = LuaLogStatus.WARN
                    luaStatusText = message
                }
                LuaLogService.STATUS_ERROR -> {
                    luaRunning = true
                    luaStatusLevel = LuaLogStatus.ERROR
                    luaStatusText = message
                }
                LuaLogService.STATUS_STOPPED -> {
                    luaRunning = false
                    luaStatusLevel = LuaLogStatus.INFO
                    luaStatusText = message
                }
            }
        }
    }

    private val luaEditReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            luaEditMode = intent.getBooleanExtra(LuaLogService.EXTRA_EDIT, false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 恢复旋转前的状态
        if (savedInstanceState != null) {
            val savedState = savedInstanceState.getString("serviceState")
            if (savedState != null) {
                serviceState = ServiceState.valueOf(savedState)
                serviceMessage = savedInstanceState.getString("serviceMessage", "")
            }
            mainPage = savedInstanceState.getInt("mainPage", 0)
            forwardListenPort = savedInstanceState.getString("forwardListenPort", "8000")
            forwardRunning = savedInstanceState.getBoolean("forwardRunning", false)
            luaRunning = savedInstanceState.getBoolean("luaRunning", false)
            luaStatusText = savedInstanceState.getString("luaStatusText", "")
            savedInstanceState.getString("luaStatusLevel")?.let {
                runCatching { luaStatusLevel = LuaLogStatus.valueOf(it) }
            }
            if (savedInstanceState.containsKey("selectedDisplayId")) {
                selectedDisplayId = savedInstanceState.getInt("selectedDisplayId")
            }
        }

        displayManagerHelper = DisplayManagerHelper(this)
        displays = displayManagerHelper.getAllDisplays()

        // 恢复上次选择的光标样式（SQLite 持久化）
        selectedCursorId = cursorIconStore.getSelectedCursorId()
        cursorColor = cursorIconStore.getSelectedColor()
        solidCursor = cursorIconStore.isSolidCursorEnabled()

        // Lua 悬浮日志配置（SharedPreferences 持久化，改一下写一次，所以直接当唯一事实来源）
        luaFilter = luaLogStore.filter
        luaLines = luaLogStore.lines.toFloat()
        luaFontSp = luaLogStore.fontSp.toFloat()
        luaOpacity = luaLogStore.opacity.toFloat()
        if (selectedCursorId == CursorPresets.CUSTOM_ID) {
            loadCustomCursorPreview()
        }

        // 默认选择内置显示器
        if (selectedDisplayId == null && displays.isNotEmpty()) {
            selectedDisplayId = displays.firstOrNull { it.isInternal }?.displayId
        }

        // 监听显示器变化
        displayManagerHelper.registerDisplayListener {
            displays = displayManagerHelper.getAllDisplays()
            // 如果选中的显示器被移除，回退到内置显示器
            if (selectedDisplayId != null && displays.none { it.displayId == selectedDisplayId }) {
                selectedDisplayId = displays.firstOrNull { it.isInternal }?.displayId
            }
        }

        // 注册服务状态广播
        val filter = IntentFilter(PointerService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }

        // 注册端口转发状态广播
        val forwardFilter = IntentFilter(PortForwardService.ACTION_FORWARD_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forwardReceiver, forwardFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(forwardReceiver, forwardFilter)
        }

        // 注册网卡列表广播
        val nicsFilter = IntentFilter(PortForwardService.ACTION_FORWARD_NICS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nicsReceiver, nicsFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(nicsReceiver, nicsFilter)
        }

        // 注册 Lua 悬浮日志状态广播
        val luaStatusFilter = IntentFilter(LuaLogService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(luaStatusReceiver, luaStatusFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(luaStatusReceiver, luaStatusFilter)
        }

        // 注册 Lua 编辑位置状态广播（页面按钮 ↔ 服务 ↔ 通知栏三方同步）
        val luaEditFilter = IntentFilter(LuaLogService.ACTION_EDIT_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(luaEditReceiver, luaEditFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(luaEditReceiver, luaEditFilter)
        }

        setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                ) {
                    TabRow(selectedTabIndex = mainPage) {
                        Tab(
                            selected = mainPage == 0,
                            onClick = { mainPage = 0 },
                            text = { Text("虚拟光标") }
                        )
                        Tab(
                            selected = mainPage == 1,
                            onClick = { mainPage = 1 },
                            text = { Text("端口转发") }
                        )
                        Tab(
                            selected = mainPage == 2,
                            onClick = {
                                mainPage = 2
                                refreshLuaNics()
                            },
                            text = { Text("Lua 日志") }
                        )
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (mainPage) {
                            0 -> DisplaySelectScreen(
                                displays = displays,
                                selectedDisplayId = selectedDisplayId,
                                serviceState = serviceState,
                                serviceMessage = serviceMessage,
                                cursorPresets = CursorPresets.all,
                                selectedCursorId = selectedCursorId,
                                customCursorBitmap = customCursorBitmap,
                                cursorColor = cursorColor,
                                solidCursor = solidCursor,
                                onSolidCursorChanged = { enabled -> onSolidCursorChanged(enabled) },
                                onDisplaySelected = { displayId ->
                                    selectedDisplayId = displayId
                                },
                                onCursorPresetSelected = { id ->
                                    onCursorPresetSelected(id)
                                },
                                onPickCustomImage = {
                                    pickCursorImage.launch("image/png")
                                },
                                onCursorColorSelected = { color ->
                                    onCursorColorSelected(color)
                                },
                                onStartService = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        )
                                        startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
                                    } else {
                                        startPointerService()
                                    }
                                },
                                onStopService = {
                                    stopService(Intent(this@MainActivity, PointerService::class.java))
                                }
                            )
                            1 -> PortForwardScreen(
                                listenPort = forwardListenPort,
                                onPortChange = { v ->
                                    if (v.all { it.isDigit() } && v.length <= 5) {
                                        forwardListenPort = v
                                    }
                                },
                                running = forwardRunning,
                                statusText = forwardStatusText,
                                statusLevel = forwardStatusLevel,
                                nics = forwardNics,
                                onStart = { startForward() },
                                onStop = { stopForward() }
                            )
                            else -> LuaLogScreen(
                                targetHost = LuaLogService.TARGET_HOST,
                                targetPort = LuaLogService.TARGET_PORT,
                                nics = luaNics,
                                filter = luaFilter,
                                lines = luaLines,
                                fontSp = luaFontSp,
                                opacity = luaOpacity,
                                running = luaRunning,
                                editMode = luaEditMode,
                                statusText = luaStatusText,
                                statusLevel = luaStatusLevel,
                                onFilterChange = { v ->
                                    luaFilter = v
                                    luaLogStore.filter = v
                                },
                                onLinesChange = { v ->
                                    luaLines = v
                                    luaLogStore.lines = v.toInt()
                                    pushLuaStyle()
                                },
                                onFontChange = { v ->
                                    luaFontSp = v
                                    luaLogStore.fontSp = v.toInt()
                                    pushLuaStyle()
                                },
                                onOpacityChange = { v ->
                                    luaOpacity = v
                                    luaLogStore.opacity = v.toInt()
                                    pushLuaStyle()
                                },
                                onToggleEdit = { toggleLuaEdit() },
                                onStart = { startLuaLog() },
                                onStop = { stopLuaLog() }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startPointerService()
            }
        }
        if (requestCode == OVERLAY_PERMISSION_LUA_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startLuaLog()
            }
        }
    }

    private fun startPointerService() {
        val displayId = selectedDisplayId ?: run {
            android.util.Log.e("MainActivity", "selectedDisplayId is null, returning")
            return
        }
        android.util.Log.d("MainActivity", "Starting PointerService with displayId: $displayId")
        serviceState = ServiceState.IDLE
        serviceMessage = ""
        val serviceIntent = Intent(this, PointerService::class.java).apply {
            putExtra(EXTRA_DISPLAY_ID, displayId)
            putExtra(PointerService.EXTRA_SOLID_CURSOR, solidCursor)
        }
        // PointerService 在 onCreate 即调用 startForeground，需用 startForegroundService
        // 启动（与 PortForwardService 一致），否则应用退到后台时 startService 会抛
        // IllegalStateException。
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    // ---- 光标样式 ----

    private fun onCursorPresetSelected(id: String) {
        if (id == selectedCursorId) return
        cursorIconStore.setSelectedCursorId(id)
        selectedCursorId = id
        notifyCursorIconChanged()
    }

    private fun onCursorColorSelected(color: Int) {
        if (color == cursorColor) return
        cursorIconStore.setSelectedColor(color)
        cursorColor = color
        notifyCursorIconChanged()
    }

    private fun onCustomImagePicked(uri: Uri) {
        lifecycleScope.launch {
            // SAF 返回的 Uri 是临时的，先拷贝进应用私有目录再引用
            val ok = CursorImages.saveCustomImage(applicationContext, uri)
            if (!ok) {
                Toast.makeText(this@MainActivity, "无法读取所选图片", Toast.LENGTH_SHORT).show()
                return@launch
            }
            customCursorBitmap = CursorImages.loadCustomBitmap(applicationContext)
            selectedCursorId = CursorPresets.CUSTOM_ID
            cursorIconStore.setSelectedCursorId(CursorPresets.CUSTOM_ID)
            notifyCursorIconChanged()
        }
    }

    private fun loadCustomCursorPreview() {
        lifecycleScope.launch {
            val bmp = CursorImages.loadCustomBitmap(applicationContext)
            if (bmp != null) {
                customCursorBitmap = bmp
            } else {
                // 自定义图片缺失（被清理等），回退默认箭头
                selectedCursorId = CursorPresets.DEFAULT_ID
                cursorIconStore.setSelectedCursorId(CursorPresets.DEFAULT_ID)
            }
        }
    }

    /** 持久化实心光标模式；服务运行中时热更新窗口 flags/透明度，未运行则下次启动生效 */
    private fun onSolidCursorChanged(enabled: Boolean) {
        if (enabled == solidCursor) return
        cursorIconStore.setSolidCursorEnabled(enabled)
        solidCursor = enabled
        if (serviceState == ServiceState.RUNNING) {
            startService(
                Intent(this, PointerService::class.java)
                    .setAction(PointerService.ACTION_UPDATE_SOLID_CURSOR)
                    .putExtra(PointerService.EXTRA_SOLID_CURSOR, enabled)
            )
        }
    }

    /** 服务运行中时通知 PointerService 热更新光标图标；未运行则下次启动时生效 */
    private fun notifyCursorIconChanged() {
        if (serviceState == ServiceState.RUNNING) {
            startService(
                Intent(this, PointerService::class.java)
                    .setAction(PointerService.ACTION_UPDATE_CURSOR_ICON)
            )
        }
    }

    private fun startForward() {
        val port = forwardListenPort.toIntOrNull() ?: return
        if (port !in 1024..65535) {
            forwardStatusText = "端口需在 1024~65535 之间"
            forwardStatusLevel = ForwardStatus.ERROR
            return
        }
        forwardStatusText = ""
        val intent = Intent(this, PortForwardService::class.java).apply {
            putExtra(PortForwardService.EXTRA_PORT, port)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopForward() {
        stopService(Intent(this, PortForwardService::class.java))
    }

    // ---- Lua 悬浮日志 ----

    /**
     * 启动悬浮日志。目标地址固定（[LuaLogService.TARGET_HOST]/[TARGET_PORT]），
     * 由服务内部的 [com.gtm.vpointer.lua.NetBinder] 按子网自动绑定 ESP 那块网卡，
     * 所以这里只做「权限校验 + 下发显示参数」。
     */
    private fun startLuaLog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                OVERLAY_PERMISSION_LUA_REQUEST_CODE
            )
            return
        }
        luaLogStore.filter = luaFilter
        luaLogStore.lines = luaLines.toInt()
        luaLogStore.fontSp = luaFontSp.toInt()
        luaLogStore.opacity = luaOpacity.toInt()

        luaStatusText = "启动中 …"
        luaStatusLevel = LuaLogStatus.INFO
        val intent = Intent(this, LuaLogService::class.java).apply {
            putExtra(LuaLogService.EXTRA_FILTER, luaFilter)
            putExtra(LuaLogService.EXTRA_LINES, luaLines.toInt())
            putExtra(LuaLogService.EXTRA_FONT_SP, luaFontSp.toInt())
            putExtra(LuaLogService.EXTRA_OPACITY, luaOpacity.toInt())
            putExtra(LuaLogService.EXTRA_DISPLAY_ID, selectedDisplayId ?: Display.DEFAULT_DISPLAY)
        }
        ContextCompat.startForegroundService(this, intent)
        luaRunning = true
        refreshLuaNics()
    }

    private fun stopLuaLog() {
        stopService(Intent(this, LuaLogService::class.java))
        luaRunning = false
        luaEditMode = false
    }

    /** 切换编辑位置（拖动）模式：服务已在跑，直接投递 action，无需重建 */
    private fun toggleLuaEdit() {
        if (!luaRunning) return
        startService(
            Intent(this, LuaLogService::class.java).setAction(LuaLogService.ACTION_EDIT)
        )
    }

    /**
     * 把「字号 / 不透明度 / 行数」实时推给正在跑的服务。
     * 滑块拖动会连发几十次 onValueChange，节流 80ms 合并成一次，避免刷爆 startService。
     */
    private fun pushLuaStyle() {
        if (!luaRunning) return
        luaStyleJob?.cancel()
        luaStyleJob = lifecycleScope.launch {
            delay(80)
            startService(
                Intent(this@MainActivity, LuaLogService::class.java)
                    .setAction(LuaLogService.ACTION_UPDATE_STYLE)
                    .putExtra(LuaLogService.EXTRA_FONT_SP, luaFontSp.toInt())
                    .putExtra(LuaLogService.EXTRA_OPACITY, luaOpacity.toInt())
                    .putExtra(LuaLogService.EXTRA_LINES, luaLines.toInt())
            )
        }
    }

    /**
     * 刷新 Lua 页的网卡诊断列表。枚举网卡是一次性开销（几百微秒），
     * 不像端口转发那样每 2 秒轮询——这里只在进页面/启动时取一次，
     * 想再看一次就再次进入该 Tab。
     */
    private fun refreshLuaNics() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { NetBinder(applicationContext).describeNics(LuaLogService.TARGET_HOST) }
                    .getOrDefault(emptyList())
            }
            luaNics = list
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("serviceState", serviceState.name)
        outState.putString("serviceMessage", serviceMessage)
        outState.putInt("mainPage", mainPage)
        outState.putString("forwardListenPort", forwardListenPort)
        outState.putBoolean("forwardRunning", forwardRunning)
        outState.putBoolean("luaRunning", luaRunning)
        outState.putString("luaStatusText", luaStatusText)
        outState.putString("luaStatusLevel", luaStatusLevel.name)
        selectedDisplayId?.let { outState.putInt("selectedDisplayId", it) }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台且停在 Lua 页时刷一次网卡诊断（热插拔后不必手动切 Tab）
        if (mainPage == 2) refreshLuaNics()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(forwardReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(nicsReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(luaStatusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(luaEditReceiver) } catch (_: Exception) {}
        displayManagerHelper.unregisterDisplayListener()
    }

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1234
        private const val OVERLAY_PERMISSION_LUA_REQUEST_CODE = 1235
        const val EXTRA_DISPLAY_ID = "display_id"
    }
}
