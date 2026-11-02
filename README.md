# SmartEyeX

Platform kacamata AI wearable dari **XNVA Labs**: satu fondasi AI (**XNAI**), dua lingkungan yang terpisah.

| | SmartEyeX **Personal** | SmartEyeX **Enterprise** |
|---|---|---|
| Untuk | Individu: pelajar, pekerja, kreator | Perusahaan, UMKM, institusi |
| AI | Pendamping harian | Asisten operasional + knowledge base |
| Data | Lokal dulu; cloud hanya dengan izin | Tenant terisolasi per organisasi, terenkripsi |
| Akses | Pemilik akun | Peran: owner, admin, manager, member, auditor |
| Kamera | Bantuan sehari-hari | Inspeksi sebagai **pendukung keputusan** |

Keduanya memakai kacamata yang sama (ESP32 + kamera + mikrofon + speaker bone-conduction) yang terhubung ke ponsel lewat Bluetooth LE.

```
 Kacamata (ESP32-S3) ──BLE──► Aplikasi Android ──HTTPS──► Backend SmartEyeX ──► Penyedia model XNAI
   kamera · mic · speaker        (Kotlin/Compose)           Personal DB │ Enterprise DB      (dipilih operator)
```

## Isi repositori

| Folder | Isi | Teruji di sini |
|---|---|---|
| [`backend/`](backend) | API platform (Python/Flask, SQLite): auth, RBAC, enkripsi, audit, XNAI, knowledge base | ✅ 108 tes otomatis |
| [`firmware/`](firmware) | Firmware kacamata (PlatformIO, ESP32-S3) + inti protokol | ✅ inti protokol · 🟡 driver perangkat keras |
| [`app/`](app) | Aplikasi Android (Kotlin, Jetpack Compose) | 🟡 belum dikompilasi di lingkungan penulis |
| [`docs/`](docs) | Arsitektur, protokol, hardware, operasi, keamanan, kebijakan | — |
| [`deploy/`](deploy) | Docker Compose + HTTPS otomatis (Caddy) + pembuat secret | 🟡 |

Status jujur setiap fitur: **[docs/STATUS.md](docs/STATUS.md)**. Baca itu sebelum mengklaim fitur kepada pengguna.

## Mulai cepat

**Backend (pengembangan lokal)**

```bash
cd backend
python -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
export XNVA_ENV=development XNVA_DATA_DIR=./data \
  XNVA_MASTER_KEY=$(openssl rand -base64 32) XNVA_JWT_SECRET=$(openssl rand -base64 48) \
  XNVA_PLATFORM_TOKEN=$(openssl rand -hex 32)
python -m unittest discover -s tests -t .      # 108 tes
flask --app xnva.wsgi run --port 8080
```

**Produksi (Docker + HTTPS)**: `cd deploy && ./init-env.sh --domain api.domain-anda.com && docker compose up -d --build` — lihat [docs/OPERATIONS.md](docs/OPERATIONS.md).

**Aplikasi Android**: buka di Android Studio, atau `gradle :app:assembleDebug`. Alamat server bisa di-bake saat build (`-PXNVA_API_BASE_URL=https://…`) atau diisi pengguna di layar Welcome.

**Firmware**: `cd firmware && pio run -e xiao_esp32s3_sense -t upload` — panduan pengkabelan dan bring-up di [docs/HARDWARE.md](docs/HARDWARE.md).

Semua langkah lengkap: [docs/INSTALL.md](docs/INSTALL.md).

## Prinsip yang ditegakkan kode (bukan sekadar janji)

- Pemisahan **Personal** dan **Enterprise** ada di backend: dua file database, dua audience token, dua keyring enkripsi. Menyembunyikan menu tidak dianggap pemisahan.
- Data di server **terenkripsi saat tersimpan** (AES-256-GCM, kunci per pengguna/organisasi, dibungkus kunci induk). Tes memastikan teks asli tidak ditemukan di berkas database.
- Setiap aksi yang mengirim data keluar ponsel melewati satu gerbang (Cloud Processing ON + sudah masuk) dan **diperiksa ulang oleh server**.
- LED kamera di kacamata **menyala sebelum sensor dihidupkan** dan tetap menyala ≥ 0,7 detik setelahnya; saklar privasi memblokir kamera dan mikrofon.
- Inspeksi visual Enterprise selalu ditandai sebagai **pendukung keputusan**; peringatan itu ditambahkan server, bukan model.
- Analitik Enterprise agregat per organisasi/divisi. Satu-satunya data per orang adalah jumlah tugas terbuka (terlihat manajer, dinyatakan di endpoint transparansi); isi percakapan AI dan gambar tidak disimpan ([transparansi](backend/xnva/api/ent_insight.py)).
- Fitur yang belum ada **tidak ditampilkan sebagai fitur**: server melaporkan `not_configured` dengan jujur.

## Dokumentasi

[Arsitektur](docs/ARCHITECTURE.md) · [Protokol BLE](docs/BLE_PROTOCOL.md) · [Hardware](docs/HARDWARE.md) · [API (OpenAPI)](docs/openapi.yaml) · [Konfigurasi](docs/CONFIGURATION.md) · [Operasi](docs/OPERATIONS.md) · [Keamanan](SECURITY.md) · [Kebijakan Privasi](docs/PRIVACY_POLICY.md) · [Ketentuan Layanan](docs/TERMS.md) · [Rilis Google Play](docs/PLAY_STORE.md) · [Status](docs/STATUS.md) · [Roadmap](docs/ROADMAP.md) · [Kontribusi](CONTRIBUTING.md) · [Changelog](CHANGELOG.md)

## Lisensi

Repositori ini belum memuat berkas `LICENSE`. Tanpa lisensi, hukum hak cipta default berlaku: seluruh hak dipegang XNVA Labs dan pihak lain belum berhak memakai ulang kode ini. Keputusan lisensi (mis. permisif untuk aplikasi/firmware, copyleft untuk backend) ada pada pemilik proyek; lihat [CONTRIBUTING.md](CONTRIBUTING.md).
