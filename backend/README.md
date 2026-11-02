# Backend SmartEyeX

Python 3.12 · Flask · SQLite (WAL) · `cryptography` · PyJWT. Rancangan: [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md). API: [docs/openapi.yaml](../docs/openapi.yaml). Konfigurasi: [docs/CONFIGURATION.md](../docs/CONFIGURATION.md). Operasi: [docs/OPERATIONS.md](../docs/OPERATIONS.md).

```bash
pip install -r requirements-dev.txt
python -m unittest discover -s tests -t .       # seluruh tes
python -m tools.gen_openapi                     # regenerasi ../docs/openapi.yaml
python -m xnva --help                           # CLI operator
gunicorn xnva.wsgi:app                          # produksi (lihat Dockerfile)
```

```
xnva/
  app.py config.py db.py schema.py        aplikasi, konfigurasi, SQLite, skema (dua database)
  crypto.py audit.py kb.py orgs.py        kripto + keyring, audit berantai, knowledge base, provisioning
  security.py validate.py errors.py       JWT/RBAC/rate limit, validasi, error JSON
  api/                                    auth, personal, devices, xnai_api, ent_admin, ent_work, ent_insight, misc
  xnai/                                   provider (OpenAI-compatible/Anthropic), prompt, sanitasi gambar, layanan
  maintenance.py cli.py                   retensi, CLI
tests/                                    108 tes (termasuk server penyedia tiruan untuk XNAI)
```
