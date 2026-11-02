"""Fails if generated protocol files are stale relative to docs/protocol/smarteyex-ble-v1.json."""
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import gen_protocol  # noqa: E402


class GeneratedFilesTests(unittest.TestCase):
    def test_up_to_date(self):
        for path, expected in gen_protocol.outputs().items():
            with open(path, encoding="utf-8") as f:
                self.assertEqual(f.read(), expected, f"{os.path.relpath(path, ROOT)} usang: jalankan python3 tools/gen_protocol.py")

    def test_spec_is_consistent(self):
        s = gen_protocol.load()
        uuids = [s["service"]] + [c["uuid"] for c in s["characteristics"].values()]
        self.assertEqual(len(uuids), len(set(uuids)))
        for group in ("commands", "events", "status", "auth"):
            vals = list(s[group].values())
            self.assertEqual(len(vals), len(set(vals)), group)
            self.assertTrue(all(0 <= v <= 255 for v in vals), group)
        self.assertEqual(s["consts"]["AUDIO_FRAME_SAMPLES"] // 2 + s["consts"]["AUDIO_HEADER_LEN"] <= s["consts"]["MIN_MTU"] - 3, True)


if __name__ == "__main__":
    unittest.main()
