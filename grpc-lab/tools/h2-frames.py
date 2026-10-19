#!/usr/bin/env python3
"""HTTP/2 帧解码：解析 h2-capture.py 抓到的字节流，逐帧打印（长度/类型/标志/流ID/载荷摘要）。

用法: python3 h2-frames.py <bin_file>
说明: HEADERS 载荷是 HPACK 压缩的，这里只给字节摘要；DATA 载荷按 gRPC 分帧解析（1B 压缩标志 + 4B 长度）。
"""
import sys

TYPES = {0: "DATA", 1: "HEADERS", 2: "PRIORITY", 3: "RST_STREAM", 4: "SETTINGS",
         5: "PUSH_PROMISE", 6: "PING", 7: "GOAWAY", 8: "WINDOW_UPDATE", 9: "CONTINUATION"}

PREFACE = bytes.fromhex("505249202a20485454502f322e300d0a0d0a534d0d0a0d0a")


def flags_of(t, f):
    names = []
    if t in (0, 1):
        if f & 0x1:
            names.append("END_STREAM")
        if f & 0x4:
            names.append("END_HEADERS")
        if f & 0x8:
            names.append("PADDED")
        if t == 1 and f & 0x20:
            names.append("PRIORITY")
    elif t in (4, 6):
        if f & 0x1:
            names.append("ACK")
    return "|".join(names) if names else "-"


def main():
    data = open(sys.argv[1], "rb").read()
    off = 0
    if data[:24] == PREFACE:
        print(f"@0  PREFACE  {data[:24]!r}")
        off = 24
    while off + 9 <= len(data):
        length = int.from_bytes(data[off:off + 3], "big")
        ftype = data[off + 3]
        flags = data[off + 4]
        stream = int.from_bytes(data[off + 5:off + 9], "big") & 0x7fffffff
        payload = data[off + 9:off + 9 + length]
        tname = TYPES.get(ftype, f"TYPE_{ftype}")
        extra = ""
        if ftype == 0 and len(payload) >= 5:  # DATA: gRPC message
            compressed = payload[0]
            mlen = int.from_bytes(payload[1:5], "big")
            body = payload[5:5 + mlen]
            extra = f" grpc_msg(compressed={compressed}, len={mlen}, body={body[:24].hex(' ')})"
        elif ftype == 3 and len(payload) == 4:  # RST_STREAM error code
            extra = f" error_code={int.from_bytes(payload, 'big')}"
        elif ftype == 4:  # SETTINGS id/value pairs
            pairs = [f"{int.from_bytes(payload[i:i+2],'big')}={int.from_bytes(payload[i+2:i+6],'big')}"
                     for i in range(0, len(payload) - 5, 6)]
            extra = " " + ",".join(pairs) if pairs else ""
        elif ftype == 8 and len(payload) == 4:  # WINDOW_UPDATE
            extra = f" increment={int.from_bytes(payload, 'big') & 0x7fffffff}"
        elif ftype == 1:  # HEADERS (HPACK)
            extra = f" hpack={payload[:12].hex(' ')}"
        print(f"@{off:<5} len={length:<5} {tname:<13} flags={flags_of(ftype, flags):<12} "
              f"stream={stream}{extra}")
        off += 9 + length
    if off < len(data):
        print(f"@{off}  剩余 {len(data) - off} 字节（不完整帧）")


if __name__ == "__main__":
    main()
