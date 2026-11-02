from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any

from .audit import AuditLog
from .config import ConfigError, Settings
from .crypto import KeyRing, PasswordHasher, Vault
from .db import Database
from .schema import ENTERPRISE, PERSONAL
from .security import RateLimiter


@dataclass
class Services:
    settings: Settings
    pdb: Database
    edb: Database
    vault: Vault
    pkeys: KeyRing
    ekeys: KeyRing
    hasher: PasswordHasher
    paudit: AuditLog
    eaudit: AuditLog
    limiter: RateLimiter
    xnai: Any = None


def build_services(settings: Settings) -> Services:
    os.makedirs(settings.data_dir, exist_ok=True)
    pdb = Database(os.path.join(settings.data_dir, "personal.db"), PERSONAL, "personal")
    edb = Database(os.path.join(settings.data_dir, "enterprise.db"), ENTERPRISE, "enterprise")
    pdb.migrate()
    edb.migrate()
    vault = Vault(settings.master_key)
    sv = _assemble(settings, pdb, edb, vault)
    for kr in (sv.pkeys, sv.ekeys):  # a wrong master key must stop the service, never mix keys
        row = kr.db.one(f"SELECT {kr.id_col} AS sid, wrapped FROM {kr.table} LIMIT 1")
        if row is not None:
            try:
                vault.unwrap(row["wrapped"], kr._aad(row["sid"]))
            except Exception as e:
                raise ConfigError("XNVA_MASTER_KEY tidak cocok dengan data yang tersimpan; layanan dihentikan") from e
    return sv


def _assemble(settings: Settings, pdb: Database, edb: Database, vault: Vault) -> Services:
    return Services(
        settings=settings, pdb=pdb, edb=edb, vault=vault,
        pkeys=KeyRing(pdb, vault, "user_keys", "user_id"),
        ekeys=KeyRing(edb, vault, "tenant_keys", "org_id"),
        hasher=PasswordHasher(settings.scrypt_n, settings.scrypt_r, settings.scrypt_p),
        paudit=AuditLog(pdb, truncate_ip=True), eaudit=AuditLog(edb), limiter=RateLimiter(),
    )
