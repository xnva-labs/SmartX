from __future__ import annotations

import logging
import uuid

from flask import Flask, g, jsonify, request

log = logging.getLogger("xnva")


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, **extra):
        super().__init__(message)
        self.status, self.code, self.message, self.extra = status, code, message, extra


def bad_request(code: str, message: str, **extra) -> ApiError:
    return ApiError(400, code, message, **extra)


def unauthorized(message: str = "Autentikasi diperlukan", code: str = "unauthorized") -> ApiError:
    return ApiError(401, code, message)


def forbidden(message: str = "Tidak punya izin untuk aksi ini", code: str = "forbidden") -> ApiError:
    return ApiError(403, code, message)


def not_found(what: str = "Data") -> ApiError:
    return ApiError(404, "not_found", f"{what} tidak ditemukan")


def conflict(code: str, message: str) -> ApiError:
    return ApiError(409, code, message)


def too_many(message: str = "Terlalu banyak permintaan, coba lagi nanti", retry_after: int = 60) -> ApiError:
    return ApiError(429, "rate_limited", message, retry_after=retry_after)


def unavailable(code: str, message: str) -> ApiError:
    return ApiError(503, code, message)


def _body(status: int, code: str, message: str, **extra):
    err = {"code": code, "message": message, "request_id": getattr(g, "request_id", None)}
    err.update(extra)
    resp = jsonify({"error": err})
    resp.status_code = status
    if status == 429 and "retry_after" in extra:
        resp.headers["Retry-After"] = str(extra["retry_after"])
    return resp


def register(app: Flask) -> None:
    @app.before_request
    def _rid():
        g.request_id = uuid.uuid4().hex[:16]

    @app.errorhandler(ApiError)
    def _api(e: ApiError):
        return _body(e.status, e.code, e.message, **e.extra)

    @app.errorhandler(404)
    def _404(_):
        return _body(404, "not_found", "Endpoint tidak ditemukan")

    @app.errorhandler(405)
    def _405(_):
        return _body(405, "method_not_allowed", "Metode HTTP tidak diizinkan")

    @app.errorhandler(413)
    def _413(_):
        return _body(413, "payload_too_large", "Ukuran permintaan melebihi batas")

    @app.errorhandler(Exception)
    def _500(e):
        log.exception("unhandled error request_id=%s path=%s", getattr(g, "request_id", "-"), request.path)
        return _body(500, "internal_error", "Terjadi kesalahan internal")
