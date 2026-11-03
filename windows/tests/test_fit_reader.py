import dataclasses
import struct
import unittest
from pathlib import Path
from unittest import mock

from memento_win.core import fit

EPOCH = 631_065_600


def fit_file(body: bytes) -> bytes:
    return bytes([14, 0x10]) + struct.pack("<HI", 2100, len(body)) + b".FIT" + b"\0\0" + body + b"\0\0"


def definition(local: int, glob: int, fields) -> bytes:
    return bytes([0x40 | local, 0, 0]) + struct.pack("<H", glob) + bytes([len(fields)]) + bytes(
        b for field in fields for b in field)


def same(a, b) -> bool:
    return dataclasses.asdict(a) == dataclasses.asdict(b)


class FitReaderTest(unittest.TestCase):
    def read_fast(self, data: bytes):
        """fit.read, failing if it handed the file to the generic reader."""
        with mock.patch.object(fit, "_read_generic", side_effect=AssertionError("fallback")):
            return fit.read(data)

    def test_the_fast_reader_matches_the_generic_one_on_a_file_from_the_android_app(self):
        data = (Path(__file__).parent / "data" / "synthetic.fit").read_bytes()
        fast, generic = self.read_fast(data), fit._read_generic(data)
        self.assertEqual(90, len(fast.points))
        self.assertTrue(same(generic, fast))

    def test_invalid_record_fields_are_left_out_as_by_the_generic_reader(self):
        # timestamp, lat, lon, altitude, enhanced altitude, heart rate
        record = definition(0, 20, [(253, 4, 0x86), (0, 4, 0x85), (1, 4, 0x85), (2, 2, 0x84), (78, 4, 0x86),
                                    (3, 1, 0x02)])
        rows = [
            (1000, 536870912, 0, 7500, 7600, 120),
            (1001, 536870913, 1, 7500, 0xFFFFFFFF, 0xFF),  # no enhanced altitude, no heart rate
            (1002, 536870914, 2, 0xFFFF, 0xFFFFFFFF, 121),  # no altitude at all
            (1003, 0x7FFFFFFF, 3, 7500, 7600, 122),  # no position: heart rate still counted
            (0xFFFFFFFF, 536870915, 4, 7500, 7600, 123),  # no timestamp: ignored
        ]
        body = record + b"".join(bytes([0]) + struct.pack("<IiiHIB", *row) for row in rows)
        body += definition(1, 23, [(253, 4, 0x86), (0, 1, 0x02)]) + bytes([1]) + struct.pack("<IB", 1004, 1)
        body += definition(2, 21, [(253, 4, 0x86), (0, 1, 0x00), (1, 1, 0x00)])
        body += bytes([2]) + struct.pack("<IBB", 1005, 0, 4) + bytes([2]) + struct.pack("<IBB", 1009, 0, 0)
        data = fit_file(body)
        fast, generic = self.read_fast(data), fit._read_generic(data)
        self.assertEqual([(1000 + EPOCH) * 1000, (1001 + EPOCH) * 1000, (1002 + EPOCH) * 1000],
                         [p.time for p in fast.points])
        self.assertEqual([1020.0, 1000.0, None], [p.alt for p in fast.points])
        self.assertEqual([120, None, 121], [p.hr for p in fast.points])
        self.assertEqual(122, fast.hr_max)
        self.assertEqual(1, len(fast.pauses))
        self.assertTrue(same(generic, fast))

    def test_a_compressed_timestamp_sends_the_whole_file_to_the_generic_reader(self):
        first = bytes([1]) + struct.pack("<Iii", 1000, 536870912, 0)  # 1000 & 0x1F == 8
        compressed = b"".join(bytes([0x80 | (2 << 5) | offset]) + struct.pack("<ii", 536870912 + offset, 0)
                              for offset in (9, 10))
        data = fit_file(definition(1, 20, [(253, 4, 0x86), (0, 4, 0x85), (1, 4, 0x85)]) + first
                        + definition(2, 20, [(0, 4, 0x85), (1, 4, 0x85)]) + compressed)
        with mock.patch.object(fit, "_read_generic", wraps=fit._read_generic) as generic:
            track = fit.read(data)
        generic.assert_called_once_with(data)
        self.assertEqual([(t + EPOCH) * 1000 for t in (1000, 1001, 1002)], [p.time for p in track.points])


if __name__ == "__main__":
    unittest.main()
