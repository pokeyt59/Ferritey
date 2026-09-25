#!/usr/bin/env python3
"""Minimal Minecraft RCON client for CI.

Usage: rcon.py <port> <password> <command> [<command> ...]
Prints each command's response on its own line.
"""
import socket
import struct
import sys

LOGIN, COMMAND = 3, 2


def send(sock, req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(data)) + data)


def recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("rcon connection closed")
        buf += chunk
    return buf


def recv(sock):
    size = struct.unpack("<i", recv_exact(sock, 4))[0]
    data = recv_exact(sock, size)
    req_id, _kind = struct.unpack("<ii", data[:8])
    return req_id, data[8:-2].decode("utf-8", "replace")


def main():
    port, password, commands = int(sys.argv[1]), sys.argv[2], sys.argv[3:]
    with socket.create_connection(("127.0.0.1", port), timeout=60) as sock:
        send(sock, 1, LOGIN, password)
        if recv(sock)[0] == -1:
            sys.exit("rcon login refused")
        for i, command in enumerate(commands, start=2):
            send(sock, i, COMMAND, command)
            print(recv(sock)[1])


if __name__ == "__main__":
    main()
