"""Enterprise knowledge base: encrypted chunks + blind-index BM25 retrieval.

Chunk text is encrypted at rest. Search terms are stored only as keyed hashes
(HMAC with a per-organization key), so the database never contains plaintext
document content or a readable vocabulary.
"""
from __future__ import annotations

import hashlib
import hmac
import math
import re
import unicodedata
from collections import Counter
from typing import Any, Dict, List, Tuple

from . import clock
from .errors import ApiError, conflict, not_found

_STOP = set("""yang dan di ke dari untuk pada dengan ini itu adalah atau juga akan ada dalam oleh agar saat jika
sebagai para kami kita anda dia mereka sudah belum bisa dapat harus the a an of to in is are for on with and or be as
at by it this that from was were been have has had not but""".split())
_WORD = re.compile(r"[a-z0-9]+")
MAX_DOC_CHARS = 400_000


def tokens(text: str) -> List[str]:
    t = unicodedata.normalize("NFKD", text.lower())
    t = "".join(c for c in t if not unicodedata.combining(c))
    return [w for w in _WORD.findall(t) if len(w) >= 2 and w not in _STOP]


def chunk_text(text: str, max_chars: int = 900, overlap: int = 150) -> List[str]:
    paras = [p.strip() for p in re.split(r"\n\s*\n", text.replace("\r", "")) if p.strip()]
    out: List[str] = []
    cur = ""
    for p in paras:
        pieces = [p] if len(p) <= max_chars else [p[i:i + max_chars] for i in range(0, len(p), max_chars - overlap)]
        for piece in pieces:
            if cur and len(cur) + len(piece) + 2 > max_chars:
                out.append(cur)
                cur = (cur[-overlap:] + "\n" if overlap and len(piece) < max_chars - overlap else "") + piece
            else:
                cur = f"{cur}\n\n{piece}" if cur else piece
    if cur:
        out.append(cur)
    return out


def _term_hash(key: bytes, term: str) -> int:
    return int.from_bytes(hmac.new(key, term.encode(), hashlib.sha256).digest()[:8], "big", signed=True)


def _visibility(ident) -> Tuple[str, List[Any]]:
    if ident.role in ("owner", "admin"):
        return "1=1", []
    if ident.role == "manager":
        return "(d.visibility IN ('org','managers') OR (d.visibility='division' AND d.division_id=?))", [ident.division_id]
    return "(d.visibility='org' OR (d.visibility='division' AND d.division_id=?))", [ident.division_id]


class KnowledgeBase:
    def __init__(self, sv):
        self.sv = sv

    def add(self, ident, title: str, text: str, visibility: str, division_id: str, source: str) -> Dict[str, Any]:
        s, org = self.sv, ident.org_id
        if len(text) > MAX_DOC_CHARS:
            raise ApiError(413, "document_too_large", f"Dokumen melebihi {MAX_DOC_CHARS} karakter")
        chunks = chunk_text(text)
        if not chunks:
            raise ApiError(400, "empty_document", "Dokumen tidak berisi teks")
        if visibility == "division":
            division_id = division_id or ident.division_id
            if not division_id or not s.edb.one("SELECT 1 FROM divisions WHERE id=? AND org_id=?", (division_id, org)):
                raise ApiError(400, "invalid_division", "Divisi tidak valid untuk visibilitas division")
        else:
            division_id = None
        doc_id = hashlib.sha256(f"{org}:{clock.now_ms()}:{title}".encode()).hexdigest()[:32]
        chash = hashlib.sha256(text.encode()).hexdigest()
        key = s.ekeys.blind_key(org)
        with s.edb.tx():
            if s.edb.one("SELECT 1 FROM kb_documents WHERE org_id=? AND content_hash=?", (org, chash)):
                raise conflict("duplicate_document", "Dokumen dengan isi yang sama sudah ada")
            s.edb.run(
                "INSERT INTO kb_documents(id,org_id,title_enc,source,visibility,division_id,created_by,created_at,chunk_count,content_hash)"
                " VALUES(?,?,?,?,?,?,?,?,?,?)",
                (doc_id, org, s.ekeys.seal(org, title, f"kb_documents.{org}.{doc_id}.title"), source, visibility, division_id,
                 ident.uid, clock.now_ms(), len(chunks), chash))
            for idx, ch in enumerate(chunks):
                toks = tokens(ch)
                cur = s.edb.conn().execute(
                    "INSERT INTO kb_chunks(org_id,doc_id,idx,text_enc,token_count) VALUES(?,?,?,?,?)",
                    (org, doc_id, idx, s.ekeys.seal(org, ch, f"kb_chunks.{org}.{doc_id}.{idx}"), max(1, len(toks))))
                cid = cur.lastrowid
                rows = [(org, _term_hash(key, t), cid, tf) for t, tf in Counter(toks).items()]
                if rows:
                    s.edb.conn().executemany("INSERT INTO kb_terms(org_id,term_hash,chunk_id,tf) VALUES(?,?,?,?)", rows)
        return {"id": doc_id, "title": title, "chunks": len(chunks), "visibility": visibility, "division_id": division_id}

    def delete(self, org: str, doc_id: str) -> None:
        if not self.sv.edb.run("DELETE FROM kb_documents WHERE id=? AND org_id=?", (doc_id, org)):
            raise not_found("Dokumen")

    def list(self, ident) -> List[Dict[str, Any]]:
        s = self.sv
        vis, args = _visibility(ident)
        rows = s.edb.all(f"SELECT d.* FROM kb_documents d WHERE d.org_id=? AND ({vis}) ORDER BY d.created_at DESC", [ident.org_id] + args)
        return [{"id": r["id"], "title": s.ekeys.open(ident.org_id, r["title_enc"], f"kb_documents.{ident.org_id}.{r['id']}.title"),
                 "source": r["source"], "visibility": r["visibility"], "division_id": r["division_id"],
                 "chunks": r["chunk_count"], "created_at": r["created_at"]} for r in rows]

    def search(self, ident, query: str, k: int = 5) -> List[Dict[str, Any]]:
        s, org = self.sv, ident.org_id
        qt = list(dict.fromkeys(tokens(query)))[:24]
        if not qt:
            return []
        key = s.ekeys.blind_key(org)
        hashes = [_term_hash(key, t) for t in qt]
        ph = ",".join("?" * len(hashes))
        vis, vargs = _visibility(ident)
        rows = s.edb.all(
            f"SELECT t.chunk_id, t.term_hash, t.tf, c.token_count, c.doc_id, c.idx FROM kb_terms t"
            f" JOIN kb_chunks c ON c.id=t.chunk_id JOIN kb_documents d ON d.id=c.doc_id"
            f" WHERE t.org_id=? AND c.org_id=? AND d.org_id=? AND t.term_hash IN ({ph}) AND ({vis})",
            [org, org, org] + hashes + vargs)
        if not rows:
            return []
        n_chunks = max(1, s.edb.scalar("SELECT COUNT(*) FROM kb_chunks WHERE org_id=?", (org,), 1))
        avgdl = s.edb.scalar("SELECT AVG(token_count) FROM kb_chunks WHERE org_id=?", (org,), 1.0)
        df = {r["term_hash"]: r["n"] for r in s.edb.all(
            f"SELECT term_hash, COUNT(*) n FROM kb_terms WHERE org_id=? AND term_hash IN ({ph}) GROUP BY term_hash", [org] + hashes)}
        scores: Dict[int, float] = {}
        meta: Dict[int, Tuple[str, int]] = {}
        k1, b = 1.4, 0.75
        for r in rows:
            d = df.get(r["term_hash"], 1)
            idf = math.log(1 + (n_chunks - d + 0.5) / (d + 0.5))
            tf, dl = r["tf"], r["token_count"]
            scores[r["chunk_id"]] = scores.get(r["chunk_id"], 0.0) + idf * (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgdl))
            meta[r["chunk_id"]] = (r["doc_id"], r["idx"])
        out = []
        for cid in sorted(scores, key=scores.get, reverse=True)[:k]:
            doc_id, idx = meta[cid]
            c = s.edb.one("SELECT text_enc FROM kb_chunks WHERE id=? AND org_id=?", (cid, org))
            d = s.edb.one("SELECT title_enc FROM kb_documents WHERE id=? AND org_id=?", (doc_id, org))
            out.append({"doc_id": doc_id, "chunk_index": idx, "score": round(scores[cid], 4),
                        "title": s.ekeys.open(org, d["title_enc"], f"kb_documents.{org}.{doc_id}.title"),
                        "text": s.ekeys.open(org, c["text_enc"], f"kb_chunks.{org}.{doc_id}.{idx}")})
        return out
