#!/usr/bin/env python3
"""Stand-in for meshsurvey-ble.exe, used only by automated tests on any OS.

Speaks the same line protocol (see desktopApp/native/windows-ble/meshsurvey-ble.cpp) and plays a
minimal Meshtastic node: answers want_config with my_info + config_complete. It is NOT a Bluetooth
implementation and proves nothing about real radios.

Usage: fake-ble-helper.py [mode]   modes: normal | adapter-off | bad-pin | drop-after-config | crash
"""
import sys

mode = sys.argv[1] if len(sys.argv) > 1 else "normal"


def out(s):
    sys.stdout.write(s + "\n")
    sys.stdout.flush()


def varint(n):
    b = bytearray()
    while True:
        x = n & 0x7F
        n >>= 7
        if n:
            b.append(x | 0x80)
        else:
            b.append(x)
            return bytes(b)


def read_varint(buf, i):
    r = s = 0
    while True:
        c = buf[i]
        i += 1
        r |= (c & 0x7F) << s
        if c < 0x80:
            return r, i
        s += 7


def want_config_nonce(buf):
    """ToRadio.want_config_id is field 3 (varint)."""
    i = 0
    while i < len(buf):
        tag, i = read_varint(buf, i)
        f, w = tag >> 3, tag & 7
        if w == 0:
            v, i = read_varint(buf, i)
            if f == 3:
                return v
        elif w == 2:
            n, i = read_varint(buf, i)
            i += n
        elif w == 5:
            i += 4
        elif w == 1:
            i += 8
        else:
            return None
    return None


out("ready meshsurvey-ble fake")
if mode == "crash":
    sys.exit(3)
connected = False
for line in sys.stdin:
    parts = line.strip().split(" ")
    cmd = parts[0]
    if cmd == "adapter":
        out("adapter off" if mode == "adapter-off" else "adapter ok")
    elif cmd == "scan":
        out("dev AA:BB:CC:DD:EE:01 -61 0 Meshtastic_ee01")
        out("dev AA:BB:CC:DD:EE:02 - 1 RAK4631 base")
        out("scan-done")
    elif cmd == "pair":
        if mode == "bad-pin" or len(parts) < 3 or parts[2] != "123456":
            out("error pair status 19 (wrong PIN, or the radio rejected pairing)")
        else:
            out("paired")
    elif cmd == "connect":
        connected = True
        out("connected 247")
    elif cmd == "tx" and connected:
        nonce = want_config_nonce(bytes.fromhex(parts[1]))
        if nonce is not None:
            my_info = bytes([0x08]) + varint(0x1234ABCD)            # MyNodeInfo.my_node_num
            out("rx " + (bytes([0x1A, len(my_info)]) + my_info).hex())  # FromRadio.my_info (field 3)
            out("rx " + (bytes([0x38]) + varint(nonce)).hex())     # FromRadio.config_complete_id (field 7)
            if mode == "drop-after-config":
                out("lost")
                connected = False
    elif cmd == "disconnect":
        connected = False
        out("disconnected")
    elif cmd == "quit":
        break
