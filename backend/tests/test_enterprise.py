import json

from tests.helpers import PW, Base
from xnva import clock, maintenance

ENT = "/v1/enterprise"


class RbacTests(Base):
    def setUp(self):
        super().setUp()
        self.owner = self.org()
        self.d1 = self.division(self.owner, "Produksi")
        self.admin = self.employee(self.owner, "admin@acme.test", "admin")
        self.manager = self.employee(self.owner, "mgr@acme.test", "manager", self.d1)
        self.member = self.employee(self.owner, "mem@acme.test", "member", self.d1)
        self.auditor = self.employee(self.owner, "aud@acme.test", "auditor")

    def test_permission_matrix(self):
        self.post(f"{ENT}/employees", {"email": "x@acme.test", "display_name": "X"}, self.member, expect=403)
        self.post(f"{ENT}/employees", {"email": "x@acme.test", "display_name": "X"}, self.manager, expect=403)
        self.post(f"{ENT}/projects", {"name": "P"}, self.member, expect=403)
        self.post(f"{ENT}/projects", {"name": "P"}, self.manager, expect=201)
        self.post(f"{ENT}/projects", {"name": "P"}, self.auditor, expect=403)
        self.get(f"{ENT}/audit", self.member, expect=403)
        self.get(f"{ENT}/audit", self.manager, expect=403)
        self.get(f"{ENT}/audit", self.auditor, expect=200)
        self.get(f"{ENT}/audit", self.admin, expect=200)
        self.put(f"{ENT}/policy", {"ai_enabled": False}, self.manager, expect=403)
        self.put(f"{ENT}/policy", {"ai_enabled": True}, self.admin, expect=200)
        self.post(f"{ENT}/tasks", {"title": "T"}, self.auditor, expect=403)
        self.get(f"{ENT}/security/sessions", self.manager, expect=403)
        self.get(f"{ENT}/security/sessions", self.admin, expect=200)

    def test_denied_attempts_are_audited(self):
        self.get(f"{ENT}/audit", self.member, expect=403)
        events = self.get(f"{ENT}/audit?action=authz", self.owner).json["items"]
        self.assertTrue(any(e["action"] == "authz.denied" and e["meta"]["perm"] == "audit.read" for e in events))

    def test_role_assignment_rules(self):
        self.post(f"{ENT}/employees", {"email": "n@acme.test", "display_name": "N", "role": "admin"}, self.admin, expect=403)
        self.post(f"{ENT}/employees", {"email": "n@acme.test", "display_name": "N", "role": "owner"}, self.admin, expect=403)
        self.post(f"{ENT}/employees", {"email": "n@acme.test", "display_name": "N", "role": "manager"}, self.admin, expect=201)
        self.put(f"{ENT}/employees/{self.manager['id']}", {"role": "admin"}, self.admin, expect=403)
        self.put(f"{ENT}/employees/{self.admin['id']}", {"role": "member"}, self.admin, expect=403)

    def test_last_owner_and_self_protection(self):
        me = self.get("/v1/auth/me", self.owner).json["id"]
        self.put(f"{ENT}/employees/{me}", {"role": "member"}, self.owner, expect=403)
        self.put(f"{ENT}/employees/{me}", {"status": "disabled"}, self.owner, expect=403)
        second = self.employee(self.owner, "o2@acme.test", "owner")
        self.put(f"{ENT}/employees/{second['id']}", {"role": "member"}, self.owner, expect=200)
        self.put(f"{ENT}/employees/{me}", {"display_name": "Nama Baru"}, self.owner, expect=200)

    def test_directory_hides_login_activity_from_non_admins(self):
        mine = self.get(f"{ENT}/employees", self.member, expect=200).json["items"]
        self.assertNotIn("last_login_at", mine[0])
        full = self.get(f"{ENT}/employees", self.admin, expect=200).json["items"]
        self.assertIn("last_login_at", full[0])

    def test_divisions_rules(self):
        self.post(f"{ENT}/divisions", {"name": "Produksi"}, self.owner, expect=409)
        self.post(f"{ENT}/divisions", {"name": "Baru"}, self.manager, expect=403)
        self.delete(f"{ENT}/divisions/{self.d1}", self.owner, expect=409)
        d2 = self.division(self.owner, "Kosong")
        self.delete(f"{ENT}/divisions/{d2}", self.owner, expect=204)
        self.assertEqual(sorted(d["name"] for d in self.get(f"{ENT}/divisions", self.member).json["items"]), ["Produksi"])


class TenantIsolationTests(Base):
    def test_cross_tenant_access_is_impossible(self):
        a, b = self.org("acme", "a@acme.test"), self.org("globex", "b@globex.test", name="Globex")
        div = self.division(a, "Ops")
        proj = self.post(f"{ENT}/projects", {"name": "Rahasia A"}, a, expect=201).json["id"]
        task = self.post(f"{ENT}/tasks", {"title": "Tugas A", "project_id": proj}, a, expect=201).json["id"]
        ann = self.post(f"{ENT}/announcements", {"title": "Info A", "body": "isi"}, a, expect=201).json["id"]
        doc = self.post(f"{ENT}/kb/documents", {"title": "SOP A", "text": "Prosedur rahasia perusahaan acme nomor satu."}, a, expect=201).json["id"]
        mt = self.post(f"{ENT}/meetings", {"title": "Rapat A", "notes": "catatan"}, a, expect=201).json["id"]
        dev = self.post("/v1/devices", {"hw_id": "AABBCCDDEEFF", "label": "Kacamata A"}, a, expect=201).json["id"]
        emp = self.employee(a, "e@acme.test", "member", div)

        for path in (f"/tasks/{task}", f"/meetings/{mt}"):
            self.get(ENT + path, b, expect=404)
        self.put(f"{ENT}/tasks/{task}", {"title": "dibajak"}, b, expect=404)
        self.post(f"{ENT}/tasks/{task}/status", {"status": "done"}, b, expect=404)
        self.delete(f"{ENT}/tasks/{task}", b, expect=404)
        self.get(f"{ENT}/tasks/{task}/comments", b, expect=404)
        self.put(f"{ENT}/projects/{proj}", {"name": "dibajak"}, b, expect=404)
        self.delete(f"{ENT}/projects/{proj}", b, expect=404)
        self.delete(f"{ENT}/announcements/{ann}", b, expect=404)
        self.delete(f"{ENT}/kb/documents/{doc}", b, expect=404)
        self.delete(f"{ENT}/meetings/{mt}", b, expect=404)
        self.put(f"{ENT}/employees/{emp['id']}", {"role": "admin"}, b, expect=404)
        self.put(f"{ENT}/divisions/{div}", {"name": "x"}, b, expect=404)
        self.delete(f"/v1/devices/{dev}", b, expect=404)
        self.get(f"/v1/devices/{dev}/secret", b, expect=404)
        # references to another tenant's objects in request bodies are rejected
        self.post(f"{ENT}/tasks", {"title": "T", "project_id": proj}, b, expect=400)
        self.post(f"{ENT}/tasks", {"title": "T", "assignee_id": emp["id"]}, b, expect=400)
        self.post(f"{ENT}/employees", {"email": "n@globex.test", "display_name": "N", "division_id": div}, b, expect=400)
        # lists never leak
        for lst in ("tasks", "projects", "announcements", "kb/documents", "meetings", "employees", "divisions", "audit", "reports"):
            body = json.dumps(self.get(f"{ENT}/{lst}", b, expect=200).json)
            for needle in ("Tugas A", "Rahasia A", "Info A", "SOP A", "Rapat A", "e@acme.test", "Ops", "acme"):
                self.assertNotIn(needle, body, f"{lst} bocor: {needle}")
        self.assertEqual(self.get(f"{ENT}/kb/search?q=prosedur+rahasia", b).json["items"], [])
        self.assertEqual(self.get("/v1/devices", b).json["items"], [])
        self.assertTrue(self.sv.eaudit.verify(self.get("/v1/auth/me", b).json["org"]["id"])["ok"])
        # login is scoped by org slug
        self.post("/v1/auth/enterprise/login", {"org": "globex", "email": "a@acme.test", "password": PW}, expect=401)

    def test_same_email_can_exist_in_two_orgs(self):
        a, b = self.org("acme", "same@x.test"), self.org("globex", "same@x.test", name="Globex")
        self.assertEqual(self.get("/v1/auth/me", a).json["org"]["slug"], "acme")
        self.assertEqual(self.get("/v1/auth/me", b).json["org"]["slug"], "globex")

    def test_org_deletion_shreds_only_that_org(self):
        a, b = self.org("acme", "a@acme.test"), self.org("globex", "b@globex.test", name="Globex")
        self.post(f"{ENT}/tasks", {"title": "RAHASIA-TASK-ACME"}, a, expect=201)
        self.post(f"{ENT}/tasks", {"title": "Tugas milik globex"}, b, expect=201)
        oid = self.get("/v1/auth/me", a).json["org"]["id"]
        self.delete(f"{ENT}/org", a, {"confirm_slug": "salah", "password": PW}, expect=400)
        self.delete(f"{ENT}/org", a, {"confirm_slug": "acme", "password": "salah-banget"}, expect=401)
        self.delete(f"{ENT}/org", a, {"confirm_slug": "acme", "password": PW}, expect=204)
        self.get("/v1/auth/me", a, expect=401)
        for table in ("orgs", "tenant_keys", "tasks", "employees", "audit_log"):
            col = "id" if table == "orgs" else ("scope_id" if table == "audit_log" else "org_id")
            self.assertEqual(self.sv.edb.scalar(f"SELECT COUNT(*) FROM {table} WHERE {col}=?", (oid,)), 0, table)
        self.assertEqual(len(self.get(f"{ENT}/tasks", b, expect=200).json["items"]), 1)


class WorkTests(Base):
    def setUp(self):
        super().setUp()
        self.owner = self.org()
        self.d1, self.d2 = self.division(self.owner, "Produksi"), self.division(self.owner, "Gudang")
        self.mgr = self.employee(self.owner, "mgr@acme.test", "manager", self.d1)
        self.m1 = self.employee(self.owner, "m1@acme.test", "member", self.d1)
        self.m1b = self.employee(self.owner, "m1b@acme.test", "member", self.d1)
        self.m2 = self.employee(self.owner, "m2@acme.test", "member", self.d2)

    def test_task_visibility_scopes(self):
        t_other = self.post(f"{ENT}/tasks", {"title": "Tugas Gudang", "assignee_id": self.m2["id"]}, self.owner, expect=201).json["id"]
        t_div = self.post(f"{ENT}/tasks", {"title": "Tugas Produksi", "assignee_id": self.m1b["id"]}, self.mgr, expect=201).json["id"]
        mine = self.post(f"{ENT}/tasks", {"title": "Tugas Saya"}, self.m1, expect=201).json
        self.assertEqual(mine["assignee_id"], self.m1["id"])
        titles = lambda tok: sorted(t["title"] for t in self.get(f"{ENT}/tasks", tok, expect=200).json["items"])
        self.assertEqual(titles(self.m1), ["Tugas Produksi", "Tugas Saya"])
        self.assertEqual(titles(self.m2), ["Tugas Gudang"])
        self.assertEqual(titles(self.mgr), ["Tugas Produksi", "Tugas Saya"])
        self.assertEqual(len(titles(self.owner)), 3)
        self.get(f"{ENT}/tasks/{t_other}", self.m1, expect=404)
        self.get(f"{ENT}/tasks/{t_div}", self.m1, expect=200)

    def test_assignment_and_status_rules_and_notifications(self):
        self.post(f"{ENT}/tasks", {"title": "T", "assignee_id": self.m2["id"]}, self.m1, expect=403)
        t = self.post(f"{ENT}/tasks", {"title": "Rakit mesin", "assignee_id": self.m1["id"], "priority": "high", "due_date": "2000-01-01"}, self.mgr, expect=201).json
        self.assertTrue(t["overdue"])
        inbox = self.get("/v1/notifications", self.m1).json
        self.assertEqual(inbox["items"][0]["kind"], "task.assigned")
        self.assertNotIn("Rakit", json.dumps(inbox))  # no task content in plaintext notification rows
        self.post(f"{ENT}/tasks/{t['id']}/status", {"status": "done"}, self.m1b, expect=403)
        d = self.post(f"{ENT}/tasks/{t['id']}/status", {"status": "done"}, self.m1, expect=200).json
        self.assertIsNotNone(d["completed_at"])
        self.assertFalse(d["overdue"])
        self.assertEqual(self.get("/v1/notifications", self.mgr).json["items"][0]["kind"], "task.done")
        d = self.post(f"{ENT}/tasks/{t['id']}/status", {"status": "doing"}, self.m1, expect=200).json
        self.assertIsNone(d["completed_at"])
        self.post(f"{ENT}/tasks/{t['id']}/status", {"status": "selesai"}, self.m1, expect=400)
        self.put(f"{ENT}/tasks/{t['id']}", {"title": "ubah"}, self.m1, expect=403)
        self.put(f"{ENT}/tasks/{t['id']}", {"title": "Rakit mesin v2", "assignee_id": self.m1b["id"]}, self.mgr, expect=200)
        self.assertEqual(self.get("/v1/notifications", self.m1b).json["items"][0]["kind"], "task.assigned")
        self.assertEqual(len(self.get(f"{ENT}/tasks?mine=1", self.m1b).json["items"]), 1)
        self.assertEqual(len(self.get(f"{ENT}/tasks?overdue=1", self.owner).json["items"]), 1)

    def test_comments(self):
        t = self.post(f"{ENT}/tasks", {"title": "T", "assignee_id": self.m1["id"]}, self.mgr, expect=201).json["id"]
        self.post(f"{ENT}/tasks/{t}/comments", {"body": "Sudah dicek"}, self.m1, expect=201)
        c = self.get(f"{ENT}/tasks/{t}/comments", self.mgr).json["items"]
        self.assertEqual((c[0]["body"], c[0]["author_name"]), ("Sudah dicek", "Karyawan"))
        self.get(f"{ENT}/tasks/{t}/comments", self.m2, expect=404)
        self.assertEqual(self.get("/v1/notifications", self.mgr).json["items"][0]["kind"], "task.comment")

    def test_task_content_is_encrypted_at_rest(self):
        self.post(f"{ENT}/tasks", {"title": "JUDUL-TUGAS-UNIK-777", "description": "DESKRIPSI-UNIK-888"}, self.owner, expect=201)
        self.sv.edb.run("PRAGMA wal_checkpoint(FULL)")
        blob = self.all_db_bytes()
        self.assertNotIn(b"JUDUL-TUGAS-UNIK-777", blob)
        self.assertNotIn(b"DESKRIPSI-UNIK-888", blob)

    def test_announcements_division_scope(self):
        self.post(f"{ENT}/announcements", {"title": "Semua", "body": "b", "pinned": True}, self.owner, expect=201)
        self.post(f"{ENT}/announcements", {"title": "Khusus Produksi", "body": "b", "division_id": self.d1}, self.mgr, expect=201)
        self.assertEqual(sorted(a["title"] for a in self.get(f"{ENT}/announcements", self.m1).json["items"]), ["Khusus Produksi", "Semua"])
        self.assertEqual([a["title"] for a in self.get(f"{ENT}/announcements", self.m2).json["items"]], ["Semua"])
        self.assertEqual(self.get("/v1/notifications", self.m2).json["items"][0]["kind"], "announcement")
        self.post(f"{ENT}/announcements", {"title": "x", "body": "y"}, self.m1, expect=403)

    def test_projects_and_meetings(self):
        p = self.post(f"{ENT}/projects", {"name": "Lini 3", "description": "d", "due_date": "2030-01-01"}, self.mgr, expect=201).json
        self.assertEqual(p["due_date"], "2030-01-01")
        self.post(f"{ENT}/projects", {"name": "P", "due_date": "31-12-2030"}, self.mgr, expect=400)
        self.put(f"{ENT}/projects/{p['id']}", {"status": "done"}, self.mgr, expect=200)
        self.assertEqual(self.get(f"{ENT}/projects?status=done", self.m1).json["items"][0]["name"], "Lini 3")
        m = self.post(f"{ENT}/meetings", {"title": "Rapat mingguan", "notes": "Budi menyiapkan laporan."}, self.m1, expect=201).json
        self.assertEqual(m["summary_status"], "unavailable")  # no AI provider configured on this server
        self.assertEqual(m["notes"], "Budi menyiapkan laporan.")
        self.get(f"{ENT}/meetings/{m['id']}", self.m2, expect=404)
        self.assertEqual(len(self.get(f"{ENT}/meetings", self.m1).json["items"]), 1)
        self.assertEqual(self.post(f"{ENT}/meetings/{m['id']}/create-tasks", {}, self.mgr, expect=201).json["task_ids"], [])
        self.post(f"{ENT}/meetings/{m['id']}/create-tasks", {"indices": [0]}, self.mgr, expect=400)
        other = self.post(f"{ENT}/meetings", {"title": "Rapat gudang", "notes": "n"}, self.m2, expect=201).json["id"]
        self.post(f"{ENT}/meetings/{other}/create-tasks", {}, self.mgr, expect=404)
        self.delete(f"{ENT}/meetings/{m['id']}", self.m1b, expect=403)
        self.delete(f"{ENT}/meetings/{m['id']}", self.m1, expect=204)


class KnowledgeTests(Base):
    def setUp(self):
        super().setUp()
        self.owner = self.org()
        self.d1, self.d2 = self.division(self.owner, "Produksi"), self.division(self.owner, "Gudang")
        self.mgr = self.employee(self.owner, "mgr@acme.test", "manager", self.d1)
        self.m1 = self.employee(self.owner, "m1@acme.test", "member", self.d1)
        self.m2 = self.employee(self.owner, "m2@acme.test", "member", self.d2)

    def add(self, tok, title, text, **kw):
        return self.post(f"{ENT}/kb/documents", {"title": title, "text": text, **kw}, tok, expect=201).json

    def search(self, tok, q):
        return [h["title"] for h in self.get(f"{ENT}/kb/search?q={q}", tok, expect=200).json["items"]]

    def test_search_ranks_and_respects_visibility(self):
        self.add(self.owner, "SOP Pemeriksaan Mesin", "Prosedur pemeriksaan mesin produksi: periksa tekanan hidrolik, suhu bearing, dan kebocoran oli sebelum shift dimulai.")
        self.add(self.owner, "SOP Gudang", "Prosedur penerimaan barang di gudang: cek surat jalan dan hitung palet.", visibility="division", division_id=self.d2)
        self.add(self.owner, "Gaji Direksi", "Struktur kompensasi direksi dan bonus tahunan rahasia.", visibility="admins")
        self.add(self.owner, "Briefing Manajer", "Target produksi kuartalan dan prosedur eskalasi untuk manajer.", visibility="managers")
        self.assertEqual(self.search(self.m1, "pemeriksaan+mesin")[0], "SOP Pemeriksaan Mesin")
        self.assertEqual(self.search(self.m1, "prosedur+gudang"), ["SOP Pemeriksaan Mesin"])
        self.assertIn("SOP Gudang", self.search(self.m2, "prosedur+gudang"))
        self.assertEqual(self.search(self.m1, "kompensasi+direksi"), [])
        self.assertEqual(self.search(self.mgr, "kompensasi+direksi"), [])
        self.assertEqual(self.search(self.owner, "kompensasi+direksi"), ["Gaji Direksi"])
        self.assertIn("Briefing Manajer", self.search(self.mgr, "eskalasi"))
        self.assertEqual(self.search(self.m1, "eskalasi"), [])
        titles = [d["title"] for d in self.get(f"{ENT}/kb/documents", self.m1).json["items"]]
        self.assertEqual(titles, ["SOP Pemeriksaan Mesin"])
        self.get(f"{ENT}/kb/search?q=x", self.m1, expect=400)

    def test_documents_are_encrypted_and_terms_are_not_readable(self):
        self.add(self.owner, "Dokumen Teknis", "Larutan zirkonium oksida dipanaskan hingga kalsinasi sempurna.")
        self.sv.edb.run("PRAGMA wal_checkpoint(FULL)")
        blob = self.all_db_bytes()
        for needle in (b"zirkonium", b"kalsinasi", b"Dokumen Teknis"):
            self.assertNotIn(needle, blob)
        self.assertEqual(self.search(self.m1, "kalsinasi"), ["Dokumen Teknis"])
        terms = self.sv.edb.all("SELECT term_hash FROM kb_terms")
        self.assertTrue(all(isinstance(r["term_hash"], int) for r in terms))

    def test_duplicates_permissions_and_deletion(self):
        d = self.add(self.owner, "A", "Isi dokumen yang cukup panjang untuk diindeks.")
        self.post(f"{ENT}/kb/documents", {"title": "B", "text": "Isi dokumen yang cukup panjang untuk diindeks."}, self.owner, expect=409)
        self.post(f"{ENT}/kb/documents", {"title": "C", "text": "Isi lain yang cukup panjang."}, self.m1, expect=403)
        self.post(f"{ENT}/kb/documents", {"title": "C", "text": "Isi lain yang cukup panjang.", "visibility": "division"}, self.mgr, expect=201)
        self.delete(f"{ENT}/kb/documents/{d['id']}", self.m1, expect=403)
        self.delete(f"{ENT}/kb/documents/{d['id']}", self.owner, expect=204)
        self.assertEqual(self.search(self.owner, "dokumen+diindeks"), [])
        self.assertEqual(self.sv.edb.scalar("SELECT COUNT(*) FROM kb_terms WHERE chunk_id IN (SELECT id FROM kb_chunks WHERE doc_id=?)", (d["id"],)), 0)

    def test_ask_requires_ai_to_be_configured(self):
        r = self.post(f"{ENT}/kb/ask", {"question": "Bagaimana prosedur pemeriksaan?"}, self.m1, expect=503)
        self.assertEqual(r.json["error"]["code"], "xnai_not_configured")


class InsightTests(Base):
    def setUp(self):
        super().setUp()
        self.owner = self.org()
        self.d1, self.d2 = self.division(self.owner, "Produksi"), self.division(self.owner, "Gudang")
        self.mgr = self.employee(self.owner, "mgr@acme.test", "manager", self.d1)
        self.m1 = self.employee(self.owner, "m1@acme.test", "member", self.d1)
        self.m2 = self.employee(self.owner, "m2@acme.test", "member", self.d2)
        self.aud = self.employee(self.owner, "aud@acme.test", "auditor")
        mk = lambda title, who, **kw: self.post(f"{ENT}/tasks", {"title": title, "assignee_id": who["id"], **kw}, self.owner, expect=201).json["id"]
        t1 = mk("a", self.m1)
        mk("b", self.m1, due_date="2000-01-01")
        t3 = mk("c", self.m2)
        mk("d", self.m2, priority="urgent")
        self.post(f"{ENT}/tasks/{t1}/status", {"status": "done"}, self.m1, expect=200)
        self.post(f"{ENT}/tasks/{t3}/status", {"status": "done"}, self.m2, expect=200)

    def test_overview_for_admin_and_scoped_for_manager(self):
        o = self.get(f"{ENT}/analytics/overview", self.owner, expect=200).json
        self.assertEqual((o["total_tasks"], o["open_now"], o["overdue_now"], o["completed_in_range"]), (4, 2, 1, 2))
        self.assertEqual(o["by_status"], {"done": 2, "todo": 2})
        self.assertEqual(o["completion_rate"], 0.5)
        self.assertEqual(o["open_by_priority"], {"normal": 1, "urgent": 1})
        self.assertIn("workload", o)
        m = self.get(f"{ENT}/analytics/overview", self.mgr, expect=200).json
        self.assertEqual((m["total_tasks"], m["open_now"], m["overdue_now"]), (2, 1, 1))
        self.get(f"{ENT}/analytics/overview", self.m1, expect=403)
        a = self.get(f"{ENT}/analytics/overview", self.aud, expect=200).json
        self.assertNotIn("workload", a)

    def test_self_view_and_transparency(self):
        me = self.get(f"{ENT}/analytics/me", self.m1, expect=200).json
        self.assertEqual((me["total_tasks"], me["open_now"]), (2, 1))
        t = self.get(f"{ENT}/transparency", self.m1, expect=200).json
        self.assertTrue(t["collected"] and t["not_collected"])

    def test_no_per_employee_ai_activity_is_exposed(self):
        body = json.dumps(self.get(f"{ENT}/analytics/overview", self.owner).json)
        self.assertNotIn(self.m1["id"], json.dumps(self.get(f"{ENT}/analytics/overview", self.owner).json.get("ai_usage")))
        self.assertEqual(self.sv.edb.scalar("SELECT COUNT(*) FROM pragma_table_info('ai_usage') WHERE name LIKE '%employee%'"), 0)

    def test_reports(self):
        r = self.post(f"{ENT}/reports", {"kind": "weekly"}, self.mgr, expect=201).json
        self.assertEqual((r["params"]["period_days"], r["metrics"]["total_tasks"]), (7, 2))
        self.post(f"{ENT}/reports", {"kind": "project"}, self.mgr, expect=400)
        self.post(f"{ENT}/reports", {"kind": "weekly", "narrative": True}, self.mgr, expect=503)
        self.assertEqual(len(self.get(f"{ENT}/reports", self.owner).json["items"]), 1)
        self.post(f"{ENT}/reports", {"kind": "weekly"}, self.m1, expect=403)

    def test_audit_chain_export_and_tamper(self):
        v = self.get(f"{ENT}/audit/verify", self.owner, expect=200).json
        self.assertTrue(v["ok"])
        self.assertGreater(v["entries"], 8)
        nd = self.get(f"{ENT}/audit/export", self.aud, expect=200)
        lines = [json.loads(l) for l in nd.get_data(as_text=True).splitlines()]
        self.assertGreater(len(lines), 8)
        self.assertEqual(lines[0]["seq"] > lines[-1]["seq"], True)
        org_id = self.get("/v1/auth/me", self.owner).json["org"]["id"]
        self.sv.edb.run("UPDATE audit_log SET actor_id='penyusup' WHERE scope_id=? AND seq=3", (org_id,))
        v = self.get(f"{ENT}/audit/verify", self.owner, expect=200).json
        self.assertEqual((v["ok"], v["broken_at_seq"]), (False, 3))

    def test_retention_purge_keeps_chain_verifiable(self):
        org_id = self.get("/v1/auth/me", self.owner).json["org"]["id"]
        before = self.sv.eaudit.verify(org_id)["entries"]
        out = maintenance.run(self.sv, now=clock.now_ms() + 400 * clock.DAY_MS)
        self.assertEqual(out["enterprise_retention"]["audit"], before)
        self.sv.eaudit.append(org_id, "after.purge")
        v = self.sv.eaudit.verify(org_id)
        self.assertTrue(v["ok"])
        self.assertEqual(v["entries"], 1)

    def test_policy_validation(self):
        self.put(f"{ENT}/policy", {"audit_retention_days": 10}, self.owner, expect=400)
        p = self.put(f"{ENT}/policy", {"audit_retention_days": 180, "vision_enabled": False}, self.owner, expect=200).json
        self.assertEqual((p["audit_retention_days"], p["vision_enabled"], p["ai_enabled"]), (180, False, True))
        info = self.get(f"{ENT}/org", self.m1).json
        self.assertNotIn("audit_retention_days", info["policy"])

    def test_security_admin_can_revoke_sessions(self):
        sessions = self.get(f"{ENT}/security/sessions", self.owner).json["items"]
        sid = [s for s in sessions if s["email"] == "m1@acme.test"][0]["id"]
        self.delete(f"{ENT}/security/sessions/{sid}", self.owner, expect=204)
        self.get(f"{ENT}/tasks", self.m1, expect=401)
        n = self.post(f"{ENT}/security/employees/{self.m2['id']}/revoke-sessions", tok=self.owner, expect=200).json["revoked"]
        self.assertEqual(n, 1)
