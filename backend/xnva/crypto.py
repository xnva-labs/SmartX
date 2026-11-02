"""Cryptographic helpers: password hashing, envelope encryption, blind index."""
from __future__ import annotations

import base64
import hashlib
import hmac
import os
import secrets
import threading
import unicodedata
from typing import Optional

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def b64e(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def b64d(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def new_token(nbytes: int = 32) -> str:
    return secrets.token_urlsafe(nbytes)


def token_hash(token: str) -> str:
    """Tokens are 256-bit random, so an unsalted SHA-256 is sufficient."""
    return hashlib.sha256(token.encode()).hexdigest()


def const_eq(a: str, b: str) -> bool:
    return hmac.compare_digest(a.encode(), b.encode())


class PasswordHasher:
    def __init__(self, n: int, r: int, p: int):
        self.n, self.r, self.p = n, r, p

    @staticmethod
    def _norm(pw: str) -> bytes:
        return unicodedata.normalize("NFKC", pw).encode()

    @staticmethod
    def _scrypt(pw: bytes, salt: bytes, n: int, r: int, p: int) -> bytes:
        return hashlib.scrypt(pw, salt=salt, n=n, r=r, p=p, dklen=32, maxmem=2 * 128 * r * (n + p + 2))

    def hash(self, password: str) -> str:
        salt = os.urandom(16)
        dk = self._scrypt(self._norm(password), salt, self.n, self.r, self.p)
        return f"scrypt${self.n}${self.r}${self.p}${b64e(salt)}${b64e(dk)}"

    def verify(self, password: str, stored: Optional[str]) -> bool:
        try:
            algo, n, r, p, salt, dk = (stored or "").split("$")
            if algo != "scrypt":
                return False
            calc = self._scrypt(self._norm(password), b64d(salt), int(n), int(r), int(p))
            return hmac.compare_digest(calc, b64d(dk))
        except (ValueError, TypeError):
            return False

    def needs_rehash(self, stored: str) -> bool:
        try:
            _, n, r, p, _, _ = stored.split("$")
            return (int(n), int(r), int(p)) != (self.n, self.r, self.p)
        except ValueError:
            return True


class Vault:
    """AES-256-GCM. A master key wraps per-scope data keys (envelope encryption)."""

    def __init__(self, master_key: bytes):
        if len(master_key) != 32:
            raise ValueError("master key must be 32 bytes")
        self._master = AESGCM(master_key)

    def wrap(self, dek: bytes, aad: str) -> str:
        nonce = os.urandom(12)
        return "k1." + b64e(nonce + self._master.encrypt(nonce, dek, aad.encode()))

    def unwrap(self, wrapped: str, aad: str) -> bytes:
        raw = b64d(wrapped.split(".", 1)[1])
        return self._master.decrypt(raw[:12], raw[12:], aad.encode())

    @staticmethod
    def seal(dek: bytes, plaintext: str, aad: str) -> str:
        nonce = os.urandom(12)
        return "v1." + b64e(nonce + AESGCM(dek).encrypt(nonce, plaintext.encode(), aad.encode()))

    @staticmethod
    def open(dek: bytes, blob: str, aad: str) -> str:
        if not blob.startswith("v1."):
            raise ValueError("unknown ciphertext version")
        raw = b64d(blob[3:])
        try:
            return AESGCM(dek).decrypt(raw[:12], raw[12:], aad.encode()).decode()
        except InvalidTag as e:
            raise ValueError("ciphertext failed authentication") from e


class KeyRing:
    """Per-scope data keys (one per user or per organization), stored wrapped."""

    def __init__(self, db, vault: Vault, table: str, id_col: str):
        self.db, self.vault, self.table, self.id_col = db, vault, table, id_col
        self._cache: dict = {}
        self._lock = threading.Lock()

    def _aad(self, scope_id: str) -> str:
        return f"{self.table}:{scope_id}"

    def create(self, scope_id: str, now: int) -> bytes:
        dek = os.urandom(32)
        self.db.run(
            f"INSERT INTO {self.table}({self.id_col}, wrapped, created_at) VALUES(?,?,?)",
            (scope_id, self.vault.wrap(dek, self._aad(scope_id)), now),
        )
        return dek

    def get(self, scope_id: str) -> bytes:
        with self._lock:
            dek = self._cache.get(scope_id)
        if dek is not None:
            return dek
        row = self.db.one(f"SELECT wrapped FROM {self.table} WHERE {self.id_col}=?", (scope_id,))
        if row is None:
            raise KeyError(scope_id)
        dek = self.vault.unwrap(row["wrapped"], self._aad(scope_id))
        with self._lock:
            if len(self._cache) > 2048:
                self._cache.clear()
            self._cache[scope_id] = dek
        return dek

    def destroy(self, scope_id: str) -> None:
        self.db.run(f"DELETE FROM {self.table} WHERE {self.id_col}=?", (scope_id,))
        with self._lock:
            self._cache.pop(scope_id, None)

    def seal(self, scope_id: str, plaintext: str, aad: str) -> str:
        return Vault.seal(self.get(scope_id), plaintext, aad)

    def open(self, scope_id: str, blob: Optional[str], aad: str) -> str:
        return "" if blob is None else Vault.open(self.get(scope_id), blob, aad)

    def blind_key(self, scope_id: str) -> bytes:
        return hmac.new(self.get(scope_id), b"xnva-blind-index-v1", hashlib.sha256).digest()

    def rewrap_all(self, new_vault: Vault) -> int:
        rows = self.db.all(f"SELECT {self.id_col} AS sid, wrapped FROM {self.table}")
        with self.db.tx():
            for row in rows:
                dek = self.vault.unwrap(row["wrapped"], self._aad(row["sid"]))
                self.db.run(
                    f"UPDATE {self.table} SET wrapped=? WHERE {self.id_col}=?",
                    (new_vault.wrap(dek, self._aad(row["sid"])), row["sid"]),
                )
        return len(rows)
