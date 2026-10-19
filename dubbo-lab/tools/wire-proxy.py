#!/usr/bin/env python3
"""极简 TCP 代理：把 consumer↔provider 的字节流按方向 dump 前 N 字节（hex）。

用法: python3 wire-proxy.py <listen_port> <target_port> [dump_bytes]
"""
import socket
import sys
import threading

LISTEN = int(sys.argv[1])
TARGET = int(sys.argv[2])
DUMP = int(sys.argv[3]) if len(sys.argv) > 3 else 64


def pump(src, dst, label, state):
    while True:
        data = src.recv(65536)
        if not data:
            break
        if state["n"] < DUMP:
            chunk = data[: DUMP - state["n"]]
            print(f"[{label}] {chunk.hex(' ')}", flush=True)
            state["n"] += len(chunk)
        dst.sendall(data)
    try:
        dst.shutdown(socket.SHUT_WR)
    except OSError:
        pass


def handle(client):
    up = socket.create_connection(("127.0.0.1", TARGET))
    s1, s2 = {"n": 0}, {"n": 0}
    t1 = threading.Thread(target=pump, args=(client, up, "C->P", s1), daemon=True)
    t2 = threading.Thread(target=pump, args=(up, client, "P->C", s2), daemon=True)
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
    print(f"wire-proxy listening 0.0.0.0:{LISTEN} -> 127.0.0.1:{TARGET}", flush=True)
    while True:
        client, _ = srv.accept()
        threading.Thread(target=handle, args=(client,), daemon=True).start()


if __name__ == "__main__":
    main()
