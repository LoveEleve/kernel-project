#!/usr/bin/env python3
"""QoS 命令客户端：连接 Dubbo QoS telnet 端口，依次发送命令并打印输出。

用法: python3 tools/qos-cmd.py <port> <command> [command...]
例:   python3 tools/qos-cmd.py 22222 "ls" "count com.myxhs.dubbo.lab.api.DemoService"
"""
import socket
import sys
import time


def main():
    if len(sys.argv) < 3:
        print("用法: qos-cmd.py <port> <command> [command...]")
        return 1
    port = int(sys.argv[1])
    commands = sys.argv[2:]
    sock = socket.create_connection(("127.0.0.1", port), timeout=5)
    sock.settimeout(1.5)
    for cmd in commands:
        sock.sendall((cmd + "\n").encode())
        time.sleep(0.4)
        out = b""
        try:
            while True:
                data = sock.recv(65536)
                if not data:
                    break
                out += data
        except socket.timeout:
            pass
        print(">>> " + cmd)
        print(out.decode(errors="replace").rstrip())
    sock.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
