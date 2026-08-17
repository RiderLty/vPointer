package com.gtm.vpointer.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ForwardStatus { INFO, OK, WARN, ERROR }

@Composable
fun PortForwardScreen(
    listenPort: String,
    onPortChange: (String) -> Unit,
    running: Boolean,
    statusText: String,
    statusLevel: ForwardStatus,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "端口转发",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "将目标网卡（按 MAC 00:02:73:6a:96:01 识别）对端设备的 Web 配置转发到本机端口，供外部访问。上游固定转发到 192.168.73.1:80。",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Text(
            text = "监听端口",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = listenPort,
            onValueChange = onPortChange,
            enabled = !running,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        val portValid = listenPort.toIntOrNull()?.let { it in 1024..65535 } ?: false

        if (statusText.isNotEmpty()) {
            val color = when (statusLevel) {
                ForwardStatus.OK -> Color(0xFF4CAF50)
                ForwardStatus.WARN -> Color(0xFFFF9800)
                ForwardStatus.ERROR -> MaterialTheme.colorScheme.error
                ForwardStatus.INFO -> Color.Gray
            }
            Text(
                text = statusText,
                fontSize = 14.sp,
                color = color,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Spacer(Modifier.weight(1f))

        if (running) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("停止转发", fontSize = 16.sp)
            }
        } else {
            Button(
                onClick = onStart,
                enabled = portValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("启动转发", fontSize = 16.sp)
            }
        }
    }
}
