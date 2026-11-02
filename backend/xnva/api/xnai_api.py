from __future__ import annotations

import base64
import binascii

from flask import Blueprint, g, jsonify

from ..errors import ApiError, bad_request
from ..security import auth, limit
from ..validate import F, validate
from .common import json_body, sv

bp = Blueprint("xnai", __name__, url_prefix="/v1/xnai")
_HIST = ("obj", {"spec": {"role": F.enum(("user", "assistant")), "text": F.str(max=8000)}, "req": True, "default": None})
_LANG = r"[A-Za-z][A-Za-z \-()]{1,39}"


def _need_ai(ident) -> None:
    if ident.realm == "enterprise":
        from ..security import require
        require(ident, "ai.use")


def _b64(s: str, what: str) -> bytes:
    try:
        return base64.b64decode(s, validate=True)
    except (binascii.Error, ValueError):
        raise bad_request("invalid_base64", f"{what} bukan base64 yang valid")


@bp.get("/status")
@auth()
def status():
    c = sv().settings.xnai
    return jsonify({"chat": c.chat_ready, "vision": c.vision_ready, "transcribe": c.stt_ready, "provider": c.provider or None})


@bp.post("/chat")
@auth()
@limit("xnai", 30, 60, per="user")
def chat():
    _need_ai(g.ident)
    d = validate(json_body(), {"message": F.str(max=8000, min=1), "history": F.list(_HIST, max=40, req=False, default=[]),
                               "thinkMode": F.str(max=20, req=False, default="RELAX"), "context": F.str(max=4000, req=False, default=""),
                               "useKnowledge": F.bool(req=False, default=True)})
    return jsonify(sv().xnai.chat(g.ident, d["message"], d["history"], d["thinkMode"], d["context"], d["useKnowledge"]))


@bp.post("/vision")
@auth()
@limit("xnai", 30, 60, per="user")
def vision():
    _need_ai(g.ident)
    d = validate(json_body(), {"imageBase64": F.str(max=9_000_000, min=100), "mode": F.enum(("describe", "read", "inspect"), req=False, default="describe"),
                               "checklist": F.list(F.str(max=200, min=1), max=30, req=False, default=[]), "question": F.str(max=500, req=False, default="")})
    return jsonify(sv().xnai.vision(g.ident, _b64(d["imageBase64"], "imageBase64"), d["mode"], d["checklist"], d["question"]))


@bp.post("/translate")
@auth()
@limit("xnai", 30, 60, per="user")
def translate():
    _need_ai(g.ident)
    d = validate(json_body(), {"text": F.str(max=8000, min=1), "targetLanguage": F.str(max=40, min=2, pattern=_LANG)})
    return jsonify(sv().xnai.translate(g.ident, d["text"], d["targetLanguage"]))


@bp.post("/transcribe")
@auth()
@limit("xnai", 30, 60, per="user")
def transcribe():
    _need_ai(g.ident)
    d = validate(json_body(), {"audioBase64": F.str(max=14_000_000, min=100), "mime": F.enum(("audio/wav", "audio/x-wav"), req=False, default="audio/wav"),
                               "language": F.str(max=8, req=False, pattern=r"[a-z]{2,3}")})
    audio = _b64(d["audioBase64"], "audioBase64")
    if len(audio) > 10 * 1024 * 1024:
        raise ApiError(413, "audio_too_large", "Audio melebihi 10 MB")
    return jsonify(sv().xnai.transcribe(g.ident, audio, d["mime"], d.get("language")))


@bp.post("/reply-suggestions")
@auth()
@limit("xnai", 30, 60, per="user")
def reply_suggestions():
    _need_ai(g.ident)
    d = validate(json_body(), {"app": F.str(max=60, min=1), "sender": F.str(max=80, req=False, default=""), "message": F.str(max=2000, min=1),
                               "tone": F.enum(("ramah", "formal", "santai", "singkat"), req=False, default="ramah")})
    return jsonify(sv().xnai.reply_suggestions(g.ident, d["app"], d["sender"], d["message"], d["tone"]))
