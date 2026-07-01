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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.gtm.vpointer.ui.screen.DisplaySelectScreen
import com.gtm.vpointer.ui.screen.ServiceState

class MainActivity : ComponentActivity() {

    private lateinit var displayManagerHelper: DisplayManagerHelper
    private var displays by mutableStateOf<List<DisplayInfo>>(emptyList())
    private var selectedDisplayId by mutableStateOf<Int?>(null)
    private var serviceState by mutableStateOf(ServiceState.IDLE)
    private var serviceMessage by mutableStateOf("")

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        setContent {
            MaterialTheme {
                DisplaySelectScreen(
                    displays = displays,
                    selectedDisplayId = selectedDisplayId,
                    serviceState = serviceState,
                    serviceMessage = serviceMessage,
                    onDisplaySelected = { displayId ->
                        selectedDisplayId = displayId
                    },
                    onStartService = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
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
                        stopService(Intent(this, PointerService::class.java))
                    }
                )
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
        startService(serviceIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        displayManagerHelper.unregisterDisplayListener()
    }

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1234
        const val EXTRA_DISPLAY_ID = "display_id"
    }
}
