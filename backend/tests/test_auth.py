import time

import jwt

from tests.helpers import PW, Base


class PersonalAuthTests(Base):
    def test_register_login_me(self):
        t = self.personal()
        me = self.get("/v1/auth/me", t, expect=200).json
        self.assertEqual((me["realm"], me["email"]), ("personal", "ani@example.com"))
        self.post("/v1/auth/personal/register", {"email": "ani@example.com", "password": PW}, expect=409)
        l = self.post("/v1/auth/personal/login", {"email": "ANI@example.com", "password": PW}, expect=200).json
        self.assertIn("access_token", l)

    def test_weak_and_invalid_input(self):
        self.post("/v1/auth/personal/register", {"email": "a@b.co", "password": "pendek"}, expect=400)
        self.post("/v1/auth/personal/register", {"email": "bukan-email", "password": PW}, expect=400)
        r = self.post("/v1/auth/personal/register", {"password": PW}, expect=400)
        self.assertEqual(r.json["error"]["code"], "validation_error")
        self.assertTrue(r.json["error"]["request_id"])
        self.post("/v1/auth/personal/register", {"email": "a@b.co", "password": "aaaaaaaaaaaa"}, expect=400)

    def test_login_is_generic_for_unknown_and_wrong_password(self):
        self.personal()
        a = self.post("/v1/auth/personal/login", {"email": "ani@example.com", "password": "salah-banget-1"}, expect=401).json
        b = self.post("/v1/auth/personal/login", {"email": "tidak@ada.com", "password": "salah-banget-1"}, expect=401).json
        self.assertEqual(a["error"]["code"], b["error"]["code"])
        self.assertEqual(a["error"]["message"], b["error"]["message"])

    def test_lockout_after_failures(self):
        self.personal()
        for _ in range(8):
            self.sv.limiter.reset()
            self.post("/v1/auth/personal/login", {"email": "ani@example.com", "password": "salah-banget-1"}, expect=401)
        self.sv.limiter.reset()
        r = self.post("/v1/auth/personal/login", {"email": "ani@example.com", "password": PW}, expect=429)
        self.assertEqual(r.json["error"]["code"], "account_locked")
        self.assertIn("Retry-After", r.headers)

    def test_refresh_rotation_and_reuse_detection(self):
        t = self.personal()
        t2 = self.post("/v1/auth/refresh", {"refresh_token": t["refresh_token"]}, expect=200).json
        self.assertNotEqual(t["refresh_token"], t2["refresh_token"])
        self.get("/v1/auth/me", t2, expect=200)
        r = self.post("/v1/auth/refresh", {"refresh_token": t["refresh_token"]}, expect=401)
        self.assertEqual(r.json["error"]["code"], "refresh_reused")
        self.post("/v1/auth/refresh", {"refresh_token": t2["refresh_token"]}, expect=401)
        self.get("/v1/auth/me", t2, expect=401)

    def test_logout_revokes_access_token(self):
        t = self.personal()
        self.post("/v1/auth/logout", tok=t, expect=204)
        self.get("/v1/auth/me", t, expect=401)

    def test_expired_and_forged_tokens(self):
        t = self.personal()
        claims = jwt.decode(t["access_token"], options={"verify_signature": False})
        old = jwt.encode({**claims, "exp": int(time.time()) - 10}, "j" * 48, algorithm="HS256")
        r = self.get("/v1/auth/me", old, expect=401)
        self.assertEqual(r.json["error"]["code"], "token_expired")
        forged = jwt.encode(claims, "x" * 48, algorithm="HS256")
        self.get("/v1/auth/me", forged, expect=401)
        self.get("/v1/auth/me", expect=401)
        none_alg = jwt.encode(claims, None, algorithm="none")
        self.get("/v1/auth/me", none_alg, expect=401)

    def test_password_change_revokes_other_sessions(self):
        t1 = self.personal()
        t2 = self.post("/v1/auth/personal/login", {"email": "ani@example.com", "password": PW}, expect=200).json
        self.post("/v1/auth/password", {"current_password": PW, "new_password": "kata-sandi-baru-456"}, t1, expect=204)
        self.get("/v1/auth/me", t1, expect=200)
        self.get("/v1/auth/me", t2, expect=401)
        self.post("/v1/auth/personal/login", {"email": "ani@example.com", "password": PW}, expect=401)
        self.post("/v1/auth/password", {"current_password": "salah", "new_password": "kata-sandi-baru-789"}, t1, expect=401)

    def test_sessions_list_and_revoke(self):
        t1 = self.personal()
        t2 = self.post("/v1/auth/personal/login", {"email": "ani@example.com", "password": PW, "device_label": "Pixel"}, expect=200).json
        items = self.get("/v1/auth/sessions", t1, expect=200).json["sessions"]
        self.assertEqual(len(items), 2)
        other = [s for s in items if not s["current"]][0]["id"]
        self.delete(f"/v1/auth/sessions/{other}", t1, expect=204)
        self.get("/v1/auth/me", t2, expect=401)
        self.delete("/v1/auth/sessions/tidak-ada", t1, expect=404)

    def test_signup_can_be_disabled(self):
        self.sv.settings.__class__  # settings is frozen; build a closed server instead
        from tests.helpers import make_env
        from xnva.app import create_app
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            app = create_app(make_env(d, XNVA_ALLOW_PERSONAL_SIGNUP="0"))
            r = app.test_client().post("/v1/auth/personal/register", json={"email": "a@b.co", "password": PW})
            self.assertEqual(r.status_code, 403)
            app.extensions["xnva"].pdb.close(), app.extensions["xnva"].edb.close()

    def test_security_headers_and_json_errors(self):
        r = self.get("/healthz", expect=200)
        self.assertEqual(r.headers["X-Content-Type-Options"], "nosniff")
        self.assertEqual(r.headers["Cache-Control"], "no-store")
        r = self.get("/v1/tidak-ada", expect=404)
        self.assertEqual(r.json["error"]["code"], "not_found")
        r = self.call("POST", "/v1/auth/personal/login", None, expect=400)
        self.assertEqual(r.json["error"]["code"], "invalid_json")


class EnterpriseAuthTests(Base):
    def test_platform_requires_token(self):
        body = {"name": "ACME", "slug": "acme", "owner_email": "o@acme.test", "owner_name": "O"}
        self.post("/v1/platform/orgs", body, expect=401)
        self.post("/v1/platform/orgs", body, headers={"X-Platform-Token": "x" * 40}, expect=401)
        self.post("/v1/platform/orgs", {**body, "slug": "Bad Slug"}, headers={"X-Platform-Token": "p" * 40}, expect=400)

    def test_org_provisioning_invite_login_and_me(self):
        owner = self.org()
        me = self.get("/v1/auth/me", owner, expect=200).json
        self.assertEqual((me["realm"], me["role"], me["org"]["slug"]), ("enterprise", "owner", "acme"))
        self.assertIn("org.manage", me["permissions"])
        l = self.post("/v1/auth/enterprise/login", {"org": "acme", "email": "owner@acme.test", "password": PW}, expect=200).json
        self.assertEqual(l["org"]["slug"], "acme")
        self.post("/v1/auth/enterprise/login", {"org": "acme", "email": "owner@acme.test", "password": "salah-banget"}, expect=401)
        self.post("/v1/auth/enterprise/login", {"org": "lain", "email": "owner@acme.test", "password": PW}, expect=401)
        self.post("/v1/platform/orgs", {"name": "Dup", "slug": "acme", "owner_email": "x@y.co", "owner_name": "X"},
                  headers={"X-Platform-Token": "p" * 40}, expect=409)

    def test_invite_single_use_and_expiry(self):
        owner = self.org()
        r = self.post("/v1/enterprise/employees", {"email": "a@acme.test", "display_name": "A"}, owner, expect=201).json
        self.post("/v1/auth/enterprise/login", {"org": "acme", "email": "a@acme.test", "password": PW}, expect=401)
        self.post("/v1/auth/enterprise/invites/accept", {"token": r["invite_token"], "password": PW}, expect=200)
        self.post("/v1/auth/enterprise/invites/accept", {"token": r["invite_token"], "password": PW}, expect=400)
        r2 = self.post("/v1/enterprise/employees", {"email": "b@acme.test", "display_name": "B"}, owner, expect=201).json
        self.sv.edb.run("UPDATE invites SET expires_at=1")
        self.post("/v1/auth/enterprise/invites/accept", {"token": r2["invite_token"], "password": PW}, expect=400)
        r3 = self.post(f"/v1/enterprise/employees/{r2['id']}/reinvite", {}, owner, expect=200).json
        self.post("/v1/auth/enterprise/invites/accept", {"token": r3["invite_token"], "password": PW}, expect=200)

    def test_personal_and_enterprise_tokens_are_not_interchangeable(self):
        p, owner = self.personal(), self.org()
        self.get("/v1/enterprise/org", p, expect=403)
        self.get("/v1/personal/profile", owner, expect=403)
        self.assertEqual(self.get("/v1/enterprise/org", p).json["error"]["code"], "wrong_realm")

    def test_disabled_employee_loses_access_immediately(self):
        owner = self.org()
        emp = self.employee(owner, "e@acme.test")
        self.get("/v1/enterprise/tasks", emp, expect=200)
        self.put(f"/v1/enterprise/employees/{emp['id']}", {"status": "disabled"}, owner, expect=200)
        self.get("/v1/enterprise/tasks", emp, expect=401)
        self.post("/v1/auth/refresh", {"refresh_token": emp["refresh_token"]}, expect=401)
        self.post("/v1/auth/enterprise/login", {"org": "acme", "email": "e@acme.test", "password": PW}, expect=401)

    def test_role_change_applies_without_new_token(self):
        owner = self.org()
        emp = self.employee(owner, "e@acme.test", role="manager")
        self.post("/v1/enterprise/projects", {"name": "P"}, emp, expect=201)
        self.put(f"/v1/enterprise/employees/{emp['id']}", {"role": "member"}, owner, expect=200)
        self.post("/v1/enterprise/projects", {"name": "P2"}, emp, expect=401)  # sessions revoked on role change
        emp2 = self.post("/v1/auth/enterprise/login", {"org": "acme", "email": "e@acme.test", "password": PW}, expect=200).json
        self.post("/v1/enterprise/projects", {"name": "P2"}, emp2, expect=403)

    def test_org_signup_flag(self):
        body = {"org_name": "Baru", "org_slug": "baru", "owner_email": "o@baru.co", "owner_name": "O", "owner_password": PW}
        self.post("/v1/auth/enterprise/signup", body, expect=403)
        from tests.helpers import make_env
        from xnva.app import create_app
        import tempfile
        with tempfile.TemporaryDirectory() as d:
            app = create_app(make_env(d, XNVA_ALLOW_ORG_SIGNUP="1"))
            r = app.test_client().post("/v1/auth/enterprise/signup", json=body)
            self.assertEqual(r.status_code, 201, r.get_data(as_text=True))
            self.assertIn("access_token", r.json)
            app.extensions["xnva"].pdb.close(), app.extensions["xnva"].edb.close()
