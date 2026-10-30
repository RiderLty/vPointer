package com.gtm.vpointer.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtm.vpointer.NicInfo
import com.gtm.vpointer.PortForwardService
import com.gtm.vpointer.PortForwarder

enum class ForwardStatus { INFO, OK, WARN, ERROR }

@Composable
fun PortForwardScreen(
    listenPort: String,
    onPortChange: (String) -> Unit,
    running: Boolean,
    statusText: String,
    statusLevel: ForwardStatus,
    nics: List<NicInfo>,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val targetHost = PortForwarder.TARGET_HOST
    val targetPort = PortForwarder.TARGET_PORT

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

        // 目标网卡就绪（转发生效）后，给出可点击的访问地址：
        // 本机全部 IPv4（含回环 127.0.0.1）+ 监听端口，点击唤起浏览器，长按复制。
        // NIC 列表由 PortForwarder 每 2 秒广播一次，无需额外探测。
        if (running && statusLevel == ForwardStatus.OK) {
            val port = listenPort.toIntOrNull() ?: PortForwardService.DEFAULT_PORT
            val urls = remember(nics, port) {
                (listOf("127.0.0.1") + nics.filter { it.isUp }.flatMap { it.ipv4s })
                    .distinct()
                    .map { "http://$it:$port" }
            }
            Text(
                text = "访问地址（点击打开浏览器，长按复制）",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            urls.forEach { AccessLinkRow(it) }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        Text(
            text = "网卡识别诊断（目标 IP: $targetHost）",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "以下列出服务识别的网卡，与上方状态同步更新。绿色行表示其子网包含目标 IP。",
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
                    text = "暂无网卡信息",
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccessLinkRow(url: String) {
    val context = LocalContext.current
    Text(
        text = url,
        fontSize = 14.sp,
        color = Color(0xFF2196F3),
        fontFamily = FontFamily.Monospace,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }.onFailure {
                        Toast.makeText(context, "无法打开浏览器", Toast.LENGTH_SHORT).show()
                    }
                },
                onLongClick = {
                    val cm = context
                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("url", url))
                    Toast.makeText(context, "已复制 $url", Toast.LENGTH_SHORT).show()
                }
            )
            .padding(vertical = 4.dp)
    )
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
                if (nic.isTarget) append("    ✔ 匹配目标")
            },
            fontSize = 13.sp,
            color = color,
            fontFamily = FontFamily.Monospace
        )
    }
}
