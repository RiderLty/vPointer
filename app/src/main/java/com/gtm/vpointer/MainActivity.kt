package com.gtm.vpointer

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val selectDisplayButton: Button = findViewById(R.id.select_display_button)
        selectDisplayButton.setOnClickListener {
            showDisplaySelectionDialog()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
        }
    }

    private fun showDisplaySelectionDialog() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays

        val displayNames = displays.map { "Display ${it.displayId}: ${it.name}" }.toTypedArray()

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Select a Display")
        builder.setItems(displayNames) { _, which ->
            val selectedDisplay = displays[which]
            startPointerService(selectedDisplay.displayId)
        }
        builder.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                // Permission denied
            }
        }
    }

    private fun startPointerService(displayId: Int = Display.DEFAULT_DISPLAY) {
        val serviceIntent = Intent(this, PointerService::class.java).apply {
            putExtra("displayId", displayId)
        }
        startService(serviceIntent)
    }

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1234
    }
}
