# Operasi

## Deploy produksi (satu server)

Prasyarat: server Linux dengan Docker + Compose, nama domain yang A/AAAA-nya menunjuk ke server, port 80/443 terbuka.

```bash
git clone <repositori-anda> smarteyex && cd smarteyex/deploy
./init-env.sh --domain api.domain-anda.com     # membuat .env berisi secret acak (izin 600)
# edit .env: isi blok XNAI_* (lihat CONFIGURATION.md)
docker compose up -d --build
curl https://api.domain-anda.com/v1/status
```

Caddy mengurus sertifikat HTTPS (Let's Encrypt) otomatis. Layanan `maintenance` menjalankan retensi harian. **Cadangkan `XNVA_MASTER_KEY` di tempat terpisah dari backup data sebelum menerima pengguna pertama.**

### Membuat organisasi (alur Enterprise)

```bash
docker compose exec api python -m xnva create-org \
  --name "PT Contoh" --slug contoh --owner-email owner@contoh.id --owner-name "Nama Owner"
```

Perintah mencetak `invite_token` (berlaku 7 hari, sekali pakai). Owner membukanya di aplikasi (Enterprise → *Saya punya kode undangan*), membuat password, lalu mengundang karyawan dari tab **Tim** (kode undangan ditampilkan sekali ke admin untuk dibagikan lewat saluran aman; server tidak mengirim email). Alternatif lewat API: `POST /v1/platform/orgs` dengan header `X-Platform-Token`.

## Cadangan dan pemulihan

```bash
# Cadangan konsisten (online, aman saat layanan berjalan) + verifikasi checksum di manifest
docker compose exec -T api python -m xnva backup --dest /data/backups
# SALIN hasilnya ke luar server (object storage / host lain). Backup di volume yang sama tidak melindungi dari kehilangan disk.

# Pemulihan (hentikan layanan dulu)
docker compose stop api maintenance
docker compose run --rm api python -m xnva restore --src /data/backups/xnva-backup-<stempel> --force
docker compose up -d
```

`restore` memverifikasi SHA-256 setiap berkas dan menolak backup yang rusak. **Uji pemulihan secara berkala** pada mesin lain. Jadwalkan cadangan lewat cron host, mis. `0 3 * * *`.

## Rutin

| Tugas | Perintah | Frekuensi |
|---|---|---|
| Retensi/pembersihan (sesi kedaluwarsa, tombstone, notifikasi, rapat, audit lama dengan checkpoint) | `python -m xnva maintenance` | harian (layanan `maintenance`) |
| Verifikasi rantai audit semua tenant | `python -m xnva verify-audit` (exit code 1 bila rusak) | harian, beri alert bila ≠ 0 |
| Cek kesehatan | `GET /healthz` (liveness), `GET /readyz` (integritas DB), `GET /v1/status` | monitor tiap menit |

## Rotasi kunci induk

1. Cadangkan data, lalu hentikan layanan.
2. `XNVA_NEW_MASTER_KEY=$(openssl rand -base64 32) python -m xnva rotate-master-key` — membungkus ulang semua data key dalam satu transaksi per database.
3. Ganti `XNVA_MASTER_KEY` di `.env` dengan nilai baru, simpan cadangannya, jalankan ulang.

Layanan menolak start jika kunci tidak cocok dengan data (`ConfigError`), sehingga salah kunci tidak dapat merusak data.

## Batas penskalaan (jujur)

- **SQLite, satu node.** Cocok untuk ribuan pengguna aktif dan puluhan organisasi menengah pada satu server. Penulisan diserialisasi (mode WAL). Untuk skala lebih besar, migrasi ke PostgreSQL ada di [ROADMAP](ROADMAP.md).
- **Rate limit dalam proses.** Image Docker menjalankan 1 worker × 8 thread agar pembatas konsisten. Bila menambah worker, pasang juga pembatas di reverse proxy (mis. `limit_req`). Penguncian login per akun tersimpan di database dan tetap konsisten.
- Panggilan model berjalan sinkron dalam thread request; batasi `XNAI_TIMEOUT_S` sesuai kapasitas thread.

## Respons insiden

| Situasi | Tindakan |
|---|---|
| Akun karyawan dicurigai bocor | Tab Tim → Nonaktifkan (sesi dicabut seketika), atau `POST /v1/enterprise/security/employees/{id}/revoke-sessions` |
| Kacamata hilang atau dicuri | Cabut di aplikasi/`DELETE /v1/devices/{id}` (pemilik atau admin): secret di server dihapus dan akun Anda tidak lagi terhubung dengannya. **Pemegang fisik kacamata tetap dapat memasangkannya ke akun lain lewat tombol pairing** — ini batas desain; flash encryption dan keamanan fisik adalah pengamannya ([HARDWARE.md](HARDWARE.md#pengerasan-produksi)) |
| `JWT_SECRET` bocor | Ganti nilainya, restart: semua access token tidak berlaku; refresh token (opak, tersimpan di server) tetap bisa dicabut per sesi |
| `MASTER_KEY` bocor | Anggap seluruh data terenkripsi terancam jika berkas database juga bocor. Rotasi kunci, evaluasi pelanggaran, beri tahu pengguna sesuai hukum yang berlaku |
| Audit tidak lolos verifikasi | Pulihkan dari cadangan terakhir yang lolos; selidiki akses ke berkas database |

## Penghapusan data

Pengguna Personal: Akun & Server → *Hapus akun* (atau `DELETE /v1/personal/account`) menghapus semua barisnya dan data key-nya. Organisasi: `DELETE /v1/enterprise/org` (owner; konfirmasi slug + password). Salinan di **cadangan lama** tetap ada sampai cadangan itu kedaluwarsa menurut kebijakan Anda: dokumentasikan masa simpan cadangan di kebijakan privasi operator.
