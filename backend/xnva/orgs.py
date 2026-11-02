"""Organization provisioning shared by the CLI, platform API and optional signup."""
from __future__ import annotations

import json
import uuid
from typing import Any, Dict, Optional

from . import clock
from .crypto import new_token, token_hash
from .errors import ApiError, conflict

DEFAULT_POLICY: Dict[str, Any] = {
    "ai_enabled": True,
    "vision_enabled": True,
    "allow_employee_devices": True,
    "audit_retention_days": 365,
    "notification_retention_days": 90,
    "meeting_retention_days": 0,
}
INVITE_TTL_MS = 7 * clock.DAY_MS


def issue_invite(sv, org_id: str, employee_id: str, created_by: Optional[str]) -> Dict[str, Any]:
    token = new_token(32)
    now = clock.now_ms()
    sv.edb.run("DELETE FROM invites WHERE org_id=? AND employee_id=? AND used_at IS NULL", (org_id, employee_id))
    sv.edb.run(
        "INSERT INTO invites(id,org_id,employee_id,token_hash,expires_at,created_by,created_at) VALUES(?,?,?,?,?,?,?)",
        (uuid.uuid4().hex, org_id, employee_id, token_hash(token), now + INVITE_TTL_MS, created_by, now))
    return {"invite_token": token, "invite_expires_at": now + INVITE_TTL_MS}


def create_org(sv, name: str, slug: str, owner_email: str, owner_name: str, quota: Optional[int] = None,
               owner_password: Optional[str] = None) -> Dict[str, Any]:
    now = clock.now_ms()
    org_id, owner_id = uuid.uuid4().hex, uuid.uuid4().hex
    with sv.edb.tx():
        if sv.edb.one("SELECT 1 FROM orgs WHERE slug=?", (slug,)):
            raise conflict("slug_taken", "Slug organisasi sudah dipakai")
        sv.edb.run(
            "INSERT INTO orgs(id,slug,name,ai_monthly_quota,policy,created_at) VALUES(?,?,?,?,?,?)",
            (org_id, slug, name, quota or sv.settings.org_default_monthly_ai_quota, json.dumps(DEFAULT_POLICY), now))
        sv.ekeys.create(org_id, now)
        pw = sv.hasher.hash(owner_password) if owner_password else None
        sv.edb.run(
            "INSERT INTO employees(id,org_id,email,display_name,role,pw_hash,status,created_at) VALUES(?,?,?,?,?,?,?,?)",
            (owner_id, org_id, owner_email, owner_name, "owner", pw, "active" if pw else "invited", now))
        out: Dict[str, Any] = {"org": {"id": org_id, "slug": slug, "name": name}, "owner_id": owner_id}
        if not pw:
            out.update(issue_invite(sv, org_id, owner_id, None))
        sv.eaudit.append(org_id, "org.created", owner_id, "owner", "org", org_id, meta={"slug": slug})
    return out


def get_policy(sv, org_id: str) -> Dict[str, Any]:
    row = sv.edb.one("SELECT policy FROM orgs WHERE id=?", (org_id,))
    if row is None:
        raise ApiError(404, "not_found", "Organisasi tidak ditemukan")
    return {**DEFAULT_POLICY, **json.loads(row["policy"])}
