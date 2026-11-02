#!/usr/bin/env sh
# Membuat deploy/.env berisi secret acak baru. Tidak pernah menimpa file yang sudah ada.
# Pemakaian: ./init-env.sh --domain api.domain-anda.com
set -eu
cd "$(dirname "$0")"
DOMAIN=""
while [ $# -gt 0 ]; do
  case "$1" in
    --domain) DOMAIN="${2:-}"; shift 2 ;;
    *) echo "Argumen tidak dikenal: $1" >&2; exit 2 ;;
  esac
done
[ -n "$DOMAIN" ] || { echo "Wajib: --domain nama.domain.anda" >&2; exit 2; }
[ ! -e .env ] || { echo ".env sudah ada; tidak ditimpa." >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl diperlukan" >&2; exit 1; }
umask 077
cat > .env <<ENVEOF
# Dibuat oleh init-env.sh. JANGAN commit file ini.
XNVA_ENV=production
XNVA_DOMAIN=$DOMAIN
XNVA_TRUSTED_PROXIES=1

# Kunci induk enkripsi data. Jika hilang, SEMUA data terenkripsi tidak bisa dipulihkan. Cadangkan di tempat aman.
XNVA_MASTER_KEY=$(openssl rand -base64 32)
XNVA_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
# Token operator untuk POST /v1/platform/orgs (provisioning organisasi). Hapus baris ini bila tidak dipakai.
XNVA_PLATFORM_TOKEN=$(openssl rand -hex 32)

# Pendaftaran akun Personal terbuka? (1/0). Pembuatan organisasi mandiri sengaja dimatikan.
XNVA_ALLOW_PERSONAL_SIGNUP=1
XNVA_ALLOW_ORG_SIGNUP=0

# --- Penyedia model XNAI: isi salah satu blok lalu jalankan ulang compose ---
# Server kompatibel OpenAI (OpenAI, vLLM, Ollama, LM Studio, OpenRouter, ...):
# XNAI_PROVIDER=openai
# XNAI_BASE_URL=https://api.openai.com/v1
# XNAI_API_KEY=
# XNAI_MODEL=
# XNAI_VISION_MODEL=
# Anthropic:
# XNAI_PROVIDER=anthropic
# XNAI_API_KEY=
# XNAI_MODEL=
# Speech-to-text (endpoint /audio/transcriptions kompatibel OpenAI):
# XNAI_STT_BASE_URL=
# XNAI_STT_API_KEY=
# XNAI_STT_MODEL=
ENVEOF
echo "Dibuat: $(pwd)/.env (izin 600)."
echo "Cadangkan XNVA_MASTER_KEY sekarang. Lalu:  docker compose up -d --build"
