package com.gtm.vpointer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.gtm.vpointer.ui.screen.DisplaySelectScreen

class MainActivity : ComponentActivity() {

    private lateinit var displayManagerHelper: DisplayManagerHelper
    private var displays by mutableStateOf<List<DisplayInfo>>(emptyList())
    private var selectedDisplayId by mutableStateOf<Int?>(null)

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

        setContent {
            MaterialTheme {
                DisplaySelectScreen(
                    displays = displays,
                    selectedDisplayId = selectedDisplayId,
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
        val serviceIntent = Intent(this, PointerService::class.java).apply {
            putExtra(EXTRA_DISPLAY_ID, displayId)
        }
        startService(serviceIntent)
        android.util.Log.d("MainActivity", "PointerService started")
    }

    override fun onDestroy() {
        super.onDestroy()
        displayManagerHelper.unregisterDisplayListener()
    }

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1234
        const val EXTRA_DISPLAY_ID = "display_id"
    }
}
