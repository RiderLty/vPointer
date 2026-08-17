package com.gtm.vpointer

import java.io.Serializable

/**
 * 一张网卡在诊断界面展示所需的信息。实现 [Serializable] 以便经广播 extra 从
 * PortForwardService 回传 MainActivity。
 */
data class NicInfo(
    val name: String,
    val ipv4s: List<String>,
    val isUp: Boolean,
    val isTarget: Boolean
) : Serializable
