import base64

from tests.helpers import PW, Base

ENT = "/v1/enterprise"
HW = "A1B2C3D4E5F6"


class DeviceTests(Base):
    def test_personal_register_secret_rotate_revoke(self):
        t = self.personal()
        r = self.post("/v1/devices", {"hw_id": HW.lower(), "label": "Kacamata Ani", "fw_version": "0.2.0"}, t, expect=201).json
        self.assertEqual(r["hw_id"], HW)
        raw = base64.urlsafe_b64decode(r["secret"] + "==")
        self.assertEqual(len(raw), 32)
        self.assertEqual(self.get(f"/v1/devices/{r['id']}/secret", t, expect=200).json["secret"], r["secret"])
        self.post("/v1/devices", {"hw_id": HW, "label": "lagi"}, t, expect=409)
        new = self.post(f"/v1/devices/{r['id']}/rotate-secret", tok=t, expect=200).json["secret"]
        self.assertNotEqual(new, r["secret"])
        self.assertEqual(self.get(f"/v1/devices/{r['id']}/secret", t).json["secret"], new)
        self.sv.pdb.run("PRAGMA wal_checkpoint(FULL)")
        self.assertNotIn(new.encode(), self.all_db_bytes())
        self.delete(f"/v1/devices/{r['id']}", t, expect=204)
        self.get(f"/v1/devices/{r['id']}/secret", t, expect=403)
        self.assertTrue(self.get("/v1/devices", t).json["items"][0]["revoked"])
        again = self.post("/v1/devices", {"hw_id": HW, "label": "Baru"}, t, expect=201).json
        self.assertNotEqual(again["secret"], new)

    def test_validation_and_isolation(self):
        a, b = self.personal("a@x.co"), self.personal("b@x.co")
        self.post("/v1/devices", {"hw_id": "bukan-hex", "label": "x"}, a, expect=400)
        d = self.post("/v1/devices", {"hw_id": HW, "label": "A"}, a, expect=201).json["id"]
        self.get(f"/v1/devices/{d}/secret", b, expect=404)
        self.delete(f"/v1/devices/{d}", b, expect=404)
        self.post(f"/v1/devices/{d}/telemetry", {"battery_pct": 50}, b, expect=404)
        self.post("/v1/devices", {"hw_id": HW, "label": "B"}, b, expect=201)  # same hardware id can belong to a different personal account

    def test_secret_reads_are_audited(self):
        t = self.personal()
        uid = self.get("/v1/auth/me", t).json["id"]
        d = self.post("/v1/devices", {"hw_id": HW, "label": "A"}, t, expect=201).json["id"]
        self.get(f"/v1/devices/{d}/secret", t, expect=200)
        self.assertIn("device.secret_read", [e["action"] for e in self.sv.paudit.query(uid)])

    def test_telemetry_and_low_battery_notification(self):
        t = self.personal()
        d = self.post("/v1/devices", {"hw_id": HW, "label": "A"}, t, expect=201).json["id"]
        self.post(f"/v1/devices/{d}/telemetry", {"battery_pct": 80, "fw_version": "0.2.1"}, t, expect=204)
        self.post(f"/v1/devices/{d}/telemetry", {"battery_pct": 12}, t, expect=204)
        self.post(f"/v1/devices/{d}/telemetry", {"battery_pct": 10}, t, expect=204)
        self.post(f"/v1/devices/{d}/telemetry", {"battery_pct": 101}, t, expect=400)
        dev = self.get("/v1/devices", t).json["items"][0]
        self.assertEqual((dev["battery_pct"], dev["fw_version"]), (10, "0.2.1"))
        low = [n for n in self.get("/v1/notifications", t).json["items"] if n["kind"] == "device.low_battery"]
        self.assertEqual(len(low), 1)
        self.put(f"/v1/devices/{d}", {"label": "Kacamata Baru"}, t, expect=200)

    def test_enterprise_devices_policy_and_admin_control(self):
        owner = self.org()
        emp = self.employee(owner, "e@acme.test", "member")
        d = self.post("/v1/devices", {"hw_id": HW, "label": "Kacamata E"}, emp, expect=201).json["id"]
        self.post("/v1/devices", {"hw_id": HW, "label": "dup"}, owner, expect=409)  # one hardware id per organization
        listed = self.get("/v1/devices", owner).json["items"]
        self.assertEqual((len(listed), listed[0]["owner_id"] == emp["id"]), (1, True))
        self.assertEqual(len(self.get("/v1/devices", emp).json["items"]), 1)
        self.get(f"/v1/devices/{d}/secret", owner, expect=404)  # admins can revoke but never read an employee's secret
        self.delete(f"/v1/devices/{d}", owner, expect=204)
        self.assertEqual(self.get("/v1/notifications", emp).json["items"][0]["kind"], "device.revoked")
        self.get(f"/v1/devices/{d}/secret", emp, expect=403)
        self.put(f"{ENT}/policy", {"allow_employee_devices": False}, owner, expect=200)
        self.post("/v1/devices", {"hw_id": "112233445566", "label": "x"}, emp, expect=403)
        self.post("/v1/devices", {"hw_id": "112233445566", "label": "x"}, owner, expect=201)

    def test_unauthenticated_and_cross_realm(self):
        self.post("/v1/devices", {"hw_id": HW, "label": "x"}, expect=401)
        p, owner = self.personal(), self.org()
        d = self.post("/v1/devices", {"hw_id": HW, "label": "P"}, p, expect=201).json["id"]
        self.get(f"/v1/devices/{d}/secret", owner, expect=404)
