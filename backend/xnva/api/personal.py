from __future__ import annotations

import uuid
from typing import Any, Dict, List

from flask import Blueprint, g, jsonify, request

from .. import clock
from ..errors import ApiError, bad_request, forbidden, not_found, unauthorized
from ..security import auth, client_ip, limit
from ..validate import F, validate
from .common import int_arg, json_body, sv

bp = Blueprint("personal", __name__, url_prefix="/v1/personal")

MEMORY_TYPES = ("PROFILE", "NOTE", "PERSON", "TASK", "PREFERENCE")
REPEATS = ("none", "daily", "weekly", "monthly")
RESOURCES: Dict[str, Dict[str, Any]] = {
    "memory": {"table": "memory_entries", "enc": ("title", "content"), "plain": ("type",)},
    "reminders": {"table": "reminders", "enc": ("title", "notes"), "plain": ("due_at", "repeat", "done_at")},
}
_ITEM_SPEC = {
    "memory": {"id": F.str(max=64, min=8, pattern=r"[A-Za-z0-9\-_]+", req=False), "type": F.enum(MEMORY_TYPES),
               "title": F.str(max=200), "content": F.str(max=8000)},
    "reminders": {"id": F.str(max=64, min=8, pattern=r"[A-Za-z0-9\-_]+", req=False), "title": F.str(max=200, min=1),
                  "notes": F.str(max=4000, req=False, default=""), "due_at": F.int(min=0), "repeat": F.enum(REPEATS, req=False, default="none"),
                  "done_at": F.int(min=0, req=False)},
}


def _privacy(uid: str) -> Dict[str, Any]:
    r = sv().pdb.one("SELECT * FROM privacy WHERE user_id=?", (uid,))
    return {"cloud_processing": bool(r["cloud_processing"]), "memory_sync": bool(r["memory_sync"]),
            "retention_days": r["retention_days"], "updated_at": r["updated_at"]}


def _need_sync(uid: str) -> None:
    if not _privacy(uid)["memory_sync"]:
        raise forbidden("Sinkronisasi cloud dimatikan. Aktifkan di Pengaturan Privasi.", "memory_sync_disabled")


def _out(res: str, row, uid: str) -> Dict[str, Any]:
    cfg, s = RESOURCES[res], sv()
    aad = f"{cfg['table']}.{uid}.{row['id']}"
    d: Dict[str, Any] = {"id": row["id"], "updated_at": row["updated_at"], "deleted": row["deleted_at"] is not None}
    for f in cfg["plain"]:
        d[f] = row[f]
    for f in cfg["enc"]:
        d[f] = "" if row["deleted_at"] is not None else s.pkeys.open(uid, row[f + "_enc"], f"{aad}.{f}")
    return d


def _write(res: str, uid: str, item: Dict[str, Any], rid: str, ts: int, deleted: bool = False) -> None:
    cfg, s = RESOURCES[res], sv()
    aad = f"{cfg['table']}.{uid}.{rid}"
    cols = ["user_id", "id", "updated_at", "deleted_at"] + list(cfg["plain"]) + [f + "_enc" for f in cfg["enc"]]
    vals: List[Any] = [uid, rid, ts, ts if deleted else None] + [item.get(f) for f in cfg["plain"]]
    vals += [s.pkeys.seal(uid, "" if deleted else item.get(f, ""), f"{aad}.{f}") for f in cfg["enc"]]
    if deleted:
        existing = s.pdb.one(f"SELECT * FROM {cfg['table']} WHERE user_id=? AND id=?", (uid, rid))
        for i, f in enumerate(cfg["plain"]):
            vals[4 + i] = existing[f] if existing else item.get(f, _blank(res, f))
    sets = ",".join(f"{c}=excluded.{c}" for c in cols[2:])
    s.pdb.run(f"INSERT INTO {cfg['table']}({','.join(cols)}) VALUES({','.join('?' * len(cols))}) "
              f"ON CONFLICT(user_id,id) DO UPDATE SET {sets}", vals)


def _blank(res: str, field: str):
    return {"type": "NOTE", "due_at": 0, "repeat": "none", "done_at": None}[field]


def _list(res: str):
    uid, cfg = g.ident.uid, RESOURCES[res]
    sql, args = f"SELECT * FROM {cfg['table']} WHERE user_id=? AND deleted_at IS NULL", [uid]
    if res == "memory" and request.args.get("type"):
        sql += " AND type=?"
        args.append(request.args["type"])
    sql += " ORDER BY updated_at DESC" if res == "memory" else " ORDER BY due_at ASC"
    return jsonify({"items": [_out(res, r, uid) for r in sv().pdb.all(sql + " LIMIT 2000", args)]})


def _upsert(res: str, rid: str = ""):
    uid, s = g.ident.uid, sv()
    _need_sync(uid)
    spec = dict(_ITEM_SPEC[res])
    d = validate(json_body(), spec, partial=bool(rid))
    rid = rid or d.get("id") or uuid.uuid4().hex
    ts = clock.now_ms()
    with s.pdb.tx():
        existing = s.pdb.one(f"SELECT * FROM {RESOURCES[res]['table']} WHERE user_id=? AND id=?", (uid, rid))
        if rid and existing is None and request.method == "PUT":
            raise not_found("Data")
        merged = {**(_out(res, existing, uid) if existing and not existing["deleted_at"] else {}), **d}
        if res == "memory" and "type" not in merged:
            raise bad_request("validation_error", "type wajib diisi")
        if res == "reminders" and ("title" not in merged or "due_at" not in merged):
            raise bad_request("validation_error", "title dan due_at wajib diisi")
        merged.setdefault("repeat", "none")
        _write(res, uid, merged, rid, ts)
        row = s.pdb.one(f"SELECT * FROM {RESOURCES[res]['table']} WHERE user_id=? AND id=?", (uid, rid))
    return jsonify(_out(res, row, uid)), (201 if request.method == "POST" else 200)


def _delete(res: str, rid: str):
    uid, s, cfg = g.ident.uid, sv(), RESOURCES[res]
    row = s.pdb.one(f"SELECT * FROM {cfg['table']} WHERE user_id=? AND id=? AND deleted_at IS NULL", (uid, rid))
    if row is None:
        raise not_found("Data")
    _write(res, uid, {}, rid, clock.now_ms(), deleted=True)
    return "", 204


def _sync(res: str):
    uid, s, cfg = g.ident.uid, sv(), RESOURCES[res]
    _need_sync(uid)
    item_spec = {**_ITEM_SPEC[res], "id": F.str(max=64, min=8, pattern=r"[A-Za-z0-9\-_]+"),
                 "updated_at": F.int(min=0), "deleted": F.bool(req=False, default=False)}
    d = validate(json_body(), {"since": F.int(min=0, req=False, default=0), "items": F.list(("obj", {"spec": item_spec, "req": True, "default": None}), max=500)})
    now = clock.now_ms()
    applied = 0
    with s.pdb.tx():
        for it in d["items"]:
            cur = s.pdb.one(f"SELECT updated_at FROM {cfg['table']} WHERE user_id=? AND id=?", (uid, it["id"]))
            if cur is not None and cur["updated_at"] >= it["updated_at"]:
                continue
            _write(res, uid, it, it["id"], min(it["updated_at"], now), deleted=it["deleted"])
            applied += 1
        rows = s.pdb.all(f"SELECT * FROM {cfg['table']} WHERE user_id=? AND updated_at>? ORDER BY updated_at LIMIT 2000", (uid, d["since"]))
    return jsonify({"server_time": now, "applied": applied, "items": [_out(res, r, uid) for r in rows]})


def _mount(res: str):
    p = f"/{res}"
    bp.add_url_rule(p, f"{res}_list", auth("personal")(lambda: _list(res)), methods=["GET"])
    bp.add_url_rule(p, f"{res}_create", auth("personal")(lambda: _upsert(res)), methods=["POST"])
    bp.add_url_rule(p + "/<rid>", f"{res}_update", auth("personal")(lambda rid: _upsert(res, rid)), methods=["PUT"])
    bp.add_url_rule(p + "/<rid>", f"{res}_delete", auth("personal")(lambda rid: _delete(res, rid)), methods=["DELETE"])
    bp.add_url_rule(p + "/sync", f"{res}_sync", auth("personal")(lambda: _sync(res)), methods=["POST"])


_mount("memory")
_mount("reminders")


@bp.delete("/memory")
@auth("personal")
def memory_clear():
    uid, s = g.ident.uid, sv()
    t = request.args.get("type")
    if t and t not in MEMORY_TYPES:
        raise bad_request("invalid_query", "type tidak dikenal")
    rows = s.pdb.all("SELECT id FROM memory_entries WHERE user_id=? AND deleted_at IS NULL" + (" AND type=?" if t else ""), (uid, t) if t else (uid,))
    ts = clock.now_ms()
    with s.pdb.tx():
        for r in rows:
            _write("memory", uid, {}, r["id"], ts, deleted=True)
        s.paudit.append(uid, "memory.cleared", uid, "user", meta={"type": t, "count": len(rows)}, ip=client_ip())
    return jsonify({"deleted": len(rows)})


@bp.get("/profile")
@auth("personal")
def profile_get():
    r = sv().pdb.one("SELECT id,email,display_name,created_at FROM users WHERE id=?", (g.ident.uid,))
    return jsonify(dict(r))


@bp.put("/profile")
@auth("personal")
def profile_put():
    d = validate(json_body(), {"display_name": F.str(max=80)})
    sv().pdb.run("UPDATE users SET display_name=? WHERE id=?", (d["display_name"], g.ident.uid))
    return profile_get()


@bp.get("/privacy")
@auth("personal")
def privacy_get():
    return jsonify(_privacy(g.ident.uid))


@bp.put("/privacy")
@auth("personal")
def privacy_put():
    uid, s = g.ident.uid, sv()
    d = validate(json_body(), {"cloud_processing": F.bool(req=False), "memory_sync": F.bool(req=False), "retention_days": F.int(min=0, max=3650, req=False)}, partial=True)
    cur = _privacy(uid)
    new = {**cur, **d}
    with s.pdb.tx():
        s.pdb.run("UPDATE privacy SET cloud_processing=?, memory_sync=?, retention_days=?, updated_at=? WHERE user_id=?",
                  (int(new["cloud_processing"]), int(new["memory_sync"]), new["retention_days"], clock.now_ms(), uid))
        s.paudit.append(uid, "privacy.updated", uid, "user", meta={k: new[k] for k in ("cloud_processing", "memory_sync", "retention_days")}, ip=client_ip())
    return jsonify(_privacy(uid))


@bp.get("/activity")
@auth("personal")
def activity():
    rows = sv().pdb.all("SELECT ts,kind,bytes_in,bytes_out,model FROM ai_activity WHERE user_id=? ORDER BY ts DESC LIMIT ?",
                        (g.ident.uid, int_arg("limit", 50, 1, 500)))
    return jsonify({"items": [dict(r) for r in rows], "note": "Hanya metadata. Isi percakapan dan gambar tidak disimpan di server."})


@bp.get("/export")
@auth("personal")
@limit("export", 5, 3600, per="user")
def export():
    uid, s = g.ident.uid, sv()
    u = s.pdb.one("SELECT id,email,display_name,created_at FROM users WHERE id=?", (uid,))
    out = {"exported_at": clock.now_ms(), "profile": dict(u), "privacy": _privacy(uid),
           "memory": [_out("memory", r, uid) for r in s.pdb.all("SELECT * FROM memory_entries WHERE user_id=? AND deleted_at IS NULL", (uid,))],
           "reminders": [_out("reminders", r, uid) for r in s.pdb.all("SELECT * FROM reminders WHERE user_id=? AND deleted_at IS NULL", (uid,))],
           "devices": [dict(r) for r in s.pdb.all("SELECT id,hw_id,label,fw_version,created_at,revoked_at FROM devices WHERE user_id=?", (uid,))],
           "ai_activity": [dict(r) for r in s.pdb.all("SELECT ts,kind,bytes_in,bytes_out,model FROM ai_activity WHERE user_id=?", (uid,))],
           "security_events": s.paudit.query(uid, limit=1000)}
    s.paudit.append(uid, "data.exported", uid, "user", ip=client_ip())
    resp = jsonify(out)
    resp.headers["Content-Disposition"] = "attachment; filename=smarteyex-export.json"
    return resp


@bp.delete("/account")
@auth("personal")
@limit("delete-account", 5, 3600, per="user")
def delete_account():
    uid, s = g.ident.uid, sv()
    d = validate(json_body(), {"password": F.str(max=256)})
    row = s.pdb.one("SELECT pw_hash FROM users WHERE id=?", (uid,))
    if row is None or not s.hasher.verify(d["password"], row["pw_hash"]):
        raise unauthorized("Password salah", "invalid_credentials")
    with s.pdb.tx():
        s.pdb.run("DELETE FROM audit_log WHERE scope_id=?", (uid,))
        s.pdb.run("DELETE FROM audit_checkpoints WHERE scope_id=?", (uid,))
        s.pdb.run("DELETE FROM users WHERE id=?", (uid,))
        s.pkeys.destroy(uid)
    return "", 204
