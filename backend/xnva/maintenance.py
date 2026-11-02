"""Retention and housekeeping. Run periodically (see docs/OPERATIONS.md)."""
from __future__ import annotations

from typing import Any, Dict, Optional

from . import clock
from .orgs import get_policy

DAY = clock.DAY_MS


def run(sv, now: Optional[int] = None) -> Dict[str, Any]:
    now = clock.now_ms() if now is None else now
    out: Dict[str, Any] = {}
    p, e = sv.pdb, sv.edb
    out["personal_sessions"] = p.run("DELETE FROM sessions WHERE expires_at<? OR (revoked_at IS NOT NULL AND revoked_at<?)", (now, now - 7 * DAY))
    out["personal_tombstones"] = (p.run("DELETE FROM memory_entries WHERE deleted_at IS NOT NULL AND deleted_at<?", (now - 30 * DAY,))
                                  + p.run("DELETE FROM reminders WHERE deleted_at IS NOT NULL AND deleted_at<?", (now - 30 * DAY,)))
    eff = "CASE WHEN (SELECT retention_days FROM privacy WHERE user_id={t}.user_id)>0 THEN (SELECT retention_days FROM privacy WHERE user_id={t}.user_id) ELSE 90 END"
    out["personal_activity"] = p.run(f"DELETE FROM ai_activity WHERE ts < ? - ({eff.format(t='ai_activity')})*{DAY}", (now,))
    out["personal_notifications"] = p.run(f"DELETE FROM notifications WHERE created_at < ? - ({eff.format(t='notifications')})*{DAY}", (now,))
    purged = 0
    for row in p.all("SELECT DISTINCT scope_id FROM audit_log"):
        purged += sv.paudit.purge_before(row["scope_id"], now - 365 * DAY)  # personal security events: 365 days, chain kept verifiable
    out["personal_security_events"] = purged
    out["enterprise_sessions"] = e.run("DELETE FROM sessions WHERE expires_at<? OR (revoked_at IS NOT NULL AND revoked_at<?)", (now, now - 7 * DAY))
    out["enterprise_invites"] = e.run("DELETE FROM invites WHERE (used_at IS NOT NULL AND used_at<?) OR expires_at<?", (now - 30 * DAY, now - 30 * DAY))
    out["enterprise_ai_usage"] = e.run("DELETE FROM ai_usage WHERE day < date(?/1000, 'unixepoch', '-400 day')", (now,))
    counts = {"notifications": 0, "meetings": 0, "audit": 0}
    for org in e.all("SELECT id FROM orgs"):
        pol = get_policy(sv, org["id"])
        counts["notifications"] += e.run("DELETE FROM notifications WHERE org_id=? AND created_at<?", (org["id"], now - pol["notification_retention_days"] * DAY))
        if pol["meeting_retention_days"] > 0:
            counts["meetings"] += e.run("DELETE FROM meetings WHERE org_id=? AND created_at<?", (org["id"], now - pol["meeting_retention_days"] * DAY))
        counts["audit"] += sv.eaudit.purge_before(org["id"], now - pol["audit_retention_days"] * DAY)
    out["enterprise_retention"] = counts
    return out
