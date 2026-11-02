import base64
import json
import os
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from xnva.app import create_app

PLATFORM_TOKEN = "p" * 40
PW = "kata-sandi-kuat-123"


def make_env(data_dir, **extra):
    env = {"XNVA_ENV": "test", "XNVA_DATA_DIR": data_dir, "XNVA_MASTER_KEY": base64.b64encode(os.urandom(32)).decode(),
           "XNVA_JWT_SECRET": "j" * 48, "XNVA_SCRYPT_N": "1024", "XNVA_SCRYPT_P": "1", "XNVA_PLATFORM_TOKEN": PLATFORM_TOKEN}
    env.update(extra)
    return env


class Base(unittest.TestCase):
    env_extra = {}

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = make_env(self.tmp.name, **self.env_extra)
        self.app = create_app(self.env)
        self.c = self.app.test_client()
        self.sv = self.app.extensions["xnva"]

    def tearDown(self):
        self.sv.pdb.close()
        self.sv.edb.close()
        self.tmp.cleanup()

    # ---- http helpers
    def call(self, method, path, body=None, tok=None, headers=None, expect=None, **kw):
        h = dict(headers or {})
        if tok:
            h["Authorization"] = "Bearer " + (tok["access_token"] if isinstance(tok, dict) else tok)
        r = self.c.open(path, method=method, json=body, headers=h, **kw)
        if expect is not None:
            self.assertEqual(r.status_code, expect, f"{method} {path} -> {r.status_code} {r.get_data(as_text=True)[:300]}")
        return r

    def get(self, path, tok=None, **kw):
        return self.call("GET", path, tok=tok, **kw)

    def post(self, path, body=None, tok=None, **kw):
        return self.call("POST", path, body if body is not None else {}, tok, **kw)

    def put(self, path, body, tok=None, **kw):
        return self.call("PUT", path, body, tok, **kw)

    def delete(self, path, tok=None, body=None, **kw):
        return self.call("DELETE", path, body, tok, **kw)

    # ---- fixtures
    def personal(self, email="ani@example.com", pw=PW):
        r = self.post("/v1/auth/personal/register", {"email": email, "password": pw, "display_name": "Ani"}, expect=201)
        return r.json

    def enable_cloud(self, tok, memory_sync=True):
        self.put("/v1/personal/privacy", {"cloud_processing": True, "memory_sync": memory_sync}, tok, expect=200)

    def org(self, slug="acme", owner="owner@acme.test", name="ACME Corp"):
        r = self.post("/v1/platform/orgs", {"name": name, "slug": slug, "owner_email": owner, "owner_name": "Pemilik"},
                      headers={"X-Platform-Token": PLATFORM_TOKEN}, expect=201)
        r2 = self.post("/v1/auth/enterprise/invites/accept", {"token": r.json["invite_token"], "password": PW, "display_name": "Pemilik"}, expect=200)
        return r2.json

    def employee(self, owner_tok, email, role="member", division_id=None, name="Karyawan"):
        body = {"email": email, "display_name": name, "role": role}
        if division_id:
            body["division_id"] = division_id
        r = self.post("/v1/enterprise/employees", body, owner_tok, expect=201)
        r2 = self.post("/v1/auth/enterprise/invites/accept", {"token": r.json["invite_token"], "password": PW}, expect=200)
        out = r2.json
        out["id"] = r.json["id"]
        return out

    def division(self, tok, name):
        return self.post("/v1/enterprise/divisions", {"name": name}, tok, expect=201).json["id"]

    def all_db_bytes(self):
        blob = b""
        for fn in os.listdir(self.tmp.name):
            with open(os.path.join(self.tmp.name, fn), "rb") as f:
                blob += f.read()
        return blob


class Upstream:
    """Local HTTP server standing in for the model provider, to verify what the backend sends."""

    def __init__(self):
        outer = self
        self.requests = []
        self.override = {}

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_POST(self):
                n = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(n)
                outer.requests.append({"path": self.path, "headers": dict(self.headers), "raw": raw})
                code, body = outer.override.get(self.path, (200, None))
                if body is None:
                    body = outer.default(self.path, raw)
                data = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.server = HTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.reply = "Jawaban uji dari penyedia"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def default(self, path, raw):
        if path.endswith("/chat/completions"):
            return {"model": "stub-model", "choices": [{"message": {"content": self.reply}}], "usage": {"prompt_tokens": 11, "completion_tokens": 7}}
        if path.endswith("/v1/messages"):
            return {"model": "stub-claude", "content": [{"type": "text", "text": self.reply}], "usage": {"input_tokens": 5, "output_tokens": 3}}
        if path.endswith("/audio/transcriptions"):
            return {"text": "halo dunia"}
        return {}

    def last_json(self):
        return json.loads(self.requests[-1]["raw"])

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class WithUpstream(Base):
    provider = "openai"

    def setUp(self):
        self.up = Upstream()
        self.env_extra = {"XNAI_PROVIDER": self.provider, "XNAI_BASE_URL": self.up.url + ("" if self.provider == "anthropic" else "/v1"),
                          "XNAI_API_KEY": "kunci-uji-123", "XNAI_MODEL": "stub-model", "XNAI_STT_BASE_URL": self.up.url + "/v1", "XNAI_STT_MODEL": "stub-stt"}
        super().setUp()

    def tearDown(self):
        super().tearDown()
        self.up.close()
