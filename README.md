# 虚拟光标 (vPointer)

在安卓设备上显示虚拟光标，通过 UDP/TCP 接收坐标控制信号，支持内置显示器和外接显示器（HDMI 等）。

## 监听端口

| 端口 | 协议 | 说明 |
|------|------|------|
| 6533 | UDP | 文本协议 |
| 6534 | UDP | 二进制协议 |
| 6535 | TCP | 二进制协议，支持多客户端，带屏幕方向回报 |

## TCP 协议（端口 6535）

### 连接

TCP 服务器监听 `0.0.0.0:6535`，支持多个客户端同时连接。连接由客户端主动发起，服务端被动 accept。

```
客户端                          服务端（安卓设备）
  |                                |
  |-------- TCP SYN ------------->|
  |<------- SYN-ACK --------------|
  |-------- ACK ----------------->|  连接建立
  |                                |
  |<------- 屏幕方向（1字节）------|  服务端立即发送当前屏幕方向
  |                                |
  |-------- 光标数据（11字节）---->|  客户端发送光标控制
  |-------- 光标数据（11字节）---->|
  |                                |
  |<------- 屏幕方向（1字节）------|  屏幕旋转时服务端主动推送
  |                                |
  |-------- FIN ----------------->|  任一端断开
```

### 屏幕方向回报（服务端 → 客户端）

连接建立时，服务端**立即**发送 1 字节的当前屏幕方向。之后屏幕方向发生变化时，服务端会**主动推送**新的方向值。

| 字节值 | 含义 |
|--------|------|
| `0x00` | 0°（竖屏正向） |
| `0x01` | 90°（横屏，顺时针） |
| `0x02` | 180°（竖屏倒置） |
| `0x03` | 270°（横屏，逆时针） |

客户端应当在连接后立即读取这 1 字节，后续随时准备接收方向变化通知。

### 光标控制数据（客户端 → 服务端）

每次发送 **11 字节**，格式如下：

```
偏移    长度    类型       说明
0       2       uint8[2]  Header（内容任意，会被忽略）
2       4       int32     X 坐标（有符号，小端序）
6       4       int32     Y 坐标（有符号，小端序）
10      1       uint8     状态位
```

**状态字节（byte[10]）位定义：**

| Bit | 名称 | 说明 |
|-----|------|------|
| 0   | show | 1=显示光标，0=隐藏光标 |
| 1   | down | 1=按下（光标缩小 0.95x），0=释放 |
| 2-7 | 保留 | 未使用 |

**字节序：小端序（Little-Endian）**，与 x86/ARM 原生一致。

### C 语言结构体定义

```c
#include <stdint.h>

// 网络传输的数据包（11字节）
typedef struct __attribute__((packed)) {
    uint8_t header[2];  // 任意值，服务端忽略
    int32_t x;          // 光标 X 坐标
    int32_t y;          // 光标 Y 坐标
    union {
        uint8_t val;
        struct __attribute__((packed)) {
            uint8_t show : 1;  // bit0: 1=显示
            uint8_t down : 1;  // bit1: 1=按下
        } bit;
    } state;
} vpointer_packet_t;

// 屏幕方向响应（1字节）
typedef enum {
    VPOINTER_ROTATION_0   = 0x00,  // 竖屏正向
    VPOINTER_ROTATION_90  = 0x01,  // 横屏顺时针90°
    VPOINTER_ROTATION_180 = 0x02,  // 竖屏倒置
    VPOINTER_ROTATION_270 = 0x03,  // 横屏逆时针90°
} vpointer_rotation_t;
```

### 完整通信示例（C）

```c
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <arpa/inet.h>

int main() {
    // 1. 连接
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in addr = {
        .sin_family = AF_INET,
        .sin_port = htons(6535),
        .sin_addr.s_addr = inet_addr("192.168.1.100")
    };
    connect(sock, (struct sockaddr*)&addr, sizeof(addr));

    // 2. 读取屏幕方向（连接后服务端立即发送）
    uint8_t orientation;
    recv(sock, &orientation, 1, 0);
    printf("屏幕方向: %d\n", orientation);

    // 3. 发送光标控制（11字节）
    uint8_t packet[11] = {0};
    packet[0] = 0xAA;  // header（任意值）
    packet[1] = 0x55;
    // x = 100, 小端序
    packet[2] = 100; packet[3] = 0; packet[4] = 0; packet[5] = 0;
    // y = 200, 小端序
    packet[6] = 200; packet[7] = 0; packet[8] = 0; packet[9] = 0;
    // state: show=1, down=0
    packet[10] = 0x01;
    send(sock, packet, 11, 0);

    // 4. 发送按下
    packet[10] = 0x03;  // show=1, down=1
    send(sock, packet, 11, 0);

    // 5. 发送释放
    packet[10] = 0x01;  // show=1, down=0
    send(sock, packet, 11, 0);

    // 6. 隐藏光标
    packet[10] = 0x00;  // show=0, down=0
    send(sock, packet, 11, 0);

    close(sock);
    return 0;
}
```

### Python 示例

```python
import socket
import struct

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.connect(("192.168.1.100", 6535))

# 读取屏幕方向
orientation = sock.recv(1)[0]
print(f"屏幕方向: {orientation}")

# 发送光标控制：header(2) + x(int32) + y(int32) + state(uint8)
header = b'\xAA\x55'
x, y, show, down = 100, 200, 1, 0
state = (show & 1) | ((down & 1) << 1)
packet = header + struct.pack('<iiB', x, y, state)
sock.send(packet)

sock.close()
```

### 注意事项

- **Header 可填任意值**，服务端不校验，仅跳过这 2 字节。
- **坐标为绝对像素值**，相对于目标显示器的分辨率。外接屏与内置屏分辨率不同，坐标范围也不同。
- **光标位置有 4px 偏移**（解决触摸穿透问题），实际显示位置为 `(x+4, y+4)`。
- **屏幕方向推送是异步的**，客户端随时可能收到 1 字节的方向更新，需与光标数据流分开处理（可用 `select`/`poll` 或独立线程读取）。
- **连接断开**由任一端发起 FIN，服务端自动清理资源。发送失败时服务端会静默移除该客户端。

## UDP 协议

### 端口 6533（文本协议）

发送 ASCII 文本到设备 IP 的 6533 端口：

```
x,y,show,down,0\n
```

逗号分隔，5 个字段，最后一个字段未使用（填 0）。

### 端口 6534（二进制协议）

发送 9 字节 UDP 包到设备 IP 的 6534 端口，格式为 `vmouse_t` 结构体（小端序）：

```
偏移  长度  类型     说明
0     4     int32   X 坐标
4     4     int32   Y 坐标
8     1     uint8   状态位（bit0=show, bit1=down）
```

## 外接屏触摸穿透

外接显示器使用 `Presentation`（Dialog 子类）渲染光标。Dialog 的 DecorView 默认会消费触摸事件，即使设置了 `FLAG_NOT_TOUCHABLE`，在某些 ROM 上触摸仍会被拦截，导致外接屏上点击无响应。

**解决方案**：光标窗口相对触摸坐标偏移 4px。偏移后触摸点落在光标窗口之外，直接穿透到下方应用，不影响触摸操作。视觉上 4px 的偏移几乎不可见。
