from __future__ import annotations

import base64
import json
import re
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from .. import clock
from ..errors import ApiError, forbidden
from ..kb import KnowledgeBase
from ..orgs import get_policy
from . import prompts
from .images import sanitize
from .providers import UpstreamError, build_provider, build_stt

HIST_TURNS, HIST_CHARS = 20, 4000


def extract_json(text: str) -> Optional[Any]:
    m = re.search(r"```(?:json)?\s*(.*?)```", text, re.S)
    cand = m.group(1) if m else text
    for open_c, close_c in (("{", "}"), ("[", "]")):
        a, b = cand.find(open_c), cand.rfind(close_c)
        if 0 <= a < b:
            try:
                return json.loads(cand[a:b + 1])
            except ValueError:
                continue
    return None


def _normalize(history: List[Dict[str, str]], message: str) -> List[Dict[str, Any]]:
    msgs: List[Dict[str, Any]] = []
    for h in history[-HIST_TURNS:]:
        role = "assistant" if h.get("role") == "assistant" else "user"
        text = str(h.get("text", ""))[:HIST_CHARS]
        if not text.strip():
            continue
        if msgs and msgs[-1]["role"] == role:
            msgs[-1]["content"] += "\n" + text
        else:
            msgs.append({"role": role, "content": text})
    while msgs and msgs[0]["role"] != "user":
        msgs.pop(0)
    if msgs and msgs[-1]["role"] == "user":
        msgs[-1]["content"] += "\n" + message
    else:
        msgs.append({"role": "user", "content": message})
    return msgs


class XnaiService:
    def __init__(self, sv):
        self.sv = sv
        self.cfg = sv.settings.xnai
        self.provider = build_provider(self.cfg)
        self.stt = build_stt(self.cfg)
        self.kb = KnowledgeBase(sv)

    # ------------------------------------------------------------ gating
    def _ready(self, kind: str) -> None:
        ok = {"stt": self.cfg.stt_ready}.get(kind, self.cfg.chat_ready)
        if not ok:
            code = "stt_not_configured" if kind == "stt" else "xnai_not_configured"
            raise ApiError(503, code, "Layanan XNAI belum dikonfigurasi di server ini. Operator perlu mengisi variabel XNAI_* (lihat docs/CONFIGURATION.md).")

    def _gate(self, ident, kind: str) -> None:
        s = self.sv
        if ident.realm == "personal":
            p = s.pdb.one("SELECT cloud_processing FROM privacy WHERE user_id=?", (ident.uid,))
            if not p or not p["cloud_processing"]:
                raise forbidden("Cloud Processing belum diizinkan. Aktifkan di Pengaturan Privasi.", "cloud_processing_disabled")
            start = int(datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000)
            used = s.pdb.scalar("SELECT COUNT(*) FROM ai_activity WHERE user_id=? AND ts>=?", (ident.uid, start), 0)
            if used >= s.settings.personal_daily_ai_limit:
                raise ApiError(429, "quota_exceeded", "Batas penggunaan AI harian tercapai", retry_after=3600)
            return
        pol = get_policy(s, ident.org_id)
        if not pol["ai_enabled"] or (kind == "vision" and not pol["vision_enabled"]):
            raise forbidden("Fitur ini dinonaktifkan oleh kebijakan organisasi", "disabled_by_policy")
        month = datetime.now(timezone.utc).strftime("%Y-%m")
        used = s.edb.scalar("SELECT SUM(requests) FROM ai_usage WHERE org_id=? AND day LIKE ?", (ident.org_id, month + "%"), 0)
        quota = s.edb.scalar("SELECT ai_monthly_quota FROM orgs WHERE id=?", (ident.org_id,), 0)
        if used >= quota:
            raise ApiError(429, "quota_exceeded", "Kuota AI bulanan organisasi habis", retry_after=3600)

    def _record(self, ident, kind: str, bytes_in: int, comp) -> None:
        s = self.sv
        if ident.realm == "personal":
            s.pdb.run("INSERT INTO ai_activity(user_id,ts,kind,bytes_in,bytes_out,model) VALUES(?,?,?,?,?,?)",
                      (ident.uid, clock.now_ms(), kind, bytes_in, len(comp.text.encode()) if comp else 0, comp.model if comp else None))
            return
        day = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        s.edb.run(
            "INSERT INTO ai_usage(org_id,day,division_id,kind,requests,tokens_in,tokens_out) VALUES(?,?,?,?,1,?,?)"
            " ON CONFLICT(org_id,day,division_id,kind) DO UPDATE SET requests=requests+1,"
            " tokens_in=tokens_in+excluded.tokens_in, tokens_out=tokens_out+excluded.tokens_out",
            (ident.org_id, day, ident.division_id or "", kind, comp.tokens_in if comp else 0, comp.tokens_out if comp else 0))

    def _call(self, ident, kind: str, system: str, messages, max_tokens: int, vision: bool = False, bytes_in: int = 0):
        self._ready("chat")
        self._gate(ident, kind)
        try:
            comp = self.provider.chat(system, messages, max_tokens, vision=vision)
        except UpstreamError as e:
            raise ApiError(e.status, e.code, e.message)
        self._record(ident, kind, bytes_in, comp)
        return comp

    # ------------------------------------------------------------- chat
    def chat(self, ident, message: str, history: List[Dict[str, str]], think_mode: str, context: str,
             use_knowledge: bool = True) -> Dict[str, Any]:
        mode = think_mode if think_mode in prompts.THINK else "RELAX"
        max_tokens = {"RELAX": min(self.cfg.max_tokens, 512), "HIGH": self.cfg.max_tokens,
                      "SUPERAUTOMATION": min(self.cfg.max_tokens * 2, 4096)}[mode]
        citations: List[Dict[str, Any]] = []
        if ident.realm == "enterprise":
            system = prompts.ENTERPRISE.format(org=ident.org_name or "organisasi")
            if use_knowledge:
                hits = self.kb.search(ident, message, k=5)
                citations = [{"doc_id": h["doc_id"], "title": h["title"], "chunk_index": h["chunk_index"]} for h in hits]
                if hits:
                    system += "\n\n" + "\n\n".join(f"<DOKUMEN judul=\"{h['title']}\">\n{h['text']}\n</DOKUMEN>" for h in hits)
                else:
                    system += "\n\n(Tidak ada dokumen knowledge base yang relevan dengan pertanyaan ini.)"
        else:
            system = prompts.PERSONAL
        system += "\n\n" + prompts.THINK[mode]
        if context.strip() and ident.realm == "personal":
            system += "\n\n" + prompts.CONTEXT_NOTE.format(ctx=context[:2000])
        comp = self._call(ident, "chat", system, _normalize(history, message), max_tokens, bytes_in=len(message.encode()))
        return {"reply": comp.text, "model": comp.model, "citations": citations,
                "usage": {"input_tokens": comp.tokens_in, "output_tokens": comp.tokens_out}}

    # ----------------------------------------------------------- vision
    def vision(self, ident, raw_image: bytes, mode: str, checklist: List[str], question: str) -> Dict[str, Any]:
        if mode == "inspect" and ident.realm != "enterprise":
            raise forbidden("Mode inspect hanya tersedia untuk akun Enterprise", "inspect_enterprise_only")
        self._ready("chat")
        self._gate(ident, "vision")
        jpeg, w, h = sanitize(raw_image)
        prompt = prompts.VISION[mode]
        if mode == "inspect":
            if not checklist:
                raise ApiError(400, "validation_error", "Mode inspect memerlukan checklist")
            prompt = prompt.format(checklist="\n".join(f"- {c}" for c in checklist))
        content: List[Dict[str, Any]] = [
            {"type": "image", "media_type": "image/jpeg", "data": base64.b64encode(jpeg).decode()},
            {"type": "text", "text": (question + "\n" if question else "") + "Jawab dalam Bahasa Indonesia."}]
        comp = self._call(ident, "vision", prompt, [{"role": "user", "content": content}], self.cfg.max_tokens, vision=True, bytes_in=len(jpeg))
        out: Dict[str, Any] = {"mode": mode, "description": comp.text, "model": comp.model, "image": {"width": w, "height": h}}
        if mode == "inspect":
            parsed = extract_json(comp.text)
            out["structured"] = parsed if isinstance(parsed, dict) else None
            out["decision_support_only"] = True
            out["advisory"] = prompts.VISION_ADVISORY
            out["description"] = (comp.text if parsed is None else _inspect_text(parsed)) + "\n\n" + prompts.VISION_ADVISORY
        return out

    # -------------------------------------------------------- utilities
    def translate(self, ident, text: str, target: str) -> Dict[str, Any]:
        comp = self._call(ident, "translate", prompts.TRANSLATE.format(target=target),
                          [{"role": "user", "content": f"<teks>\n{text}\n</teks>"}], self.cfg.max_tokens, bytes_in=len(text.encode()))
        return {"translated": comp.text, "model": comp.model}

    def transcribe(self, ident, audio: bytes, mime: str, language: Optional[str]) -> Dict[str, Any]:
        self._ready("stt")
        self._gate(ident, "transcribe")
        try:
            text = self.stt.transcribe(audio, mime, language)
        except UpstreamError as e:
            raise ApiError(e.status, e.code, e.message)
        self._record(ident, "transcribe", len(audio), None)
        return {"text": text}

    def reply_suggestions(self, ident, app: str, sender: str, message: str, tone: str) -> Dict[str, Any]:
        data = f"Aplikasi: {app}\nPengirim: {sender or '-'}\n<pesan>\n{message}\n</pesan>"
        comp = self._call(ident, "reply", prompts.REPLY.format(tone=tone), [{"role": "user", "content": data}], 400, bytes_in=len(message.encode()))
        parsed = extract_json(comp.text)
        items = [str(x).strip() for x in parsed if str(x).strip()][:3] if isinstance(parsed, list) else [l.strip("-• ").strip() for l in comp.text.splitlines() if l.strip()][:3]
        return {"suggestions": items, "model": comp.model}

    def summarize_meeting(self, ident, notes: str) -> Dict[str, Any]:
        comp = self._call(ident, "meeting", prompts.MEETING, [{"role": "user", "content": f"<catatan>\n{notes[:30000]}\n</catatan>"}],
                          self.cfg.max_tokens, bytes_in=len(notes.encode()))
        parsed = extract_json(comp.text)
        if not isinstance(parsed, dict):
            return {"summary": comp.text, "decisions": [], "action_items": []}
        return {"summary": str(parsed.get("summary", "")), "decisions": [str(x) for x in parsed.get("decisions", []) if x],
                "action_items": [a for a in parsed.get("action_items", []) if isinstance(a, dict) and a.get("title")][:30]}

    def narrate(self, ident, metrics: Dict[str, Any]) -> str:
        comp = self._call(ident, "report", prompts.REPORT, [{"role": "user", "content": json.dumps(metrics, ensure_ascii=False)}], 600)
        return comp.text


def _inspect_text(p: Dict[str, Any]) -> str:
    lines = [f"Hasil keseluruhan: {p.get('overall', 'cannot_determine')}"]
    for f in p.get("findings", []) or []:
        if isinstance(f, dict):
            lines.append(f"- {f.get('item', '?')}: {f.get('status', '?')} — {f.get('note', '')}")
    if p.get("observations"):
        lines.append("Pengamatan: " + "; ".join(str(o) for o in p["observations"]))
    return "\n".join(lines)
