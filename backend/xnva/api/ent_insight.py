"""Enterprise insight: knowledge base, analytics, reports, audit, transparency."""
from __future__ import annotations

import base64
import binascii
import json
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone
from typing import Any, Dict, List

from flask import Blueprint, Response, g, jsonify, request

from .. import clock
from ..errors import ApiError, bad_request, not_found
from ..security import auth, client_ip, limit, require
from ..validate import F, validate
from .common import int_arg, json_body, sv
from .ent_admin import audit

bp = Blueprint("ent_insight", __name__, url_prefix="/v1/enterprise")

TRANSPARENCY = {
    "collected": [
        "Data tugas yang Anda atau tim Anda masukkan: judul, status, prioritas, tenggat, penanggung jawab.",
        "Jumlah permintaan AI per divisi per hari (tanpa isi pertanyaan dan tanpa nama pengguna).",
        "Catatan audit tindakan administratif dan keamanan (siapa mengubah apa, kapan).",
        "Status perangkat yang Anda daftarkan: nama, versi firmware, level baterai, waktu terakhir terhubung.",
    ],
    "not_collected": [
        "Isi percakapan dengan XNAI per karyawan, dan gambar dari kamera kacamata (tidak disimpan di server).",
        "Lokasi, penekanan tombol, tangkapan layar, atau aktivitas di luar aplikasi.",
        "Isi notifikasi atau pesan pribadi dari aplikasi lain di ponsel Anda.",
        "Metrik produktivitas tersembunyi per individu: analitik hanya agregat tim/divisi, kecuali beban tugas yang terlihat oleh manajer.",
    ],
    "who_can_see": {
        "member": "Tugas yang ditugaskan kepada atau dibuat oleh Anda, serta tugas di divisi Anda.",
        "manager": "Tugas dan analitik divisinya, termasuk jumlah tugas terbuka per anggota.",
        "admin_owner": "Seluruh data organisasi dan catatan audit.",
        "auditor": "Catatan audit dan analitik agregat; tanpa isi tugas.",
    },
}
_NO_CONTENT_ROLES = ("auditor",)


# ------------------------------------------------------------------- KB

@bp.get("/kb/documents")
@auth("enterprise", "kb.read")
def kb_list():
    return jsonify({"items": sv().xnai.kb.list(g.ident)})


@bp.post("/kb/documents")
@auth("enterprise", "kb.manage")
@limit("kb-write", 60, 3600, per="user")
def kb_add():
    d = validate(json_body(), {"title": F.str(max=200, min=1), "text": F.str(max=400_000, min=10), "visibility": F.enum(("org", "division", "managers", "admins"), req=False, default="org"),
                               "division_id": F.str(max=64, req=False, default=""), "source": F.str(max=200, req=False, default="")})
    doc = sv().xnai.kb.add(g.ident, d["title"], d["text"], d["visibility"], d["division_id"], d["source"])
    audit("kb.document_added", "kb_document", doc["id"], {"visibility": d["visibility"], "chunks": doc["chunks"]})
    return jsonify(doc), 201


@bp.post("/kb/documents/from-image")
@auth("enterprise", "kb.manage")
@limit("kb-write", 60, 3600, per="user")
def kb_add_image():
    require(g.ident, "ai.use")
    d = validate(json_body(), {"title": F.str(max=200, min=1), "imageBase64": F.str(max=9_000_000, min=100), "visibility": F.enum(("org", "division", "managers", "admins"), req=False, default="org"),
                               "division_id": F.str(max=64, req=False, default="")})
    try:
        raw = base64.b64decode(d["imageBase64"], validate=True)
    except (binascii.Error, ValueError):
        raise bad_request("invalid_base64", "imageBase64 bukan base64 yang valid")
    out = sv().xnai.vision(g.ident, raw, "read", [], "")
    doc = sv().xnai.kb.add(g.ident, d["title"], out["description"], d["visibility"], d["division_id"], "ocr:image")
    audit("kb.document_added", "kb_document", doc["id"], {"visibility": d["visibility"], "chunks": doc["chunks"], "ocr": True})
    return jsonify({**doc, "ocr_text": out["description"]}), 201


@bp.delete("/kb/documents/<doc_id>")
@auth("enterprise", "kb.manage")
def kb_delete(doc_id: str):
    sv().xnai.kb.delete(g.ident.org_id, doc_id)
    audit("kb.document_deleted", "kb_document", doc_id)
    return "", 204


@bp.get("/kb/search")
@auth("enterprise", "kb.read")
def kb_search():
    q = (request.args.get("q") or "").strip()
    if not 2 <= len(q) <= 300:
        raise bad_request("invalid_query", "Parameter q harus 2-300 karakter")
    return jsonify({"items": sv().xnai.kb.search(g.ident, q, k=int_arg("k", 5, 1, 10))})


@bp.post("/kb/ask")
@auth("enterprise", "ai.use")
@limit("xnai", 30, 60, per="user")
def kb_ask():
    d = validate(json_body(), {"question": F.str(max=2000, min=3)})
    out = sv().xnai.chat(g.ident, d["question"], [], "HIGH", "", True)
    return jsonify({"answer": out["reply"], "citations": out["citations"], "model": out["model"]})


# ------------------------------------------------------------- analytics

def _scope_rows(i, project_id: str = ""):
    where, args = "t.org_id=?", [i.org_id]
    if i.role == "manager":
        where += " AND t.division_id=?"
        args.append(i.division_id)
    if project_id:
        where += " AND t.project_id=?"
        args.append(project_id)
    return sv().edb.all("SELECT t.status,t.priority,t.due_date,t.created_at,t.completed_at,t.division_id,t.assignee_id FROM tasks t"
                        + f" WHERE {where} LIMIT 50000", args)


def compute_metrics(rows, days: int) -> Dict[str, Any]:
    now = clock.now_ms()
    since = now - days * clock.DAY_MS
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    by_status, by_prio, by_div = Counter(), Counter(), defaultdict(lambda: Counter())
    load, cycle, on_time, with_due, overdue, done_range, created_range = Counter(), [], 0, 0, 0, 0, 0
    for r in rows:
        by_status[r["status"]] += 1
        key = r["division_id"] or "-"
        if r["created_at"] >= since:
            created_range += 1
        if r["status"] != "done":
            by_prio[r["priority"]] += 1
            by_div[key]["open"] += 1
            if r["assignee_id"]:
                load[r["assignee_id"]] += 1
            if r["due_date"] and r["due_date"] < today:
                overdue += 1
                by_div[key]["overdue"] += 1
        elif r["completed_at"] and r["completed_at"] >= since:
            done_range += 1
            by_div[key]["done_in_range"] += 1
            cycle.append((r["completed_at"] - r["created_at"]) / clock.DAY_MS)
            if r["due_date"]:
                with_due += 1
                if datetime.fromtimestamp(r["completed_at"] / 1000, timezone.utc).strftime("%Y-%m-%d") <= r["due_date"]:
                    on_time += 1
    open_now = sum(v for k, v in by_status.items() if k != "done")
    return {"range_days": days, "total_tasks": len(rows), "by_status": dict(by_status), "open_by_priority": dict(by_prio),
            "open_now": open_now, "overdue_now": overdue, "created_in_range": created_range, "completed_in_range": done_range,
            "completion_rate": round(done_range / max(1, done_range + open_now), 3),
            "on_time_rate": round(on_time / with_due, 3) if with_due else None,
            "avg_cycle_days": round(sum(cycle) / len(cycle), 2) if cycle else None,
            "by_division": {k: dict(v) for k, v in by_div.items()}, "_load": dict(load)}


def _ai_usage(i, days: int) -> Dict[str, Any]:
    since = datetime.fromtimestamp((clock.now_ms() - days * clock.DAY_MS) / 1000, timezone.utc).strftime("%Y-%m-%d")
    rows = sv().edb.all("SELECT division_id,kind,SUM(requests) r,SUM(tokens_in) ti,SUM(tokens_out) to_ FROM ai_usage WHERE org_id=? AND day>=?"
                        + (" AND division_id=?" if i.role == "manager" else "") + " GROUP BY division_id,kind",
                        [i.org_id, since] + ([i.division_id or ""] if i.role == "manager" else []))
    by_kind, by_div = Counter(), Counter()
    for r in rows:
        by_kind[r["kind"]] += r["r"]
        by_div[r["division_id"] or "-"] += r["r"]
    return {"requests": sum(by_kind.values()), "by_kind": dict(by_kind), "by_division": dict(by_div),
            "tokens_in": sum(r["ti"] for r in rows), "tokens_out": sum(r["to_"] for r in rows)}


@bp.get("/analytics/overview")
@auth("enterprise", "analytics.read")
def analytics_overview():
    i, s = g.ident, sv()
    days = int_arg("days", 30, 1, 365)
    m = compute_metrics(_scope_rows(i), days)
    load = m.pop("_load")
    if i.can("tasks.assign"):
        names = {r["id"]: r["display_name"] for r in s.edb.all("SELECT id,display_name FROM employees WHERE org_id=?", (i.org_id,))}
        m["workload"] = sorted(({"employee_id": k, "name": names.get(k, "?"), "open": v} for k, v in load.items()), key=lambda x: -x["open"])[:50]
    m["division_names"] = {r["id"]: r["name"] for r in s.edb.all("SELECT id,name FROM divisions WHERE org_id=?", (i.org_id,))}
    m["ai_usage"] = _ai_usage(i, days)
    cutoff = clock.now_ms() - 7 * clock.DAY_MS
    m["devices"] = {"registered": s.edb.scalar("SELECT COUNT(*) FROM devices WHERE org_id=? AND revoked_at IS NULL", (i.org_id,), 0),
                    "seen_last_7d": s.edb.scalar("SELECT COUNT(*) FROM devices WHERE org_id=? AND revoked_at IS NULL AND last_seen_at>?", (i.org_id, cutoff), 0)}
    return jsonify(m)


@bp.get("/analytics/me")
@auth("enterprise", "tasks.read")
def analytics_me():
    i = g.ident
    rows = sv().edb.all("SELECT status,priority,due_date,created_at,completed_at,division_id,assignee_id FROM tasks WHERE org_id=? AND assignee_id=?", (i.org_id, i.uid))
    m = compute_metrics(rows, int_arg("days", 30, 1, 365))
    m.pop("_load")
    m.pop("by_division")
    return jsonify(m)


@bp.get("/transparency")
@auth("enterprise")
def transparency():
    return jsonify(TRANSPARENCY)


# --------------------------------------------------------------- reports

def _report_out(r) -> Dict[str, Any]:
    org = g.ident.org_id
    return {"id": r["id"], "kind": r["kind"], "params": json.loads(r["params"]), "metrics": json.loads(r["metrics"]),
            "narrative": sv().ekeys.open(org, r["narrative_enc"], f"reports.{org}.{r['id']}.narrative") if r["narrative_enc"] else "",
            "created_by": r["created_by"], "created_at": r["created_at"]}


@bp.post("/reports")
@auth("enterprise", "reports.create")
def report_create():
    i, s = g.ident, sv()
    d = validate(json_body(), {"kind": F.enum(("weekly", "project", "custom")), "project_id": F.str(max=64, req=False, default=""), "period_days": F.int(min=1, max=365, req=False),
                               "narrative": F.bool(req=False, default=False)})
    days = d.get("period_days") or (7 if d["kind"] == "weekly" else 30)
    if d["kind"] == "project":
        if not d["project_id"] or not s.edb.one("SELECT 1 FROM projects WHERE id=? AND org_id=?", (d["project_id"], i.org_id)):
            raise bad_request("invalid_project", "Laporan proyek memerlukan project_id yang valid")
    metrics = compute_metrics(_scope_rows(i, d["project_id"] if d["kind"] == "project" else ""), days)
    metrics.pop("_load")
    narrative = ""
    if d["narrative"]:
        narrative = s.xnai.narrate(i, {k: v for k, v in metrics.items() if k != "by_division"})
    rid, now = uuid.uuid4().hex, clock.now_ms()
    s.edb.run("INSERT INTO reports(id,org_id,kind,params,metrics,narrative_enc,created_by,created_at) VALUES(?,?,?,?,?,?,?,?)",
              (rid, i.org_id, d["kind"], json.dumps({"period_days": days, "project_id": d["project_id"] or None}), json.dumps(metrics),
               s.ekeys.seal(i.org_id, narrative, f"reports.{i.org_id}.{rid}.narrative") if narrative else None, i.uid, now))
    audit("report.created", "report", rid, {"kind": d["kind"]})
    return jsonify(_report_out(s.edb.one("SELECT * FROM reports WHERE id=? AND org_id=?", (rid, i.org_id)))), 201


@bp.get("/reports")
@auth("enterprise", "reports.create")
def report_list():
    rows = sv().edb.all("SELECT * FROM reports WHERE org_id=? ORDER BY created_at DESC LIMIT 100", (g.ident.org_id,))
    return jsonify({"items": [_report_out(r) for r in rows]})


@bp.get("/reports/<rid>")
@auth("enterprise", "reports.create")
def report_get(rid: str):
    r = sv().edb.one("SELECT * FROM reports WHERE id=? AND org_id=?", (rid, g.ident.org_id))
    if r is None:
        raise not_found("Laporan")
    return jsonify(_report_out(r))


# ------------------------------------------------------------------ audit

@bp.get("/audit")
@auth("enterprise", "audit.read")
def audit_list():
    i, s = g.ident, sv()
    before = request.args.get("before")
    items = s.eaudit.query(i.org_id, int_arg("limit", 100, 1, 500), int(before) if before and before.isdigit() else None, request.args.get("action"))
    names = {r["id"]: r["display_name"] for r in s.edb.all("SELECT id,display_name FROM employees WHERE org_id=?", (i.org_id,))}
    for it in items:
        it["actor_name"] = names.get(it["actor_id"])
    return jsonify({"items": items})


@bp.get("/audit/verify")
@auth("enterprise", "audit.read")
def audit_verify():
    res = sv().eaudit.verify(g.ident.org_id)
    audit("audit.verified", meta={"ok": res["ok"]})
    return jsonify(res)


@bp.get("/audit/export")
@auth("enterprise", "audit.read")
@limit("audit-export", 5, 3600, per="user")
def audit_export():
    i, s = g.ident, sv()
    audit("audit.exported")

    def gen():
        before = None
        while True:
            page = s.eaudit.query(i.org_id, 500, before)
            if not page:
                break
            for it in page:
                yield json.dumps(it, ensure_ascii=False) + "\n"
            before = page[-1]["seq"]
    return Response(gen(), mimetype="application/x-ndjson", headers={"Content-Disposition": "attachment; filename=audit.ndjson"})
