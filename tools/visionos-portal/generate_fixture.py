#!/usr/bin/env python3
"""Generate the protocol's independent golden artifact; does not execute tests."""
import pathlib
import struct

FORMAT = '<4sHH6QII17d'
values = (0, 0, 2, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 2.4, 1.35, .064)
packet = struct.pack(FORMAT, b'P6DV', 1, 200, 1, 1, 1, 1, 1_000_000_000, 1_000_000_000, 1, 0, *values)
destination = pathlib.Path(__file__).resolve().parents[2] / 'PortalCore' / 'fixtures' / 'state-v1.bin'
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_bytes(packet)
print(f'Wrote independent Python struct fixture: {destination}')
