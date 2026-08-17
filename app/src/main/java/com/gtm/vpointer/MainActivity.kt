package com.gtm.vpointer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.gtm.vpointer.ui.screen.DisplaySelectScreen
import com.gtm.vpointer.ui.screen.ForwardStatus
import com.gtm.vpointer.ui.screen.PortForwardScreen
import com.gtm.vpointer.ui.screen.ServiceState

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
        }

        displayManagerHelper = DisplayManagerHelper(this)
        displays = displayManagerHelper.getAllDisplays()

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
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (mainPage) {
                            0 -> DisplaySelectScreen(
                                displays = displays,
                                selectedDisplayId = selectedDisplayId,
                                serviceState = serviceState,
                                serviceMessage = serviceMessage,
                                onDisplaySelected = { displayId ->
                                    selectedDisplayId = displayId
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
                            else -> PortForwardScreen(
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
        }
        // PointerService 在 onCreate 即调用 startForeground，需用 startForegroundService
        // 启动（与 PortForwardService 一致），否则应用退到后台时 startService 会抛
        // IllegalStateException。
        ContextCompat.startForegroundService(this, serviceIntent)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("serviceState", serviceState.name)
        outState.putString("serviceMessage", serviceMessage)
        outState.putInt("mainPage", mainPage)
        outState.putString("forwardListenPort", forwardListenPort)
        outState.putBoolean("forwardRunning", forwardRunning)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(forwardReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(nicsReceiver) } catch (_: Exception) {}
        displayManagerHelper.unregisterDisplayListener()
    }

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1234
        const val EXTRA_DISPLAY_ID = "display_id"
    }
}
