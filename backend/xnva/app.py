from __future__ import annotations

from typing import Mapping, Optional

from flask import Flask, request
from werkzeug.middleware.proxy_fix import ProxyFix

from . import errors
from .api import auth, devices, ent_admin, ent_insight, ent_work, misc, personal, xnai_api
from .config import load_settings
from .services import build_services
from .xnai.service import XnaiService


def create_app(env: Optional[Mapping[str, str]] = None) -> Flask:
    settings = load_settings(env)
    app = Flask(__name__)
    app.config["MAX_CONTENT_LENGTH"] = settings.max_body_bytes
    app.json.ensure_ascii = False
    app.json.sort_keys = False
    sv = build_services(settings)
    sv.xnai = XnaiService(sv)
    app.extensions["xnva"] = sv
    errors.register(app)
    if settings.trusted_proxies:
        n = settings.trusted_proxies
        app.wsgi_app = ProxyFix(app.wsgi_app, x_for=n, x_proto=n, x_host=n)
    for bp in (auth.bp, auth.platform_bp, personal.bp, misc.bp, devices.bp, xnai_api.bp, ent_admin.bp, ent_work.bp, ent_insight.bp):
        app.register_blueprint(bp)

    @app.before_request
    def _preflight():
        if request.method == "OPTIONS" and settings.cors_origins:
            from flask import make_response
            return make_response("", 204)

    @app.after_request
    def _headers(resp):
        resp.headers["X-Content-Type-Options"] = "nosniff"
        resp.headers["Cache-Control"] = "no-store"
        resp.headers["Referrer-Policy"] = "no-referrer"
        resp.headers["Content-Security-Policy"] = "default-src 'none'; frame-ancestors 'none'"
        if settings.production and request.is_secure:
            resp.headers["Strict-Transport-Security"] = "max-age=31536000; includeSubDomains"
        origin = request.headers.get("Origin")
        if origin and origin in settings.cors_origins:
            resp.headers["Access-Control-Allow-Origin"] = origin
            resp.headers["Vary"] = "Origin"
            resp.headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type"
            resp.headers["Access-Control-Allow-Methods"] = "GET, POST, PUT, DELETE, OPTIONS"
        return resp

    return app
