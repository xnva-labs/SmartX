"""Runtime configuration, read only from environment variables.

Nothing sensitive has a default: the master key and JWT secret must be set
explicitly, otherwise the service refuses to start.
"""
from __future__ import annotations

import base64
import binascii
import os
from dataclasses import dataclass
from typing import Mapping, Optional


class ConfigError(RuntimeError):
    pass


@dataclass(frozen=True)
class XnaiConfig:
    provider: str  # "", "openai" (any OpenAI-compatible server) or "anthropic"
    base_url: str
    api_key: str
    model: str
    vision_model: str
    stt_base_url: str
    stt_api_key: str
    stt_model: str
    timeout_s: int
    max_tokens: int
    max_tokens_param: str

    @property
    def chat_ready(self) -> bool:
        if self.provider == "anthropic":
            return bool(self.model and self.api_key)
        if self.provider == "openai":
            return bool(self.model and (self.api_key or self.base_url))
        return False

    @property
    def vision_ready(self) -> bool:
        return self.chat_ready

    @property
    def stt_ready(self) -> bool:
        return bool(self.stt_base_url and self.stt_model)


@dataclass(frozen=True)
class Settings:
    env: str
    data_dir: str
    master_key: bytes
    jwt_secret: str
    access_ttl_s: int
    refresh_ttl_days: int
    scrypt_n: int
    scrypt_r: int
    scrypt_p: int
    allow_personal_signup: bool
    allow_org_signup: bool
    platform_token: Optional[str]
    max_body_bytes: int
    personal_daily_ai_limit: int
    org_default_monthly_ai_quota: int
    login_max_failures: int
    login_lock_minutes: int
    trusted_proxies: int
    cors_origins: tuple
    xnai: XnaiConfig

    @property
    def production(self) -> bool:
        return self.env == "production"


def _get(env: Mapping[str, str], name: str, default: str = "") -> str:
    v = env.get(name)
    return v.strip() if v is not None and v.strip() != "" else default


def _int(env: Mapping[str, str], name: str, default: int, lo: int, hi: int) -> int:
    raw = _get(env, name, str(default))
    try:
        v = int(raw)
    except ValueError as e:
        raise ConfigError(f"{name} harus berupa bilangan bulat") from e
    if not lo <= v <= hi:
        raise ConfigError(f"{name} harus di antara {lo} dan {hi}")
    return v


def _bool(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = _get(env, name, "1" if default else "0").lower()
    if raw in ("1", "true", "yes", "on"):
        return True
    if raw in ("0", "false", "no", "off"):
        return False
    raise ConfigError(f"{name} harus 0/1")


def load_settings(env: Optional[Mapping[str, str]] = None) -> Settings:
    env = os.environ if env is None else env
    mode = _get(env, "XNVA_ENV", "production")
    if mode not in ("production", "development", "test"):
        raise ConfigError("XNVA_ENV harus production, development, atau test")

    raw_key = _get(env, "XNVA_MASTER_KEY")
    if not raw_key:
        raise ConfigError("XNVA_MASTER_KEY wajib diisi (32 byte, base64). Buat dengan: openssl rand -base64 32")
    try:
        master = base64.b64decode(raw_key, validate=True)
    except (binascii.Error, ValueError) as e:
        raise ConfigError("XNVA_MASTER_KEY bukan base64 yang valid") from e
    if len(master) != 32:
        raise ConfigError("XNVA_MASTER_KEY harus tepat 32 byte setelah decode base64")

    jwt_secret = _get(env, "XNVA_JWT_SECRET")
    if len(jwt_secret) < 32:
        raise ConfigError("XNVA_JWT_SECRET wajib diisi, minimal 32 karakter. Buat dengan: openssl rand -base64 48")

    scrypt_n = _int(env, "XNVA_SCRYPT_N", 2**15, 2**10, 2**20)
    if mode == "production" and scrypt_n < 2**15:
        raise ConfigError("XNVA_SCRYPT_N minimal 32768 pada production")

    platform_token = _get(env, "XNVA_PLATFORM_TOKEN") or None
    if platform_token is not None and len(platform_token) < 32:
        raise ConfigError("XNVA_PLATFORM_TOKEN minimal 32 karakter")

    provider = _get(env, "XNAI_PROVIDER").lower()
    if provider not in ("", "openai", "anthropic"):
        raise ConfigError("XNAI_PROVIDER harus kosong, openai, atau anthropic")
    default_base = {"openai": "https://api.openai.com/v1", "anthropic": "https://api.anthropic.com"}.get(provider, "")
    xnai = XnaiConfig(
        provider=provider,
        base_url=_get(env, "XNAI_BASE_URL", default_base).rstrip("/"),
        api_key=_get(env, "XNAI_API_KEY"),
        model=_get(env, "XNAI_MODEL"),
        vision_model=_get(env, "XNAI_VISION_MODEL") or _get(env, "XNAI_MODEL"),
        stt_base_url=_get(env, "XNAI_STT_BASE_URL").rstrip("/"),
        stt_api_key=_get(env, "XNAI_STT_API_KEY"),
        stt_model=_get(env, "XNAI_STT_MODEL"),
        timeout_s=_int(env, "XNAI_TIMEOUT_S", 60, 5, 300),
        max_tokens=_int(env, "XNAI_MAX_TOKENS", 1024, 64, 8192),
        max_tokens_param=_get(env, "XNAI_MAX_TOKENS_PARAM", "max_tokens"),
    )
    if xnai.max_tokens_param not in ("max_tokens", "max_completion_tokens"):
        raise ConfigError("XNAI_MAX_TOKENS_PARAM harus max_tokens atau max_completion_tokens")

    return Settings(
        env=mode,
        data_dir=_get(env, "XNVA_DATA_DIR", "./data"),
        master_key=master,
        jwt_secret=jwt_secret,
        access_ttl_s=_int(env, "XNVA_ACCESS_TTL_S", 900, 60, 86400),
        refresh_ttl_days=_int(env, "XNVA_REFRESH_TTL_DAYS", 30, 1, 365),
        scrypt_n=scrypt_n,
        scrypt_r=8,
        scrypt_p=_int(env, "XNVA_SCRYPT_P", 3, 1, 16),
        allow_personal_signup=_bool(env, "XNVA_ALLOW_PERSONAL_SIGNUP", True),
        allow_org_signup=_bool(env, "XNVA_ALLOW_ORG_SIGNUP", False),
        platform_token=platform_token,
        max_body_bytes=_int(env, "XNVA_MAX_BODY_MB", 8, 1, 64) * 1024 * 1024,
        personal_daily_ai_limit=_int(env, "XNVA_PERSONAL_DAILY_AI_LIMIT", 200, 1, 1_000_000),
        org_default_monthly_ai_quota=_int(env, "XNVA_ORG_MONTHLY_AI_QUOTA", 20000, 1, 100_000_000),
        login_max_failures=_int(env, "XNVA_LOGIN_MAX_FAILURES", 8, 3, 100),
        login_lock_minutes=_int(env, "XNVA_LOGIN_LOCK_MINUTES", 15, 1, 1440),
        trusted_proxies=_int(env, "XNVA_TRUSTED_PROXIES", 0, 0, 5),
        cors_origins=tuple(o for o in _get(env, "XNVA_CORS_ORIGINS").split(",") if o),
        xnai=xnai,
    )
