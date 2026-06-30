#!/usr/bin/env python3
"""vPointer UDP 二进制协议测试程序（端口 6534）

发送 vmouse_t 结构体（9字节，小端序）到设备的 6534 端口，
并在本地监听回复的屏幕方向数据。

用法：
    python3 test_udp.py <设备IP>
    python3 test_udp.py 192.168.1.100
"""

import socket
import struct
import sys
import time
import threading


def build_packet(x: int, y: int, show: bool, down: bool) -> bytes:
    """构建 9 字节的 vmouse_t 包"""
    state = (1 if show else 0) | ((1 if down else 0) << 1)
    return struct.pack('<iiB', x, y, state)


def orientation_listener(sock: socket.socket):
    """后台线程：监听服务端回复的屏幕方向"""
    while True:
        try:
            data, addr = sock.recvfrom(1)
            if data:
                orientation = data[0]
                names = {0: "0° 竖屏", 1: "90° 横屏", 2: "180° 倒置", 3: "270° 横屏"}
                print(f"[方向] {names.get(orientation, f'未知({orientation})')}  (来自 {addr})")
        except Exception:
            break


def main():
    if len(sys.argv) < 2:
        print(f"用法: {sys.argv[0]} <设备IP>")
        sys.exit(1)

    host = sys.argv[1]
    port = 6534

    # 创建 UDP socket，绑定到随机端口用于接收方向回复
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(('0.0.0.0', 0))
    local_port = sock.getsockname()[1]
    print(f"本地监听端口: {local_port}")
    print(f"目标: {host}:{port}")

    # 启动后台方向监听
    listener = threading.Thread(target=orientation_listener, args=(sock,), daemon=True)
    listener.start()

    print("\n--- 测试开始 ---\n")
    time.sleep(0.3)

    # 测试 1: 显示光标并移动到 (100, 100)
    print("[1] 显示光标 → (100, 100)")
    sock.sendto(build_packet(100, 100, show=True, down=False), (host, port))
    time.sleep(0.5)

    # 测试 2: 移动到 (500, 500)
    print("[2] 移动光标 → (500, 500)")
    sock.sendto(build_packet(500, 500, show=True, down=False), (host, port))
    time.sleep(0.5)

    # 测试 3: 按下
    print("[3] 按下光标")
    sock.sendto(build_packet(500, 500, show=True, down=True), (host, port))
    time.sleep(0.3)

    # 测试 4: 释放
    print("[4] 释放光标")
    sock.sendto(build_packet(500, 500, show=True, down=False), (host, port))
    time.sleep(0.3)

    # 测试 5: 移动到屏幕中央 (960, 540)
    print("[5] 移动光标 → (960, 540)")
    sock.sendto(build_packet(960, 540, show=True, down=False), (host, port))
    time.sleep(0.5)

    # 测试 6: 隐藏光标
    print("[6] 隐藏光标")
    sock.sendto(build_packet(0, 0, show=False, down=False), (host, port))
    time.sleep(0.3)

    # 测试 7: 重新显示
    print("[7] 重新显示光标 → (200, 200)")
    sock.sendto(build_packet(200, 200, show=True, down=False), (host, port))
    time.sleep(0.5)

    print("\n--- 测试完成 ---")
    print("按 Enter 退出（方向监听仍在后台运行）...")
    try:
        input()
    except (EOFError, KeyboardInterrupt):
        pass

    sock.close()
    print("已关闭")


if __name__ == "__main__":
    main()
