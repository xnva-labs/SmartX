"""Generate docs/openapi.yaml from the endpoint table below.

tests/test_openapi.py fails if this table and the Flask routes disagree, or if the committed
YAML is stale. Regenerate with:  python -m tools.gen_openapi
"""
from __future__ import annotations

import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from xnva import __version__  # noqa: E402

# (method, path, auth, summary, body, query, success_status)
# auth: none | any | personal | platform | enterprise[:permission[+permission]]
E = [
    ("GET", "/healthz", "none", "Liveness probe", "", "", 200),
    ("GET", "/readyz", "none", "Readiness probe (memeriksa integritas kedua database)", "", "", 200),
    ("GET", "/v1/status", "none", "Status fitur layanan; jujur melaporkan configured/not_configured untuk XNAI", "", "", 200),
    ("POST", "/v1/auth/personal/register", "none", "Daftar akun Personal", "email:email password:str(256) display_name?:str(80) device_label?:str(80)", "", 201),
    ("POST", "/v1/auth/personal/login", "none", "Masuk akun Personal", "email:email password:str(256) device_label?:str(80)", "", 200),
    ("POST", "/v1/auth/enterprise/login", "none", "Masuk akun karyawan organisasi", "org:str(40) email:email password:str(256) device_label?:str(80)", "", 200),
    ("POST", "/v1/auth/enterprise/invites/accept", "none", "Terima undangan karyawan dan set password", "token:str(200) password:str(256) display_name?:str(80) device_label?:str(80)", "", 200),
    ("POST", "/v1/auth/enterprise/signup", "none", "Buat organisasi mandiri (nonaktif secara default: XNVA_ALLOW_ORG_SIGNUP)", "org_name:str(120) org_slug:str(40) owner_email:email owner_name:str(80) owner_password:str(256)", "", 201),
    ("POST", "/v1/auth/refresh", "none", "Tukar refresh token (rotasi; pemakaian ulang mencabut sesi)", "refresh_token:str(300)", "", 200),
    ("POST", "/v1/auth/logout", "any", "Cabut sesi saat ini", "", "", 204),
    ("GET", "/v1/auth/me", "any", "Identitas, realm, peran, dan izin saat ini", "", "", 200),
    ("GET", "/v1/auth/sessions", "any", "Daftar sesi aktif milik sendiri", "", "", 200),
    ("DELETE", "/v1/auth/sessions/{sid}", "any", "Cabut sesi milik sendiri", "", "", 204),
    ("POST", "/v1/auth/password", "any", "Ganti password (mencabut sesi lain)", "current_password:str(256) new_password:str(256)", "", 204),
    ("POST", "/v1/platform/orgs", "platform", "Provisioning organisasi (header X-Platform-Token)", "name:str(120) slug:str(40) owner_email:email owner_name:str(80) ai_monthly_quota?:int", "", 201),
    ("GET", "/v1/personal/profile", "personal", "Profil akun", "", "", 200),
    ("PUT", "/v1/personal/profile", "personal", "Ubah nama tampilan", "display_name:str(80)", "", 200),
    ("GET", "/v1/personal/privacy", "personal", "Pengaturan privasi sisi server", "", "", 200),
    ("PUT", "/v1/personal/privacy", "personal", "Ubah consent cloud processing, sinkronisasi memori, retensi", "cloud_processing?:bool memory_sync?:bool retention_days?:int", "", 200),
    ("GET", "/v1/personal/activity", "personal", "Transparansi aktivitas AI (metadata saja)", "", "limit:int", 200),
    ("GET", "/v1/personal/export", "personal", "Ekspor seluruh data akun (JSON)", "", "", 200),
    ("DELETE", "/v1/personal/account", "personal", "Hapus akun dan seluruh data (crypto-shredding)", "password:str(256)", "", 204),
    ("GET", "/v1/personal/memory", "personal", "Daftar memori (perlu memory_sync)", "", "type:str", 200),
    ("POST", "/v1/personal/memory", "personal", "Buat/ganti memori", "id?:str(64) type:enum(PROFILE|NOTE|PERSON|TASK|PREFERENCE) title:str(200) content:str(8000)", "", 201),
    ("DELETE", "/v1/personal/memory", "personal", "Hapus memori massal (opsional per tipe)", "", "type:str", 200),
    ("PUT", "/v1/personal/memory/{rid}", "personal", "Ubah memori", "type?:str title?:str(200) content?:str(8000)", "", 200),
    ("DELETE", "/v1/personal/memory/{rid}", "personal", "Hapus memori (tombstone untuk sinkronisasi)", "", "", 204),
    ("POST", "/v1/personal/memory/sync", "personal", "Sinkronisasi dua arah (last-write-wins)", "since?:int items:list[obj]", "", 200),
    ("GET", "/v1/personal/reminders", "personal", "Daftar reminder", "", "", 200),
    ("POST", "/v1/personal/reminders", "personal", "Buat reminder", "id?:str(64) title:str(200) notes?:str(4000) due_at:int repeat?:enum(none|daily|weekly|monthly) done_at?:int", "", 201),
    ("PUT", "/v1/personal/reminders/{rid}", "personal", "Ubah reminder", "title?:str(200) notes?:str(4000) due_at?:int repeat?:str done_at?:int", "", 200),
    ("DELETE", "/v1/personal/reminders/{rid}", "personal", "Hapus reminder", "", "", 204),
    ("POST", "/v1/personal/reminders/sync", "personal", "Sinkronisasi reminder", "since?:int items:list[obj]", "", 200),
    ("GET", "/v1/devices", "any", "Daftar perangkat kacamata (admin Enterprise: seluruh organisasi)", "", "", 200),
    ("POST", "/v1/devices", "any", "Daftarkan perangkat; secret 32 byte dikembalikan", "hw_id:str(12) label:str(80) fw_version?:str(32)", "", 201),
    ("PUT", "/v1/devices/{did}", "any", "Ganti nama perangkat", "label:str(80)", "", 200),
    ("DELETE", "/v1/devices/{did}", "any", "Cabut perangkat (secret dihapus)", "", "", 204),
    ("GET", "/v1/devices/{did}/secret", "any", "Ambil secret perangkat (hanya pemilik; diaudit)", "", "", 200),
    ("POST", "/v1/devices/{did}/rotate-secret", "any", "Rotasi secret perangkat", "", "", 200),
    ("POST", "/v1/devices/{did}/telemetry", "any", "Laporan baterai/firmware dari ponsel", "battery_pct?:int fw_version?:str(32)", "", 204),
    ("GET", "/v1/notifications", "any", "Kotak masuk notifikasi", "", "since:int limit:int", 200),
    ("POST", "/v1/notifications/{nid}/read", "any", "Tandai sudah dibaca", "", "", 204),
    ("POST", "/v1/notifications/read-all", "any", "Tandai semua sudah dibaca", "", "", 200),
    ("GET", "/v1/xnai/status", "any", "Status konfigurasi XNAI", "", "", 200),
    ("POST", "/v1/xnai/chat", "any", "Percakapan XNAI (Enterprise: dengan retrieval knowledge base + sitasi)", "message:str(8000) history?:list[obj] thinkMode?:str(20) context?:str(4000) useKnowledge?:bool", "", 200),
    ("POST", "/v1/xnai/vision", "any", "Analisis gambar: describe, read (OCR), inspect (Enterprise)", "imageBase64:str mode?:enum(describe|read|inspect) checklist?:list[str] question?:str(500)", "", 200),
    ("POST", "/v1/xnai/translate", "any", "Terjemahan", "text:str(8000) targetLanguage:str(40)", "", 200),
    ("POST", "/v1/xnai/transcribe", "any", "Speech-to-text (butuh XNAI_STT_*)", "audioBase64:str mime?:enum(audio/wav|audio/x-wav) language?:str(8)", "", 200),
    ("POST", "/v1/xnai/reply-suggestions", "any", "Saran balasan pesan (disetujui pengguna sebelum dikirim)", "app:str(60) sender?:str(80) message:str(2000) tone?:enum(ramah|formal|santai|singkat)", "", 200),
    ("GET", "/v1/enterprise/org", "enterprise", "Info organisasi dan kebijakan", "", "", 200),
    ("PUT", "/v1/enterprise/policy", "enterprise:policy.manage", "Ubah kebijakan organisasi", "ai_enabled?:bool vision_enabled?:bool allow_employee_devices?:bool audit_retention_days?:int notification_retention_days?:int meeting_retention_days?:int", "", 200),
    ("DELETE", "/v1/enterprise/org", "enterprise:org.manage", "Hapus organisasi dan seluruh datanya", "confirm_slug:str(40) password:str(256)", "", 204),
    ("GET", "/v1/enterprise/divisions", "enterprise:employees.read", "Daftar divisi", "", "", 200),
    ("POST", "/v1/enterprise/divisions", "enterprise:divisions.manage", "Buat divisi", "name:str(80)", "", 201),
    ("PUT", "/v1/enterprise/divisions/{did}", "enterprise:divisions.manage", "Ganti nama divisi", "name:str(80)", "", 200),
    ("DELETE", "/v1/enterprise/divisions/{did}", "enterprise:divisions.manage", "Hapus divisi yang tidak terpakai", "", "", 204),
    ("GET", "/v1/enterprise/employees", "enterprise:employees.read", "Direktori karyawan", "", "", 200),
    ("POST", "/v1/enterprise/employees", "enterprise:employees.manage", "Undang karyawan (token undangan dikembalikan sekali)", "email:email display_name:str(80) role?:enum(owner|admin|manager|member|auditor) division_id?:str(64) title?:str(80)", "", 201),
    ("PUT", "/v1/enterprise/employees/{eid}", "enterprise:employees.manage", "Ubah data/peran/status karyawan", "display_name?:str(80) title?:str(80) role?:enum(owner|admin|manager|member|auditor) division_id?:str(64) status?:enum(active|disabled)", "", 200),
    ("POST", "/v1/enterprise/employees/{eid}/reinvite", "enterprise:employees.manage", "Terbitkan ulang undangan", "", "", 200),
    ("GET", "/v1/enterprise/security/sessions", "enterprise:security.manage", "Sesi aktif seluruh organisasi", "", "", 200),
    ("DELETE", "/v1/enterprise/security/sessions/{sid}", "enterprise:security.manage", "Cabut sesi", "", "", 204),
    ("POST", "/v1/enterprise/security/employees/{eid}/revoke-sessions", "enterprise:security.manage", "Cabut semua sesi seorang karyawan", "", "", 200),
    ("GET", "/v1/enterprise/projects", "enterprise:projects.read", "Daftar proyek", "", "status:str", 200),
    ("POST", "/v1/enterprise/projects", "enterprise:projects.manage", "Buat proyek", "name:str(200) description?:str(4000) status?:enum(active|paused|done|archived) owner_id?:str(64) division_id?:str(64) due_date?:date", "", 201),
    ("PUT", "/v1/enterprise/projects/{pid}", "enterprise:projects.manage", "Ubah proyek", "name?:str(200) description?:str(4000) status?:enum(active|paused|done|archived) owner_id?:str(64) division_id?:str(64) due_date?:date", "", 200),
    ("DELETE", "/v1/enterprise/projects/{pid}", "enterprise:projects.manage", "Hapus proyek", "", "", 204),
    ("GET", "/v1/enterprise/tasks", "enterprise:tasks.read", "Daftar tugas (terfilter sesuai peran/divisi)", "", "status:str project:str assignee:str priority:str mine:int overdue:int limit:int", 200),
    ("POST", "/v1/enterprise/tasks", "enterprise:tasks.create", "Buat tugas (menugaskan orang lain butuh tasks.assign)", "title:str(200) description?:str(8000) project_id?:str(64) assignee_id?:str(64) priority?:enum(low|normal|high|urgent) due_date?:date", "", 201),
    ("GET", "/v1/enterprise/tasks/{tid}", "enterprise:tasks.read", "Detail tugas", "", "", 200),
    ("PUT", "/v1/enterprise/tasks/{tid}", "enterprise:tasks.read", "Ubah tugas (pembuat atau manajer)", "title?:str(200) description?:str(8000) project_id?:str(64) assignee_id?:str(64) priority?:enum(low|normal|high|urgent) due_date?:date", "", 200),
    ("POST", "/v1/enterprise/tasks/{tid}/status", "enterprise:tasks.read", "Ubah status tugas", "status:enum(todo|doing|blocked|done)", "", 200),
    ("DELETE", "/v1/enterprise/tasks/{tid}", "enterprise:tasks.read", "Hapus tugas (pembuat atau manajer)", "", "", 204),
    ("GET", "/v1/enterprise/tasks/{tid}/comments", "enterprise:tasks.read", "Komentar tugas", "", "", 200),
    ("POST", "/v1/enterprise/tasks/{tid}/comments", "enterprise:tasks.read", "Tambah komentar", "body:str(4000)", "", 201),
    ("GET", "/v1/enterprise/announcements", "enterprise:announcements.read", "Pengumuman internal", "", "", 200),
    ("POST", "/v1/enterprise/announcements", "enterprise:announcements.manage", "Buat pengumuman (notifikasi ke target)", "title:str(200) body:str(8000) division_id?:str(64) pinned?:bool expires_in_days?:int", "", 201),
    ("DELETE", "/v1/enterprise/announcements/{aid}", "enterprise:announcements.manage", "Hapus pengumuman", "", "", 204),
    ("GET", "/v1/enterprise/kb/documents", "enterprise:kb.read", "Dokumen knowledge base yang boleh diakses", "", "", 200),
    ("POST", "/v1/enterprise/kb/documents", "enterprise:kb.manage", "Tambah dokumen teks/markdown", "title:str(200) text:str(400000) visibility?:enum(org|division|managers|admins) division_id?:str(64) source?:str(200)", "", 201),
    ("POST", "/v1/enterprise/kb/documents/from-image", "enterprise:kb.manage+ai.use", "Tambah dokumen dari foto (OCR via XNAI vision)", "title:str(200) imageBase64:str visibility?:enum(org|division|managers|admins) division_id?:str(64)", "", 201),
    ("DELETE", "/v1/enterprise/kb/documents/{doc_id}", "enterprise:kb.manage", "Hapus dokumen", "", "", 204),
    ("GET", "/v1/enterprise/kb/search", "enterprise:kb.read", "Cari di knowledge base (BM25 atas indeks buta)", "", "q:str k:int", 200),
    ("POST", "/v1/enterprise/kb/ask", "enterprise:ai.use", "Tanya knowledge base; jawaban dengan sitasi", "question:str(2000)", "", 200),
    ("GET", "/v1/enterprise/meetings", "enterprise:meetings.manage", "Daftar rapat yang terlihat", "", "", 200),
    ("POST", "/v1/enterprise/meetings", "enterprise:meetings.manage", "Simpan catatan rapat (+ ringkasan XNAI bila tersedia)", "title:str(200) notes:str(60000) held_at?:int summarize?:bool", "", 201),
    ("GET", "/v1/enterprise/meetings/{mid}", "enterprise:meetings.manage", "Detail rapat", "", "", 200),
    ("DELETE", "/v1/enterprise/meetings/{mid}", "enterprise:meetings.manage", "Hapus rapat", "", "", 204),
    ("POST", "/v1/enterprise/meetings/{mid}/summarize", "enterprise:meetings.manage", "Ringkas ulang", "", "", 200),
    ("POST", "/v1/enterprise/meetings/{mid}/create-tasks", "enterprise:tasks.assign", "Ubah action item menjadi tugas", "indices?:list[int]", "", 201),
    ("GET", "/v1/enterprise/analytics/overview", "enterprise:analytics.read", "Analitik agregat (manajer: hanya divisinya)", "", "days:int", 200),
    ("GET", "/v1/enterprise/analytics/me", "enterprise:tasks.read", "Ringkasan tugas milik sendiri", "", "days:int", 200),
    ("GET", "/v1/enterprise/transparency", "enterprise", "Pernyataan data apa yang dikumpulkan dan tidak dikumpulkan", "", "", 200),
    ("GET", "/v1/enterprise/reports", "enterprise:reports.create", "Daftar laporan", "", "", 200),
    ("POST", "/v1/enterprise/reports", "enterprise:reports.create", "Buat laporan operasional (+ narasi XNAI opsional)", "kind:enum(weekly|project|custom) project_id?:str(64) period_days?:int narrative?:bool", "", 201),
    ("GET", "/v1/enterprise/reports/{rid}", "enterprise:reports.create", "Detail laporan", "", "", 200),
    ("GET", "/v1/enterprise/audit", "enterprise:audit.read", "Catatan audit (terbaru dulu)", "", "limit:int before:int action:str", 200),
    ("GET", "/v1/enterprise/audit/verify", "enterprise:audit.read", "Verifikasi rantai hash audit", "", "", 200),
    ("GET", "/v1/enterprise/audit/export", "enterprise:audit.read", "Ekspor audit sebagai NDJSON", "", "", 200),
]

ERR = {"400": "validation_error / invalid_json", "401": "unauthorized / token_expired / invalid_credentials", "403": "forbidden / wrong_realm / disabled_by_policy",
       "404": "not_found", "409": "conflict", "429": "rate_limited / quota_exceeded / account_locked", "503": "xnai_not_configured / upstream tidak tersedia"}


def _schema(tok: str) -> dict:
    t = tok.strip()
    m = re.fullmatch(r"(\w+)(?:\((.+)\))?", t) or re.fullmatch(r"list\[(\w+)\]", t)
    if t.startswith("list["):
        inner = t[5:-1]
        return {"type": "array", "items": {"type": "object"} if inner == "obj" else _schema(inner)}
    base, arg = m.group(1), m.group(2)
    if base == "str":
        return {"type": "string", **({"maxLength": int(arg)} if arg else {})}
    if base == "int":
        return {"type": "integer"}
    if base == "bool":
        return {"type": "boolean"}
    if base == "email":
        return {"type": "string", "format": "email"}
    if base == "date":
        return {"type": "string", "format": "date"}
    if base == "enum":
        return {"type": "string", "enum": arg.split("|")}
    return {"type": "object"}


def _body(spec: str) -> dict:
    props, req = {}, []
    for field in spec.split():
        name, typ = field.split(":", 1)
        if name.endswith("?"):
            name = name[:-1]
        else:
            req.append(name)
        props[name] = _schema(typ)
    out = {"type": "object", "properties": props}
    if req:
        out["required"] = req
    return out


def build() -> dict:
    paths: dict = {}
    for method, path, auth, summary, body, query, ok in E:
        op: dict = {"summary": summary, "tags": [path.split("/")[2] if path.startswith("/v1/") else "ops"], "operationId": None}
        if path.startswith("/v1/enterprise/"):
            op["tags"] = ["enterprise"]
        params = [{"name": n, "in": "path", "required": True, "schema": {"type": "string"}} for n in re.findall(r"\{(\w+)\}", path)]
        params += [{"name": q.split(":")[0], "in": "query", "required": False, "schema": _schema(q.split(":")[1])} for q in query.split()]
        if params:
            op["parameters"] = params
        if auth in ("any", "personal") or auth.startswith("enterprise"):
            op["security"] = [{"bearerAuth": []}]
            realm = "personal" if auth == "personal" else "enterprise" if auth.startswith("enterprise") else "personal atau enterprise"
            desc = f"Realm token: {realm}."
            if ":" in auth:
                desc += " Izin RBAC: " + ", ".join(auth.split(":")[1].split("+")) + "."
                op["x-required-permissions"] = auth.split(":")[1].split("+")
            op["description"] = desc
        elif auth == "platform":
            op["security"] = [{"platformToken": []}]
        else:
            op["security"] = []
        if body:
            op["requestBody"] = {"required": True, "content": {"application/json": {"schema": _body(body)}}}
        resp = {str(ok): {"description": "Berhasil" if ok != 204 else "Berhasil tanpa isi"}}
        if path.endswith("/audit/export"):
            resp["200"]["content"] = {"application/x-ndjson": {"schema": {"type": "string"}}}
        elif ok != 204:
            resp[str(ok)]["content"] = {"application/json": {"schema": {"type": "object"}}}
        for code in ("400", "401", "403", "404", "409", "429", "503"):
            if (code == "400" and not (body or query)) or (code in ("401", "403") and auth == "none" and "login" not in path and "refresh" not in path and "accept" not in path and "signup" not in path):
                continue
            if code in ("401", "403") and auth == "none" and path.startswith("/h"):
                continue
            resp[code] = {"$ref": f"#/components/responses/E{code}"}
        op["responses"] = resp
        op.pop("operationId")
        paths.setdefault(path, {})[method.lower()] = op
    return {
        "openapi": "3.0.3",
        "info": {"title": "SmartEyeX Platform API", "version": __version__,
                 "description": ("API platform SmartEyeX (XNVA Labs): dua lingkungan terpisah (Personal dan Enterprise) di atas satu fondasi XNAI.\n"
                                 "Semua waktu dalam epoch milidetik. Semua error berbentuk {\"error\": {\"code\", \"message\", \"request_id\"}}.\n"
                                 "Token Personal tidak berlaku di endpoint Enterprise dan sebaliknya (HTTP 403 wrong_realm).")},
        "servers": [{"url": "/", "description": "Origin tempat backend di-deploy (HTTPS wajib di production)"}],
        "tags": [{"name": t} for t in ("ops", "auth", "platform", "personal", "devices", "notifications", "xnai", "enterprise")],
        "components": {
            "securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"},
                                "platformToken": {"type": "apiKey", "in": "header", "name": "X-Platform-Token"}},
            "schemas": {"Error": {"type": "object", "properties": {"error": {"type": "object", "required": ["code", "message"], "properties": {
                "code": {"type": "string"}, "message": {"type": "string"}, "request_id": {"type": "string"}}}}}},
            "responses": {f"E{c}": {"description": d, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}} for c, d in ERR.items()},
        },
        "paths": paths,
    }


def render() -> str:
    return "# Dihasilkan oleh backend/tools/gen_openapi.py — jangan diedit manual.\n" + yaml.safe_dump(build(), sort_keys=False, allow_unicode=True, width=120)


if __name__ == "__main__":
    out = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "docs", "openapi.yaml")
    with open(out, "w", encoding="utf-8") as f:
        f.write(render())
    print("ditulis:", out)
