"""Bounded passive Windows IPv4 capture; retain only portal/media diagnostics."""
import collections
import argparse
import datetime
import json
import pathlib
import socket
import struct
import time

arguments=argparse.ArgumentParser()
arguments.add_argument('--output',type=pathlib.Path)
arguments.add_argument('--duration', type=int, choices=range(1, 61), default=15)
arguments.add_argument('--wait-for-state', type=int, choices=range(0, 601), default=0)
args=arguments.parse_args()

result = {"startedAt": datetime.datetime.now().astimezone().isoformat(), "durationSeconds": args.duration,
          "interface": "10.1.95.5", "syntheticPacketsSent": 0}
counts = collections.Counter()
portal_samples = []
statuses=[]
input_types=collections.Counter()
last_state=None
capture = None
try:
    capture = socket.socket(socket.AF_INET, socket.SOCK_RAW, socket.IPPROTO_IP)
    capture.bind((result["interface"], 0))
    capture.setsockopt(socket.IPPROTO_IP, socket.IP_HDRINCL, 1)
    capture.ioctl(socket.SIO_RCVALL, socket.RCVALL_ON)
    capture.settimeout(0.5)
    waiting = args.wait_for_state > 0
    result['armedAt'] = result['startedAt']
    result['triggered'] = not waiting
    deadline = time.monotonic() + (args.wait_for_state or result["durationSeconds"])
    total = 0
    while time.monotonic() < deadline:
        try:
            packet, _ = capture.recvfrom(65535)
        except socket.timeout:
            continue
        total += 1
        if len(packet) < 20 or packet[0] >> 4 != 4 or packet[9] != 17:
            continue
        offset = (packet[0] & 15) * 4
        if len(packet) < offset + 8:
            continue
        source, destination, length, _ = struct.unpack_from("!HHHH", packet, offset)
        if not ({source, destination} & {4243, 47998, 47999, 48000}):
            continue
        source_ip, dest_ip = socket.inet_ntoa(packet[12:16]), socket.inet_ntoa(packet[16:20])
        payload=packet[offset + 8:offset + length]
        if waiting:
            if destination != 4243 or len(payload) != 200 or payload[:4] != b'P6DV':
                continue
            waiting = False
            result['triggered'] = True
            result['startedAt'] = datetime.datetime.now().astimezone().isoformat()
            deadline = time.monotonic() + result['durationSeconds']
            if args.output:
                args.output.with_suffix('.started.json').write_text(json.dumps({'startedAt': result['startedAt']}))
        counts[f"{source_ip}:{source} -> {dest_ip}:{destination}"] += 1
        if source==4243 and source_ip==result['interface'] and payload.startswith(b'{'):
            try:
                status=json.loads(payload)
                if status.get('version')==1 and 'renderFrameID' in status:
                    statuses.append({'observedAt': datetime.datetime.now().astimezone().isoformat(), **status})
            except (ValueError,UnicodeError):
                pass
        if destination==4243:
            input_types[payload[:4].decode('ascii',errors='replace')]+=1
            if len(payload)==200 and payload[:4]==b'P6DV':
                values=struct.unpack_from('<6Q',payload,8)
                last_state=dict(zip(('session','epoch','sequence','geometry','sampleNs','targetNs'),values))
                last_state['trackingValid']=struct.unpack_from('<I',payload,56)[0]==1
                last_state['widthHeightIpd']=struct.unpack_from('<3d',payload,176)
        if 4243 in (source, destination) and len(portal_samples) < 3:
            portal_samples.append({"source": source_ip, "destination": dest_ip,
                                   "sourcePort": source, "destinationPort": destination,
                                   "payloadLength": length - 8,
                                   "payloadHex": packet[offset + 8:offset + length].hex()})
    result["totalIPv4PacketsObserved"] = total
except Exception as error:
    result["error"] = str(error)
finally:
    if capture:
        try:
            capture.ioctl(socket.SIO_RCVALL, socket.RCVALL_OFF)
        except OSError:
            pass
        capture.close()
    result["flows"] = dict(counts)
    result["portalSamples"] = portal_samples
    result['inputTypes']=dict(input_types)
    result['lastState']=last_state
    result['statuses']=statuses
    result["endedAt"] = datetime.datetime.now().astimezone().isoformat()
    (args.output or pathlib.Path(__file__).with_suffix(".json")).write_text(json.dumps(result, indent=2))
