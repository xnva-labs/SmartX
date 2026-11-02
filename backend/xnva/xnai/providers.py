"""Adapters for the upstream model provider (any OpenAI-compatible server, or Anthropic)."""
from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

import requests

from ..config import XnaiConfig

log = logging.getLogger("xnva.xnai")


class UpstreamError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status, self.code, self.message = status, code, message


@dataclass
class Completion:
    text: str
    model: str
    tokens_in: int
    tokens_out: int


def _map_error(status: int) -> UpstreamError:
    if status in (401, 403):
        return UpstreamError(502, "xnai_upstream_auth", "Kredensial penyedia model di server ditolak")
    if status == 429:
        return UpstreamError(503, "xnai_busy", "Penyedia model sedang sibuk, coba lagi sebentar lagi")
    if status == 400 or status == 422:
        return UpstreamError(502, "xnai_upstream_rejected", "Penyedia model menolak permintaan (model/format tidak didukung)")
    return UpstreamError(502, "xnai_upstream_error", "Penyedia model tidak dapat memproses permintaan")


def _post(url: str, headers: Dict[str, str], timeout: int, **kw) -> Dict[str, Any]:
    try:
        r = requests.post(url, headers=headers, timeout=(5, timeout), allow_redirects=False, **kw)
    except requests.Timeout as e:
        raise UpstreamError(504, "xnai_timeout", "Penyedia model tidak merespons tepat waktu") from e
    except requests.RequestException as e:
        log.warning("upstream connection failed: %s", type(e).__name__)
        raise UpstreamError(502, "xnai_unreachable", "Tidak dapat menghubungi penyedia model") from e
    if r.status_code != 200:
        log.warning("upstream status=%s body=%s", r.status_code, r.text[:300].replace("\n", " "))
        raise _map_error(r.status_code)
    try:
        return r.json()
    except ValueError as e:
        raise UpstreamError(502, "xnai_upstream_error", "Respons penyedia model tidak valid") from e


def _flatten(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(p.get("text", "") for p in content if isinstance(p, dict) and p.get("type") in ("text", "output_text"))
    return ""


class OpenAICompat:
    def __init__(self, cfg: XnaiConfig):
        self.cfg = cfg

    def _headers(self, key: str) -> Dict[str, str]:
        h = {"Content-Type": "application/json"}
        if key:
            h["Authorization"] = f"Bearer {key}"
        return h

    @staticmethod
    def _part(p: Dict[str, Any]) -> Dict[str, Any]:
        if p["type"] == "image":
            return {"type": "image_url", "image_url": {"url": f"data:{p['media_type']};base64,{p['data']}"}}
        return {"type": "text", "text": p["text"]}

    def chat(self, system: str, messages: List[Dict[str, Any]], max_tokens: int, vision: bool = False) -> Completion:
        model = self.cfg.vision_model if vision else self.cfg.model
        msgs = [{"role": "system", "content": system}]
        for m in messages:
            c = m["content"]
            msgs.append({"role": m["role"], "content": c if isinstance(c, str) else [self._part(p) for p in c]})
        data = _post(f"{self.cfg.base_url}/chat/completions", self._headers(self.cfg.api_key), self.cfg.timeout_s,
                     json={"model": model, "messages": msgs, self.cfg.max_tokens_param: max_tokens})
        try:
            text = _flatten(data["choices"][0]["message"]["content"])
        except (KeyError, IndexError, TypeError) as e:
            raise UpstreamError(502, "xnai_upstream_error", "Respons penyedia model tidak valid") from e
        u = data.get("usage") or {}
        return Completion(text.strip(), data.get("model", model), int(u.get("prompt_tokens", 0)), int(u.get("completion_tokens", 0)))

    def transcribe(self, audio: bytes, mime: str, language: Optional[str]) -> str:
        c = self.cfg
        headers = {"Authorization": f"Bearer {c.stt_api_key}"} if c.stt_api_key else {}
        form = {"model": c.stt_model, "response_format": "json"}
        if language:
            form["language"] = language
        data = _post(f"{c.stt_base_url}/audio/transcriptions", headers, c.timeout_s, data=form,
                     files={"file": ("audio" + (".wav" if "wav" in mime else ".bin"), audio, mime)})
        return str(data.get("text", "")).strip()


class AnthropicProvider:
    def __init__(self, cfg: XnaiConfig):
        self.cfg = cfg

    @staticmethod
    def _part(p: Dict[str, Any]) -> Dict[str, Any]:
        if p["type"] == "image":
            return {"type": "image", "source": {"type": "base64", "media_type": p["media_type"], "data": p["data"]}}
        return {"type": "text", "text": p["text"]}

    def chat(self, system: str, messages: List[Dict[str, Any]], max_tokens: int, vision: bool = False) -> Completion:
        model = self.cfg.vision_model if vision else self.cfg.model
        msgs = [{"role": m["role"], "content": m["content"] if isinstance(m["content"], str) else [self._part(p) for p in m["content"]]}
                for m in messages]
        headers = {"Content-Type": "application/json", "x-api-key": self.cfg.api_key, "anthropic-version": "2023-06-01"}
        data = _post(f"{self.cfg.base_url}/v1/messages", headers, self.cfg.timeout_s,
                     json={"model": model, "max_tokens": max_tokens, "system": system, "messages": msgs})
        text = "".join(b.get("text", "") for b in data.get("content", []) if isinstance(b, dict) and b.get("type") == "text")
        u = data.get("usage") or {}
        return Completion(text.strip(), data.get("model", model), int(u.get("input_tokens", 0)), int(u.get("output_tokens", 0)))

    def transcribe(self, audio: bytes, mime: str, language: Optional[str]) -> str:
        raise UpstreamError(503, "stt_not_configured", "Transkripsi memakai penyedia terpisah (XNAI_STT_*)")


def build_provider(cfg: XnaiConfig):
    if cfg.provider == "anthropic":
        return AnthropicProvider(cfg)
    if cfg.provider == "openai":
        return OpenAICompat(cfg)
    return None


def build_stt(cfg: XnaiConfig):
    return OpenAICompat(cfg) if cfg.stt_ready else None
