package com.gtm.vpointer

import android.os.Bundle
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.activity.ComponentActivity // 导入 ComponentActivity
import android.graphics.BitmapFactory
import android.view.WindowManager
import android.provider.Settings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
//import kotlinx.coroutines.GlobalScope
//import kotlinx.coroutines.launch
//import java.net.DatagramPacket
//import java.net.DatagramSocket
import android.view.Gravity
import androidx.annotation.RequiresApi

class MainActivity : ComponentActivity() {  // 继承自 ComponentActivity
    private var isPointerViewAttached = false // 用于标记视图是否已附加
    private var isShow = false
    private lateinit var pointerImageView: ImageView
    private lateinit var udpReceiver: UdpReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestOverlayPermission()  // 请求悬浮窗权限

        // 初始化 UDP 接收器
        udpReceiver = UdpReceiver { abs_x, abs_y, show_int, _, _ ->
            if (show_int == 1) {
                if (!isShow){
                    isShow = true
                    showPointer()
                }
                // 更新指针的位置
                updatePointerPosition(abs_x, abs_y)
            } else {
                // 不显示指针
                removePointer()
            }
        }
        udpReceiver.startReceiving()

        // 创建悬浮窗
        createFloatingPointer()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createFloatingPointer() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, // 普通应用用这个
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, // 允许绘制到状态栏和导航栏
            android.graphics.PixelFormat.TRANSLUCENT
        )

        // 将坐标系的原点设置为屏幕的左上角（包含状态栏区域）
        params.gravity = Gravity.TOP or Gravity.START

        pointerImageView = ImageView(this)
        pointerImageView.setImageBitmap(BitmapFactory.decodeResource(resources, R.drawable.pointer_arrow))

        params.x = 0
        params.y = 0

        try {
            windowManager.addView(pointerImageView, params)
            isPointerViewAttached = true
        } catch (e: Exception) {
            Log.e("MainActivity", "无法添加悬浮窗: ${e.message}")
        }
    }


    private fun updatePointerPosition(abs_x: Int, abs_y: Int) {
        if (!isPointerViewAttached) return
        // 确保更新操作在主线程中进行
        runOnUiThread {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = pointerImageView.layoutParams as WindowManager.LayoutParams
            params.x = abs_x
            params.y = abs_y
            windowManager.updateViewLayout(pointerImageView, params) // 更新位置
        }
    }

    // 显示指针（恢复视图）
    private fun showPointer() {
        if (!isPointerViewAttached) {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = pointerImageView.layoutParams as WindowManager.LayoutParams
            windowManager.addView(pointerImageView, params)
            isPointerViewAttached = true
        }
    }
    private fun removePointer() {
        if (isPointerViewAttached) {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager.removeView(pointerImageView) // 从窗口管理器中移除视图
            isPointerViewAttached = false
            isShow = false
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
        }
    }

    companion object {
        private const val REQUEST_OVERLAY_PERMISSION = 1234
    }
}