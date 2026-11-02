import json

from tests.helpers import PW, Base

SECRET = "RAHASIA-KATA-UNIK-98765"


class PersonalDataTests(Base):
    def test_memory_requires_cloud_sync_consent(self):
        t = self.personal()
        r = self.post("/v1/personal/memory", {"type": "NOTE", "title": "t", "content": "c"}, t, expect=403)
        self.assertEqual(r.json["error"]["code"], "memory_sync_disabled")
        self.enable_cloud(t)
        self.post("/v1/personal/memory", {"type": "NOTE", "title": "t", "content": "c"}, t, expect=201)

    def test_memory_crud_and_encryption_at_rest(self):
        t = self.personal()
        self.enable_cloud(t)
        m = self.post("/v1/personal/memory", {"type": "PROFILE", "title": "Nama", "content": SECRET}, t, expect=201).json
        self.assertEqual(self.get("/v1/personal/memory?type=PROFILE", t).json["items"][0]["content"], SECRET)
        self.sv.pdb.run("PRAGMA wal_checkpoint(FULL)")
        self.assertNotIn(SECRET.encode(), self.all_db_bytes())
        u = self.put(f"/v1/personal/memory/{m['id']}", {"content": "baru"}, t, expect=200).json
        self.assertEqual((u["content"], u["title"]), ("baru", "Nama"))
        self.delete(f"/v1/personal/memory/{m['id']}", t, expect=204)
        self.assertEqual(self.get("/v1/personal/memory", t).json["items"], [])
        self.delete(f"/v1/personal/memory/{m['id']}", t, expect=404)
        self.put("/v1/personal/memory/tidak-ada-1", {"content": "x"}, t, expect=404)
        self.post("/v1/personal/memory", {"type": "SALAH", "title": "t", "content": "c"}, t, expect=400)

    def test_data_is_isolated_between_users(self):
        a, b = self.personal("a@x.co"), self.personal("b@x.co")
        self.enable_cloud(a)
        self.enable_cloud(b)
        m = self.post("/v1/personal/memory", {"type": "NOTE", "title": "t", "content": "milik A"}, a, expect=201).json
        self.assertEqual(self.get("/v1/personal/memory", b).json["items"], [])
        self.put(f"/v1/personal/memory/{m['id']}", {"content": "dibajak"}, b, expect=404)
        self.post("/v1/personal/memory", {"id": m["id"], "type": "NOTE", "title": "t", "content": "milik B"}, b, expect=201)
        self.assertEqual(self.get("/v1/personal/memory", a).json["items"][0]["content"], "milik A")
        self.assertEqual(self.get("/v1/personal/memory", b).json["items"][0]["content"], "milik B")

    def test_sync_last_write_wins_and_tombstones(self):
        t = self.personal()
        self.enable_cloud(t)
        item = {"id": "client-uuid-0001", "type": "NOTE", "title": "judul", "content": "v1", "updated_at": 1000}
        r = self.post("/v1/personal/memory/sync", {"since": 0, "items": [item]}, t, expect=200).json
        self.assertEqual(r["applied"], 1)
        r = self.post("/v1/personal/memory/sync", {"since": 0, "items": [{**item, "content": "lama", "updated_at": 500}]}, t, expect=200).json
        self.assertEqual(r["applied"], 0)
        self.assertEqual([i["content"] for i in r["items"]], ["v1"])
        r = self.post("/v1/personal/memory/sync", {"since": 0, "items": [{**item, "deleted": True, "updated_at": 2000}]}, t, expect=200).json
        self.assertTrue(r["items"][0]["deleted"])
        self.assertEqual(r["items"][0]["content"], "")
        self.assertEqual(self.get("/v1/personal/memory", t).json["items"], [])

    def test_clear_memory_by_type(self):
        t = self.personal()
        self.enable_cloud(t)
        for ty in ("NOTE", "NOTE", "TASK"):
            self.post("/v1/personal/memory", {"type": ty, "title": "t", "content": "c"}, t, expect=201)
        self.assertEqual(self.delete("/v1/personal/memory?type=NOTE", t, expect=200).json["deleted"], 2)
        self.assertEqual(len(self.get("/v1/personal/memory", t).json["items"]), 1)

    def test_reminders(self):
        t = self.personal()
        self.enable_cloud(t)
        r = self.post("/v1/personal/reminders", {"title": "Minum obat", "due_at": 1_900_000_000_000, "repeat": "daily", "notes": SECRET}, t, expect=201).json
        self.assertEqual(r["repeat"], "daily")
        self.post("/v1/personal/reminders", {"title": "Tanpa waktu"}, t, expect=400)
        self.put(f"/v1/personal/reminders/{r['id']}", {"done_at": 1_900_000_100_000}, t, expect=200)
        items = self.get("/v1/personal/reminders", t).json["items"]
        self.assertEqual((items[0]["title"], items[0]["done_at"], items[0]["notes"]), ("Minum obat", 1_900_000_100_000, SECRET))
        self.sv.pdb.run("PRAGMA wal_checkpoint(FULL)")
        self.assertNotIn(SECRET.encode(), self.all_db_bytes())

    def test_privacy_defaults_off_and_updates_are_audited(self):
        t = self.personal()
        p = self.get("/v1/personal/privacy", t).json
        self.assertEqual((p["cloud_processing"], p["memory_sync"]), (False, False))
        p = self.put("/v1/personal/privacy", {"cloud_processing": True, "retention_days": 30}, t, expect=200).json
        self.assertEqual((p["cloud_processing"], p["retention_days"]), (True, 30))
        uid = self.get("/v1/auth/me", t).json["id"]
        actions = [e["action"] for e in self.sv.paudit.query(uid)]
        self.assertIn("privacy.updated", actions)
        self.assertTrue(self.sv.paudit.verify(uid)["ok"])

    def test_export_contains_decrypted_data(self):
        t = self.personal()
        self.enable_cloud(t)
        self.post("/v1/personal/memory", {"type": "NOTE", "title": "t", "content": SECRET}, t, expect=201)
        r = self.get("/v1/personal/export", t, expect=200)
        self.assertIn("attachment", r.headers["Content-Disposition"])
        self.assertEqual(r.json["memory"][0]["content"], SECRET)
        self.assertEqual(r.json["profile"]["email"], "ani@example.com")

    def test_delete_account_erases_everything(self):
        t = self.personal()
        self.enable_cloud(t)
        uid = self.get("/v1/auth/me", t).json["id"]
        self.post("/v1/personal/memory", {"type": "NOTE", "title": "t", "content": SECRET}, t, expect=201)
        self.delete("/v1/personal/account", t, {"password": "salah-banget-1"}, expect=401)
        self.delete("/v1/personal/account", t, {"password": PW}, expect=204)
        self.get("/v1/auth/me", t, expect=401)
        for table in ("users", "user_keys", "sessions", "privacy", "memory_entries"):
            self.assertEqual(self.sv.pdb.scalar(f"SELECT COUNT(*) FROM {table}"), 0, table)
        self.assertEqual(self.sv.pdb.scalar("SELECT COUNT(*) FROM audit_log WHERE scope_id=?", (uid,)), 0)
        self.post("/v1/auth/personal/login", {"email": "ani@example.com", "password": PW}, expect=401)

    def test_profile_update_and_activity_empty(self):
        t = self.personal()
        self.put("/v1/personal/profile", {"display_name": "Budi"}, t, expect=200)
        self.assertEqual(self.get("/v1/personal/profile", t).json["display_name"], "Budi")
        self.assertEqual(self.get("/v1/personal/activity", t).json["items"], [])

    def test_notifications_inbox(self):
        t = self.personal()
        uid = self.get("/v1/auth/me", t).json["id"]
        from xnva.api.misc import notify
        with self.app.app_context():
            notify("personal", uid, uid, "x", "Halo", "isi")
        inbox = self.get("/v1/notifications", t, expect=200).json
        self.assertEqual((inbox["unread"], len(inbox["items"])), (1, 1))
        self.post(f"/v1/notifications/{inbox['items'][0]['id']}/read", tok=t, expect=204)
        self.assertEqual(self.get("/v1/notifications", t).json["unread"], 0)
