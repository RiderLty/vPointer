# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Android 应用（Kotlin + Jetpack Compose，单模块 `:app`），在安卓设备上显示虚拟光标，通过 UDP/TCP 接收坐标信号控制，支持内置显示器（WindowManager 覆盖层）和外接显示器（Presentation）。附带网卡 TCP 端口转发功能（按 MAC 识别目标网卡）。完整协议文档见 `README.md`（端口 6533/6534/6535 与端口转发）。

## 构建与调试命令

```bash
./gradlew assembleDebug       # 构建 debug APK
./gradlew installDebug        # 安装到已连接设备
./gradlew assembleRelease     # 构建 release（需要签名配置，见下）
./gradlew test                # 运行本地单元测试（目前仅一个示例测试）
```

- Release 签名：`app/build.gradle.kts` 引用 `app/release-key.jks`（gitignored，仓库中不存在），密码来自环境变量 `KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。本地无 keystore 时 `assembleRelease` 会失败——日常用 `assembleDebug`。
- CI（`.github/workflows/build.yml`）：push `v*` tag 触发，从 secret `ANDROID_KEYSTORE_BASE64` 还原 keystore，在 Docker 镜像 `riderlty/vpoint-builder` 内执行 `gradle assembleRelease` 并发布 Release。
- 构建环境：Gradle 8.13、AGP 8.12.2、Kotlin 2.0.21、JDK 17、compileSdk 36 / minSdk 24 / Java 11。`cmd.sh` / `Dockerfile-builder` 记录手动搭建构建环境与 Docker 构建的步骤。
- 调试日志：`adb logcat | grep -i "vpointer\|PointerService\|MainActivity\|PortForward"`。
- 协议手动测试：`python3 test_tcp.py <设备IP>`（TCP 6535）、`python3 test_udp.py <设备IP>`（UDP 6534）。需真机/模拟器在局域网内，发送坐标并观察光标。

## 架构

### 两个互相独立的前台服务

`PointerService`（虚拟光标）与 `PortForwardService`（端口转发）完全解耦，可单独启停。二者都是 `foregroundServiceType="specialUse"`，通过**包名限定（`setPackage(packageName)`）的隐式广播**把状态回传给 `MainActivity`——`MainActivity` 注册两个 BroadcastReceiver（`statusReceiver` / `forwardReceiver`）监听 `ACTION_STATUS` / `ACTION_FORWARD_STATUS`。新增服务状态上报应沿用这一模式。

### PointerService（`PointerService.kt`，核心）

onCreate 时一次性绑定 UDP 6533、UDP 6534、TCP 6535 三个端口，任一失败全部回滚并 `stopSelf()`（端口冲突是常见启动失败原因）。三个接收器各自在 `GlobalScope.launch` 的无限循环里收包、解析，再 `Handler(mainLooper).post { handlePointer(x, y, show, down) }` 切回主线程驱动渲染器。

- **协议**：6533 文本 `x,y,show,down,0\n`；6534 二进制 `vmouse_t` 9 字节小端（int32 x + int32 y + uint8 state，bit0=show，bit1=down）；6535 TCP 11 字节（header `0x55 0xAA` + 9 字节 vmouse_t），header 用三态状态机逐字节滑动同步。TCP 连接建立时立即回发 1 字节屏幕方向，旋转时主动推送；`down` 期间方向上报节流到 1Hz（用 `DisplayListener` 兜底防漏发，勿移除）。
- **旋转检测刻意不用传感器**：方向上报靠 `startDisplayListener()` 里 `DisplayManager.DisplayListener.onDisplayChanged`（旋转会改 default display 的 rotation 属性）。勿改回 `OrientationEventListener`——它会持续占用加速度计（实测 5Hz、服务常驻期间全天开启）并每 200ms 唤醒 CPU 做 `getDeviceRotation()`，是显著的常驻耗电。
- **`handlePointer` 有坐标/按下去重**（`lastX/lastY/lastDown`，`removeExistingPointer()` 里重置）：坐标或按下状态未变时跳过 `setPosition`/`setScale`（binder IPC）。新增路径注意保持去重语义，否则高频重复发包会引入不必要的系统调用。
- **坐标是绝对像素值**，相对目标显示器分辨率；渲染器只做位移，不做坐标系换算。

### 渲染器抽象（PointerService 内部）

`PointerRenderer` 接口有两个实现，**这是本仓库最需要小心的地方**：

- `OverlayRenderer`：内置屏用 `WindowManager` 的 `TYPE_APPLICATION_OVERLAY` 覆盖层。
- `PresentationRenderer`：外接屏**必须**用 `Presentation`。普通应用无法把覆盖层窗口加到副屏（系统会静默重定位回内置屏）。且窗口必须设为 WRAP_CONTENT、跟随坐标移动（`window.attributes.x/y`），不能全屏——全屏 Presentation 即使带 `FLAG_NOT_TOUCHABLE` 也会在部分 ROM 上拦截外接屏触摸。光标相对触摸点偏移 4px（`cursorOffsetPx`）实现触摸穿透。按显示器 DPI 密度缩放（`densityScaleFor`）保持物理大小一致。

修改渲染逻辑时，两个实现必须同步改动，且保留上述注释里记录的平台陷阱。

### 多网卡路由（PointerService）

`findLocalAddressFor(remote)` 遍历本机网卡，按子网匹配远端 IP，把每个 UDP 客户端缓存到 `clients`（含其本地网卡 IP）。回发屏幕方向时，用**绑定到对应网卡的发送 socket**（`sendSockets` 缓存）发包，避免默认路由走错网卡。`sendDeviceOrientation` 同时向 UDP clients 和 TCP clients（`OutputStream` 集合）发方向字节。

### 端口转发（`PortForwardService.kt` + `PortForwarder.kt`）

非 root 实现 socat `so-bindtodevice` 的等价方案：监听端口 `8000`（UI 可改，范围 1024~65535）→ 转发到 `192.168.73.1:80`。目标网卡按 MAC `00:02:73:6a:96:01` 在 `NetworkInterface` 枚举中识别（名称可能为 eth0/eth1/eth2 等，与名称无关）；找到后用 `ConnectivityManager` 匹配同名 `Network` 并 `Network.bindSocket()` 强制走该网卡，无 Network 时回退绑定该网卡的 IPv4 源地址。网卡热插拔由 `NetworkCallback` + 2 秒轮询兜底感知：拔出只暂停上游（监听端口保留），重插自动恢复。需要 `ACCESS_NETWORK_STATE`，缺失时回退到 NetworkInterface 枚举（有降级逻辑，勿破坏）。

### 显示器枚举（`DisplayManagerHelper.kt`）

包装 `DisplayManager`，产出 `DisplayInfo`（含 `isInternal` 判断：`displayId == Display.DEFAULT_DISPLAY`）。`MainActivity` 和 `PointerService` 各持有一份实例。`MainActivity` 用它列显示器、选目标显示器（`EXTRA_DISPLAY_ID` 传给服务）；目标显示器被拔出时 `PointerService` 通过 `DisplayListener` 回退到内置屏。

### 其他

- `UdpReceiver.kt` 是**遗留死代码**（无任何引用），别和 PointerService 里的 `startUdpReceiver()` 私有方法混淆。
- UI 全部 Jetpack Compose（Material3），两个 Tab 页 `DisplaySelectScreen` / `PortForwardScreen`，状态经回调向上提升到 `MainActivity` 的 Compose state。
- 权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`SYSTEM_ALERT_WINDOW`（启动光标服务前需动态申请）、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`。
