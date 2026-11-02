import base64
import json
import os
import tempfile
import unittest

from xnva import audit, config, crypto
from xnva.db import Database
from xnva.schema import PERSONAL
from xnva.kb import chunk_text, tokens


class CryptoTests(unittest.TestCase):
    def test_password_hash_roundtrip_and_params(self):
        h = crypto.PasswordHasher(1024, 8, 1)
        s = h.hash("rahasia-panjang-1")
        self.assertTrue(h.verify("rahasia-panjang-1", s))
        self.assertFalse(h.verify("salah", s))
        self.assertFalse(h.verify("x", "bukan-hash"))
        self.assertTrue(crypto.PasswordHasher(2048, 8, 1).needs_rehash(s))
        self.assertNotEqual(h.hash("a" * 12), h.hash("a" * 12))

    def test_vault_seal_open_and_aad_binding(self):
        v = crypto.Vault(os.urandom(32))
        dek = os.urandom(32)
        blob = v.seal(dek, "isi rahasia", "tbl.col.1")
        self.assertNotIn("rahasia", blob)
        self.assertEqual(v.open(dek, blob, "tbl.col.1"), "isi rahasia")
        with self.assertRaises(ValueError):
            v.open(dek, blob, "tbl.col.2")
        with self.assertRaises(ValueError):
            v.open(os.urandom(32), blob, "tbl.col.1")
        tampered = blob[:-2] + ("A" if blob[-1] != "A" else "B") + "A"
        with self.assertRaises(Exception):
            v.open(dek, tampered, "tbl.col.1")

    def test_master_key_wrap(self):
        v = crypto.Vault(os.urandom(32))
        dek = os.urandom(32)
        self.assertEqual(v.unwrap(v.wrap(dek, "a"), "a"), dek)
        with self.assertRaises(Exception):
            v.unwrap(v.wrap(dek, "a"), "b")


class ConfigTests(unittest.TestCase):
    def base(self, **kw):
        e = {"XNVA_MASTER_KEY": base64.b64encode(os.urandom(32)).decode(), "XNVA_JWT_SECRET": "s" * 40}
        e.update(kw)
        return e

    def test_requires_secrets(self):
        with self.assertRaises(config.ConfigError):
            config.load_settings({})
        with self.assertRaises(config.ConfigError):
            config.load_settings(self.base(XNVA_MASTER_KEY=base64.b64encode(b"short").decode()))
        with self.assertRaises(config.ConfigError):
            config.load_settings(self.base(XNVA_JWT_SECRET="pendek"))

    def test_production_enforces_scrypt_cost(self):
        with self.assertRaises(config.ConfigError):
            config.load_settings(self.base(XNVA_SCRYPT_N="1024"))
        self.assertTrue(config.load_settings(self.base()).production)

    def test_xnai_readiness(self):
        s = config.load_settings(self.base())
        self.assertFalse(s.xnai.chat_ready)
        s = config.load_settings(self.base(XNAI_PROVIDER="anthropic", XNAI_MODEL="m", XNAI_API_KEY="k"))
        self.assertTrue(s.xnai.chat_ready)
        s = config.load_settings(self.base(XNAI_PROVIDER="anthropic", XNAI_MODEL="m"))
        self.assertFalse(s.xnai.chat_ready)


class AuditTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = Database(os.path.join(self.tmp.name, "a.db"), PERSONAL, "t")
        self.db.migrate()
        self.log = audit.AuditLog(self.db)

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def test_chain_verifies_and_detects_tamper(self):
        for i in range(5):
            self.log.append("s1", f"act.{i}", "u", "r", meta={"i": i}, now=1000 + i)
        self.assertEqual(self.log.verify("s1"), {"ok": True, "entries": 5, "broken_at_seq": None})
        self.db.run("UPDATE audit_log SET action='act.hack' WHERE scope_id='s1' AND seq=3")
        res = self.log.verify("s1")
        self.assertFalse(res["ok"])
        self.assertEqual(res["broken_at_seq"], 3)

    def test_deleted_entry_is_detected(self):
        for i in range(4):
            self.log.append("s1", "a", now=10 + i)
        self.db.run("DELETE FROM audit_log WHERE scope_id='s1' AND seq=2")
        self.assertFalse(self.log.verify("s1")["ok"])

    def test_purge_keeps_chain_valid_and_scopes_are_independent(self):
        for i in range(6):
            self.log.append("s1", "a", now=100 + i)
        self.log.append("s2", "b", now=1)
        removed = self.log.purge_before("s1", 103)
        self.assertEqual(removed, 3)
        self.assertTrue(self.log.verify("s1")["ok"])
        self.log.append("s1", "after-purge", now=500)
        self.assertEqual(self.log.verify("s1")["entries"], 4)
        self.assertEqual(self.log.verify("s2")["entries"], 1)

    def test_personal_ip_is_truncated(self):
        self.assertEqual(audit.coarse_ip("203.0.113.77"), "203.0.113.0/24")
        self.assertTrue(audit.coarse_ip("2001:db8:abcd:1234::1").endswith("/48"))
        self.assertIsNone(audit.coarse_ip("bukan-ip"))


class KbUnitTests(unittest.TestCase):
    def test_tokens_drop_stopwords_and_accents(self):
        self.assertEqual(tokens("Prosedur untuk Café dan the Mesin"), ["prosedur", "cafe", "mesin"])

    def test_chunking_bounds(self):
        text = "\n\n".join(f"Paragraf nomor {i} " + "isi " * 80 for i in range(30))
        chunks = chunk_text(text)
        self.assertGreater(len(chunks), 3)
        self.assertTrue(all(len(c) <= 1100 for c in chunks))
        self.assertEqual(chunk_text("   \n\n  "), [])


if __name__ == "__main__":
    unittest.main()
