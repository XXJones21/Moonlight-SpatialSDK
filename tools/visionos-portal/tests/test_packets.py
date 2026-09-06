import dataclasses
import pathlib
import struct
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from send_pose import State, decode_state, encode_state, encode_reset, parse_status


class PacketTests(unittest.TestCase):
    def test_independent_golden(self):
        golden = pathlib.Path(__file__).resolve().parents[3] / 'PortalCore/fixtures/state-v1.bin'
        packet = golden.read_bytes()
        self.assertEqual(200, len(packet))
        self.assertEqual(packet, encode_state(State()))
        self.assertEqual(State(), decode_state(packet))
        self.assertEqual(200, struct.calcsize('<4sHH6QII17d'))

    def test_lengths(self):
        packet = encode_state(State())
        for length in range(200):
            with self.assertRaises(ValueError):
                decode_state(packet[:length])
        with self.assertRaises(ValueError):
            decode_state(packet + b'\0')

    def test_header_and_numeric_rejections(self):
        for offset in (0, 4, 6, 56, 60):
            packet = bytearray(encode_state(State())); packet[offset] = 255
            with self.assertRaises(ValueError):
                decode_state(packet)
        for offset, value in ((64, float('nan')), (112, 0.), (176, -1.)):
            packet = bytearray(encode_state(State())); struct.pack_into('<d', packet, offset, value)
            with self.assertRaises(ValueError):
                decode_state(packet)

    def test_uint64_and_reset(self):
        state = dataclasses.replace(State(), sequence=(1 << 64) - 1)
        self.assertEqual(state, decode_state(encode_state(state)))
        self.assertEqual((b'P6DR', 1, 32, 4, 5, 6), struct.unpack('<4sHH3Q', encode_reset(4, 5, 6)))
        with self.assertRaises(ValueError):
            encode_reset(1, 2, 1)

    def test_status(self):
        packet = b'{"version":1,"sessionID":"1","trackingEpoch":"1","acceptedSequence":"3","geometryRevision":"1","renderFrameID":"9007199254740993","trackingValid":true,"outputMode":"sbs","errorCode":"none"}\n'
        self.assertIsNotNone(parse_status(packet, 1))
        self.assertIsNone(parse_status(packet, 2))
        self.assertIsNone(parse_status(packet.replace(b'"sessionID":"1"', b'"sessionID":1'), 1))
        self.assertIsNone(parse_status(packet[:-1], 1))
        self.assertIsNone(parse_status(packet.replace(b'"version":1', b'"version":1,"version":1'), 1))


if __name__ == '__main__':
    unittest.main()
