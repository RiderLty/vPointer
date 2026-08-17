package com.gtm.vpointer.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtm.vpointer.PortForwarder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

enum class ForwardStatus { INFO, OK, WARN, ERROR }

private data class NicInfo(
    val name: String,
    val mac: String,
    val ipv4: String?,
    val isUp: Boolean,
    val isTarget: Boolean
)

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
    val targetMac = PortForwarder.TARGET_MAC
    val targetHost = PortForwarder.TARGET_HOST
    val targetPort = PortForwarder.TARGET_PORT

    // 诊断：动态枚举本机网卡，展示系统实际读到的名称与 MAC（与 PortForwarder 过滤逻辑同源）
    var nics by remember { mutableStateOf<List<NicInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            nics = withContext(Dispatchers.IO) { enumerateNics(targetMac) }
            delay(2000)
        }
    }

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
            text = "将目标网卡（按 MAC $targetMac 识别）对端设备的 Web 配置转发到本机端口，供外部访问。上游固定转发到 $targetHost:$targetPort。",
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

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text(
            text = "网卡识别诊断（目标 MAC: $targetMac）",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "以下列出系统实际读到的网卡名称与 MAC，每 2 秒刷新。绿色行表示与目标 MAC 匹配。",
            fontSize = 12.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            if (nics.isEmpty()) {
                Text(
                    text = "正在扫描网卡…",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                nics.forEach { nic -> NicRow(nic) }
            }
        }

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

@Composable
private fun NicRow(nic: NicInfo) {
    val color = if (nic.isTarget) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = "${nic.name}  [${if (nic.isUp) "UP" else "DOWN"}]",
            fontSize = 14.sp,
            fontWeight = if (nic.isTarget) FontWeight.Bold else FontWeight.Normal,
            color = color,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = buildString {
                append("MAC: ").append(nic.mac)
                nic.ipv4?.let { append("    IPv4: ").append(it) }
                if (nic.isTarget) append("    ✔ 匹配目标")
            },
            fontSize = 13.sp,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * 枚举本机网卡，返回系统实际读到的名称 / MAC / IPv4 / up 状态，与 [PortForwarder] 的
 * 过滤逻辑使用同一套 API（[NetworkInterface.getHardwareAddress]），用于诊断为何目标网卡未被匹配。
 */
private fun enumerateNics(targetMac: String): List<NicInfo> {
    val result = mutableListOf<NicInfo>()
    try {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return result
        for (ni in ifaces) {
            if (ni.isLoopback) continue
            val mac = ni.hardwareAddress?.let { formatMac(it) } ?: "不可读(null)"
            val ipv4 = runCatching {
                val addrs = ni.inetAddresses
                var ip: String? = null
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        ip = a.hostAddress
                        break
                    }
                }
                ip
            }.getOrNull()
            result += NicInfo(
                name = ni.name,
                mac = mac,
                ipv4 = ipv4,
                isUp = ni.isUp,
                isTarget = mac.equals(targetMac, ignoreCase = true)
            )
        }
    } catch (_: Exception) {
        // 枚举失败：返回空列表，由调用方显示占位
    }
    return result
}

private fun formatMac(bytes: ByteArray): String =
    bytes.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
