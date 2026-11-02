# Konfigurasi backend

Seluruh konfigurasi lewat **variabel lingkungan**. Tidak ada secret yang punya nilai bawaan: tanpa `XNVA_MASTER_KEY` dan `XNVA_JWT_SECRET` layanan menolak start. Nilai di luar rentang yang diizinkan juga menghentikan start dengan pesan yang jelas.

`deploy/init-env.sh` membuat berkas `.env` berisi secret acak baru.

## Inti

| Variabel | Default | Keterangan |
|---|---|---|
| `XNVA_ENV` | `production` | `production`, `development`, atau `test`. Pada `production`, `XNVA_SCRYPT_N` minimal 32768 dan HSTS dikirim lewat HTTPS. |
| `XNVA_DATA_DIR` | `./data` | Lokasi `personal.db` dan `enterprise.db`. **Cadangkan.** |
| `XNVA_MASTER_KEY` | wajib | 32 byte base64: `openssl rand -base64 32`. Membungkus semua data key. **Hilang = seluruh data terenkripsi tak bisa dipulihkan.** Cadangkan terpisah dari data. |
| `XNVA_JWT_SECRET` | wajib | ≥ 32 karakter. Menandatangani access token (HS256). Mengganti nilainya mencabut semua access token yang sedang beredar. |
| `XNVA_TRUSTED_PROXIES` | `0` | Jumlah reverse proxy tepercaya di depan aplikasi (0–5), agar IP klien (rate limit, audit) benar. Compose memakai `1`. |
| `XNVA_CORS_ORIGINS` | kosong | Daftar origin (dipisah koma) yang boleh mengakses API dari browser. Aplikasi Android tidak membutuhkannya. |
| `XNVA_MAX_BODY_MB` | `8` | Batas ukuran request (gambar/audio base64 harus muat). |

## Akun dan keamanan

| Variabel | Default | Keterangan |
|---|---|---|
| `XNVA_ALLOW_PERSONAL_SIGNUP` | `1` | Pendaftaran akun Personal terbuka. |
| `XNVA_ALLOW_ORG_SIGNUP` | `0` | Pembuatan organisasi mandiri. Default mati: organisasi dibuat operator (CLI atau `POST /v1/platform/orgs`). |
| `XNVA_PLATFORM_TOKEN` | kosong | ≥ 32 karakter. Mengaktifkan `POST /v1/platform/orgs`. Kosong = endpoint selalu menolak. |
| `XNVA_ACCESS_TTL_S` | `900` | Umur access token (60–86400). |
| `XNVA_REFRESH_TTL_DAYS` | `30` | Umur refresh token; diperpanjang setiap rotasi. |
| `XNVA_SCRYPT_N` / `XNVA_SCRYPT_P` | `32768` / `3` | Biaya hash password (r=8). Hash lama otomatis di-upgrade saat login. |
| `XNVA_LOGIN_MAX_FAILURES` | `8` | Percobaan gagal sebelum akun dikunci. |
| `XNVA_LOGIN_LOCK_MINUTES` | `15` | Lama penguncian. |

## Kuota AI

| Variabel | Default | Keterangan |
|---|---|---|
| `XNVA_PERSONAL_DAILY_AI_LIMIT` | `200` | Permintaan AI per pengguna Personal per hari (UTC). |
| `XNVA_ORG_MONTHLY_AI_QUOTA` | `20000` | Kuota bulanan organisasi baru; dapat diubah per organisasi (`ai_monthly_quota`). |

## Penyedia model XNAI

Tanpa konfigurasi ini server tetap berjalan; endpoint AI menjawab **503 `xnai_not_configured`** dan `/v1/status` melaporkan `not_configured`.

| Variabel | Keterangan |
|---|---|
| `XNAI_PROVIDER` | `openai` (OpenAI atau server kompatibel: vLLM, Ollama, LM Studio, OpenRouter, …) atau `anthropic`. |
| `XNAI_BASE_URL` | Default: `https://api.openai.com/v1` / `https://api.anthropic.com`. Untuk server sendiri, isi URL-nya (termasuk `/v1` bila dipakai). |
| `XNAI_API_KEY` | Kunci penyedia. Wajib untuk `anthropic`; opsional untuk server lokal. |
| `XNAI_MODEL` | **Wajib** (tidak ada nama model bawaan agar tidak usang). Harus mendukung chat. |
| `XNAI_VISION_MODEL` | Model untuk gambar; default sama dengan `XNAI_MODEL` (harus mendukung gambar). |
| `XNAI_MAX_TOKENS` | `1024`. Mode `SUPERAUTOMATION` memakai 2× (maks 4096), `RELAX` maks 512. |
| `XNAI_MAX_TOKENS_PARAM` | `max_tokens` atau `max_completion_tokens` (sebagian model OpenAI baru). |
| `XNAI_TIMEOUT_S` | `60` (5–300). |
| `XNAI_STT_BASE_URL`, `XNAI_STT_API_KEY`, `XNAI_STT_MODEL` | Speech-to-text: endpoint `/audio/transcriptions` kompatibel OpenAI. Diperlukan agar tahan-tombol-bicara di kacamata berfungsi. |

Yang dikirim ke penyedia: teks pesan + riwayat singkat + konteks profil (Personal, hanya bila pengguna mengizinkan Cloud Processing), potongan dokumen yang boleh dibaca peminta (Enterprise), gambar yang sudah dibersihkan metadata-nya, atau audio. Kunci penyedia **tidak pernah** dikirim ke klien.
