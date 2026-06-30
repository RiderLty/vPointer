#!/usr/bin/env python3
"""vPointer TCP 协议测试程序

连接安卓设备的 6535 端口，发送光标控制数据，接收屏幕方向回报。

用法：
    python3 test_tcp.py <设备IP>
    python3 test_tcp.py 192.168.1.100
"""

import socket
import struct
import sys
import time
import threading

HEADER = b'\x55\xAA'  # 固定 header


def build_packet(x: int, y: int, show: bool, down: bool) -> bytes:
    """构建 11 字节的光标控制包"""
    state = (1 if show else 0) | ((1 if down else 0) << 1)
    return HEADER + struct.pack('<iiB', x, y, state)


def recv_orientation(sock: socket.socket) -> int:
    """读取 1 字节屏幕方向"""
    data = sock.recv(1)
    if not data:
        raise ConnectionError("连接已关闭")
    return data[0]


def orientation_listener(sock: socket.socket):
    """后台线程：持续监听屏幕方向变化"""
    while True:
        try:
            orientation = recv_orientation(sock)
            names = {0: "0° 竖屏", 1: "90° 横屏", 2: "180° 倒置", 3: "270° 横屏"}
            print(f"[方向] {names.get(orientation, f'未知({orientation})')}")
        except Exception:
            break


def main():
    if len(sys.argv) < 2:
        print(f"用法: {sys.argv[0]} <设备IP>")
        sys.exit(1)

    host = sys.argv[1]
    port = 6535

    print(f"连接 {host}:{port} ...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((host, port))
    print("已连接")

    # 读取初始屏幕方向
    orientation = recv_orientation(sock)
    names = {0: "0° 竖屏", 1: "90° 横屏", 2: "180° 倒置", 3: "270° 横屏"}
    print(f"当前屏幕方向: {names.get(orientation, f'未知({orientation})')}")

    # 启动后台方向监听
    listener = threading.Thread(target=orientation_listener, args=(sock,), daemon=True)
    listener.start()

    print("\n--- 测试开始 ---")
    time.sleep(0.5)

    # 测试 1: 显示光标并移动到 (100, 100)
    print("[1] 显示光标 → (100, 100)")
    sock.send(build_packet(100, 100, show=True, down=False))
    time.sleep(0.5)

    # 测试 2: 移动光标到 (500, 500)
    print("[2] 移动光标 → (500, 500)")
    sock.send(build_packet(500, 500, show=True, down=False))
    time.sleep(0.5)

    # 测试 3: 按下
    print("[3] 按下光标")
    sock.send(build_packet(500, 500, show=True, down=True))
    time.sleep(0.3)

    # 测试 4: 释放
    print("[4] 释放光标")
    sock.send(build_packet(500, 500, show=True, down=False))
    time.sleep(0.3)

    # 测试 5: 移动到屏幕中央 (960, 540)
    print("[5] 移动光标 → (960, 540)")
    sock.send(build_packet(960, 540, show=True, down=False))
    time.sleep(0.5)

    # 测试 6: 隐藏光标
    print("[6] 隐藏光标")
    sock.send(build_packet(0, 0, show=False, down=False))
    time.sleep(0.3)

    # 测试 7: header 错误的包（应被服务端丢弃）
    print("[7] 发送错误 header 的包（应被丢弃）")
    bad_packet = b'\xFF\xFF' + struct.pack('<iiB', 100, 100, 1)
    sock.send(bad_packet)
    time.sleep(0.3)

    # 测试 8: 重新显示光标确认服务仍正常
    print("[8] 重新显示光标 → (200, 200)")
    sock.send(build_packet(200, 200, show=True, down=False))
    time.sleep(0.5)

    print("\n--- 测试完成 ---")
    print("按 Enter 退出...")
    try:
        input()
    except (EOFError, KeyboardInterrupt):
        pass

    sock.close()
    print("已断开连接")


if __name__ == "__main__":
    main()
