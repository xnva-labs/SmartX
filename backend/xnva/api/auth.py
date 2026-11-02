from __future__ import annotations

import hmac
import uuid
from typing import Any, Dict, Optional

from flask import Blueprint, g, jsonify, request

from .. import clock
from ..crypto import const_eq, new_token, token_hash
from ..errors import ApiError, conflict, forbidden, unauthorized
from ..orgs import create_org
from ..security import PERMISSIONS, ROLE_PERMS, auth, client_ip, issue_access, limit
from ..validate import F, check_password, validate
from .common import json_body, sv

bp = Blueprint("auth", __name__, url_prefix="/v1/auth")
_SLUG = r"[a-z0-9][a-z0-9\-]{1,38}[a-z0-9]"
_TIMING_REF: Dict[str, str] = {}


def _equalize_timing(password: str) -> None:
    """Spend the same time as a real check so unknown accounts are indistinguishable."""
    if "h" not in _TIMING_REF:
        _TIMING_REF["h"] = sv().hasher.hash("xnva-timing-reference")
    sv().hasher.verify(password, _TIMING_REF["h"])


def _issue(realm: str, uid: str, org_id: Optional[str], label: str) -> Dict[str, Any]:
    s, now = sv(), clock.now_ms()
    sid, secret = uuid.uuid4().hex, new_token(32)
    exp = now + s.settings.refresh_ttl_days * clock.DAY_MS
    db = s.pdb if realm == "personal" else s.edb
    if realm == "personal":
        db.run("INSERT INTO sessions(id,user_id,refresh_hash,label,created_at,last_used_at,expires_at) VALUES(?,?,?,?,?,?,?)",
               (sid, uid, token_hash(secret), label, now, now, exp))
    else:
        db.run("INSERT INTO sessions(id,org_id,employee_id,refresh_hash,label,created_at,last_used_at,expires_at) VALUES(?,?,?,?,?,?,?,?)",
               (sid, org_id, uid, token_hash(secret), label, now, now, exp))
    return {"access_token": issue_access(s.settings, realm, uid, sid, org_id),
            "refresh_token": f"{'p' if realm == 'personal' else 'e'}.{sid}.{secret}",
            "token_type": "Bearer", "expires_in": s.settings.access_ttl_s}


def _check_lock(row) -> None:
    now = clock.now_ms()
    if row["locked_until"] > now:
        raise ApiError(429, "account_locked", "Akun dikunci sementara karena terlalu banyak percobaan gagal",
                       retry_after=max(1, (row["locked_until"] - now) // 1000))


def _fail(db, table: str, row, audit, scope: str, role: Optional[str]) -> None:
    s = sv()
    fails = row["failed_logins"] + 1
    lock = clock.now_ms() + s.settings.login_lock_minutes * 60_000 if fails >= s.settings.login_max_failures else 0
    db.run(f"UPDATE {table} SET failed_logins=?, locked_until=? WHERE id=?", (0 if lock else fails, lock, row["id"]))
    audit.append(scope, "auth.login_failed", row["id"], role, ip=client_ip(), meta={"locked": bool(lock)})


def _ok(db, table: str, row, pw: str) -> None:
    s = sv()
    new_hash = s.hasher.hash(pw) if s.hasher.needs_rehash(row["pw_hash"]) else row["pw_hash"]
    db.run(f"UPDATE {table} SET failed_logins=0, locked_until=0, last_login_at=?, pw_hash=? WHERE id=?",
           (clock.now_ms(), new_hash, row["id"]))


# ---------------------------------------------------------------- personal

@bp.post("/personal/register")
@limit("register", 10, 3600)
def personal_register():
    s = sv()
    if not s.settings.allow_personal_signup:
        raise forbidden("Pendaftaran akun personal dinonaktifkan di server ini", "signup_disabled")
    d = validate(json_body(), {"email": F.email(), "password": F.str(max=256), "display_name": F.str(max=80, req=False, default=""),
                               "device_label": F.str(max=80, req=False, default="")})
    check_password(d["password"], d["email"])
    uid, now = uuid.uuid4().hex, clock.now_ms()
    with s.pdb.tx():
        if s.pdb.one("SELECT 1 FROM users WHERE email=?", (d["email"],)):
            raise conflict("email_taken", "Email sudah terdaftar")
        s.pdb.run("INSERT INTO users(id,email,pw_hash,display_name,created_at) VALUES(?,?,?,?,?)",
                  (uid, d["email"], s.hasher.hash(d["password"]), d["display_name"], now))
        s.pkeys.create(uid, now)
        s.pdb.run("INSERT INTO privacy(user_id,updated_at) VALUES(?,?)", (uid, now))
        s.paudit.append(uid, "account.registered", uid, "user", ip=client_ip())
        tokens = _issue("personal", uid, None, d["device_label"])
    return jsonify({"user": {"id": uid, "email": d["email"], "display_name": d["display_name"]}, **tokens}), 201


@bp.post("/personal/login")
@limit("login", 20, 60)
def personal_login():
    s = sv()
    d = validate(json_body(), {"email": F.email(), "password": F.str(max=256), "device_label": F.str(max=80, req=False, default="")})
    row = s.pdb.one("SELECT * FROM users WHERE email=?", (d["email"],))
    if row is None or row["status"] != "active":
        _equalize_timing(d["password"])
        raise unauthorized("Email atau password salah", "invalid_credentials")
    _check_lock(row)
    if not s.hasher.verify(d["password"], row["pw_hash"]):
        _fail(s.pdb, "users", row, s.paudit, row["id"], "user")
        raise unauthorized("Email atau password salah", "invalid_credentials")
    with s.pdb.tx():
        _ok(s.pdb, "users", row, d["password"])
        tokens = _issue("personal", row["id"], None, d["device_label"])
        s.paudit.append(row["id"], "auth.login", row["id"], "user", ip=client_ip())
    return jsonify({"user": {"id": row["id"], "email": row["email"], "display_name": row["display_name"]}, **tokens})


# -------------------------------------------------------------- enterprise

def _org_out(org_id: str, slug: str, name: str) -> Dict[str, str]:
    return {"id": org_id, "slug": slug, "name": name}


@bp.post("/enterprise/login")
@limit("login", 20, 60)
def enterprise_login():
    s = sv()
    d = validate(json_body(), {"org": F.str(max=40, min=2, pattern=_SLUG, req=True), "email": F.email(),
                               "password": F.str(max=256), "device_label": F.str(max=80, req=False, default="")})
    org = s.edb.one("SELECT * FROM orgs WHERE slug=?", (d["org"].lower(),))
    emp = s.edb.one("SELECT * FROM employees WHERE org_id=? AND email=?", (org["id"], d["email"])) if org else None
    if org is None or org["status"] != "active" or emp is None or emp["status"] != "active" or not emp["pw_hash"]:
        _equalize_timing(d["password"])
        raise unauthorized("Organisasi, email, atau password salah", "invalid_credentials")
    _check_lock(emp)
    if not s.hasher.verify(d["password"], emp["pw_hash"]):
        _fail(s.edb, "employees", emp, s.eaudit, org["id"], emp["role"])
        raise unauthorized("Organisasi, email, atau password salah", "invalid_credentials")
    with s.edb.tx():
        _ok(s.edb, "employees", emp, d["password"])
        tokens = _issue("enterprise", emp["id"], org["id"], d["device_label"])
        s.eaudit.append(org["id"], "auth.login", emp["id"], emp["role"], ip=client_ip())
    return jsonify({"org": _org_out(org["id"], org["slug"], org["name"]), "employee": {"id": emp["id"], "email": emp["email"],
                    "display_name": emp["display_name"], "role": emp["role"]}, **tokens})


@bp.post("/enterprise/invites/accept")
@limit("invite", 10, 600)
def enterprise_accept_invite():
    s = sv()
    d = validate(json_body(), {"token": F.str(max=200, min=20), "password": F.str(max=256), "display_name": F.str(max=80, req=False),
                               "device_label": F.str(max=80, req=False, default="")})
    now = clock.now_ms()
    inv = s.edb.one("SELECT * FROM invites WHERE token_hash=?", (token_hash(d["token"]),))
    if inv is None or inv["used_at"] is not None or inv["expires_at"] < now:
        raise ApiError(400, "invite_invalid", "Undangan tidak valid atau sudah kedaluwarsa")
    emp = s.edb.one("SELECT * FROM employees WHERE id=? AND org_id=?", (inv["employee_id"], inv["org_id"]))
    org = s.edb.one("SELECT * FROM orgs WHERE id=?", (inv["org_id"],))
    if emp is None or emp["status"] == "disabled" or org is None or org["status"] != "active":
        raise ApiError(400, "invite_invalid", "Undangan tidak valid atau sudah kedaluwarsa")
    check_password(d["password"], emp["email"])
    with s.edb.tx():
        s.edb.run("UPDATE employees SET pw_hash=?, status='active', display_name=? WHERE id=?",
                  (s.hasher.hash(d["password"]), d.get("display_name") or emp["display_name"], emp["id"]))
        s.edb.run("UPDATE invites SET used_at=? WHERE id=?", (now, inv["id"]))
        tokens = _issue("enterprise", emp["id"], org["id"], d["device_label"])
        s.eaudit.append(org["id"], "invite.accepted", emp["id"], emp["role"], "employee", emp["id"], ip=client_ip())
    return jsonify({"org": _org_out(org["id"], org["slug"], org["name"]), "employee": {"id": emp["id"], "email": emp["email"],
                    "display_name": d.get("display_name") or emp["display_name"], "role": emp["role"]}, **tokens})


@bp.post("/enterprise/signup")
@limit("org-signup", 5, 3600)
def enterprise_signup():
    s = sv()
    if not s.settings.allow_org_signup:
        raise forbidden("Pembuatan organisasi mandiri dinonaktifkan; hubungi operator layanan", "signup_disabled")
    d = validate(json_body(), {"org_name": F.str(max=120, min=2), "org_slug": F.str(max=40, min=3, pattern=_SLUG),
                               "owner_email": F.email(), "owner_name": F.str(max=80, min=1), "owner_password": F.str(max=256)})
    check_password(d["owner_password"], d["owner_email"])
    out = create_org(s, d["org_name"], d["org_slug"], d["owner_email"], d["owner_name"], owner_password=d["owner_password"])
    with s.edb.tx():
        tokens = _issue("enterprise", out["owner_id"], out["org"]["id"], "")
    return jsonify({"org": out["org"], "employee_id": out["owner_id"], **tokens}), 201


# ------------------------------------------------------------ both realms

@bp.post("/refresh")
@limit("refresh", 60, 60)
def refresh():
    s = sv()
    d = validate(json_body(), {"refresh_token": F.str(max=300, min=10)})
    try:
        prefix, sid, secret = d["refresh_token"].split(".", 2)
    except ValueError:
        raise unauthorized("Refresh token tidak valid", "invalid_refresh")
    if prefix not in ("p", "e"):
        raise unauthorized("Refresh token tidak valid", "invalid_refresh")
    realm = "personal" if prefix == "p" else "enterprise"
    db, now = (s.pdb if realm == "personal" else s.edb), clock.now_ms()
    failure = None  # (code, message): raised after the transaction commits so revocations persist
    with db.tx():
        row = db.one("SELECT * FROM sessions WHERE id=?", (sid,))
        if row is None or row["revoked_at"] is not None or row["expires_at"] < now:
            raise unauthorized("Sesi berakhir, silakan masuk lagi", "invalid_refresh")
        presented = token_hash(secret)
        scope = row["user_id"] if realm == "personal" else row["org_id"]
        audit = s.paudit if realm == "personal" else s.eaudit
        actor = row["user_id"] if realm == "personal" else row["employee_id"]
        if row["prev_refresh_hash"] and const_eq(presented, row["prev_refresh_hash"]):
            db.run("UPDATE sessions SET revoked_at=? WHERE id=?", (now, sid))
            audit.append(scope, "auth.refresh_reuse_detected", actor, None, "session", sid, ip=client_ip())
            failure = ("refresh_reused", "Token dipakai ulang; sesi dicabut demi keamanan")
        elif not const_eq(presented, row["refresh_hash"]):
            raise unauthorized("Refresh token tidak valid", "invalid_refresh")
        else:
            if realm == "personal":
                u = db.one("SELECT status FROM users WHERE id=?", (row["user_id"],))
                org_id, uid, ok = None, row["user_id"], bool(u and u["status"] == "active")
            else:
                e = db.one("SELECT e.status es, o.status os FROM employees e JOIN orgs o ON o.id=e.org_id WHERE e.id=?", (row["employee_id"],))
                org_id, uid, ok = row["org_id"], row["employee_id"], bool(e and e["es"] == "active" and e["os"] == "active")
            if not ok:
                db.run("UPDATE sessions SET revoked_at=? WHERE id=?", (now, sid))
                failure = ("invalid_refresh", "Akun tidak aktif")
            else:
                new_secret = new_token(32)
                db.run("UPDATE sessions SET refresh_hash=?, prev_refresh_hash=?, last_used_at=?, expires_at=? WHERE id=?",
                       (token_hash(new_secret), row["refresh_hash"], now, now + s.settings.refresh_ttl_days * clock.DAY_MS, sid))
    if failure:
        raise unauthorized(failure[1], failure[0])
    return jsonify({"access_token": issue_access(s.settings, realm, uid, sid, org_id), "refresh_token": f"{prefix}.{sid}.{new_secret}",
                    "token_type": "Bearer", "expires_in": s.settings.access_ttl_s})


def _revoke(ident, sid: str) -> int:
    s = sv()
    if ident.realm == "personal":
        return s.pdb.run("UPDATE sessions SET revoked_at=? WHERE id=? AND user_id=? AND revoked_at IS NULL", (clock.now_ms(), sid, ident.uid))
    return s.edb.run("UPDATE sessions SET revoked_at=? WHERE id=? AND employee_id=? AND org_id=? AND revoked_at IS NULL",
                     (clock.now_ms(), sid, ident.uid, ident.org_id))


def _audit(ident):
    return sv().paudit if ident.realm == "personal" else sv().eaudit


@bp.post("/logout")
@auth()
def logout():
    _revoke(g.ident, g.ident.sid)
    _audit(g.ident).append(g.ident.scope_id, "auth.logout", g.ident.uid, g.ident.role, ip=client_ip())
    return "", 204


@bp.get("/me")
@auth()
def me():
    i = g.ident
    out: Dict[str, Any] = {"realm": i.realm, "id": i.uid, "email": i.email, "display_name": i.name}
    if i.realm == "enterprise":
        out.update({"org": _org_out(i.org_id, i.org_slug, i.org_name), "role": i.role, "division_id": i.division_id,
                    "permissions": sorted(ROLE_PERMS[i.role])})
    return jsonify(out)


@bp.get("/sessions")
@auth()
def sessions():
    i, s = g.ident, sv()
    now = clock.now_ms()
    if i.realm == "personal":
        rows = s.pdb.all("SELECT id,label,created_at,last_used_at,expires_at FROM sessions WHERE user_id=? AND revoked_at IS NULL AND expires_at>? ORDER BY last_used_at DESC", (i.uid, now))
    else:
        rows = s.edb.all("SELECT id,label,created_at,last_used_at,expires_at FROM sessions WHERE employee_id=? AND org_id=? AND revoked_at IS NULL AND expires_at>? ORDER BY last_used_at DESC", (i.uid, i.org_id, now))
    return jsonify({"sessions": [{**dict(r), "current": r["id"] == i.sid} for r in rows]})


@bp.delete("/sessions/<sid>")
@auth()
def revoke_session(sid: str):
    if not _revoke(g.ident, sid):
        raise ApiError(404, "not_found", "Sesi tidak ditemukan")
    _audit(g.ident).append(g.ident.scope_id, "auth.session_revoked", g.ident.uid, g.ident.role, "session", sid, ip=client_ip())
    return "", 204


@bp.post("/password")
@auth()
@limit("password", 10, 600, per="user")
def change_password():
    i, s = g.ident, sv()
    d = validate(json_body(), {"current_password": F.str(max=256), "new_password": F.str(max=256)})
    if i.realm == "personal":
        row, db, table = s.pdb.one("SELECT * FROM users WHERE id=?", (i.uid,)), s.pdb, "users"
    else:
        row, db, table = s.edb.one("SELECT * FROM employees WHERE id=? AND org_id=?", (i.uid, i.org_id)), s.edb, "employees"
    if row is None or not s.hasher.verify(d["current_password"], row["pw_hash"]):
        raise unauthorized("Password saat ini salah", "invalid_credentials")
    check_password(d["new_password"], i.email)
    with db.tx():
        db.run(f"UPDATE {table} SET pw_hash=? WHERE id=?", (s.hasher.hash(d["new_password"]), i.uid))
        col = "user_id" if i.realm == "personal" else "employee_id"
        db.run(f"UPDATE sessions SET revoked_at=? WHERE {col}=? AND id<>? AND revoked_at IS NULL", (clock.now_ms(), i.uid, i.sid))
        _audit(i).append(i.scope_id, "auth.password_changed", i.uid, i.role, ip=client_ip())
    return "", 204


# ---------------------------------------------------------------- platform

platform_bp = Blueprint("platform", __name__, url_prefix="/v1/platform")


@platform_bp.post("/orgs")
@limit("platform", 30, 60)
def platform_create_org():
    s = sv()
    supplied = request.headers.get("X-Platform-Token", "")
    if not s.settings.platform_token or not hmac.compare_digest(supplied.encode(), s.settings.platform_token.encode()):
        raise unauthorized("Platform token salah", "invalid_platform_token")
    d = validate(json_body(), {"name": F.str(max=120, min=2), "slug": F.str(max=40, min=3, pattern=_SLUG), "owner_email": F.email(),
                               "owner_name": F.str(max=80, min=1), "ai_monthly_quota": F.int(min=1, max=100_000_000, req=False)})
    out = create_org(s, d["name"], d["slug"], d["owner_email"], d["owner_name"], d.get("ai_monthly_quota"))
    return jsonify(out), 201
