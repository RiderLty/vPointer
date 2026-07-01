package com.gtm.vpointer.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gtm.vpointer.DisplayInfo

enum class ServiceState { IDLE, RUNNING, ERROR }

@Composable
fun DisplaySelectScreen(
    displays: List<DisplayInfo>,
    selectedDisplayId: Int?,
    serviceState: ServiceState,
    serviceMessage: String,
    onDisplaySelected: (Int) -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
    ) {
        Text(
            text = "选择显示器",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "选择要在哪个显示器上显示虚拟光标",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(displays) { display ->
                DisplayItem(
                    display = display,
                    isSelected = display.displayId == selectedDisplayId,
                    onClick = { onDisplaySelected(display.displayId) }
                )
            }
        }

        // 状态信息
        if (serviceMessage.isNotEmpty()) {
            val statusColor = when (serviceState) {
                ServiceState.RUNNING -> Color(0xFF4CAF50)
                ServiceState.ERROR -> MaterialTheme.colorScheme.error
                ServiceState.IDLE -> Color.Gray
            }
            Text(
                text = serviceMessage,
                fontSize = 14.sp,
                color = statusColor,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        // 按钮区域
        when (serviceState) {
            ServiceState.RUNNING -> {
                Button(
                    onClick = onStopService,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(50.dp)
                ) {
                    Text("停止服务", fontSize = 16.sp)
                }
            }
            else -> {
                Button(
                    onClick = onStartService,
                    enabled = selectedDisplayId != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(50.dp)
                ) {
                    Text("启动服务", fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
fun DisplayItem(
    display: DisplayInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = display.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${display.width} × ${display.height}  ·  ${display.densityDpi} dpi  ·  ${"%.1f".format(display.refreshRate)} Hz",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(4.dp))
                val stateText = when (display.state) {
                    android.view.Display.STATE_ON -> "亮屏"
                    android.view.Display.STATE_OFF -> "息屏"
                    android.view.Display.STATE_DOZE -> "休眠"
                    android.view.Display.STATE_DOZE_SUSPEND -> "休眠挂起"
                    else -> "未知"
                }
                val stateColor = when (display.state) {
                    android.view.Display.STATE_ON -> Color(0xFF4CAF50)
                    android.view.Display.STATE_OFF -> Color.Gray
                    else -> Color(0xFFFF9800)
                }
                val typeText = if (display.isInternal) "内置显示器" else "外接显示器"
                val typeColor = if (display.isInternal) Color.Blue else Color.Green
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "ID: ${display.displayId}  ·  ", fontSize = 12.sp, color = Color.Gray)
                    Text(text = stateText, fontSize = 12.sp, color = stateColor)
                    Text(text = "  ·  ", fontSize = 12.sp, color = Color.Gray)
                    Text(text = typeText, fontSize = 12.sp, color = typeColor)
                }
            }

            if (isSelected) {
                Text(
                    text = "✓",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
