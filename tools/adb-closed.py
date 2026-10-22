#!/usr/bin/env python3
"""Check that network ADB is closed on the TV.

Usage: tools/adb-closed.py <tv ip> [port]

Only "Connection refused" proves the port is closed. A timeout or
"No route to host" proves nothing (on macOS, Local Network privacy can cause it).
Exit codes: 0 closed, 1 still open, 2 inconclusive.
"""
import errno
import socket
import sys

if len(sys.argv) < 2:
    sys.exit(__doc__)
host = sys.argv[1]
port = int(sys.argv[2]) if len(sys.argv) > 2 else 5555

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(5)
try:
    s.connect((host, port))
except ConnectionRefusedError:
    print(f"{host}:{port} refused the connection: network ADB is closed.")
    sys.exit(0)
except OSError as e:
    reason = errno.errorcode.get(e.errno, str(e)) if e.errno else str(e)
    print(f"{host}:{port} gave {reason}. That proves nothing; is the TV on and is this "
          "computer allowed to reach the local network?")
    sys.exit(2)
finally:
    s.close()
print(f"{host}:{port} is still OPEN. Switch off USB and network debugging on the TV and reboot it.")
sys.exit(1)
