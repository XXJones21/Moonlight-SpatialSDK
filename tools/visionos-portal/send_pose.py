#!/usr/bin/env python3
"""Synthetic full-state sender. Only the standard library is required."""
import argparse
import csv
import json
import math
import secrets
import socket
import struct
import time
from dataclasses import dataclass

FORMAT = '<4sHH6QII17d'
RESET_FORMAT = '<4sHH3Q'
PACKET_SIZE = 200


@dataclass(frozen=True)
class State:
    session: int = 1
    epoch: int = 1
    sequence: int = 1
    revision: int = 1
    sample_ns: int = 1_000_000_000
    target_ns: int = 1_000_000_000
    valid: bool = True
    head: tuple = (0., 0., 2., 0., 0., 0., 1.)
    portal: tuple = (0., 0., 0., 0., 0., 0., 1.)
    width: float = 2.4
    height: float = 1.35
    eye_separation: float = .064


def validate(s):
    for value in (s.session, s.epoch, s.sequence, s.revision):
        if not 0 < value < (1 << 64):
            raise ValueError('identifiers must be nonzero uint64')
    for value in (s.sample_ns, s.target_ns):
        if not 0 <= value < (1 << 64):
            raise ValueError('timestamps must be uint64')
    for transform in (s.head, s.portal):
        if len(transform) != 7 or not all(math.isfinite(v) for v in transform):
            raise ValueError('invalid rigid transform')
        if abs(sum(v * v for v in transform[3:]) - 1) > .001:
            raise ValueError('quaternion must have unit norm')
    if not all(math.isfinite(v) and v > 0 for v in (s.width, s.height, s.eye_separation)) or s.eye_separation > .2:
        raise ValueError('invalid dimensions')


def encode_state(s):
    validate(s)
    return struct.pack(FORMAT, b'P6DV', 1, PACKET_SIZE,
                       s.session, s.epoch, s.sequence, s.revision, s.sample_ns, s.target_ns,
                       int(s.valid), 0, *s.head, *s.portal, s.width, s.height, s.eye_separation)


def decode_state(packet):
    if len(packet) != PACKET_SIZE:
        raise ValueError('state length must be exactly 200')
    values = struct.unpack(FORMAT, packet)
    if values[:3] != (b'P6DV', 1, 200) or values[9] not in (0, 1) or values[10] != 0:
        raise ValueError('invalid state header or flags')
    s = State(*values[3:9], bool(values[9]), tuple(values[11:18]), tuple(values[18:25]), *values[25:28])
    validate(s)
    return s


def encode_reset(session, previous_epoch, next_epoch):
    if not 0 < session < (1 << 64) or not 0 < previous_epoch < next_epoch < (1 << 64):
        raise ValueError('invalid reset transition')
    return struct.pack(RESET_FORMAT, b'P6DR', 1, 32, session, previous_epoch, next_epoch)


def parse_status(data, session):
    if not data or len(data) > 1024 or not data.endswith(b'\n'):
        return None
    try:
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise ValueError('duplicate status key')
                result[key] = value
            return result
        value = json.loads(data, object_pairs_hook=unique)
        keys = {'version', 'sessionID', 'trackingEpoch', 'acceptedSequence', 'geometryRevision',
                'renderFrameID', 'trackingValid', 'outputMode', 'errorCode'}
        if not isinstance(value, dict) or set(value) != keys or type(value['version']) is not int or value['version'] != 1:
            return None
        for key in ('sessionID', 'trackingEpoch', 'acceptedSequence', 'geometryRevision', 'renderFrameID'):
            item = value[key]
            if not isinstance(item, str) or not item.isascii() or not item.isdecimal() or str(int(item)) != item or int(item) >= 1 << 64:
                return None
        if value['sessionID'] != str(session) or int(value['trackingEpoch']) == 0 or type(value['trackingValid']) is not bool:
            return None
        if value['errorCode'] not in ('none', 'staleTracking', 'invalidGeometry', 'unsupportedRuntime', 'outputUnavailable'):
            return None
        mode = value['outputMode']
        if not isinstance(mode, str) or not 0 < len(mode) <= 32 or not all(c.isascii() and (c.isalnum() or c in '_-') for c in mode):
            return None
        if value['trackingValid'] and value['errorCode'] != 'none':
            return None
        return value
    except (ValueError, TypeError, UnicodeError):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=4243)
    parser.add_argument('--pattern', choices=('static', 'sweep', 'roll'), default='static')
    parser.add_argument('--duration', type=float, default=10.)
    parser.add_argument('--hz', type=float, default=90.)
    parser.add_argument('--csv', default='sent-poses.csv')
    parser.add_argument('--session', type=int, default=None)
    parser.add_argument('--reset-at', type=float, help='Request epoch 2 at this elapsed second; retry until acknowledged')
    args = parser.parse_args()
    if not math.isfinite(args.hz) or not 0 < args.hz <= 1000 or not math.isfinite(args.duration) or args.duration <= 0:
        parser.error('duration must be positive and hz must be in (0,1000]')
    if not 0 < args.port < 65536:
        parser.error('invalid UDP port')
    session = args.session if args.session is not None else (secrets.randbits(64) or 1)
    if not 0 < session < (1 << 64):
        parser.error('session must be a nonzero uint64')
    if args.reset_at is not None and (not math.isfinite(args.reset_at) or args.reset_at < 0):
        parser.error('reset-at must be a nonnegative finite time')
    # Connected UDP guarantees status comes from the selected host/port and uses our sender endpoint.
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock, open(args.csv, 'w', newline='', encoding='utf-8') as log:
        sock.connect((args.host, args.port))
        sock.setblocking(False)
        writer = csv.writer(log)
        writer.writerow(('sessionID', 'trackingEpoch', 'sequence', 'sampleTimeNs', 'targetTimeNs', 'elapsedSeconds', 'pattern'))
        start = time.monotonic(); deadline = start; sequence = 0; epoch = 1; next_reset = 0.
        while (elapsed := time.monotonic() - start) < args.duration:
            sequence += 1
            for _ in range(64):
                try:
                    status = parse_status(sock.recv(2048), session)
                    if status and status['trackingEpoch'] == '2' and args.reset_at is not None and elapsed >= args.reset_at:
                        epoch = 2
                except (BlockingIOError, ConnectionResetError):
                    break
            if args.reset_at is not None and elapsed >= args.reset_at and epoch == 1 and elapsed >= next_reset:
                sock.send(encode_reset(session, 1, 2)); next_reset = elapsed + .1
            angle = .5 * math.sin(elapsed)
            head = (0., 0., 2., 0., 0., 0., 1.)
            if args.pattern == 'sweep':
                head = (.4 * math.sin(elapsed), .15 * math.sin(elapsed * .7), 2., 0., math.sin(angle / 2), 0., math.cos(angle / 2))
            elif args.pattern == 'roll':
                head = (0., 0., 2., 0., 0., math.sin(angle / 2), math.cos(angle / 2))
            timestamp = time.monotonic_ns()
            s = State(session=session, epoch=epoch, sequence=sequence, sample_ns=timestamp, target_ns=timestamp, head=head)
            sock.send(encode_state(s))
            writer.writerow((session, epoch, sequence, timestamp, timestamp, f'{elapsed:.6f}', args.pattern))
            deadline += 1 / args.hz
            delay = deadline - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            else:
                deadline = time.monotonic()  # Never replay queued motion after a scheduling stall.
    print(f'Sent {sequence} snapshots, session {session}; CSV: {args.csv}')


if __name__ == '__main__':
    main()
