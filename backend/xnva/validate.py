"""Small request-body validator (no external dependency)."""
from __future__ import annotations

import re
from datetime import date
from typing import Any, Dict, Tuple

from .errors import ApiError

_EMAIL = re.compile(r"^[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(\.[A-Za-z0-9\-]+)+$")
_MISSING = object()


class F:
    @staticmethod
    def str(max: int = 200, min: int = 0, req: bool = True, default: Any = _MISSING, pattern: str = ""):
        return ("str", dict(max=max, min=min, req=req, default=default, pattern=pattern))

    @staticmethod
    def int(min: int = 0, max: int = 2**53, req: bool = True, default: Any = _MISSING):
        return ("int", dict(min=min, max=max, req=req, default=default))

    @staticmethod
    def bool(req: bool = True, default: Any = _MISSING):
        return ("bool", dict(req=req, default=default))

    @staticmethod
    def enum(values: Tuple[str, ...], req: bool = True, default: Any = _MISSING):
        return ("enum", dict(values=values, req=req, default=default))

    @staticmethod
    def email(req: bool = True):
        return ("email", dict(req=req, default=_MISSING))

    @staticmethod
    def date(req: bool = True, default: Any = _MISSING):
        return ("date", dict(req=req, default=default))

    @staticmethod
    def list(item: Tuple, max: int = 100, req: bool = True, default: Any = _MISSING):
        return ("list", dict(item=item, max=max, req=req, default=default))

    @staticmethod
    def obj(spec: Dict[str, Tuple], req: bool = True, default: Any = _MISSING):
        return ("obj", dict(spec=spec, req=req, default=default))


def _check(name: str, kind: str, o: Dict[str, Any], v: Any, problems: list) -> Any:
    def bad(msg: str):
        problems.append({"field": name, "problem": msg})
        return None

    if kind in ("str", "email", "date", "enum"):
        if not isinstance(v, str):
            return bad("harus berupa teks")
        v = v.strip() if kind != "str" else v.replace("\x00", "").strip()
        if kind == "str":
            if len(v) < o["min"]:
                return bad(f"minimal {o['min']} karakter")
            if len(v) > o["max"]:
                return bad(f"maksimal {o['max']} karakter")
            if o["pattern"] and not re.fullmatch(o["pattern"], v):
                return bad("format tidak valid")
        elif kind == "email":
            v = v.lower()
            if len(v) > 254 or not _EMAIL.match(v):
                return bad("email tidak valid")
        elif kind == "date":
            try:
                date.fromisoformat(v)
            except ValueError:
                return bad("harus berformat YYYY-MM-DD")
        elif v not in o["values"]:
            return bad("harus salah satu dari: " + ", ".join(o["values"]))
        return v
    if kind == "int":
        if isinstance(v, bool) or not isinstance(v, int):
            return bad("harus berupa bilangan bulat")
        if not o["min"] <= v <= o["max"]:
            return bad(f"harus di antara {o['min']} dan {o['max']}")
        return v
    if kind == "bool":
        return v if isinstance(v, bool) else bad("harus true/false")
    if kind == "list":
        if not isinstance(v, list):
            return bad("harus berupa daftar")
        if len(v) > o["max"]:
            return bad(f"maksimal {o['max']} item")
        ik, io = o["item"]
        return [_check(f"{name}[{i}]", ik, io, x, problems) for i, x in enumerate(v)]
    if kind == "obj":
        if not isinstance(v, dict):
            return bad("harus berupa objek")
        return validate(v, o["spec"], _problems=problems, _prefix=name + ".")
    return bad("tipe tidak dikenal")


def validate(data: Any, spec: Dict[str, Tuple], partial: bool = False, _problems: list = None, _prefix: str = "") -> Dict[str, Any]:
    top = _problems is None
    problems = [] if top else _problems
    if not isinstance(data, dict):
        raise ApiError(400, "validation_error", "Body harus berupa objek JSON")
    out: Dict[str, Any] = {}
    for name, (kind, o) in spec.items():
        v = data.get(name)
        if v is None:
            if partial:
                continue
            if o.get("default", _MISSING) is not _MISSING:
                out[name] = o["default"]
            elif o["req"]:
                problems.append({"field": _prefix + name, "problem": "wajib diisi"})
            continue
        out[name] = _check(_prefix + name, kind, o, v, problems)
    if top and problems:
        raise ApiError(400, "validation_error", "Input tidak valid", fields=problems)
    return out


def check_password(pw: str, email: str = "") -> None:
    if not isinstance(pw, str) or len(pw) < 10:
        raise ApiError(400, "weak_password", "Password minimal 10 karakter")
    if len(pw) > 256:
        raise ApiError(400, "weak_password", "Password maksimal 256 karakter")
    if len(set(pw)) < 4 or (email and pw.lower() == email.lower()):
        raise ApiError(400, "weak_password", "Password terlalu mudah ditebak")
