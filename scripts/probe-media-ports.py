#!/usr/bin/env python3
"""公网侧自检 LiveKit 媒体面连通性（纯标准库，可在任意联网机器上跑）。

通话「能振铃、能接通，但两端黑屏/无声」= 信令通（wss 443 走 Caddy）而媒体面不通。
本脚本从外部探测媒体端口，区分「云安全组没放行」与「服务没在听」：

    python3 scripts/probe-media-ports.py pomelo.host

探测方式与端口来源（对齐 conf/livekit.yaml 与 docker-compose.yml 的端口映射）：
  - 3478/udp   TURN：发 STUN Binding Request，TURN/ICE-Lite 会回 Binding Success
  - 30000-30100/udp  WebRTC 媒体（ICE 候选端口段）：同样用 STUN Binding Request 探活
  - 7881/tcp   WebRTC over TCP 回退：TCP 握手

任一项无响应即说明该端口在链路上被丢弃（云防火墙/安全组最先怀疑），
客户端将因 ICE 打不通而永远停在「连接中」，表现就是摄像头不亮、画面全黑。
"""
import os
import socket
import struct
import sys

MAGIC_COOKIE = b"\x21\x12\xa4\x42"
UDP_PORTS = [3478, 30000, 30050, 30100]
TCP_PORTS = [7881]
TIMEOUT = 3.0


def stun_binding_request() -> bytes:
    # type=0x0001(Binding Request), length=0, magic cookie + 12 字节事务 ID
    return struct.pack("!HHI", 0x0001, 0, 0x2112A442) + os.urandom(12)


def probe_udp(ip: str, port: int) -> bool:
    pkt = stun_binding_request()
    txid = pkt[8:]
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(TIMEOUT)
    try:
        sock.sendto(pkt, (ip, port))
        data, _ = sock.recvfrom(1024)
        # Binding Success Response 会回带同一个事务 ID
        return len(data) >= 20 and data[4:8] == MAGIC_COOKIE and data[8:20] == txid
    except (socket.timeout, OSError):
        return False
    finally:
        sock.close()


def probe_tcp(ip: str, port: int) -> bool:
    sock = socket.socket()
    sock.settimeout(TIMEOUT)
    try:
        sock.connect((ip, port))
        return True
    except OSError:
        return False
    finally:
        sock.close()


def main() -> int:
    host = sys.argv[1] if len(sys.argv) > 1 else "pomelo.host"
    try:
        ip = socket.gethostbyname(host)
    except OSError as e:
        print(f"域名解析失败 {host}: {e}")
        return 2
    print(f"目标 {host} -> {ip}（探测超时 {TIMEOUT:.0f}s）\n")

    failed = []
    for port in UDP_PORTS:
        ok = probe_udp(ip, port)
        print(f"UDP {port:<6} {'通' if ok else '不通'}")
        if not ok:
            failed.append(f"udp/{port}")
    for port in TCP_PORTS:
        ok = probe_tcp(ip, port)
        print(f"TCP {port:<6} {'通' if ok else '不通'}")
        if not ok:
            failed.append(f"tcp/{port}")

    if failed:
        print(f"\n结论：媒体面不通（{'、'.join(failed)}）——通话会「接通但黑屏/无声」。")
        print("处理：云控制台安全组放行 3478/udp、30000-30100/udp、7881/tcp，")
        print("     并在服务器上确认端口确实在听：ss -lunp | grep -E '3478|300'、docker compose ps livekit")
        return 1
    print("\n结论：媒体面通，ICE 可直连。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
