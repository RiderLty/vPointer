package com.gtm.vpointer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 独立的前台服务，负责运行 [PortForwarder]。与 PointerService 解耦，可单独启停。
 *
 * 启动：ContextCompat.startForegroundService(ctx, Intent(EXTRA_PORT=port))
 * 停止：stopService(Intent(ctx, PortForwardService::class.java))
 * 状态通过 [ACTION_FORWARD_STATUS] 广播回 MainActivity，与 PointerService 的模式一致。
 */
class PortForwardService : Service() {

    private var forwarder: PortForwarder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("端口转发启动中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val existing = forwarder
        if (existing != null && existing.isRunning) {
            Log.i(TAG, "already running, ignore restart on :$port")
            return START_REDELIVER_INTENT
        }
        val f = PortForwarder(
            this,
            onStatus = { status, msg -> onForwarderStatus(status, msg) },
            onNics = { nics -> onNics(nics) }
        )
        forwarder = f
        f.start(port)
        if (!f.isRunning) {
            // 端口绑定失败：start() 内已广播 ERROR，关闭前台服务
            forwarder = null
            stopSelf()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        // 读取状态后再停止：绑定失败时 isRunning=false，不应覆盖 ERROR 的上报
        val wasRunning = forwarder?.isRunning == true
        forwarder?.stop()
        forwarder = null
        if (wasRunning) sendStatusBroadcast(STATUS_STOPPED, "端口转发已停止")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onForwarderStatus(status: PortForwarder.Status, message: String) {
        val s = when (status) {
            PortForwarder.Status.STARTED -> STATUS_STARTED
            PortForwarder.Status.IFACE_UP -> STATUS_IFACE_UP
            PortForwarder.Status.IFACE_DOWN -> STATUS_IFACE_DOWN
            PortForwarder.Status.ERROR -> STATUS_ERROR
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(message.ifEmpty { "端口转发运行中" }))
        sendStatusBroadcast(s, message)
    }

    private fun onNics(nics: List<NicInfo>) {
        val intent = Intent(ACTION_FORWARD_NICS).apply {
            putExtra(EXTRA_NICS, ArrayList(nics))
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIF_CHANNEL, "端口转发", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "网卡端口转发后台服务" }
        nm.createNotificationChannel(channel)
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("vPointer 端口转发")
            .setContentText(text)
            .setSmallIcon(R.drawable.pointer_arrow)
            .setOngoing(true)
            .build()
    }

    private fun sendStatusBroadcast(status: String, message: String) {
        Log.d(TAG, "Status: $status - $message")
        val intent = Intent(ACTION_FORWARD_STATUS).apply {
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    companion object {
        private const val TAG = "PortForwardService"
        private const val NOTIF_ID = 2
        private const val NOTIF_CHANNEL = "vpointer_forward"

        const val ACTION_FORWARD_STATUS = "com.gtm.vpointer.FORWARD_STATUS"
        const val ACTION_FORWARD_NICS = "com.gtm.vpointer.FORWARD_NICS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_NICS = "nics"
        const val EXTRA_PORT = "forward_port"
        const val DEFAULT_PORT = 8000

        const val STATUS_STARTED = "started"
        const val STATUS_IFACE_UP = "iface_up"
        const val STATUS_IFACE_DOWN = "iface_down"
        const val STATUS_ERROR = "error"
        const val STATUS_STOPPED = "stopped"
    }
}
