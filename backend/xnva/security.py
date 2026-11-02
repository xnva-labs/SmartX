"""Authentication (JWT + server-side sessions), RBAC, rate limiting."""
from __future__ import annotations

import functools
import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass
from typing import Callable, Deque, Dict, Optional

import jwt
from flask import current_app, g, request

from . import clock
from .errors import ApiError, forbidden, too_many, unauthorized

ISS = "xnva-smarteyex"
AUD = {"personal": "smarteyex-personal", "enterprise": "smarteyex-enterprise"}

ROLES = ("owner", "admin", "manager", "member", "auditor")
_ALL = {"owner", "admin", "manager", "member", "auditor"}
PERMISSIONS: Dict[str, set] = {
    "org.manage": {"owner"},
    "employees.manage": {"owner", "admin"},
    "employees.read": _ALL,
    "divisions.manage": {"owner", "admin"},
    "projects.manage": {"owner", "admin", "manager"},
    "projects.read": _ALL,
    "tasks.create": {"owner", "admin", "manager", "member"},
    "tasks.assign": {"owner", "admin", "manager"},
    "tasks.read": _ALL,
    "kb.manage": {"owner", "admin", "manager"},
    "kb.read": _ALL,
    "announcements.manage": {"owner", "admin", "manager"},
    "announcements.read": _ALL,
    "meetings.manage": {"owner", "admin", "manager", "member"},
    "analytics.read": {"owner", "admin", "manager", "auditor"},
    "reports.create": {"owner", "admin", "manager"},
    "audit.read": {"owner", "admin", "auditor"},
    "security.manage": {"owner", "admin"},
    "devices.manage": {"owner", "admin"},
    "policy.manage": {"owner", "admin"},
    "ai.use": {"owner", "admin", "manager", "member"},
}
ROLE_PERMS: Dict[str, set] = {r: {p for p, roles in PERMISSIONS.items() if r in roles} for r in ROLES}


@dataclass
class Identity:
    realm: str
    uid: str
    sid: str
    email: str = ""
    name: str = ""
    org_id: Optional[str] = None
    org_slug: Optional[str] = None
    org_name: Optional[str] = None
    role: Optional[str] = None
    division_id: Optional[str] = None

    @property
    def scope_id(self) -> str:
        return self.org_id if self.realm == "enterprise" else self.uid

    def can(self, perm: str) -> bool:
        return self.realm == "enterprise" and self.role in ROLE_PERMS and perm in ROLE_PERMS[self.role]

    @property
    def sees_all(self) -> bool:
        return self.role in ("owner", "admin", "auditor")


def issue_access(settings, realm: str, uid: str, sid: str, org_id: Optional[str] = None) -> str:
    now = int(time.time())
    claims = {"iss": ISS, "aud": AUD[realm], "sub": uid, "sid": sid, "realm": realm, "iat": now, "exp": now + settings.access_ttl_s}
    if org_id:
        claims["org"] = org_id
    return jwt.encode(claims, settings.jwt_secret, algorithm="HS256")


def _services():
    return current_app.extensions["xnva"]


def authenticate(realm: str = "any") -> Identity:
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer ") or len(header) < 20:
        raise unauthorized()
    sv = _services()
    try:
        claims = jwt.decode(header[7:], sv.settings.jwt_secret, algorithms=["HS256"], issuer=ISS,
                            audience=list(AUD.values()), options={"require": ["exp", "iat", "sub", "sid", "aud", "iss"]})
    except jwt.ExpiredSignatureError:
        raise unauthorized("Token kedaluwarsa", "token_expired")
    except jwt.InvalidTokenError:
        raise unauthorized("Token tidak valid", "invalid_token")
    got = claims.get("realm")
    if got not in AUD or claims.get("aud") != AUD[got]:
        raise unauthorized("Token tidak valid", "invalid_token")
    if realm != "any" and got != realm:
        raise ApiError(403, "wrong_realm", f"Endpoint ini hanya untuk akun {realm}")
    now = clock.now_ms()
    if got == "personal":
        r = sv.pdb.one(
            "SELECT s.id sid, u.id uid, u.email, u.display_name, u.status FROM sessions s JOIN users u ON u.id=s.user_id"
            " WHERE s.id=? AND u.id=? AND s.revoked_at IS NULL AND s.expires_at>?", (claims["sid"], claims["sub"], now))
        if r is None or r["status"] != "active":
            raise unauthorized("Sesi tidak aktif", "session_revoked")
        return Identity("personal", r["uid"], r["sid"], r["email"], r["display_name"])
    r = sv.edb.one(
        "SELECT s.id sid, e.id uid, e.org_id, e.email, e.display_name, e.role, e.division_id, e.status est,"
        " o.status ost, o.slug, o.name oname FROM sessions s JOIN employees e ON e.id=s.employee_id"
        " JOIN orgs o ON o.id=e.org_id WHERE s.id=? AND e.id=? AND s.org_id=e.org_id AND s.revoked_at IS NULL"
        " AND s.expires_at>?", (claims["sid"], claims["sub"], now))
    if r is None or r["est"] != "active" or r["ost"] != "active" or claims.get("org") != r["org_id"]:
        raise unauthorized("Sesi tidak aktif", "session_revoked")
    return Identity("enterprise", r["uid"], r["sid"], r["email"], r["display_name"], r["org_id"], r["slug"],
                    r["oname"], r["role"], r["division_id"])


def require(ident: Identity, perm: str) -> None:
    if not ident.can(perm):
        sv = _services()
        sv.eaudit.append(ident.org_id, "authz.denied", ident.uid, ident.role, meta={"perm": perm, "path": request.path}, ip=client_ip())
        raise forbidden()


def auth(realm: str = "any", perm: Optional[str] = None) -> Callable:
    def deco(fn):
        @functools.wraps(fn)
        def wrapper(*a, **kw):
            ident = authenticate(realm)
            if perm:
                require(ident, perm)
            g.ident = ident
            return fn(*a, **kw)
        return wrapper
    return deco


def client_ip() -> str:
    return request.remote_addr or ""


class RateLimiter:
    def __init__(self):
        self._hits: Dict[str, Deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def check(self, key: str, limit: int, window_s: int) -> None:
        now = time.monotonic()
        with self._lock:
            q = self._hits[key]
            while q and now - q[0] > window_s:
                q.popleft()
            if len(q) >= limit:
                raise too_many(retry_after=max(1, int(window_s - (now - q[0]))))
            q.append(now)
            if len(self._hits) > 20000:
                for k in [k for k, v in self._hits.items() if not v or now - v[-1] > window_s]:
                    self._hits.pop(k, None)

    def reset(self) -> None:
        with self._lock:
            self._hits.clear()


def limit(name: str, count: int, window_s: int, per: str = "ip") -> Callable:
    def deco(fn):
        @functools.wraps(fn)
        def wrapper(*a, **kw):
            who = client_ip() if per == "ip" or not hasattr(g, "ident") else g.ident.uid
            _services().limiter.check(f"{name}:{who}", count, window_s)
            return fn(*a, **kw)
        return wrapper
    return deco
