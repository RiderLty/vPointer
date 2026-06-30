package com.gtm.vpointer

import android.animation.ObjectAnimator
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PointerService : Service() {

    // 抽象出渲染器：内置屏用 WindowManager 覆盖层，外接屏用 Presentation
    private var renderer: PointerRenderer? = null

    private var isShow = false

    private val socket = DatagramSocket(6533)
    private val socket6534 = DatagramSocket(6534)
    private val serverSocket = ServerSocket(6535)

    // 记录 UDP 客户端及其所在的本地网卡 IP，发包时绑定到该网卡
    private data class ClientInfo(val remoteAddr: InetAddress, val remotePort: Int, val localAddr: InetAddress?)
    private val clients = mutableSetOf<ClientInfo>()
    // 按本地 IP 缓存的发送 socket，避免每次创建
    private val sendSockets = mutableMapOf<InetAddress, DatagramSocket>()
    private val tcpClients = mutableSetOf<OutputStream>()
    private lateinit var orientationEventListener: OrientationEventListener
    private var lastRotation = -1

    private var targetDisplayId = Display.DEFAULT_DISPLAY
    private lateinit var displayManagerHelper: DisplayManagerHelper
    private var displayListener: DisplayManager.DisplayListener? = null

    /**
     * 通过远端 IP 反查应该使用的本地网卡 IP。
     * 遍历本机所有网络接口，找到子网匹配（远端 IP 在该接口的子网内）的接口，
     * 返回其本地 IP。发送时绑定到该 IP 即可强制走对应网卡。
     */
    private fun findLocalAddressFor(remote: InetAddress): InetAddress? {
        try {
            val remoteBytes = remote.address
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val localAddr = ia.address ?: continue
                    // 只匹配同类型（IPv4 对 IPv4）
                    if (localAddr.javaClass != remote.javaClass) continue
                    val prefix = ia.networkPrefixLength
                    val localBytes = localAddr.address
                    if (localBytes.size != remoteBytes.size) continue
                    // 按 prefix length 计算掩码，比较网络部分
                    val fullBytes = prefix / 8
                    val remainBits = prefix % 8
                    var match = true
                    for (i in 0 until fullBytes) {
                        if (localBytes[i] != remoteBytes[i]) { match = false; break }
                    }
                    if (match && remainBits > 0 && fullBytes < localBytes.size) {
                        val mask = (0xFF shl (8 - remainBits)) and 0xFF
                        if ((localBytes[fullBytes].toInt() and mask) != (remoteBytes[fullBytes].toInt() and mask)) {
                            match = false
                        }
                    }
                    if (match) return localAddr
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("PointerService", "findLocalAddressFor failed", e)
        }
        return null
    }

    override fun onCreate() {
        super.onCreate()
        displayManagerHelper = DisplayManagerHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val displayId = intent?.getIntExtra(MainActivity.EXTRA_DISPLAY_ID, Display.DEFAULT_DISPLAY)
            ?: Display.DEFAULT_DISPLAY
        android.util.Log.d("PointerService", "onStartCommand called with displayId: $displayId")

        // 如果显示器 ID 变化，重新创建窗口
        if (displayId != targetDisplayId) {
            android.util.Log.d("PointerService", "Display changed from $targetDisplayId to $displayId, recreating pointer")
            removeExistingPointer()
            targetDisplayId = displayId
            createFloatingPointer()
        } else if (renderer == null) {
            android.util.Log.d("PointerService", "renderer is null, creating new one")
            createFloatingPointer()
        }

        startUdpReceiver()
        startBinaryUdpReceiver()
        startTcpServer()
        startOrientationListener()
        startDisplayListener()

        android.util.Log.d("PointerService", "onStartCommand completed, returning START_STICKY")
        return START_STICKY
    }

    private fun createFloatingPointer() {
        android.util.Log.d("PointerService", "createFloatingPointer called, targetDisplayId: $targetDisplayId")
        val display = displayManagerHelper.getDisplayById(targetDisplayId)
        android.util.Log.d("PointerService", "Display found: ${display?.displayId}, name: ${display?.name}")

        // 目标显示器不存在，回退到内置屏覆盖层
        if (display == null) {
            android.util.Log.w("PointerService", "Target display not found, falling back to DEFAULT_DISPLAY")
            targetDisplayId = Display.DEFAULT_DISPLAY
            renderer = OverlayRenderer(this)
            isShow = false
            return
        }

        // 内置屏：用 WindowManager 覆盖层（已验证可用）。
        // 外接屏：普通应用无法把覆盖层窗口加到副屏（系统会静默重定位回内置屏），
        //         必须使用 Presentation 才能在指定 Display 上绘制。
        renderer = if (display.displayId == Display.DEFAULT_DISPLAY) {
            android.util.Log.d("PointerService", "Using OverlayRenderer for default display")
            OverlayRenderer(this)
        } else {
            android.util.Log.d("PointerService", "Using PresentationRenderer for display ${display.displayId}")
            PresentationRenderer(this, display)
        }
        isShow = false
    }

    private fun removeExistingPointer() {
        renderer?.destroy()
        renderer = null
        isShow = false
    }

    private fun startDisplayListener() {
        if (displayListener != null) return
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                // 新显示器插入，不需要处理
            }

            override fun onDisplayRemoved(displayId: Int) {
                // 如果目标显示器被移除，回退到主屏幕
                if (displayId == targetDisplayId) {
                    Handler(Looper.getMainLooper()).post {
                        removeExistingPointer()
                        targetDisplayId = Display.DEFAULT_DISPLAY
                        createFloatingPointer()
                    }
                }
            }

            override fun onDisplayChanged(displayId: Int) {
                // 显示器属性变化，不需要处理
            }
        }
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
    }

    private fun startUdpReceiver() {
        GlobalScope.launch {
            val buffer = ByteArray(1024)
            while (true) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val localAddr = findLocalAddressFor(packet.address)
                    clients.add(ClientInfo(packet.address, packet.port, localAddr))
                    val data = String(packet.data, 0, packet.length)
                    val values = data.split(",")
                    if (values.size == 5) {
                        val abs_x = values[0].toInt()
                        val abs_y = values[1].toInt()
                        val show_int = values[2].toInt()
                        val downing_int = values[3].toInt()

                        Handler(Looper.getMainLooper()).post {
                            handlePointer(abs_x, abs_y, show_int, downing_int)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // 6534 端口：二进制协议，vmouse_t 结构体（小端序）
    // struct vmouse_t { int32_t x; int32_t y; uint8_t state; } // 9 bytes
    // state: bit0=show, bit1=down
    private fun startBinaryUdpReceiver() {
        GlobalScope.launch {
            val buffer = ByteArray(9)
            while (true) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket6534.receive(packet)
                    val localAddr = findLocalAddressFor(packet.address)
                    clients.add(ClientInfo(packet.address, packet.port, localAddr))
                    if (packet.length == 9) {
                        val bb = ByteBuffer.wrap(packet.data).order(ByteOrder.LITTLE_ENDIAN)
                        val x = bb.getInt()
                        val y = bb.getInt()
                        val state = bb.get().toInt() and 0xFF
                        val show = state and 0x01
                        val down = (state shr 1) and 0x01

                        Handler(Looper.getMainLooper()).post {
                            handlePointer(x, y, show, down)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // 6535 端口：TCP 二进制协议，支持多个客户端连接
    // 数据格式：2字节 header + 9字节 vmouse_t = 11字节
    // header 内容忽略，vmouse_t 与 6534 端口格式一致（小端序）
    // 连接建立后也会上报屏幕方向
    private fun startTcpServer() {
        GlobalScope.launch {
            while (true) {
                try {
                    val clientSocket = serverSocket.accept()
                    tcpClients.add(clientSocket.getOutputStream())
                    android.util.Log.d("PointerService", "TCP client connected from ${clientSocket.remoteSocketAddress}")
                    // 立即发送当前屏幕方向
                    sendTcpOrientation(clientSocket.getOutputStream())
                    launch { handleTcpClient(clientSocket) }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private suspend fun handleTcpClient(clientSocket: Socket) {
        try {
            val input = clientSocket.getInputStream()
            val header = ByteArray(2)
            val body = ByteArray(9)
            while (true) {
                // 读取 2 字节 header，必须为 0x55 0xAA，否则丢弃直到同步
                if (!readFully(input, header)) break
                if (header[0] != 0x55.toByte() || header[1] != 0xAA.toByte()) {
                    // header 不匹配，逐字节滑动窗口重新同步
                    header[0] = header[1]
                    if (input.read() == -1) break
                    header[1] = input.read().toByte()
                    if (header[1] == (-1).toByte()) break
                    continue
                }
                // 读取 9 字节 vmouse_t
                if (!readFully(input, body)) break

                val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val x = bb.getInt()
                val y = bb.getInt()
                val state = bb.get().toInt() and 0xFF
                val show = state and 0x01
                val down = (state shr 1) and 0x01

                Handler(Looper.getMainLooper()).post {
                    handlePointer(x, y, show, down)
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("PointerService", "TCP client disconnected: ${e.message}")
        } finally {
            tcpClients.remove(clientSocket.getOutputStream())
            try { clientSocket.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Boolean {
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read == -1) return false
            offset += read
        }
        return true
    }

    private fun sendTcpOrientation(output: OutputStream) {
        try {
            val rotation = getDeviceRotation()
            val orientationByte: Byte = when (rotation) {
                Surface.ROTATION_0 -> 0x00
                Surface.ROTATION_90 -> 0x01
                Surface.ROTATION_180 -> 0x02
                Surface.ROTATION_270 -> 0x03
                else -> 0x00
            }
            output.write(byteArrayOf(orientationByte))
            output.flush()
        } catch (e: Exception) {
            // 忽略发送错误
        }
    }

    private fun startOrientationListener() {
        if (::orientationEventListener.isInitialized) return
        orientationEventListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                val rotation = getDeviceRotation()
                if (rotation != lastRotation) {
                    lastRotation = rotation
                    sendDeviceOrientation(rotation)
                }
            }
        }
        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable()
        }
    }

    private fun handlePointer(abs_x: Int, abs_y: Int, show_int: Int, downing_int: Int) {
        if (show_int == 1) {
            if (!isShow) {
                showPointer()
            }
            renderer?.setPosition(abs_x, abs_y)
            if (downing_int == 1) {
                renderer?.setScale(0.95f)
                sendDeviceOrientation(getDeviceRotation())
            } else {
                renderer?.setScale(1.0f)
            }
        } else {
            if (isShow) {
                removePointer()
            }
        }
    }

    private fun showPointer() {
        renderer?.show()
        sendDeviceOrientation(getDeviceRotation())
        isShow = true
    }

    private fun removePointer() {
        renderer?.hide()
        isShow = false
    }

    private fun getDeviceRotation(): Int {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        }
        return display?.rotation ?: Surface.ROTATION_0
    }

    private fun sendDeviceOrientation(rotation: Int) {
        val orientationByte: Byte = when (rotation) {
            Surface.ROTATION_0 -> 0x00
            Surface.ROTATION_90 -> 0x01
            Surface.ROTATION_180 -> 0x02
            Surface.ROTATION_270 -> 0x03
            else -> 0x00
        }
        GlobalScope.launch {
            val data = byteArrayOf(orientationByte)
            clients.forEach { client ->
                try {
                    val packet = DatagramPacket(data, data.size, client.remoteAddr, client.remotePort)
                    // 绑定到接收该客户端数据的本地网卡发包，解决多网卡路由问题
                    val sendSocket = client.localAddr?.let { localAddr ->
                        sendSockets.getOrPut(localAddr) {
                            DatagramSocket(0, localAddr)
                        }
                    } ?: socket  // 找不到本地地址时 fallback 到默认 socket
                    sendSocket.send(packet)
                } catch (e: Exception) {
                    // 发送失败时移除该客户端
                    clients.remove(client)
                }
            }
            val tcpData = byteArrayOf(orientationByte)
            val dead = mutableListOf<OutputStream>()
            tcpClients.forEach { output ->
                try {
                    output.write(tcpData)
                    output.flush()
                } catch (e: Exception) {
                    dead.add(output)
                }
            }
            tcpClients.removeAll(dead.toSet())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeExistingPointer()
        socket.close()
        socket6534.close()
        sendSockets.values.forEach { try { it.close() } catch (_: Exception) {} }
        sendSockets.clear()
        serverSocket.close()
        tcpClients.forEach { try { it.close() } catch (_: Exception) {} }
        tcpClients.clear()
        if (::orientationEventListener.isInitialized) {
            orientationEventListener.disable()
        }
        displayListener?.let {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.unregisterDisplayListener(it)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    // ---- 渲染器抽象 ----

    private interface PointerRenderer {
        fun show()
        fun hide()
        fun setPosition(x: Int, y: Int)
        fun setScale(scale: Float)
        fun destroy()
    }

    /** 创建光标 ImageView，左上角为锚点 */
    private fun createPointerImageView(): ImageView {
        return ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeResource(resources, R.drawable.pointer_arrow))
            alpha = 0f
            pivotX = 0f
            pivotY = 0f
        }
    }

    private fun fade(view: View, to: Float) {
        val animator = ObjectAnimator.ofFloat(view, "alpha", view.alpha, to)
        animator.duration = 200
        animator.start()
    }

    /**
     * 计算目标显示器相对内置屏的密度缩放比例。
     * 光标位图是固定像素尺寸，在低 DPI 大屏上会显得过大；按密度比例缩放后，
     * 光标在外接屏上的物理大小与内置屏（手机）保持一致，无需用户手动调整。
     */
    private fun densityScaleFor(display: Display): Float {
        val internalDpi = resources.displayMetrics.densityDpi
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val targetDpi = metrics.densityDpi
        if (internalDpi <= 0 || targetDpi <= 0) return 1f
        return targetDpi.toFloat() / internalDpi.toFloat()
    }

    /** 内置屏：WindowManager 覆盖层 */
    private inner class OverlayRenderer(context: Context) : PointerRenderer {
        private val windowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val imageView = createPointerImageView()
        private val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        private var attached = false

        override fun show() {
            if (!attached) {
                try {
                    windowManager.addView(imageView, params)
                    attached = true
                } catch (e: Exception) {
                    android.util.Log.e("PointerService", "Overlay addView failed", e)
                }
            }
            fade(imageView, 1f)
        }

        override fun hide() {
            fade(imageView, 0f)
        }

        override fun setPosition(x: Int, y: Int) {
            if (!attached) return
            params.x = x
            params.y = y
            try {
                windowManager.updateViewLayout(imageView, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun setScale(scale: Float) {
            imageView.scaleX = scale
            imageView.scaleY = scale
        }

        override fun destroy() {
            if (attached) {
                try {
                    windowManager.removeView(imageView)
                } catch (e: Exception) {
                    // ignore
                }
                attached = false
            }
        }
    }

    /** 外接屏：Presentation（绑定到指定 Display） */
    private inner class PresentationRenderer(
        context: Context,
        display: Display
    ) : PointerRenderer {
        // 按目标显示器密度缩放，使光标物理大小与内置屏一致
        private val baseScale = densityScaleFor(display)
        // 光标窗口偏移量：触摸点恰好落在光标窗口上时会被 DecorView 拦截，
        // 偏移后触摸点落在窗口之外，直接穿透到下方应用。
        private val cursorOffsetPx = 4
        private val imageView = createPointerImageView()
        private val container = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(
                imageView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.START
                )
            )
        }
        private val presentation = Presentation(context, display).apply {
            setCancelable(false)
            setContentView(container)
            window?.apply {
                // 不要调用 setType 覆盖窗口类型：Presentation 内部已用
                // TYPE_PRESENTATION(2037) 创建了绑定到目标 Display 的 window context，
                // 强行改成 TYPE_APPLICATION_OVERLAY(2038) 会导致 window type mismatch 异常。
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                // 关键：窗口设为光标大小(WRAP_CONTENT)并按坐标移动，而非全屏容器。
                // 全屏 Presentation 窗口即使带 FLAG_NOT_TOUCHABLE 也会拦截外接屏触摸，
                // 改成跟随坐标的小窗口后，行为与内置屏 OverlayRenderer 一致，触摸正常穿透。
                setLayout(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
                val lp = attributes
                lp.gravity = Gravity.TOP or Gravity.START
                lp.x = 0
                lp.y = 0
                attributes = lp
            }
        }
        private var shown = false

        override fun show() {
            if (!shown) {
                try {
                    presentation.show()
                    shown = true
                    android.util.Log.d(
                        "PointerService",
                        "Presentation shown on display ${presentation.display?.displayId}, baseScale=$baseScale"
                    )
                    // 双重防护：FLAG_NOT_TOUCHABLE 在 Window 级别，但 Dialog 的 DecorView
                    // 默认实现仍可能消费触摸事件。在 View 层面也显式禁用，确保触摸穿透。
                    presentation.window?.decorView?.apply {
                        isClickable = false
                        isFocusable = false
                        setOnTouchListener { _, _ -> false }
                    }
                    container.isClickable = false
                    container.isFocusable = false
                    container.setOnTouchListener { _, _ -> false }
                } catch (e: Exception) {
                    android.util.Log.e("PointerService", "Presentation show failed", e)
                }
            }
            imageView.scaleX = baseScale
            imageView.scaleY = baseScale
            fade(imageView, 1f)
        }

        override fun hide() {
            fade(imageView, 0f)
        }

        override fun setPosition(x: Int, y: Int) {
            // 移动整个光标小窗口，而非在全屏窗口内 translation，
            // 这样窗口仅覆盖光标自身像素，外接屏其余区域触摸正常穿透。
            val window = presentation.window ?: return
            val lp = window.attributes
            // 光标偏移 4px：触摸点恰好落在光标窗口上时会被 DecorView 拦截，
            // 偏移后触摸点落在窗口之外，直接穿透到下方应用。
            lp.x = x + cursorOffsetPx
            lp.y = y + cursorOffsetPx
            // 显式重置 flags，确保某些 ROM 在 attributes 赋回时不会丢失标志
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            try {
                window.attributes = lp
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun setScale(scale: Float) {
            // 按压系数叠加到密度基准缩放上
            imageView.scaleX = baseScale * scale
            imageView.scaleY = baseScale * scale
        }

        override fun destroy() {
            try {
                presentation.dismiss()
            } catch (e: Exception) {
                // ignore
            }
            shown = false
        }
    }
}
