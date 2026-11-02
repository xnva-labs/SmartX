"""Operator CLI: python -m xnva <command>"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import shutil
import sys
import time

from . import __version__, maintenance
from .config import ConfigError, load_settings
from .crypto import Vault
from .orgs import create_org
from .services import build_services

_SLUG = __import__("re").compile(r"[a-z0-9][a-z0-9\-]{1,38}[a-z0-9]")


def _sha(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _restore(settings, src: str, force: bool) -> int:
    with open(os.path.join(src, "manifest.json")) as f:
        manifest = json.load(f)
    for name, digest in manifest["files"].items():
        if _sha(os.path.join(src, name)) != digest:
            print(f"Checksum {name} tidak cocok; backup rusak", file=sys.stderr)
            return 1
    os.makedirs(settings.data_dir, exist_ok=True)
    if any(os.path.exists(os.path.join(settings.data_dir, n)) for n in manifest["files"]) and not force:
        print("Database tujuan sudah ada. Hentikan layanan lalu jalankan ulang dengan --force", file=sys.stderr)
        return 1
    for name in manifest["files"]:
        for suffix in ("", "-wal", "-shm"):
            p = os.path.join(settings.data_dir, name + suffix)
            if os.path.exists(p):
                os.remove(p)
        shutil.copy2(os.path.join(src, name), os.path.join(settings.data_dir, name))
    print("pemulihan selesai")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="xnva", description="SmartEyeX platform operator CLI")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("init", help="buat/migrasi database")
    c = sub.add_parser("create-org", help="buat organisasi + undangan untuk owner")
    c.add_argument("--name", required=True)
    c.add_argument("--slug", required=True)
    c.add_argument("--owner-email", required=True)
    c.add_argument("--owner-name", required=True)
    c.add_argument("--quota", type=int)
    sub.add_parser("maintenance", help="jalankan retensi dan pembersihan")
    v = sub.add_parser("verify-audit", help="verifikasi rantai hash audit log")
    v.add_argument("--org", help="slug organisasi (default: semua + semua akun personal)")
    b = sub.add_parser("backup", help="snapshot konsisten kedua database")
    b.add_argument("--dest", required=True)
    r = sub.add_parser("restore", help="pulihkan dari backup (hentikan layanan dulu)")
    r.add_argument("--src", required=True)
    r.add_argument("--force", action="store_true")
    sub.add_parser("rotate-master-key", help="bungkus ulang semua data key dengan XNVA_NEW_MASTER_KEY")
    sub.add_parser("version")
    a = ap.parse_args(argv)
    if a.cmd == "version":
        print(__version__)
        return 0
    try:
        settings = load_settings()
    except ConfigError as e:
        print(f"Konfigurasi tidak valid: {e}", file=sys.stderr)
        return 2
    if a.cmd == "restore":
        return _restore(settings, a.src, a.force)
    try:
        sv = build_services(settings)
    except ConfigError as e:
        print(f"Konfigurasi tidak valid: {e}", file=sys.stderr)
        return 2

    if a.cmd == "init":
        print(json.dumps({"personal": sv.pdb.integrity_ok(), "enterprise": sv.edb.integrity_ok(), "data_dir": settings.data_dir}))
    elif a.cmd == "create-org":
        if not _SLUG.fullmatch(a.slug):
            print("slug harus 3-40 karakter: huruf kecil, angka, tanda minus", file=sys.stderr)
            return 2
        print(json.dumps(create_org(sv, a.name, a.slug, a.owner_email.lower(), a.owner_name, a.quota), indent=2))
    elif a.cmd == "maintenance":
        print(json.dumps(maintenance.run(sv), indent=2))
    elif a.cmd == "verify-audit":
        results, ok = {}, True
        if a.org:
            row = sv.edb.one("SELECT id FROM orgs WHERE slug=?", (a.org,))
            scopes = [("enterprise", row["id"], a.org)] if row else []
        else:
            scopes = [("enterprise", r["id"], r["slug"]) for r in sv.edb.all("SELECT id, slug FROM orgs")]
            scopes += [("personal", r["scope_id"], r["scope_id"]) for r in sv.pdb.all("SELECT DISTINCT scope_id FROM audit_log")]
        for realm, sid, label in scopes:
            res = (sv.eaudit if realm == "enterprise" else sv.paudit).verify(sid)
            ok &= res["ok"]
            results[f"{realm}:{label}"] = res
        print(json.dumps({"ok": ok, "scopes": results}, indent=2))
        return 0 if ok else 1
    elif a.cmd == "backup":
        stamp = time.strftime("%Y%m%dT%H%M%SZ", time.gmtime())
        dest = os.path.join(a.dest, f"xnva-backup-{stamp}")
        manifest = {"version": __version__, "created_utc": stamp, "files": {}}
        for name, db in (("personal.db", sv.pdb), ("enterprise.db", sv.edb)):
            path = os.path.join(dest, name)
            db.backup_to(path)
            manifest["files"][name] = _sha(path)
        with open(os.path.join(dest, "manifest.json"), "w") as f:
            json.dump(manifest, f, indent=2)
        print(dest)
    elif a.cmd == "rotate-master-key":
        raw = os.environ.get("XNVA_NEW_MASTER_KEY", "")
        try:
            new = base64.b64decode(raw, validate=True)
            assert len(new) == 32
        except Exception:
            print("XNVA_NEW_MASTER_KEY wajib berupa 32 byte base64", file=sys.stderr)
            return 2
        nv = Vault(new)
        print(json.dumps({"rewrapped_user_keys": sv.pkeys.rewrap_all(nv), "rewrapped_tenant_keys": sv.ekeys.rewrap_all(nv),
                          "next": "ganti XNVA_MASTER_KEY dengan nilai baru lalu restart layanan"}))
    return 0
