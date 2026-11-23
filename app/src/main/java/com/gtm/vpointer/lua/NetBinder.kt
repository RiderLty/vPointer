package com.gtm.vpointer.lua

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.gtm.vpointer.NicInfo
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * 出站 Socket 的「绑定网卡」工具，策略与 [com.gtm.vpointer.PortForwarder] 的上游连接完全一致：
 *
 *  1. 按**子网匹配**识别该走哪块网卡（Android 6+ 读不到网卡 MAC，故不用 MAC）：
 *     遍历 [NetworkInterface]，找子网包含目标 IP 的接口，拿到“接口名 + 本机 IP”。
 *     这与 [com.gtm.vpointer.PointerService.findLocalAddressFor] 是同一套算法，多网卡设备上
 *     默认路由可能不是目标网卡（如以太网 192.168.73.x 与 WLAN 并存）。
 *  2. 用 [ConnectivityManager] 找到同名 [Network]，对 Socket 调 [Network.bindSocket]，
 *     强制流量走该网络（等价 root 才能用的 SO_BINDTODEVICE）。
 *  3. 该网卡若只是裸网卡、没被系统登记成 Network，则回退到**源地址绑定**
 *     （bind 到该网卡的 IPv4），Linux 内核按源地址选出口。
 *  4. 目标就是回环地址时全部跳过：127.0.0.1 走 loopback，绑到任何物理网卡都会连不上。
 *
 * Lua 日志的默认目标是 127.0.0.1:8000（同机 WS 服务），此时走第 4 条；把地址改成
 * 局域网 IP（如另一台设备的 10.204.38.133）时，第 1–3 条才真正起作用。
 */
class NetBinder(private val context: Context) {

    /** 绑定结果：socket 已 connect 完成，[via] 是人类可读的出口说明，用于 UI/日志。 */
    data class Bound(val socket: Socket, val via: String)

    /** 解析结果：目标该走哪块网卡（[loopback]=true 时三者皆 null，表示同机回环）。 */
    data class Target(
        val ifaceName: String?,
        val network: Network?,
        val localIp: InetAddress?,
        val loopback: Boolean
    )

    /** 找不到子网包含目标的网卡。**这是「等待网卡」而非「连接失败」**，调用方应放慢重试。 */
    class TargetUnavailableException(host: String) :
        java.io.IOException("未找到子网包含 $host 的网卡")

    /**
     * 解析目标该走哪块网卡：回环直接放行；否则必须子网命中，命中再按名字找同名 Network。
     * 与 PortForwarder.rescanTarget 的产出（name / network / localIp）一一对应。
     */
    fun resolve(host: String): Target {
        val remote = InetAddress.getByName(host)
        if (remote.isLoopbackAddress) return Target(null, null, null, true)
        val hit = findLocalAddressFor(remote) ?: throw TargetUnavailableException(host)
        return Target(hit.first, findNetworkByName(hit.first), hit.second, false)
    }

    fun open(host: String, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): Bound {
        val target = resolve(host)
        val remote = InetAddress.getByName(host)

        var sock = Socket()
        var boundToNetwork = false
        if (target.network != null) {
            boundToNetwork = runCatching {
                target.network.bindSocket(sock)
                true
            }.getOrElse { e ->
                Log.w(TAG, "network.bindSocket 失败：${e.message}")
                runCatching { sock.close() }
                // 部分实现抛异常前已改动 Socket 状态，换新 Socket 做源地址回退
                sock = Socket()
                false
            }
        }

        // 与 PortForwarder 同一条闸门：有 Network 却绑不上、又没有源地址可回退时**放弃连接**，
        // 宁可不连也不让它落到默认网卡（本机默认网是 WiFi，包会从 wlan0 出去找 192.168.73.1）。
        if (target.network != null && !boundToNetwork && target.localIp == null) {
            runCatching { sock.close() }
            throw java.io.IOException(
                "网卡 ${target.ifaceName} 绑定失败且无源地址可回退，已放弃（避免走默认网卡）"
            )
        }

        if (!boundToNetwork && target.localIp != null) {
            try {
                sock.bind(InetSocketAddress(target.localIp, 0))
            } catch (e: Exception) {
                runCatching { sock.close() }
                throw e
            }
        }
        sock.tcpNoDelay = true
        sock.connect(InetSocketAddress(remote, port), timeoutMs)

        val via = when {
            boundToNetwork -> "Network(${target.ifaceName})"
            target.localIp != null -> "源地址(${target.localIp.hostAddress})"
            target.loopback -> "loopback"
            else -> "默认路由"
        }
        Log.d(TAG, "open $host:$port via $via")
        return Bound(sock, via)
    }

    /** 子网匹配：返回 (接口名, 本机 IPv4)。与 PointerService 内实现同一算法。 */
    private fun findLocalAddressFor(remote: InetAddress): Pair<String, InetAddress>? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val localAddr = ia.address ?: continue
                    if (localAddr.javaClass != remote.javaClass) continue
                    if (sameSubnet(localAddr, ia.networkPrefixLength, remote)) {
                        return ni.name to localAddr
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "网卡枚举失败：${e.message}")
        }
        return null
    }

    /**
     * 列出全部网卡，并标记哪一块的子网包含 [targetHost]。
     * 供界面做「网卡识别诊断」用——与端口转发页那份列表同构，用户看到的是同一套判定。
     */
    fun describeNics(targetHost: String): List<NicInfo> {
        val remote = runCatching { InetAddress.getByName(targetHost) }.getOrNull()
        val result = mutableListOf<NicInfo>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (ni.isLoopback) continue
                val ipv4s = mutableListOf<String>()
                var isTarget = false
                if (ni.isUp) {
                    for (ia in ni.interfaceAddresses) {
                        val addr = ia.address ?: continue
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            ipv4s += addr.hostAddress
                            if (remote != null && sameSubnet(addr, ia.networkPrefixLength, remote)) {
                                isTarget = true
                            }
                        }
                    }
                }
                result += NicInfo(ni.name, ipv4s, ni.isUp, isTarget)
            }
        } catch (e: Exception) {
            Log.w(TAG, "网卡枚举失败：${e.message}")
        }
        return result
    }

    /** 按 prefix length 计算掩码比较网络部分，与 PortForwarder.sameSubnet 同一算法 */
    private fun sameSubnet(local: InetAddress, prefix: Short, remote: InetAddress): Boolean {
        val lb = local.address
        val rb = remote.address
        if (lb.size != rb.size) return false
        val prefixLength = prefix.toInt()
        if (prefixLength !in 0..(lb.size * 8)) return false
        val fullBytes = prefixLength / 8
        val remainBits = prefixLength % 8
        for (i in 0 until fullBytes) {
            if (lb[i] != rb[i]) return false
        }
        if (remainBits > 0 && fullBytes < lb.size) {
            val mask = (0xFF shl (8 - remainBits)) and 0xFF
            if ((lb[fullBytes].toInt() and mask) != (rb[fullBytes].toInt() and mask)) return false
        }
        return true
    }

    private fun findNetworkByName(name: String): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return try {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { n ->
                runCatching { cm.getLinkProperties(n)?.interfaceName }.getOrNull() == name
            }
        } catch (e: SecurityException) {
            // 缺 ACCESS_NETWORK_STATE 时跳过，回退到源地址绑定
            Log.w(TAG, "ConnectivityManager 不可用：${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "NetBinder"
        const val CONNECT_TIMEOUT_MS = 5000
    }
}
