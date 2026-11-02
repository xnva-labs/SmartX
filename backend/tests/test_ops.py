import base64
import json
import os
import subprocess
import sys
import tempfile
import unittest

from tests.helpers import PW, Base, make_env
from xnva import clock, maintenance
from xnva.app import create_app

BACKEND = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def run_cli(env, *args):
    e = {**os.environ, **env, "PYTHONPATH": BACKEND}
    return subprocess.run([sys.executable, "-m", "xnva", *args], env=e, capture_output=True, text=True)


class CliTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = make_env(os.path.join(self.tmp.name, "data"))

    def tearDown(self):
        self.tmp.cleanup()

    def test_init_create_org_and_login_via_api(self):
        r = run_cli(self.env, "init")
        self.assertEqual(r.returncode, 0, r.stderr)
        r = run_cli(self.env, "create-org", "--name", "Contoh PT", "--slug", "contoh", "--owner-email", "Owner@Contoh.test", "--owner-name", "Owner")
        self.assertEqual(r.returncode, 0, r.stderr)
        out = json.loads(r.stdout)
        app = create_app(self.env)
        c = app.test_client()
        acc = c.post("/v1/auth/enterprise/invites/accept", json={"token": out["invite_token"], "password": PW})
        self.assertEqual(acc.status_code, 200)
        self.assertEqual(acc.json["employee"]["email"], "owner@contoh.test")
        app.extensions["xnva"].pdb.close(), app.extensions["xnva"].edb.close()
        self.assertEqual(run_cli(self.env, "create-org", "--name", "X", "--slug", "BAD SLUG", "--owner-email", "a@b.co", "--owner-name", "A").returncode, 2)

    def test_missing_secrets_fail_fast(self):
        r = run_cli({"XNVA_MASTER_KEY": "", "XNVA_JWT_SECRET": ""}, "init")
        self.assertEqual(r.returncode, 2)
        self.assertIn("XNVA_MASTER_KEY", r.stderr)

    def test_backup_restore_and_verify_audit(self):
        run_cli(self.env, "init")
        out = json.loads(run_cli(self.env, "create-org", "--name", "A", "--slug", "aaa", "--owner-email", "o@a.co", "--owner-name", "O").stdout)
        dest = os.path.join(self.tmp.name, "bk")
        r = run_cli(self.env, "backup", "--dest", dest)
        self.assertEqual(r.returncode, 0, r.stderr)
        snap = r.stdout.strip()
        self.assertTrue(os.path.exists(os.path.join(snap, "manifest.json")))
        v = run_cli(self.env, "verify-audit")
        self.assertEqual(v.returncode, 0, v.stdout)
        self.assertTrue(json.loads(v.stdout)["ok"])
        # simulate disaster, then restore
        data = self.env["XNVA_DATA_DIR"]
        for fn in os.listdir(data):
            os.remove(os.path.join(data, fn))
        self.assertEqual(run_cli(self.env, "restore", "--src", snap).returncode, 0)
        app = create_app(self.env)
        acc = app.test_client().post("/v1/auth/enterprise/invites/accept", json={"token": out["invite_token"], "password": PW})
        self.assertEqual(acc.status_code, 200)
        app.extensions["xnva"].pdb.close(), app.extensions["xnva"].edb.close()
        self.assertEqual(run_cli(self.env, "restore", "--src", snap).returncode, 1)  # refuses to overwrite without --force
        self.assertEqual(run_cli(self.env, "restore", "--src", snap, "--force").returncode, 0)

    def test_restore_rejects_corrupted_backup(self):
        run_cli(self.env, "init")
        snap = run_cli(self.env, "backup", "--dest", os.path.join(self.tmp.name, "bk")).stdout.strip()
        with open(os.path.join(snap, "personal.db"), "ab") as f:
            f.write(b"corrupt")
        self.assertEqual(run_cli(self.env, "restore", "--src", snap, "--force").returncode, 1)

    def test_tampered_audit_is_reported_by_cli(self):
        run_cli(self.env, "init")
        run_cli(self.env, "create-org", "--name", "A", "--slug", "aaa", "--owner-email", "o@a.co", "--owner-name", "O")
        import sqlite3
        con = sqlite3.connect(os.path.join(self.env["XNVA_DATA_DIR"], "enterprise.db"))
        con.execute("UPDATE audit_log SET action='dipalsukan'")
        con.commit(), con.close()
        v = run_cli(self.env, "verify-audit")
        self.assertEqual(v.returncode, 1)
        self.assertFalse(json.loads(v.stdout)["ok"])

    def test_master_key_rotation_keeps_data_readable(self):
        app = create_app(self.env)
        c = app.test_client()
        t = c.post("/v1/auth/personal/register", json={"email": "a@x.co", "password": PW}).json
        h = {"Authorization": "Bearer " + t["access_token"]}
        c.put("/v1/personal/privacy", json={"memory_sync": True}, headers=h)
        c.post("/v1/personal/memory", json={"type": "NOTE", "title": "t", "content": "ISI-SEBELUM-ROTASI"}, headers=h)
        sv = app.extensions["xnva"]
        sv.pdb.run("PRAGMA wal_checkpoint(FULL)"), sv.pdb.close(), sv.edb.close()
        new_key = base64.b64encode(os.urandom(32)).decode()
        r = run_cli({**self.env, "XNVA_NEW_MASTER_KEY": new_key}, "rotate-master-key")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(json.loads(r.stdout)["rewrapped_user_keys"], 1)
        from xnva.config import ConfigError
        with self.assertRaises(ConfigError):  # the old key is refused at startup instead of corrupting data
            create_app(self.env)
        new_app = create_app({**self.env, "XNVA_MASTER_KEY": new_key})
        got = new_app.test_client().get("/v1/personal/memory", headers=h)
        self.assertEqual(got.json["items"][0]["content"], "ISI-SEBELUM-ROTASI")
        new_app.extensions["xnva"].pdb.close(), new_app.extensions["xnva"].edb.close()
        self.assertEqual(run_cli({**self.env, "XNVA_NEW_MASTER_KEY": "bukan-kunci"}, "rotate-master-key").returncode, 2)


class MaintenanceTests(Base):
    def test_cleanup_and_retention(self):
        t = self.personal()
        uid = self.get("/v1/auth/me", t).json["id"]
        self.enable_cloud(t)
        m = self.post("/v1/personal/memory", {"type": "NOTE", "title": "t", "content": "c"}, t, expect=201).json
        self.delete(f"/v1/personal/memory/{m['id']}", t, expect=204)
        now = clock.now_ms()
        self.sv.pdb.run("INSERT INTO ai_activity(user_id,ts,kind,bytes_in,bytes_out) VALUES(?,?,?,?,?)", (uid, now - 100 * clock.DAY_MS, "chat", 1, 1))
        self.sv.pdb.run("INSERT INTO ai_activity(user_id,ts,kind,bytes_in,bytes_out) VALUES(?,?,?,?,?)", (uid, now - 5 * clock.DAY_MS, "chat", 1, 1))
        out = maintenance.run(self.sv, now=now + 31 * clock.DAY_MS)
        self.assertEqual(out["personal_tombstones"], 1)
        self.assertEqual(out["personal_activity"], 1)  # default 90-day retention removes only the 100-day-old row
        self.assertEqual(self.sv.pdb.scalar("SELECT COUNT(*) FROM ai_activity"), 1)
        t = self.post("/v1/auth/personal/login", {"email": "ani@example.com", "password": PW}, expect=200).json
        self.put("/v1/personal/privacy", {"retention_days": 1}, t, expect=200)
        out = maintenance.run(self.sv, now=now)
        self.assertEqual(self.sv.pdb.scalar("SELECT COUNT(*) FROM ai_activity"), 0)

    def test_personal_security_events_expire_after_a_year_and_chain_stays_valid(self):
        t = self.personal()
        uid = self.get("/v1/auth/me", t).json["id"]
        n = self.sv.paudit.verify(uid)["entries"]
        self.assertGreaterEqual(n, 1)
        out = maintenance.run(self.sv, now=clock.now_ms() + 400 * clock.DAY_MS)
        self.assertEqual(out["personal_security_events"], n)
        self.sv.paudit.append(uid, "after.purge")
        self.assertTrue(self.sv.paudit.verify(uid)["ok"])

    def test_expired_sessions_and_invites_are_removed(self):
        owner = self.org()
        self.post("/v1/enterprise/employees", {"email": "x@acme.test", "display_name": "X"}, owner, expect=201)
        far = clock.now_ms() + 120 * clock.DAY_MS
        out = maintenance.run(self.sv, now=far)
        self.assertGreaterEqual(out["enterprise_sessions"], 1)
        self.assertGreaterEqual(out["enterprise_invites"], 1)


class HealthTests(Base):
    def test_health_ready_status(self):
        self.assertEqual(self.get("/healthz", expect=200).json["status"], "ok")
        self.assertEqual(self.get("/readyz", expect=200).json["status"], "ready")
        f = self.get("/v1/status", expect=200).json["features"]
        self.assertEqual((f["xnai_chat"], f["org_signup"]), ("not_configured", False))

    def test_rate_limit_on_login(self):
        self.personal()
        codes = [self.post("/v1/auth/personal/login", {"email": "ani@example.com", "password": "salah-banget-1"}).status_code for _ in range(25)]
        self.assertIn(429, codes)

    def test_body_size_limit(self):
        r = self.c.post("/v1/auth/personal/login", data=b"x" * (9 * 1024 * 1024), content_type="application/json")
        self.assertEqual(r.status_code, 413)
        self.assertEqual(r.json["error"]["code"], "payload_too_large")
