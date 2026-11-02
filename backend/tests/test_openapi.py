import os
import re
import unittest

from tests.helpers import Base
from tools import gen_openapi

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


class OpenApiTests(Base):
    def test_every_route_is_documented_and_vice_versa(self):
        flask_routes = set()
        for r in self.app.url_map.iter_rules():
            if r.endpoint == "static":
                continue
            for m in r.methods - {"HEAD", "OPTIONS"}:
                flask_routes.add((m, re.sub(r"<(\w+)>", r"{\1}", str(r.rule))))
        documented = {(m, p) for m, p, *_ in gen_openapi.E}
        self.assertEqual(sorted(flask_routes - documented), [], "rute belum didokumentasikan")
        self.assertEqual(sorted(documented - flask_routes), [], "dokumentasi untuk rute yang tidak ada")
        self.assertEqual(len(gen_openapi.E), len(documented), "entri ganda di tabel")

    def test_committed_yaml_is_up_to_date(self):
        with open(os.path.join(ROOT, "docs", "openapi.yaml"), encoding="utf-8") as f:
            self.assertEqual(f.read(), gen_openapi.render(), "jalankan: python -m tools.gen_openapi")

    def test_documented_permissions_match_code(self):
        from xnva.security import PERMISSIONS
        for m, p, auth, *_ in gen_openapi.E:
            if ":" in auth:
                for perm in auth.split(":")[1].split("+"):
                    self.assertIn(perm, PERMISSIONS, f"{m} {p}: izin tidak dikenal {perm}")


if __name__ == "__main__":
    unittest.main()
