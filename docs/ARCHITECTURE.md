# Arsitektur SmartEyeX

## Gambaran

```
                       ┌────────────────────────── Backend SmartEyeX (Flask) ───────────────────────────┐
                       │  API Gateway: validasi, rate limit, header keamanan, request-id                │
Kacamata ──BLE──► App ─┤  Auth Service ── JWT 15 mnt + refresh rotasi ── Audit Service (rantai hash)    │
 ESP32-S3   Android    │      │                                                                          │
                       │  ┌───┴─────────────┐                         ┌─────────────────────────┐        │
                       │  │ Personal Service│                         │ Enterprise Service      │        │
                       │  │ memori·reminder │                         │ org·divisi·karyawan·RBAC│        │
                       │  │ privasi·ekspor  │                         │ proyek·tugas·pengumuman │        │
                       │  └───┬─────────────┘                         │ rapat·laporan·analitik  │        │
                       │  personal.db  ◄─ keyring pengguna            │ knowledge base (BM25)   │        │
                       │  (terenkripsi)                                └───┬─────────────────────┘        │
                       │                                          enterprise.db ◄─ keyring per organisasi│
                       │  Device Service · Notification inbox · XNAI Core ──► penyedia model (HTTPS)     │
                       └─────────────────────────────────────────────────────────────────────────────────┘
```

Satu basis kode, dua lingkungan operasional. **Pemisahan dipaksa di backend**, tidak hanya di UI:

| Lapisan | Personal | Enterprise |
|---|---|---|
| Penyimpanan | `personal.db` | `enterprise.db` (berkas fisik terpisah; tidak ada query lintas realm) |
| Identitas | tabel `users` | tabel `orgs` + `employees`, login memakai slug organisasi |
| Token | audience `smarteyex-personal` | audience `smarteyex-enterprise`; token realm lain ditolak (403 `wrong_realm`) |
| Kunci enkripsi | satu data key per pengguna | satu data key per organisasi |
| Otorisasi | pemilik akun | RBAC dari **database pada setiap request** (peran berubah/akun dinonaktifkan berlaku seketika) |
| Audit | peristiwa keamanan, IP dipotong (/24, /48) | rantai hash per organisasi, IP penuh |

Batas yang wajib dijaga dan dites: memori pribadi tidak masuk knowledge base perusahaan dan sebaliknya (`tests/test_enterprise.py::TenantIsolationTests`, `tests/test_auth.py::test_personal_and_enterprise_tokens_are_not_interchangeable`).

## Komponen backend

| Komponen (spesifikasi) | Modul | Catatan |
|---|---|---|
| API Gateway | `xnva/app.py`, `security.py`, `errors.py` | validasi input, rate limit, error JSON seragam, header keamanan |
| Authentication Service | `api/auth.py` | scrypt (N=32768), lockout per akun, refresh rotasi + deteksi pemakaian ulang |
| Personal Service / Memory Service | `api/personal.py` | memori & reminder terenkripsi, sinkronisasi last-write-wins, ekspor, hapus akun |
| Enterprise Service | `api/ent_admin.py`, `ent_work.py`, `ent_insight.py` | organisasi, RBAC, tugas, rapat, laporan, analitik, audit |
| Enterprise Knowledge Service | `kb.py` | potongan teks terenkripsi + indeks buta, BM25, filter visibilitas **sebelum** model melihat teks |
| XNAI Core | `xnai/` | adapter OpenAI-compatible dan Anthropic; prompt; kuota; sanitasi gambar |
| Vision Service | `xnai/service.py`, `xnai/images.py` | EXIF dibuang, ukuran dibatasi, tidak disimpan; mode `describe`/`read`/`inspect` |
| Notification Service | `api/misc.py` | kotak masuk persisten (polling); isi sensitif tidak dimasukkan ke teks notifikasi |
| Device Service | `api/devices.py` | registri kacamata, secret per perangkat (terenkripsi), rotasi, pencabutan |
| Audit Service | `audit.py` | rantai hash, verifikasi, ekspor NDJSON, retensi dengan checkpoint |

## Kriptografi

```
XNVA_MASTER_KEY (env, 32 byte) ──wrap──► data key per pengguna/organisasi (AES-256-GCM, tabel user_keys / tenant_keys)
                                              │
                          ┌───────────────────┼─────────────────────────┐
                    seal(field, AAD)     secret perangkat         kunci indeks buta (HMAC)
```

- Setiap kolom sensitif disegel dengan AES-256-GCM; **AAD = tabel.pemilik.id.kolom**, sehingga ciphertext tidak bisa dipindah antar baris/kolom.
- Menghapus akun/organisasi menghapus data key-nya (**crypto-shredding**) sekaligus barisnya; `secure_delete` mengosongkan halaman SQLite.
- Rotasi kunci induk: `python -m xnva rotate-master-key` (membungkus ulang data key; data tidak dienkripsi ulang).
- Service menolak start bila `XNVA_MASTER_KEY` tidak cocok dengan data yang ada (mencegah campuran kunci).
- Pencarian knowledge base: istilah disimpan sebagai `HMAC(kunci_org, istilah)` 64-bit; database tidak memuat kosakata terbaca. Kebocoran yang diterima: pola frekuensi istilah (batasan *searchable encryption* umum).

## Alur utama

**Tanya suara lewat kacamata** (tahan tombol): kacamata → mic ADPCM 16 kHz via BLE → ponsel menyusun WAV → `POST /v1/xnai/transcribe` → `POST /v1/xnai/chat` (Enterprise: retrieval knowledge base dengan filter peran) → TTS di ponsel → ADPCM via BLE → speaker bone-conduction. Setiap langkah cloud lewat `CloudGate` (Cloud Processing ON + sudah masuk) dan diperiksa lagi di server.

**Foto** (tekan sekali / dua kali): LED kamera menyala → sensor aktif → JPEG dikirim lewat BLE (CRC32) → `POST /v1/xnai/vision` (EXIF dibuang, tidak disimpan) → jawaban dibacakan.

**Pairing kacamata**: tombol ditahan saat menyala (3 dtk) membuka jendela 60 dtk → ponsel mendaftarkan perangkat (server membuat secret 32 byte) → secret ditulis ke kacamata lewat link BLE terenkripsi → sesi berikutnya memakai autentikasi HMAC timbal-balik. Rinci: [BLE_PROTOCOL.md](BLE_PROTOCOL.md).

## Batas yang diketahui

Lihat [STATUS.md](STATUS.md) dan [SECURITY.md](../SECURITY.md). Singkatnya: SQLite satu node (belum Postgres/multi-node), rate limit dalam proses, belum ada SSO/MFA, notifikasi lewat polling (belum FCM), pairing BLE "Just Works" dengan jendela fisik 60 detik.
