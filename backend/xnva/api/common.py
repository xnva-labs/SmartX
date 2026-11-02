from __future__ import annotations

from flask import current_app, request

from ..errors import bad_request


def sv():
    return current_app.extensions["xnva"]


def json_body() -> dict:
    data = request.get_json(silent=True)
    if data is None:
        raise bad_request("invalid_json", "Body harus JSON yang valid (Content-Type: application/json)")
    return data


def int_arg(name: str, default: int, lo: int, hi: int) -> int:
    raw = request.args.get(name)
    if raw is None:
        return default
    try:
        v = int(raw)
    except ValueError:
        raise bad_request("invalid_query", f"Parameter {name} harus bilangan bulat")
    return max(lo, min(hi, v))
