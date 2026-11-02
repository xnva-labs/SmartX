"""Enterprise work management: projects, tasks, comments, announcements, meetings."""
from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

from flask import Blueprint, g, jsonify, request

from .. import clock
from ..errors import ApiError, bad_request, forbidden, not_found
from ..orgs import get_policy
from ..security import auth, client_ip, require
from ..validate import F, validate
from .common import int_arg, json_body, sv
from .ent_admin import audit, division_ok
from .misc import notify

bp = Blueprint("ent_work", __name__, url_prefix="/v1/enterprise")
STATUSES = ("todo", "doing", "blocked", "done")
PRIORITIES = ("low", "normal", "high", "urgent")
_ID = dict(max=64)
_DATE_OR_EMPTY = r"(\d{4}-\d{2}-\d{2})?"


def _enc(table: str, rid: str, field: str, text: str) -> str:
    org = g.ident.org_id
    return sv().ekeys.seal(org, text, f"{table}.{org}.{rid}.{field}")


def _dec(table: str, rid: str, field: str, blob: Optional[str]) -> str:
    org = g.ident.org_id
    return sv().ekeys.open(org, blob, f"{table}.{org}.{rid}.{field}")


def _today() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def _valid_date(v: Optional[str]) -> Optional[str]:
    if not v:
        return None
    try:
        datetime.strptime(v, "%Y-%m-%d")
    except ValueError:
        raise ApiError(400, "validation_error", "due_date harus YYYY-MM-DD")
    return v


def _employee(org: str, eid: str, active_only: bool = True):
    r = sv().edb.one("SELECT id, division_id, status FROM employees WHERE id=? AND org_id=?", (eid, org))
    if r is None or (active_only and r["status"] != "active"):
        raise ApiError(400, "invalid_employee", "Karyawan tidak ditemukan atau tidak aktif")
    return r


def _project_row(org: str, pid: str):
    r = sv().edb.one("SELECT * FROM projects WHERE id=? AND org_id=?", (pid, org))
    if r is None:
        raise ApiError(400, "invalid_project", "Proyek tidak ditemukan di organisasi ini")
    return r


# ---------------------------------------------------------------- projects

def _project_out(r) -> Dict[str, Any]:
    return {"id": r["id"], "name": _dec("projects", r["id"], "name", r["name_enc"]), "description": _dec("projects", r["id"], "desc", r["description_enc"]),
            "status": r["status"], "owner_id": r["owner_id"], "division_id": r["division_id"], "due_date": r["due_date"],
            "created_at": r["created_at"], "updated_at": r["updated_at"]}


@bp.get("/projects")
@auth("enterprise", "projects.read")
def projects_list():
    st = request.args.get("status")
    sql, args = "SELECT * FROM projects WHERE org_id=?", [g.ident.org_id]
    if st:
        sql += " AND status=?"
        args.append(st)
    else:
        sql += " AND status<>'archived'"
    return jsonify({"items": [_project_out(r) for r in sv().edb.all(sql + " ORDER BY created_at DESC LIMIT 500", args)]})


@bp.post("/projects")
@auth("enterprise", "projects.manage")
def projects_create():
    i, s = g.ident, sv()
    d = validate(json_body(), {"name": F.str(max=200, min=1), "description": F.str(max=4000, req=False, default=""), "status": F.enum(("active", "paused", "done", "archived"), req=False, default="active"),
                               "owner_id": F.str(**_ID, req=False), "division_id": F.str(**_ID, req=False), "due_date": F.str(max=10, req=False, pattern=_DATE_OR_EMPTY)})
    if d.get("owner_id"):
        _employee(i.org_id, d["owner_id"])
    division_ok(i.org_id, d.get("division_id", ""))
    pid, now = uuid.uuid4().hex, clock.now_ms()
    with s.edb.tx():
        s.edb.run("INSERT INTO projects(id,org_id,name_enc,description_enc,status,owner_id,division_id,due_date,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                  (pid, i.org_id, _enc("projects", pid, "name", d["name"]), _enc("projects", pid, "desc", d["description"]), d["status"],
                   d.get("owner_id") or i.uid, d.get("division_id") or None, _valid_date(d.get("due_date")), now, now))
        audit("project.created", "project", pid)
    return jsonify(_project_out(s.edb.one("SELECT * FROM projects WHERE id=? AND org_id=?", (pid, i.org_id)))), 201


@bp.put("/projects/<pid>")
@auth("enterprise", "projects.manage")
def projects_update(pid: str):
    i, s = g.ident, sv()
    d = validate(json_body(), {"name": F.str(max=200, min=1, req=False), "description": F.str(max=4000, req=False), "status": F.enum(("active", "paused", "done", "archived"), req=False),
                               "owner_id": F.str(**_ID, req=False), "division_id": F.str(**_ID, req=False), "due_date": F.str(max=10, req=False, pattern=_DATE_OR_EMPTY)}, partial=True)
    with s.edb.tx():
        r = s.edb.one("SELECT * FROM projects WHERE id=? AND org_id=?", (pid, i.org_id))
        if r is None:
            raise not_found("Proyek")
        cur = _project_out(r)
        if d.get("owner_id"):
            _employee(i.org_id, d["owner_id"])
        if d.get("division_id"):
            division_ok(i.org_id, d["division_id"])
        new = {**cur, **d}
        s.edb.run("UPDATE projects SET name_enc=?, description_enc=?, status=?, owner_id=?, division_id=?, due_date=?, updated_at=? WHERE id=? AND org_id=?",
                  (_enc("projects", pid, "name", new["name"]), _enc("projects", pid, "desc", new["description"]), new["status"], new["owner_id"] or None,
                   new["division_id"] or None, _valid_date(new["due_date"]), clock.now_ms(), pid, i.org_id))
        audit("project.updated", "project", pid, {k: v for k, v in d.items() if k not in ("name", "description")})
        row = s.edb.one("SELECT * FROM projects WHERE id=? AND org_id=?", (pid, i.org_id))
    return jsonify(_project_out(row))


@bp.delete("/projects/<pid>")
@auth("enterprise", "projects.manage")
def projects_delete(pid: str):
    if not sv().edb.run("DELETE FROM projects WHERE id=? AND org_id=?", (pid, g.ident.org_id)):
        raise not_found("Proyek")
    audit("project.deleted", "project", pid)
    return "", 204


# ------------------------------------------------------------------- tasks

def _task_scope(i) -> Tuple[str, List[Any]]:
    if i.sees_all:
        return "1=1", []
    if i.role == "manager":
        return "(t.division_id=? OR t.assignee_id=? OR t.creator_id=?)", [i.division_id, i.uid, i.uid]
    return "(t.assignee_id=? OR t.creator_id=? OR t.division_id=?)", [i.uid, i.uid, i.division_id]


def _task_visible(i, r) -> bool:
    if i.sees_all:
        return True
    if r["assignee_id"] == i.uid or r["creator_id"] == i.uid:
        return True
    return r["division_id"] is not None and r["division_id"] == i.division_id


def _task_out(r) -> Dict[str, Any]:
    return {"id": r["id"], "title": _dec("tasks", r["id"], "title", r["title_enc"]), "description": _dec("tasks", r["id"], "desc", r["description_enc"]),
            "status": r["status"], "priority": r["priority"], "project_id": r["project_id"], "assignee_id": r["assignee_id"], "creator_id": r["creator_id"],
            "division_id": r["division_id"], "due_date": r["due_date"], "created_at": r["created_at"], "updated_at": r["updated_at"], "completed_at": r["completed_at"],
            "overdue": bool(r["due_date"] and r["due_date"] < _today() and r["status"] != "done")}


def _get_task(tid: str):
    r = sv().edb.one("SELECT * FROM tasks t WHERE t.id=? AND t.org_id=?", (tid, g.ident.org_id))
    if r is None or not _task_visible(g.ident, r):
        raise not_found("Tugas")
    return r


@bp.get("/tasks")
@auth("enterprise", "tasks.read")
def tasks_list():
    i = g.ident
    where, args = _task_scope(i)
    sql, params = f"SELECT t.* FROM tasks t WHERE t.org_id=? AND {where}", [i.org_id] + args
    for key, col in (("status", "t.status"), ("project", "t.project_id"), ("assignee", "t.assignee_id"), ("priority", "t.priority")):
        v = request.args.get(key)
        if v:
            sql += f" AND {col}=?"
            params.append(v)
    if request.args.get("mine") == "1":
        sql += " AND t.assignee_id=?"
        params.append(i.uid)
    if request.args.get("overdue") == "1":
        sql += " AND t.due_date IS NOT NULL AND t.due_date<? AND t.status<>'done'"
        params.append(_today())
    sql += " ORDER BY (t.status='done'), t.due_date IS NULL, t.due_date, t.created_at DESC LIMIT ?"
    params.append(int_arg("limit", 200, 1, 1000))
    return jsonify({"items": [_task_out(r) for r in sv().edb.all(sql, params)]})


@bp.post("/tasks")
@auth("enterprise", "tasks.create")
def tasks_create():
    i, s = g.ident, sv()
    d = validate(json_body(), {"title": F.str(max=200, min=1), "description": F.str(max=8000, req=False, default=""), "project_id": F.str(**_ID, req=False),
                               "assignee_id": F.str(**_ID, req=False), "priority": F.enum(PRIORITIES, req=False, default="normal"),
                               "due_date": F.str(max=10, req=False, pattern=_DATE_OR_EMPTY)})
    assignee = d.get("assignee_id") or (i.uid if i.role == "member" else None)
    if assignee and assignee != i.uid:
        require(i, "tasks.assign")
    division = i.division_id
    if assignee:
        division = _employee(i.org_id, assignee)["division_id"]
    project = _project_row(i.org_id, d["project_id"]) if d.get("project_id") else None
    if project is not None and not division:
        division = project["division_id"]
    tid, now = uuid.uuid4().hex, clock.now_ms()
    with s.edb.tx():
        s.edb.run("INSERT INTO tasks(id,org_id,project_id,title_enc,description_enc,status,priority,assignee_id,creator_id,division_id,due_date,created_at,updated_at)"
                  " VALUES(?,?,?,?,?,'todo',?,?,?,?,?,?,?)",
                  (tid, i.org_id, d.get("project_id") or None, _enc("tasks", tid, "title", d["title"]), _enc("tasks", tid, "desc", d["description"]),
                   d["priority"], assignee, i.uid, division, _valid_date(d.get("due_date")), now, now))
        audit("task.created", "task", tid, {"assignee_id": assignee})
    if assignee and assignee != i.uid:
        notify("enterprise", i.org_id, assignee, "task.assigned", "Tugas baru untuk Anda", "Anda ditugaskan pada sebuah tugas.", "task", tid)
    return jsonify(_task_out(s.edb.one("SELECT * FROM tasks WHERE id=? AND org_id=?", (tid, i.org_id)))), 201


@bp.get("/tasks/<tid>")
@auth("enterprise", "tasks.read")
def tasks_get(tid: str):
    return jsonify(_task_out(_get_task(tid)))


@bp.put("/tasks/<tid>")
@auth("enterprise", "tasks.read")
def tasks_update(tid: str):
    i, s = g.ident, sv()
    d = validate(json_body(), {"title": F.str(max=200, min=1, req=False), "description": F.str(max=8000, req=False), "project_id": F.str(**_ID, req=False),
                               "assignee_id": F.str(**_ID, req=False), "priority": F.enum(PRIORITIES, req=False), "due_date": F.str(max=10, req=False, pattern=_DATE_OR_EMPTY)}, partial=True)
    notify_to = None
    with s.edb.tx():
        r = _get_task(tid)
        if not (i.can("tasks.assign") or r["creator_id"] == i.uid):
            raise forbidden("Hanya pembuat tugas atau manajer yang dapat mengubah tugas ini")
        cur = _task_out(r)
        new = {**cur, **d}
        division = r["division_id"]
        if "assignee_id" in d and (d["assignee_id"] or None) != r["assignee_id"]:
            require(i, "tasks.assign")
            if d["assignee_id"]:
                division = _employee(i.org_id, d["assignee_id"])["division_id"]
                notify_to = d["assignee_id"]
        if d.get("project_id"):
            _project_row(i.org_id, d["project_id"])
        s.edb.run("UPDATE tasks SET title_enc=?, description_enc=?, project_id=?, assignee_id=?, priority=?, due_date=?, division_id=?, updated_at=? WHERE id=? AND org_id=?",
                  (_enc("tasks", tid, "title", new["title"]), _enc("tasks", tid, "desc", new["description"]), new["project_id"] or None, new["assignee_id"] or None,
                   new["priority"], _valid_date(new["due_date"]), division, clock.now_ms(), tid, i.org_id))
        audit("task.updated", "task", tid, {k: v for k, v in d.items() if k not in ("title", "description")})
        row = s.edb.one("SELECT * FROM tasks WHERE id=? AND org_id=?", (tid, i.org_id))
    if notify_to and notify_to != i.uid:
        notify("enterprise", i.org_id, notify_to, "task.assigned", "Tugas baru untuk Anda", "Anda ditugaskan pada sebuah tugas.", "task", tid)
    return jsonify(_task_out(row))


@bp.post("/tasks/<tid>/status")
@auth("enterprise", "tasks.read")
def tasks_status(tid: str):
    i, s = g.ident, sv()
    d = validate(json_body(), {"status": F.enum(STATUSES)})
    with s.edb.tx():
        r = _get_task(tid)
        if not (r["assignee_id"] == i.uid or r["creator_id"] == i.uid or i.can("tasks.assign")):
            raise forbidden("Hanya penerima tugas, pembuat, atau manajer yang dapat mengubah status")
        now = clock.now_ms()
        s.edb.run("UPDATE tasks SET status=?, updated_at=?, completed_at=? WHERE id=? AND org_id=?",
                  (d["status"], now, now if d["status"] == "done" else None, tid, i.org_id))
        audit("task.status_changed", "task", tid, {"from": r["status"], "to": d["status"]})
        row = s.edb.one("SELECT * FROM tasks WHERE id=? AND org_id=?", (tid, i.org_id))
    if d["status"] == "done" and r["creator_id"] and r["creator_id"] != i.uid:
        notify("enterprise", i.org_id, r["creator_id"], "task.done", "Tugas selesai", "Sebuah tugas yang Anda buat telah diselesaikan.", "task", tid)
    return jsonify(_task_out(row))


@bp.delete("/tasks/<tid>")
@auth("enterprise", "tasks.read")
def tasks_delete(tid: str):
    i = g.ident
    r = _get_task(tid)
    if not (i.can("tasks.assign") or r["creator_id"] == i.uid):
        raise forbidden("Hanya pembuat tugas atau manajer yang dapat menghapus tugas ini")
    sv().edb.run("DELETE FROM tasks WHERE id=? AND org_id=?", (tid, i.org_id))
    audit("task.deleted", "task", tid)
    return "", 204


@bp.get("/tasks/<tid>/comments")
@auth("enterprise", "tasks.read")
def comments_list(tid: str):
    _get_task(tid)
    rows = sv().edb.all("SELECT c.*, e.display_name FROM task_comments c LEFT JOIN employees e ON e.id=c.author_id WHERE c.task_id=? AND c.org_id=? ORDER BY c.created_at",
                        (tid, g.ident.org_id))
    return jsonify({"items": [{"id": r["id"], "author_id": r["author_id"], "author_name": r["display_name"], "body": _dec("task_comments", r["id"], "body", r["body_enc"]),
                               "created_at": r["created_at"]} for r in rows]})


@bp.post("/tasks/<tid>/comments")
@auth("enterprise", "tasks.read")
def comments_create(tid: str):
    i, s = g.ident, sv()
    r = _get_task(tid)
    d = validate(json_body(), {"body": F.str(max=4000, min=1)})
    cid = uuid.uuid4().hex
    s.edb.run("INSERT INTO task_comments(id,org_id,task_id,author_id,body_enc,created_at) VALUES(?,?,?,?,?,?)",
              (cid, i.org_id, tid, i.uid, _enc("task_comments", cid, "body", d["body"]), clock.now_ms()))
    for target in {r["assignee_id"], r["creator_id"]} - {None, i.uid}:
        notify("enterprise", i.org_id, target, "task.comment", "Komentar baru", "Ada komentar baru pada sebuah tugas.", "task", tid)
    return jsonify({"id": cid}), 201


# ----------------------------------------------------------- announcements

def _ann_out(r) -> Dict[str, Any]:
    return {"id": r["id"], "title": _dec("announcements", r["id"], "title", r["title_enc"]), "body": _dec("announcements", r["id"], "body", r["body_enc"]),
            "author_id": r["author_id"], "division_id": r["division_id"], "pinned": bool(r["pinned"]), "created_at": r["created_at"], "expires_at": r["expires_at"]}


@bp.get("/announcements")
@auth("enterprise", "announcements.read")
def announcements_list():
    i = g.ident
    sql, args = "SELECT * FROM announcements WHERE org_id=? AND (expires_at IS NULL OR expires_at>?)", [i.org_id, clock.now_ms()]
    if not i.sees_all:
        sql += " AND (division_id IS NULL OR division_id=?)"
        args.append(i.division_id)
    return jsonify({"items": [_ann_out(r) for r in sv().edb.all(sql + " ORDER BY pinned DESC, created_at DESC LIMIT 200", args)]})


@bp.post("/announcements")
@auth("enterprise", "announcements.manage")
def announcements_create():
    i, s = g.ident, sv()
    d = validate(json_body(), {"title": F.str(max=200, min=1), "body": F.str(max=8000, min=1), "division_id": F.str(**_ID, req=False),
                               "pinned": F.bool(req=False, default=False), "expires_in_days": F.int(min=1, max=365, req=False)})
    division_ok(i.org_id, d.get("division_id", ""))
    aid, now = uuid.uuid4().hex, clock.now_ms()
    with s.edb.tx():
        s.edb.run("INSERT INTO announcements(id,org_id,title_enc,body_enc,author_id,division_id,pinned,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",
                  (aid, i.org_id, _enc("announcements", aid, "title", d["title"]), _enc("announcements", aid, "body", d["body"]), i.uid, d.get("division_id") or None,
                   int(d["pinned"]), now, now + d["expires_in_days"] * clock.DAY_MS if d.get("expires_in_days") else None))
        q, a = "SELECT id FROM employees WHERE org_id=? AND status='active' AND id<>?", [i.org_id, i.uid]
        if d.get("division_id"):
            q += " AND division_id=?"
            a.append(d["division_id"])
        for r in s.edb.all(q, a):
            notify("enterprise", i.org_id, r["id"], "announcement", "Pengumuman baru", "Ada pengumuman baru dari organisasi Anda.", "announcement", aid)
        audit("announcement.created", "announcement", aid)
    return jsonify(_ann_out(s.edb.one("SELECT * FROM announcements WHERE id=? AND org_id=?", (aid, i.org_id)))), 201


@bp.delete("/announcements/<aid>")
@auth("enterprise", "announcements.manage")
def announcements_delete(aid: str):
    if not sv().edb.run("DELETE FROM announcements WHERE id=? AND org_id=?", (aid, g.ident.org_id)):
        raise not_found("Pengumuman")
    audit("announcement.deleted", "announcement", aid)
    return "", 204


# ---------------------------------------------------------------- meetings

def _meeting_out(r, full: bool = True) -> Dict[str, Any]:
    d = {"id": r["id"], "title": _dec("meetings", r["id"], "title", r["title_enc"]), "held_at": r["held_at"], "summary_status": r["summary_status"],
         "division_id": r["division_id"], "created_by": r["created_by"], "created_at": r["created_at"]}
    if full:
        d["notes"] = _dec("meetings", r["id"], "notes", r["notes_enc"])
        d["summary"] = _dec("meetings", r["id"], "summary", r["summary_enc"]) if r["summary_enc"] else ""
        d["action_items"] = json.loads(_dec("meetings", r["id"], "actions", r["actions_enc"])) if r["actions_enc"] else []
    return d


def _meeting_visible(i, r) -> bool:
    return i.sees_all or r["created_by"] == i.uid or (r["division_id"] is not None and r["division_id"] == i.division_id)


def _get_meeting(mid: str):
    r = sv().edb.one("SELECT * FROM meetings WHERE id=? AND org_id=?", (mid, g.ident.org_id))
    if r is None or not _meeting_visible(g.ident, r):
        raise not_found("Rapat")
    return r


def _summarize(mid: str, notes: str) -> str:
    """Runs the AI summary. Returns the resulting status; never raises for AI problems."""
    i, s = g.ident, sv()
    pol = get_policy(s, i.org_id)
    if not s.settings.xnai.chat_ready or not pol["ai_enabled"] or not i.can("ai.use"):
        return "unavailable"
    try:
        out = s.xnai.summarize_meeting(i, notes)
    except ApiError:
        return "failed"
    s.edb.run("UPDATE meetings SET summary_enc=?, actions_enc=?, summary_status='done' WHERE id=? AND org_id=?",
              (_enc("meetings", mid, "summary", out["summary"] + ("\n\nKeputusan:\n- " + "\n- ".join(out["decisions"]) if out["decisions"] else "")),
               _enc("meetings", mid, "actions", json.dumps(out["action_items"], ensure_ascii=False)), mid, i.org_id))
    return "done"


@bp.post("/meetings")
@auth("enterprise", "meetings.manage")
def meetings_create():
    i, s = g.ident, sv()
    d = validate(json_body(), {"title": F.str(max=200, min=1), "notes": F.str(max=60000, min=1), "held_at": F.int(min=0, req=False), "summarize": F.bool(req=False, default=True)})
    mid, now = uuid.uuid4().hex, clock.now_ms()
    s.edb.run("INSERT INTO meetings(id,org_id,title_enc,held_at,notes_enc,summary_status,division_id,created_by,created_at) VALUES(?,?,?,?,?,'none',?,?,?)",
              (mid, i.org_id, _enc("meetings", mid, "title", d["title"]), d.get("held_at", now), _enc("meetings", mid, "notes", d["notes"]), i.division_id, i.uid, now))
    status = _summarize(mid, d["notes"]) if d["summarize"] else "none"
    s.edb.run("UPDATE meetings SET summary_status=? WHERE id=? AND org_id=?", (status, mid, i.org_id))
    audit("meeting.created", "meeting", mid, {"summary_status": status})
    return jsonify(_meeting_out(s.edb.one("SELECT * FROM meetings WHERE id=? AND org_id=?", (mid, i.org_id)))), 201


@bp.get("/meetings")
@auth("enterprise", "meetings.manage")
def meetings_list():
    i = g.ident
    rows = sv().edb.all("SELECT * FROM meetings WHERE org_id=? ORDER BY held_at DESC LIMIT 300", (i.org_id,))
    return jsonify({"items": [_meeting_out(r, False) for r in rows if _meeting_visible(i, r)]})


@bp.get("/meetings/<mid>")
@auth("enterprise", "meetings.manage")
def meetings_get(mid: str):
    return jsonify(_meeting_out(_get_meeting(mid)))


@bp.post("/meetings/<mid>/summarize")
@auth("enterprise", "meetings.manage")
def meetings_summarize(mid: str):
    r = _get_meeting(mid)
    status = _summarize(mid, _dec("meetings", mid, "notes", r["notes_enc"]))
    sv().edb.run("UPDATE meetings SET summary_status=? WHERE id=? AND org_id=?", (status, mid, g.ident.org_id))
    audit("meeting.summarized", "meeting", mid, {"summary_status": status})
    return jsonify(_meeting_out(_get_meeting(mid)))


@bp.post("/meetings/<mid>/create-tasks")
@auth("enterprise", "tasks.assign")
def meetings_to_tasks(mid: str):
    i, s = g.ident, sv()
    r = _get_meeting(mid)
    d = validate(json_body(), {"indices": F.list(F.int(min=0, max=1000), max=30, req=False)})
    items = json.loads(_dec("meetings", mid, "actions", r["actions_enc"])) if r["actions_enc"] else []
    idx = d.get("indices") or list(range(len(items)))
    title = _dec("meetings", mid, "title", r["title_enc"])
    created = []
    with s.edb.tx():
        for n in idx:
            if n >= len(items):
                raise bad_request("invalid_index", f"Indeks {n} di luar daftar action item")
            tid, now = uuid.uuid4().hex, clock.now_ms()
            s.edb.run("INSERT INTO tasks(id,org_id,title_enc,description_enc,status,priority,creator_id,division_id,created_at,updated_at) VALUES(?,?,?,?,'todo','normal',?,?,?,?)",
                      (tid, i.org_id, _enc("tasks", tid, "title", str(items[n].get("title", ""))[:200]),
                       _enc("tasks", tid, "desc", f"Dari rapat: {title}. Penanggung jawab (saran): {items[n].get('assignee_hint', '-')}. Tenggat (saran): {items[n].get('due_hint', '-')}."),
                       i.uid, i.division_id, now, now))
            created.append(tid)
        audit("meeting.tasks_created", "meeting", mid, {"count": len(created)})
    return jsonify({"task_ids": created}), 201


@bp.delete("/meetings/<mid>")
@auth("enterprise", "meetings.manage")
def meetings_delete(mid: str):
    r = _get_meeting(mid)
    if r["created_by"] != g.ident.uid and not g.ident.can("employees.manage"):
        raise forbidden("Hanya pembuat rapat atau admin yang dapat menghapus")
    sv().edb.run("DELETE FROM meetings WHERE id=? AND org_id=?", (mid, g.ident.org_id))
    audit("meeting.deleted", "meeting", mid)
    return "", 204
