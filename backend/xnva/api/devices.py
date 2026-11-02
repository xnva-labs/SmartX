from __future__ import annotations

import os
import uuid
from typing import Any, Dict

from flask import Blueprint, g, jsonify

from .. import clock
from ..crypto import b64e
from ..errors import conflict, forbidden, not_found
from ..orgs import get_policy
from ..security import auth, client_ip, limit, require
from ..validate import F, validate
from .common import json_body, sv
from .misc import notify

bp = Blueprint("devices", __name__, url_prefix="/v1/devices")
_HW = r"[0-9A-Fa-f]{12}"
_FW = r"[0-9A-Za-z.\-+]{1,32}"


def _ctx(i):
    s = sv()
    if i.realm == "personal":
        return s.pdb, s.pkeys, s.paudit, "devices", "user_id=?", (i.uid,)
    return s.edb, s.ekeys, s.eaudit, "devices", "org_id=? AND owner_id=?", (i.org_id, i.uid)


def _row_out(r, with_owner: bool = False) -> Dict[str, Any]:
    d = {"id": r["id"], "hw_id": r["hw_id"], "label": r["label"], "fw_version": r["fw_version"], "battery_pct": r["battery_pct"],
         "last_seen_at": r["last_seen_at"], "created_at": r["created_at"], "revoked": r["revoked_at"] is not None}
    if with_owner:
        d["owner_id"] = r["owner_id"]
    return d


def _get_own(i, did: str):
    db, _, _, t, where, args = _ctx(i)
    r = db.one(f"SELECT * FROM {t} WHERE id=? AND {where}", (did,) + args)
    if r is None:
        raise not_found("Perangkat")
    return r


@bp.post("")
@auth()
@limit("device-reg", 20, 3600, per="user")
def register():
    i, s = g.ident, sv()
    d = validate(json_body(), {"hw_id": F.str(max=12, min=12, pattern=_HW), "label": F.str(max=80, min=1), "fw_version": F.str(max=32, req=False, pattern=_FW)})
    hw = d["hw_id"].upper()
    if i.realm == "enterprise" and not i.can("devices.manage") and not get_policy(s, i.org_id)["allow_employee_devices"]:
        raise forbidden("Kebijakan organisasi tidak mengizinkan karyawan mendaftarkan perangkat", "disabled_by_policy")
    db, kr, audit, t, where, args = _ctx(i)
    secret, did, now = os.urandom(32), uuid.uuid4().hex, clock.now_ms()
    with db.tx():
        if i.realm == "enterprise":
            clash = db.one("SELECT id, revoked_at FROM devices WHERE org_id=? AND hw_id=?", (i.org_id, hw))
        else:
            clash = db.one("SELECT id, revoked_at FROM devices WHERE user_id=? AND hw_id=?", (i.uid, hw))
        if clash is not None and clash["revoked_at"] is None:
            raise conflict("device_exists", "Perangkat ini sudah terdaftar")
        if clash is not None:
            db.run(f"DELETE FROM devices WHERE id=?", (clash["id"],))
        sealed = kr.seal(i.scope_id, b64e(secret), f"devices.{did}.secret")
        if i.realm == "personal":
            db.run("INSERT INTO devices(id,user_id,hw_id,label,secret_wrapped,fw_version,created_at) VALUES(?,?,?,?,?,?,?)",
                   (did, i.uid, hw, d["label"], sealed, d.get("fw_version"), now))
        else:
            db.run("INSERT INTO devices(id,org_id,owner_id,hw_id,label,secret_wrapped,fw_version,created_at) VALUES(?,?,?,?,?,?,?,?)",
                   (did, i.org_id, i.uid, hw, d["label"], sealed, d.get("fw_version"), now))
        audit.append(i.scope_id, "device.registered", i.uid, i.role, "device", did, ip=client_ip(), meta={"hw_id": hw})
    return jsonify({"id": did, "hw_id": hw, "label": d["label"], "secret": b64e(secret)}), 201


@bp.get("")
@auth()
def list_devices():
    i, s = g.ident, sv()
    if i.realm == "enterprise" and i.can("devices.manage"):
        rows = s.edb.all("SELECT * FROM devices WHERE org_id=? ORDER BY created_at DESC", (i.org_id,))
        return jsonify({"items": [_row_out(r, True) for r in rows]})
    db, _, _, t, where, args = _ctx(i)
    return jsonify({"items": [_row_out(r) for r in db.all(f"SELECT * FROM {t} WHERE {where} ORDER BY created_at DESC", args)]})


@bp.get("/<did>/secret")
@auth()
@limit("device-secret", 30, 3600, per="user")
def secret(did: str):
    i = g.ident
    r = _get_own(i, did)
    if r["revoked_at"] is not None:
        raise forbidden("Perangkat sudah dicabut", "device_revoked")
    _, kr, audit, *_ = _ctx(i)
    audit.append(i.scope_id, "device.secret_read", i.uid, i.role, "device", did, ip=client_ip())
    return jsonify({"id": did, "secret": kr.open(i.scope_id, r["secret_wrapped"], f"devices.{did}.secret")})


@bp.post("/<did>/rotate-secret")
@auth()
def rotate(did: str):
    i = g.ident
    r = _get_own(i, did)
    if r["revoked_at"] is not None:
        raise forbidden("Perangkat sudah dicabut", "device_revoked")
    db, kr, audit, t, *_ = _ctx(i)
    new = os.urandom(32)
    with db.tx():
        db.run(f"UPDATE {t} SET secret_wrapped=? WHERE id=?", (kr.seal(i.scope_id, b64e(new), f"devices.{did}.secret"), did))
        audit.append(i.scope_id, "device.secret_rotated", i.uid, i.role, "device", did, ip=client_ip())
    return jsonify({"id": did, "secret": b64e(new)})


@bp.post("/<did>/telemetry")
@auth()
@limit("telemetry", 120, 60, per="user")
def telemetry(did: str):
    i = g.ident
    r = _get_own(i, did)
    d = validate(json_body(), {"battery_pct": F.int(min=0, max=100, req=False), "fw_version": F.str(max=32, req=False, pattern=_FW)})
    db, _, _, t, *_ = _ctx(i)
    now = clock.now_ms()
    db.run(f"UPDATE {t} SET battery_pct=COALESCE(?,battery_pct), fw_version=COALESCE(?,fw_version), last_seen_at=? WHERE id=?",
           (d.get("battery_pct"), d.get("fw_version"), now, did))
    b = d.get("battery_pct")
    if b is not None and b <= 15 and (r["battery_pct"] is None or r["battery_pct"] > 15):
        notify(i.realm, i.scope_id, i.uid, "device.low_battery", "Baterai kacamata rendah", f"{r['label']} tersisa {b}%.", "device", did)
    return "", 204


@bp.put("/<did>")
@auth()
def rename(did: str):
    i = g.ident
    _get_own(i, did)
    d = validate(json_body(), {"label": F.str(max=80, min=1)})
    db, _, _, t, *_ = _ctx(i)
    db.run(f"UPDATE {t} SET label=? WHERE id=?", (d["label"], did))
    return jsonify(_row_out(db.one(f"SELECT * FROM {t} WHERE id=?", (did,))))


@bp.delete("/<did>")
@auth()
def revoke(did: str):
    i, s = g.ident, sv()
    if i.realm == "enterprise" and i.can("devices.manage"):
        r = s.edb.one("SELECT * FROM devices WHERE id=? AND org_id=?", (did, i.org_id))
        if r is None:
            raise not_found("Perangkat")
        owner = r["owner_id"]
    else:
        r = _get_own(i, did)
        owner = i.uid
    db, _, audit, t, *_ = _ctx(i)
    db.run(f"UPDATE {t} SET revoked_at=?, secret_wrapped=? WHERE id=? AND revoked_at IS NULL", (clock.now_ms(), "k1.revoked", did))
    audit.append(i.scope_id, "device.revoked", i.uid, i.role, "device", did, ip=client_ip())
    if i.realm == "enterprise" and owner != i.uid:
        notify("enterprise", i.org_id, owner, "device.revoked", "Perangkat dicabut", f"{r['label']} dicabut oleh administrator.", "device", did)
    return "", 204
