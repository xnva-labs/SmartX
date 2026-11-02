"""Endpoints shared by both realms: notification inbox and service status."""
from __future__ import annotations

import uuid
from typing import Optional

from flask import Blueprint, g, jsonify

from .. import __version__, clock
from ..errors import not_found
from ..security import auth
from .common import int_arg, sv

bp = Blueprint("misc", __name__)


def notify(realm: str, scope: str, user_id: str, kind: str, title: str, body: str, ref_type: Optional[str] = None, ref_id: Optional[str] = None) -> None:
    s = sv()
    if realm == "personal":
        s.pdb.run("INSERT INTO notifications(id,user_id,kind,title,body,created_at,ref_type,ref_id) VALUES(?,?,?,?,?,?,?,?)",
                  (uuid.uuid4().hex, user_id, kind, title[:200], body[:1000], clock.now_ms(), ref_type, ref_id))
    else:
        s.edb.run("INSERT INTO notifications(id,org_id,employee_id,kind,title,body,created_at,ref_type,ref_id) VALUES(?,?,?,?,?,?,?,?,?)",
                  (uuid.uuid4().hex, scope, user_id, kind, title[:200], body[:1000], clock.now_ms(), ref_type, ref_id))


def _scoped(i):
    s = sv()
    if i.realm == "personal":
        return s.pdb, "user_id=?", (i.uid,)
    return s.edb, "org_id=? AND employee_id=?", (i.org_id, i.uid)


@bp.get("/v1/notifications")
@auth()
def inbox():
    from flask import request
    db, where, args = _scoped(g.ident)
    since = int_arg("since", 0, 0, 2**53)
    rows = db.all(f"SELECT id,kind,title,body,created_at,read_at,ref_type,ref_id FROM notifications WHERE {where} AND created_at>? ORDER BY created_at DESC LIMIT ?",
                  args + (since, int_arg("limit", 50, 1, 200)))
    unread = db.scalar(f"SELECT COUNT(*) FROM notifications WHERE {where} AND read_at IS NULL", args, 0)
    return jsonify({"items": [dict(r) for r in rows], "unread": unread, "server_time": clock.now_ms()})


@bp.post("/v1/notifications/<nid>/read")
@auth()
def mark_read(nid: str):
    db, where, args = _scoped(g.ident)
    if not db.run(f"UPDATE notifications SET read_at=? WHERE id=? AND {where} AND read_at IS NULL", (clock.now_ms(), nid) + args):
        raise not_found("Notifikasi")
    return "", 204


@bp.post("/v1/notifications/read-all")
@auth()
def mark_all_read():
    db, where, args = _scoped(g.ident)
    n = db.run(f"UPDATE notifications SET read_at=? WHERE {where} AND read_at IS NULL", (clock.now_ms(),) + args)
    return jsonify({"updated": n})


@bp.get("/healthz")
def healthz():
    return jsonify({"status": "ok", "version": __version__})


@bp.get("/readyz")
def readyz():
    s = sv()
    ok = s.pdb.integrity_ok() and s.edb.integrity_ok()
    return (jsonify({"status": "ready" if ok else "degraded"}), 200 if ok else 503)


@bp.get("/v1/status")
def status():
    s = sv().settings
    x = s.xnai
    return jsonify({
        "service": "smarteyex-platform", "version": __version__,
        "features": {
            "personal_signup": s.allow_personal_signup, "org_signup": s.allow_org_signup,
            "xnai_chat": "configured" if x.chat_ready else "not_configured",
            "xnai_vision": "configured" if x.vision_ready else "not_configured",
            "xnai_transcribe": "configured" if x.stt_ready else "not_configured",
            "xnai_provider": x.provider or None,
        },
    })
