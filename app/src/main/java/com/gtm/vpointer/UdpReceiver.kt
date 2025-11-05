package com.gtm.vpointer

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpReceiver(private val listener: (Int, Int, Int, Int, Int) -> Unit) {

    companion object {
        private val socket = DatagramSocket(6533)
        private val clients = mutableSetOf<Pair<InetAddress, Int>>()

        fun sendOrientation(orientation: Byte) {
            GlobalScope.launch {
                clients.forEach { (address, port) ->
                    try {
                        val data = byteArrayOf(orientation)
                        val packet = DatagramPacket(data, data.size, address, port)
                        socket.send(packet)
                    } catch (e: Exception) {
                        // Ignore send errors
                    }
                }
            }
        }
    }

    fun startReceiving() {
        GlobalScope.launch {
            while (true) {
                try {
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)

                    clients.add(Pair(packet.address, packet.port))

                    val data = String(packet.data, 0, packet.length)
                    val values = data.split(",")
                    if (values.size == 5) {
                        val abs_x = values[0].toInt()
                        val abs_y = values[1].toInt()
                        val show_int = values[2].toInt()
                        val downing_int = values[3].toInt()
                        val global_device_orientation = values[4].toInt()

                        listener(abs_x, abs_y, show_int, downing_int, global_device_orientation)
                    }
                } catch (e: Exception) {
                    // Handle exception
                }
            }
        }
    }

    fun stopReceiving() {
        socket.close()
    }
}
