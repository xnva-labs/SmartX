"""Tamper-evident audit log: every entry commits to the previous entry's hash."""
from __future__ import annotations

import hashlib
import ipaddress
import json
from typing import Any, Dict, List, Optional

from . import clock


def coarse_ip(ip: Optional[str]) -> Optional[str]:
    """Personal realm stores only a truncated address (/24 or /48)."""
    if not ip:
        return None
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return None
    prefix = 24 if addr.version == 4 else 48
    return str(ipaddress.ip_network(f"{ip}/{prefix}", strict=False).network_address) + f"/{prefix}"


def _canon(obj: Dict[str, Any]) -> str:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def _genesis(scope_id: str) -> str:
    return hashlib.sha256(f"xnva-audit-genesis:{scope_id}".encode()).hexdigest()


def _entry_hash(prev: str, e: Dict[str, Any]) -> str:
    return hashlib.sha256((prev + _canon(e)).encode()).hexdigest()


class AuditLog:
    def __init__(self, db, truncate_ip: bool = False):
        self.db, self.truncate_ip = db, truncate_ip

    def append(self, scope_id: str, action: str, actor_id: Optional[str] = None, actor_role: Optional[str] = None,
               target_type: Optional[str] = None, target_id: Optional[str] = None, ip: Optional[str] = None,
               meta: Optional[Dict[str, Any]] = None, now: Optional[int] = None) -> int:
        ts = clock.now_ms() if now is None else now
        ip = coarse_ip(ip) if self.truncate_ip else ip
        with self.db.tx():
            last = self.db.one("SELECT seq, hash FROM audit_log WHERE scope_id=? ORDER BY seq DESC LIMIT 1", (scope_id,))
            if last is None:
                cp = self.db.one("SELECT up_to_seq, hash FROM audit_checkpoints WHERE scope_id=?", (scope_id,))
                seq, prev = (cp["up_to_seq"] + 1, cp["hash"]) if cp else (1, _genesis(scope_id))
            else:
                seq, prev = last["seq"] + 1, last["hash"]
            entry = {"scope_id": scope_id, "seq": seq, "ts": ts, "actor_id": actor_id, "actor_role": actor_role,
                     "action": action, "target_type": target_type, "target_id": target_id, "ip": ip, "meta": meta or {}}
            self.db.run(
                "INSERT INTO audit_log(scope_id,seq,ts,actor_id,actor_role,action,target_type,target_id,ip,meta,prev_hash,hash)"
                " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                (scope_id, seq, ts, actor_id, actor_role, action, target_type, target_id, ip, _canon(entry["meta"]),
                 prev, _entry_hash(prev, entry)),
            )
        return seq

    def verify(self, scope_id: str) -> Dict[str, Any]:
        cp = self.db.one("SELECT up_to_seq, hash FROM audit_checkpoints WHERE scope_id=?", (scope_id,))
        prev = cp["hash"] if cp else _genesis(scope_id)
        expect_seq = (cp["up_to_seq"] + 1) if cp else 1
        count = 0
        for r in self.db.all("SELECT * FROM audit_log WHERE scope_id=? ORDER BY seq", (scope_id,)):
            entry = {"scope_id": scope_id, "seq": r["seq"], "ts": r["ts"], "actor_id": r["actor_id"],
                     "actor_role": r["actor_role"], "action": r["action"], "target_type": r["target_type"],
                     "target_id": r["target_id"], "ip": r["ip"], "meta": json.loads(r["meta"])}
            if r["seq"] != expect_seq or r["prev_hash"] != prev or r["hash"] != _entry_hash(prev, entry):
                return {"ok": False, "entries": count, "broken_at_seq": r["seq"]}
            prev, expect_seq, count = r["hash"], expect_seq + 1, count + 1
        return {"ok": True, "entries": count, "broken_at_seq": None}

    def query(self, scope_id: str, limit: int = 100, before_seq: Optional[int] = None,
              action_prefix: Optional[str] = None) -> List[Dict[str, Any]]:
        sql, args = "SELECT * FROM audit_log WHERE scope_id=?", [scope_id]
        if before_seq is not None:
            sql += " AND seq<?"
            args.append(before_seq)
        if action_prefix:
            sql += " AND action LIKE ?"
            args.append(action_prefix.replace("%", "").replace("_", "") + "%")
        sql += " ORDER BY seq DESC LIMIT ?"
        args.append(limit)
        return [{**{k: r[k] for k in ("seq", "ts", "actor_id", "actor_role", "action", "target_type", "target_id", "ip")},
                 "meta": json.loads(r["meta"])} for r in self.db.all(sql, args)]

    def purge_before(self, scope_id: str, cutoff_ts: int) -> int:
        """Delete old entries but keep the chain verifiable via a checkpoint."""
        with self.db.tx():
            last_old = self.db.one(
                "SELECT seq, hash FROM audit_log WHERE scope_id=? AND ts<? ORDER BY seq DESC LIMIT 1", (scope_id, cutoff_ts))
            if last_old is None:
                return 0
            self.db.run(
                "INSERT INTO audit_checkpoints(scope_id,up_to_seq,hash,ts) VALUES(?,?,?,?)"
                " ON CONFLICT(scope_id) DO UPDATE SET up_to_seq=excluded.up_to_seq, hash=excluded.hash, ts=excluded.ts",
                (scope_id, last_old["seq"], last_old["hash"], clock.now_ms()))
            return self.db.run("DELETE FROM audit_log WHERE scope_id=? AND seq<=?", (scope_id, last_old["seq"]))
