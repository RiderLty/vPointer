package com.gtm.vpointer.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtm.vpointer.NicInfo

enum class LuaLogStatus { INFO, OK, WARN, ERROR }

/**
 * Lua 悬浮日志页（第三个 Tab）。
 *
 * 与端口转发同构：**目标地址不用填**，固定指向 ESP 网卡那一侧的日志服务
 * （[com.gtm.vpointer.lua.LuaLogService.TARGET_HOST]），本页只做两件事——
 * 展示网卡诊断（哪块网卡子网命中目标）+ 调整显示参数。
 *
 * 版式沿用 [PortForwardScreen]：标题 → 灰字说明 → 目标只读行 → 可滚动的
 * 诊断/调节区（weight(1f) + verticalScroll）→ 底部整宽主按钮（50dp）。
 */
@Composable
fun LuaLogScreen(
    targetHost: String,
    targetPort: Int,
    nics: List<NicInfo>,
    filter: String,
    lines: Float,
    fontSp: Float,
    opacity: Float,
    running: Boolean,
    editMode: Boolean,
    statusText: String,
    statusLevel: LuaLogStatus,
    onFilterChange: (String) -> Unit,
    onLinesChange: (Float) -> Unit,
    onFontChange: (Float) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onToggleEdit: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Lua 悬浮日志",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "把 ESP 网卡那一侧的 Lua 脚本日志半透明浮在屏幕上层，触摸完全穿透。" +
                "目标地址固定为 $targetHost:$targetPort，网卡按「子网包含 $targetHost」自动识别，" +
                "与端口转发同一套判定，无需填写主机/端口。",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 14.dp)
        )

        // 目标只读展示：抄端口转发页的「上游固定 …」写法，让人一眼知道连的是谁
        Text(
            text = "目标  ws://$targetHost:$targetPort/ws",
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF4CAF50),
            modifier = Modifier.padding(bottom = 14.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "网卡识别诊断（目标 IP: $targetHost）",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                text = "绿色行表示其子网包含目标 IP，连接会绑定到那块网卡；此处与端口转发页同源。",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (nics.isEmpty()) {
                Text(
                    text = "暂无网卡信息",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                nics.forEach { NicLine(it) }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

            OutlinedTextField(
                value = filter,
                onValueChange = onFilterChange,
                enabled = !running,
                singleLine = true,
                label = { Text("日志筛选前缀") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "逗号分隔，可混用两种规则：普通词＝前缀（先剥掉 [lua] 包装再比 payload 开头，如 liu、[lua）；" +
                    "以 re: 开头＝正则（对整行与 payload 各做一次匹配，如 re:档位\\s*\\d、re:hb|recoil）。留空＝全部。",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
            )

            SliderItem(
                label = "显示行数",
                value = lines,
                valueText = "${lines.toInt()} 行",
                range = 1f..24f,
                steps = 22,
                onChange = onLinesChange
            )
            SliderItem(
                label = "字号",
                value = fontSp,
                valueText = "${fontSp.toInt()} sp",
                range = 8f..28f,
                steps = 19,
                onChange = onFontChange
            )
            SliderItem(
                label = "背景不透明度",
                value = opacity,
                valueText = "${opacity.toInt()}/255",
                range = 40f..255f,
                steps = 0,
                onChange = onOpacityChange
            )

            Text(
                text = "屏幕上浮出文字窗（穿透）+ 按钮条「隐/显」「清」；通知栏另有 编辑位置 / 清空 / 停止。" +
                    "拖动仅在编辑模式下生效，避免游戏中划过误拖。",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        // 状态行常显在按钮上方（与 PortForwardScreen 一致），不跟着滚动区滚走
        if (statusText.isNotEmpty()) {
            val color = when (statusLevel) {
                LuaLogStatus.OK -> Color(0xFF4CAF50)
                LuaLogStatus.WARN -> Color(0xFFFF9800)
                LuaLogStatus.ERROR -> MaterialTheme.colorScheme.error
                LuaLogStatus.INFO -> Color.Gray
            }
            Text(
                text = statusText,
                fontSize = 13.sp,
                color = color,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (running) {
            // 编辑位置：把文字窗从「完全穿透」切成「可拖动」，页面按钮与通知栏 action 等价
            OutlinedButton(
                onClick = onToggleEdit,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (editMode) Color(0xFFFFB300) else MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Text(
                    text = if (editMode) "退出编辑位置（停止拖动）" else "编辑位置（拖动悬浮窗）",
                    fontSize = 15.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (running) {
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("停止悬浮日志", fontSize = 16.sp)
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("启动悬浮日志", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun SliderItem(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = valueText,
            fontSize = 13.sp,
            color = Color.Gray,
            fontFamily = FontFamily.Monospace
        )
    }
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    )
}

@Composable
private fun NicLine(nic: NicInfo) {
    val color = if (nic.isTarget) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
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
