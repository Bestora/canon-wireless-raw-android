"""Minimal PTP/IP responder that misbehaves on purpose.

usage: python3 -I fake_camera.py <port> <scenario>
  ok              well-behaved camera
  eof_mid_packet  GetPartialObject: announce N payload bytes, send a few, close the socket
  stall_mid_packet  GetPartialObject: announce N payload bytes, send a few, then go silent
  silent_init     never answer the Init Command Request
  slow_once       first GetPartialObject answered after 6 s, later ones immediately
  init_fail_n:K   answer the first K Init Command Requests with INIT_FAIL, then ACK
"""
import socket
import struct
import sys
import threading
import time

INIT_CMD_REQ, INIT_CMD_ACK, INIT_EVT_REQ, INIT_EVT_ACK, INIT_FAIL = 1, 2, 3, 4, 5
CMD_REQ, CMD_RESP, DATA_START, DATA_END = 6, 7, 9, 12
OC_GET_PARTIAL = 0x101B


def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("peer closed")
        buf += chunk
    return buf


def recv_packet(s):
    length, ptype = struct.unpack("<II", recv_exact(s, 8))
    return ptype, recv_exact(s, length - 8)


def send_packet(s, ptype, body):
    s.sendall(struct.pack("<II", 8 + len(body), ptype) + body)


def response(s, txn, code=0x2001):
    send_packet(s, CMD_RESP, struct.pack("<HI", code, txn))


def main():
    port, scenario = int(sys.argv[1]), sys.argv[2]
    fail_left = int(scenario.split(":")[1]) if scenario.startswith("init_fail_n:") else 0
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(4)
    print(f"fake camera on :{port} scenario={scenario}", flush=True)
    state = {"partial_calls": 0, "fail_left": fail_left}
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve, args=(conn, scenario, state), daemon=True).start()


def serve(conn, scenario, state):
    try:
        ptype, body = recv_packet(conn)
    except ConnectionError:
        conn.close()
        return
    if ptype == INIT_EVT_REQ:
        send_packet(conn, INIT_EVT_ACK, b"")
        time.sleep(3600)  # keep the event socket open, never read again
        return
    if ptype != INIT_CMD_REQ:
        conn.close()
        return
    if scenario == "silent_init":
        time.sleep(60)
        conn.close()
        return
    if state["fail_left"] > 0:
        state["fail_left"] -= 1
        send_packet(conn, INIT_FAIL, struct.pack("<I", 1))
        conn.close()
        return
    guid = body[:16]
    name = "fake".encode("utf-16-le") + b"\0\0"
    send_packet(conn, INIT_CMD_ACK, struct.pack("<I", 1) + guid + name + struct.pack("<I", 0x00010000))
    try:
        while True:
            ptype, body = recv_packet(conn)
            if ptype != CMD_REQ:
                continue
            _phase, code, txn = struct.unpack("<IHI", body[:10])
            params = struct.unpack(f"<{(len(body) - 10) // 4}I", body[10:])
            if code != OC_GET_PARTIAL:
                response(conn, txn)
                continue
            state["partial_calls"] += 1
            n = params[2]
            payload = bytes(i & 0xFF for i in range(n))
            if scenario == "slow_once" and state["partial_calls"] == 1:
                time.sleep(6)
            send_packet(conn, DATA_START, struct.pack("<IQ", txn, n))
            if scenario in ("eof_mid_packet", "stall_mid_packet"):
                head = struct.pack("<III", 12 + n, DATA_END, txn)
                conn.sendall(head + payload[:16])
                if scenario == "eof_mid_packet":
                    conn.shutdown(socket.SHUT_RDWR)
                    conn.close()
                    return
                time.sleep(3600)
            send_packet(conn, DATA_END, struct.pack("<I", txn) + payload)
            response(conn, txn)
    except (ConnectionError, OSError):
        conn.close()

if __name__ == "__main__":
    main()
