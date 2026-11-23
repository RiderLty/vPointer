package com.gtm.vpointer.lua

import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom

/**
 * 极简 RFC 6455 WebSocket 客户端（明文回环/局域网专用）。
 *
 * 为什么手写而不是引 OkHttp：本工程刻意保持零网络依赖（只有 Compose 那套），
 * 为了一条本地日志通道拉进 OkHttp 不划算，也和 PortForwarder 手写转发引擎的
 * 风格一致。只实现必须的部分：
 *   - 握手（GET + Upgrade + Sec-WebSocket-Key / Version 13）
 *   - 数据帧：文本 / 二进制 / 分片拼接
 *   - 控制帧：ping → pong、close
 * 不支持：wss/TLS、扩展协商、permessage-deflate。
 *
 * 服务端（pico-hid-mapper / 8000 端口）推的是**二进制帧**，payload[0] 是帧类型：
 *   0x01 文本日志   0x02 陀螺仪 32B   0x03 光标 9B(vmouse_t)
 * 本类只负责把字节交出去，类型判断在 [LuaLogService]。
 */
class MiniWebSocket(private val path: String) {

    interface Listener {
        fun onOpen()
        fun onBinary(data: ByteArray)
        fun onText(text: String)
        /** 对端关闭或本地 close()：读循环退出即视为关闭 */
        fun onClosed()
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null

    /** 完成握手。失败抛 IOException，由调用方决定是否重连。 */
    fun connect(sock: Socket, hostHeader: String) {
        socket = sock
        val inStream = BufferedInputStream(sock.getInputStream(), 8192)
        val outStream = sock.getOutputStream()
        input = inStream
        output = outStream

        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("User-Agent: vPointer-LuaLog\r\n")
            append("\r\n")
        }
        outStream.write(request.toByteArray(Charsets.ISO_8859_1))
        outStream.flush()

        // 逐字节读到 \r\n\r\n：用 BufferedReader 会把紧跟其后的帧字节预读进缓冲，
        // 之后再从原始流读帧就会丢数据。
        val header = readHttpHeader(inStream)
        val statusLine = header.lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains(" 101")) {
            throw IOException("WS 握手失败：$statusLine")
        }
    }

    private fun readHttpHeader(ins: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = ins.read()
            if (b < 0) throw IOException("握手期间连接断开")
            sb.append(b.toChar())
            if (sb.length >= 4) {
                val n = sb.length
                if (sb[n - 1] == '\n' && sb[n - 2] == '\r' && sb[n - 3] == '\n' && sb[n - 4] == '\r') break
            }
        }
        return sb.toString()
    }

    /**
     * 阻塞读一帧并分发；返回 false 表示连接已结束（EOF 或收到 close）。
     * 分片消息（opcode=0 续帧）会一直累积到 FIN。
     */
    fun readFrame(listener: Listener): Boolean {
        val acc = ByteArrayOutputStream()
        var messageOpcode = -1
        while (true) {
            val b0 = readByte() ?: return false
            val b1 = readByte() ?: return false
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                val hi = readByte() ?: return false
                val lo = readByte() ?: return false
                len = (((hi shl 8) or lo) and 0xFFFF).toLong()
            } else if (len == 127L) {
                var l = 0L
                repeat(8) {
                    val b = readByte() ?: return false
                    l = (l shl 8) or b.toLong()
                }
                len = l
            }
            if (len < 0 || len > MAX_FRAME_BYTES) throw IOException("帧过大：$len")
            val mask = if (masked) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(len.toInt())
            if (payload.isNotEmpty()) readFully(payload)
            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                }
            }

            when (opcode) {
                0x0, 0x1, 0x2 -> {
                    if (messageOpcode < 0) messageOpcode = opcode
                    acc.write(payload)
                    if (fin) {
                        val data = acc.toByteArray()
                        acc.reset()
                        if (messageOpcode == 0x1) listener.onText(String(data, Charsets.UTF_8))
                        else listener.onBinary(data)
                        messageOpcode = -1
                    }
                }
                0x8 -> { // close
                    runCatching { sendFrame(0x8, ByteArray(0)) }
                    return false
                }
                0x9 -> runCatching { sendFrame(0xA, payload) } // ping → pong
                0xA -> Unit                                   // pong：忽略
                else -> Unit                                  // 其他控制帧：忽略
            }
        }
    }

    /** 主动断开。读循环会因 socket 关闭而返回 false。 */
    fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun readByte(): Int? {
        val b = input?.read() ?: return null
        return if (b < 0) null else b
    }

    private fun readFully(buf: ByteArray) {
        val inStream = input ?: throw IOException("socket 已关闭")
        var off = 0
        while (off < buf.size) {
            val n = inStream.read(buf, off, buf.size - off)
            if (n <= 0) throw IOException("读取帧体时连接断开")
            off += n
        }
    }

    /** 客户端发出的帧必须加掩码（RFC 6455 §5.3）。 */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val outStream = output ?: return
        val head = ByteArrayOutputStream()
        head.write(0x80 or (opcode and 0x0F))
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        when {
            payload.size < 126 -> head.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                head.write(0x80 or 126)
                head.write((payload.size ushr 8) and 0xFF)
                head.write(payload.size and 0xFF)
            }
            else -> {
                head.write(0x80 or 127)
                var l = payload.size.toLong()
                val tmp = ByteArray(8)
                for (i in 7 downTo 0) {
                    tmp[i] = (l and 0xFF).toByte()
                    l = l ushr 8
                }
                head.write(tmp)
            }
        }
        head.write(mask)
        val body = ByteArray(payload.size) { i ->
            (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        }
        synchronized(this) {
            outStream.write(head.toByteArray())
            outStream.write(body)
            outStream.flush()
        }
    }

    private companion object {
        const val MAX_FRAME_BYTES = 1L shl 20 // 1 MiB，超出视为协议异常
    }
}
