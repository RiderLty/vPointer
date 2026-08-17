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
import java.net.InetAddress
import java.net.NetworkInterface

enum class ForwardStatus { INFO, OK, WARN, ERROR }

private data class NicInfo(
    val name: String,
    val mac: String,
    val ipv4s: List<String>,
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
    val targetHost = PortForwarder.TARGET_HOST
    val targetPort = PortForwarder.TARGET_PORT

    // 诊断：动态枚举本机网卡，展示系统实际读到的名称 / IP / MAC（与 PortForwarder 过滤逻辑同源）
    var nics by remember { mutableStateOf<List<NicInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            nics = withContext(Dispatchers.IO) { enumerateNics(targetHost) }
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
            text = "将目标网卡（按「子网包含 $targetHost」识别，如本机 192.168.73.2）对端设备的 Web 配置转发到本机端口，供外部访问。上游固定转发到 $targetHost:$targetPort。",
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
            text = "网卡识别诊断（目标 IP: $targetHost）",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "以下列出系统实际读到的网卡名称 / IP / MAC，每 2 秒刷新。绿色行表示其子网包含目标 IP。",
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
                append("IP: ").append(if (nic.ipv4s.isEmpty()) "无" else nic.ipv4s.joinToString(", "))
                append("    MAC: ").append(nic.mac)
                if (nic.isTarget) append("    ✔ 匹配目标")
            },
            fontSize = 13.sp,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * 枚举本机网卡，返回系统实际读到的名称 / IPv4 列表 / MAC / up 状态。匹配判定按「子网包含
 * [targetHost]」进行（与 [PortForwarder.findInterfaceFor] 同一套算法），用于诊断为何目标网卡未被匹配。
 */
private fun enumerateNics(targetHost: String): List<NicInfo> {
    val result = mutableListOf<NicInfo>()
    try {
        val remote = InetAddress.getByName(targetHost)
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return result
        for (ni in ifaces) {
            if (ni.isLoopback) continue
            val mac = ni.hardwareAddress?.let { formatMac(it) } ?: "不可读(null)"
            val ipv4s = mutableListOf<String>()
            var target = false
            for (ia in ni.interfaceAddresses) {
                val addr = ia.address ?: continue
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    ipv4s += addr.hostAddress
                    if (sameSubnet(addr, ia.networkPrefixLength, remote)) target = true
                }
            }
            result += NicInfo(ni.name, mac, ipv4s, ni.isUp, target)
        }
    } catch (_: Exception) {
        // 枚举失败：返回空列表，由调用方显示占位
    }
    return result
}

private fun sameSubnet(local: InetAddress, prefix: Short, remote: InetAddress): Boolean {
    val lb = local.address
    val rb = remote.address
    if (lb.size != rb.size) return false
    val fullBytes = prefix / 8
    val remainBits = prefix % 8
    for (i in 0 until fullBytes) {
        if (lb[i] != rb[i]) return false
    }
    if (remainBits > 0 && fullBytes < lb.size) {
        val mask = (0xFF shl (8 - remainBits)) and 0xFF
        if ((lb[fullBytes].toInt() and mask) != (rb[fullBytes].toInt() and mask)) return false
    }
    return true
}

private fun formatMac(bytes: ByteArray): String =
    bytes.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }
