#!/usr/bin/env python3
"""Forward 127.0.0.1:15555 to the TV's ADB port.

Use it when the assistant's sandbox cannot reach the local network but your
own terminal can. Run it in a terminal and leave it running:

  tools/relay.py 192.168.1.50

then point the tools at it: TV_HOST=127.0.0.1 TV_PORT=15555
"""
import asyncio
import sys

if len(sys.argv) < 2:
    sys.exit(__doc__)
TV = (sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 5555)


async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    finally:
        writer.close()


async def handle(client_r, client_w):
    try:
        tv_r, tv_w = await asyncio.open_connection(*TV)
    except OSError as e:
        print(f"cannot reach {TV[0]}:{TV[1]}: {e}")
        client_w.close()
        return
    await asyncio.gather(pipe(client_r, tv_w), pipe(tv_r, client_w), return_exceptions=True)


async def main():
    server = await asyncio.start_server(handle, "127.0.0.1", 15555)
    print(f"relaying 127.0.0.1:15555 -> {TV[0]}:{TV[1]} (Ctrl+C to stop)")
    async with server:
        await server.serve_forever()


try:
    asyncio.run(main())
except KeyboardInterrupt:
    pass
