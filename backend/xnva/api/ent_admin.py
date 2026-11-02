"""Enterprise administration: organization, policy, divisions, employees, security."""
from __future__ import annotations

import json
import uuid
from typing import Any, Dict

from flask import Blueprint, g, jsonify, request

from .. import clock
from ..errors import ApiError, conflict, forbidden, not_found, unauthorized
from ..orgs import DEFAULT_POLICY, get_policy, issue_invite
from ..security import auth, client_ip, limit
from ..validate import F, validate
from .common import json_body, sv
from .misc import notify

bp = Blueprint("ent_admin", __name__, url_prefix="/v1/enterprise")


def audit(action: str, ttype=None, tid=None, meta=None) -> None:
    i = g.ident
    sv().eaudit.append(i.org_id, action, i.uid, i.role, ttype, tid, ip=client_ip(), meta=meta)


def can_assign(actor_role: str, target_role: str) -> bool:
    if actor_role == "owner":
        return True
    return actor_role == "admin" and target_role in ("manager", "member", "auditor")


def division_ok(org: str, did: str) -> None:
    if did and not sv().edb.one("SELECT 1 FROM divisions WHERE id=? AND org_id=?", (did, org)):
        raise ApiError(400, "invalid_division", "Divisi tidak ditemukan di organisasi ini")


# -------------------------------------------------------------------- org

@bp.get("/org")
@auth("enterprise")
def org_info():
    i, s = g.ident, sv()
    o = s.edb.one("SELECT id,slug,name,status,ai_monthly_quota,created_at FROM orgs WHERE id=?", (i.org_id,))
    pol = get_policy(s, i.org_id)
    if not i.can("policy.manage") and i.role != "auditor":
        pol = {k: pol[k] for k in ("ai_enabled", "vision_enabled", "allow_employee_devices")}
    return jsonify({**dict(o), "policy": pol, "you": {"id": i.uid, "role": i.role, "division_id": i.division_id},
                    "employees": s.edb.scalar("SELECT COUNT(*) FROM employees WHERE org_id=? AND status='active'", (i.org_id,), 0)})


@bp.put("/policy")
@auth("enterprise", "policy.manage")
def policy_put():
    d = validate(json_body(), {"ai_enabled": F.bool(req=False), "vision_enabled": F.bool(req=False), "allow_employee_devices": F.bool(req=False),
                               "audit_retention_days": F.int(min=90, max=3650, req=False), "notification_retention_days": F.int(min=7, max=3650, req=False),
                               "meeting_retention_days": F.int(min=0, max=3650, req=False)}, partial=True)
    s, i = sv(), g.ident
    new = {**get_policy(s, i.org_id), **d}
    with s.edb.tx():
        s.edb.run("UPDATE orgs SET policy=? WHERE id=?", (json.dumps({k: new[k] for k in DEFAULT_POLICY}), i.org_id))
        audit("policy.updated", "org", i.org_id, d)
    return jsonify(new)


@bp.delete("/org")
@auth("enterprise", "org.manage")
@limit("org-delete", 3, 3600, per="user")
def org_delete():
    s, i = sv(), g.ident
    d = validate(json_body(), {"confirm_slug": F.str(max=40), "password": F.str(max=256)})
    row = s.edb.one("SELECT pw_hash FROM employees WHERE id=? AND org_id=?", (i.uid, i.org_id))
    if d["confirm_slug"] != i.org_slug:
        raise ApiError(400, "confirmation_mismatch", "Slug konfirmasi tidak cocok")
    if not row or not s.hasher.verify(d["password"], row["pw_hash"]):
        raise unauthorized("Password salah", "invalid_credentials")
    with s.edb.tx():
        s.edb.run("DELETE FROM audit_log WHERE scope_id=?", (i.org_id,))
        s.edb.run("DELETE FROM audit_checkpoints WHERE scope_id=?", (i.org_id,))
        s.edb.run("DELETE FROM orgs WHERE id=?", (i.org_id,))
        s.ekeys.destroy(i.org_id)
    return "", 204


# -------------------------------------------------------------- divisions

@bp.get("/divisions")
@auth("enterprise", "employees.read")
def divisions_list():
    rows = sv().edb.all("SELECT d.id,d.name,d.created_at,(SELECT COUNT(*) FROM employees e WHERE e.division_id=d.id AND e.org_id=d.org_id AND e.status='active') AS members"
                        " FROM divisions d WHERE d.org_id=? ORDER BY d.name", (g.ident.org_id,))
    return jsonify({"items": [dict(r) for r in rows]})


@bp.post("/divisions")
@auth("enterprise", "divisions.manage")
def divisions_create():
    d = validate(json_body(), {"name": F.str(max=80, min=1)})
    did = uuid.uuid4().hex
    s = sv()
    with s.edb.tx():
        if s.edb.one("SELECT 1 FROM divisions WHERE org_id=? AND name=?", (g.ident.org_id, d["name"])):
            raise conflict("division_exists", "Nama divisi sudah dipakai")
        s.edb.run("INSERT INTO divisions(id,org_id,name,created_at) VALUES(?,?,?,?)", (did, g.ident.org_id, d["name"], clock.now_ms()))
        audit("division.created", "division", did, {"name": d["name"]})
    return jsonify({"id": did, "name": d["name"]}), 201


@bp.put("/divisions/<did>")
@auth("enterprise", "divisions.manage")
def divisions_rename(did: str):
    d = validate(json_body(), {"name": F.str(max=80, min=1)})
    s = sv()
    try:
        n = s.edb.run("UPDATE divisions SET name=? WHERE id=? AND org_id=?", (d["name"], did, g.ident.org_id))
    except Exception:
        raise conflict("division_exists", "Nama divisi sudah dipakai")
    if not n:
        raise not_found("Divisi")
    audit("division.renamed", "division", did, {"name": d["name"]})
    return jsonify({"id": did, "name": d["name"]})


@bp.delete("/divisions/<did>")
@auth("enterprise", "divisions.manage")
def divisions_delete(did: str):
    s, org = sv(), g.ident.org_id
    if not s.edb.one("SELECT 1 FROM divisions WHERE id=? AND org_id=?", (did, org)):
        raise not_found("Divisi")
    used = s.edb.scalar("SELECT (SELECT COUNT(*) FROM employees WHERE division_id=? AND org_id=?) + (SELECT COUNT(*) FROM kb_documents WHERE division_id=? AND org_id=?)"
                        " + (SELECT COUNT(*) FROM announcements WHERE division_id=? AND org_id=?)", (did, org) * 3, 0)
    if used:
        raise conflict("division_in_use", "Divisi masih dipakai karyawan, dokumen, atau pengumuman")
    s.edb.run("DELETE FROM divisions WHERE id=? AND org_id=?", (did, org))
    audit("division.deleted", "division", did)
    return "", 204


# -------------------------------------------------------------- employees

def emp_out(r, manage: bool) -> Dict[str, Any]:
    d = {"id": r["id"], "email": r["email"], "display_name": r["display_name"], "title": r["title"], "role": r["role"],
         "division_id": r["division_id"], "status": r["status"]}
    if manage:
        d["last_login_at"] = r["last_login_at"]
    return d


@bp.get("/employees")
@auth("enterprise", "employees.read")
def employees_list():
    manage = g.ident.can("employees.manage")
    rows = sv().edb.all("SELECT * FROM employees WHERE org_id=?" + ("" if manage else " AND status='active'") + " ORDER BY display_name", (g.ident.org_id,))
    return jsonify({"items": [emp_out(r, manage) for r in rows]})


@bp.post("/employees")
@auth("enterprise", "employees.manage")
@limit("invite-create", 60, 3600, per="user")
def employees_invite():
    s, i = sv(), g.ident
    d = validate(json_body(), {"email": F.email(), "display_name": F.str(max=80, min=1), "role": F.enum(("owner", "admin", "manager", "member", "auditor"), req=False, default="member"),
                               "division_id": F.str(max=64, req=False), "title": F.str(max=80, req=False, default="")})
    if not can_assign(i.role, d["role"]):
        raise forbidden("Peran Anda tidak boleh menetapkan peran tersebut", "role_not_assignable")
    division_ok(i.org_id, d.get("division_id", ""))
    eid = uuid.uuid4().hex
    with s.edb.tx():
        if s.edb.one("SELECT 1 FROM employees WHERE org_id=? AND email=?", (i.org_id, d["email"])):
            raise conflict("email_taken", "Email sudah terdaftar di organisasi ini")
        s.edb.run("INSERT INTO employees(id,org_id,email,display_name,title,role,division_id,status,created_at) VALUES(?,?,?,?,?,?,?,'invited',?)",
                  (eid, i.org_id, d["email"], d["display_name"], d["title"], d["role"], d.get("division_id"), clock.now_ms()))
        inv = issue_invite(s, i.org_id, eid, i.uid)
        audit("employee.invited", "employee", eid, {"role": d["role"]})
    return jsonify({"id": eid, "email": d["email"], "role": d["role"], **inv}), 201


@bp.post("/employees/<eid>/reinvite")
@auth("enterprise", "employees.manage")
def employees_reinvite(eid: str):
    s, i = sv(), g.ident
    r = s.edb.one("SELECT * FROM employees WHERE id=? AND org_id=?", (eid, i.org_id))
    if r is None:
        raise not_found("Karyawan")
    if r["status"] != "invited" or not can_assign(i.role, r["role"]):
        raise conflict("not_reinvitable", "Hanya karyawan berstatus undangan yang dapat diundang ulang")
    with s.edb.tx():
        inv = issue_invite(s, i.org_id, eid, i.uid)
        audit("employee.reinvited", "employee", eid)
    return jsonify({"id": eid, **inv})


@bp.put("/employees/<eid>")
@auth("enterprise", "employees.manage")
def employees_update(eid: str):
    s, i = sv(), g.ident
    d = validate(json_body(), {"display_name": F.str(max=80, min=1, req=False), "title": F.str(max=80, req=False), "role": F.enum(("owner", "admin", "manager", "member", "auditor"), req=False),
                               "division_id": F.str(max=64, req=False), "status": F.enum(("active", "disabled"), req=False)}, partial=True)
    with s.edb.tx():
        r = s.edb.one("SELECT * FROM employees WHERE id=? AND org_id=?", (eid, i.org_id))
        if r is None:
            raise not_found("Karyawan")
        if r["id"] == i.uid and ("role" in d or d.get("status") == "disabled"):
            raise forbidden("Tidak boleh mengubah peran atau menonaktifkan akun sendiri", "self_change")
        if (("role" in d or "status" in d) and not can_assign(i.role, r["role"])) or ("role" in d and not can_assign(i.role, d["role"])):
            raise forbidden("Peran Anda tidak boleh mengubah karyawan ini", "role_not_assignable")
        if r["role"] == "owner" and (d.get("role", "owner") != "owner" or d.get("status") == "disabled"):
            if s.edb.scalar("SELECT COUNT(*) FROM employees WHERE org_id=? AND role='owner' AND status='active'", (i.org_id,), 0) <= 1:
                raise conflict("last_owner", "Organisasi harus memiliki minimal satu owner aktif")
        if d.get("status") == "active" and r["status"] == "invited":
            raise conflict("invite_pending", "Karyawan belum menerima undangan")
        if "division_id" in d:
            division_ok(i.org_id, d["division_id"])
        new = {**dict(r), **d}
        s.edb.run("UPDATE employees SET display_name=?, title=?, role=?, division_id=?, status=? WHERE id=? AND org_id=?",
                  (new["display_name"], new["title"], new["role"], new["division_id"] or None, new["status"], eid, i.org_id))
        if d.get("status") == "disabled" or ("role" in d and d["role"] != r["role"]):
            s.edb.run("UPDATE sessions SET revoked_at=? WHERE employee_id=? AND org_id=? AND revoked_at IS NULL", (clock.now_ms(), eid, i.org_id))
        audit("employee.updated", "employee", eid, {k: v for k, v in d.items()})
        row = s.edb.one("SELECT * FROM employees WHERE id=? AND org_id=?", (eid, i.org_id))
    if "role" in d and d["role"] != r["role"]:
        notify("enterprise", i.org_id, eid, "employee.role_changed", "Peran Anda diperbarui", f"Peran baru: {d['role']}.", "employee", eid)
    return jsonify(emp_out(row, True))


# --------------------------------------------------------------- security

@bp.get("/security/sessions")
@auth("enterprise", "security.manage")
def sec_sessions():
    rows = sv().edb.all("SELECT s.id, s.label, s.created_at, s.last_used_at, e.id AS employee_id, e.display_name, e.email FROM sessions s"
                        " JOIN employees e ON e.id=s.employee_id WHERE s.org_id=? AND s.revoked_at IS NULL AND s.expires_at>?"
                        " ORDER BY s.last_used_at DESC LIMIT 500", (g.ident.org_id, clock.now_ms()))
    return jsonify({"items": [dict(r) for r in rows]})


@bp.delete("/security/sessions/<sid>")
@auth("enterprise", "security.manage")
def sec_revoke(sid: str):
    if not sv().edb.run("UPDATE sessions SET revoked_at=? WHERE id=? AND org_id=? AND revoked_at IS NULL", (clock.now_ms(), sid, g.ident.org_id)):
        raise not_found("Sesi")
    audit("session.revoked_by_admin", "session", sid)
    return "", 204


@bp.post("/security/employees/<eid>/revoke-sessions")
@auth("enterprise", "security.manage")
def sec_revoke_all(eid: str):
    n = sv().edb.run("UPDATE sessions SET revoked_at=? WHERE employee_id=? AND org_id=? AND revoked_at IS NULL", (clock.now_ms(), eid, g.ident.org_id))
    audit("session.revoked_all_by_admin", "employee", eid, {"count": n})
    return jsonify({"revoked": n})
