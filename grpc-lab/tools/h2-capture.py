#!/usr/bin/env python3
"""TCP 代理：把 consumer↔provider 的完整字节流按方向写入文件（供 h2-frames.py 解码）。

用法: python3 h2-capture.py <listen_port> <target_port> <out_prefix>
输出: <out_prefix>.c2p.bin（客户端→服务端）、<out_prefix>.p2c.bin（服务端→客户端）
"""
import socket
import sys
import threading

LISTEN = int(sys.argv[1])
TARGET = int(sys.argv[2])
PREFIX = sys.argv[3]


def pump(src, dst, path):
    with open(path, "ab") as f:
        while True:
            data = src.recv(65536)
            if not data:
                break
            f.write(data)
            f.flush()
            dst.sendall(data)
    try:
        dst.shutdown(socket.SHUT_WR)
    except OSError:
        pass


def handle(client):
    up = socket.create_connection(("127.0.0.1", TARGET))
    t1 = threading.Thread(target=pump, args=(client, up, PREFIX + ".c2p.bin"), daemon=True)
    t2 = threading.Thread(target=pump, args=(up, client, PREFIX + ".p2c.bin"), daemon=True)
    t1.start()
    t2.start()
    t1.join()
    t2.join()
    client.close()
    up.close()


def main():
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("0.0.0.0", LISTEN))
    srv.listen(5)
    print(f"h2-capture listening 0.0.0.0:{LISTEN} -> 127.0.0.1:{TARGET}, out={PREFIX}.*.bin", flush=True)
    while True:
        client, _ = srv.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
