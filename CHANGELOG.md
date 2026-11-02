# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/); versi mengikuti SemVer.

## [0.2.0] — platform dua lingkungan

### Ditambahkan
- **Backend** (`backend/`): API Personal dan Enterprise terpisah; autentikasi, RBAC, isolasi tenant, enkripsi saat tersimpan, audit rantai hash, XNAI (chat, vision, terjemahan, transkripsi, saran balasan), knowledge base, tugas/proyek/pengumuman/rapat/laporan/analitik, registri kacamata, CLI operator (backup/restore/retensi/rotasi kunci). 108 tes otomatis.
- **Firmware** (`firmware/`): firmware kacamata ESP32-S3 (kamera, mikrofon PDM, speaker bone-conduction, BLE, autentikasi HMAC, LED kamera, saklar privasi, deep sleep) + inti protokol teruji di host.
- **Protokol BLE v1** (`docs/protocol/`) dengan pembangkit konstanta C++/Kotlin dan vektor uji lintas implementasi.
- **Aplikasi**: layar Welcome (Personal/Enterprise), Akun & Server, klien API, sesi terenkripsi, Enterprise berbasis server, klien BLE kacamata + asisten gestur, hapus akun.
- Deploy: Docker, Compose, HTTPS otomatis (Caddy), pembuat secret.
- Dokumentasi: arsitektur, protokol, hardware, operasi, keamanan, kebijakan, status jujur, panduan Play Store.

### Diubah
- PIN: SHA-256 tanpa salt → PBKDF2-HMAC-SHA256 + HMAC kunci Keystore + lockout (hash lama dimigrasi saat unlock berikutnya).
- Memori lokal dienkripsi dengan Android Keystore (data lama dimigrasi saat dibaca).
- `XnaiRepository`, `VisionRepository`, `TranslationRepository` memakai server SmartEyeX (sebelumnya endpoint kosong).
- Layar Perangkat dapat di-scroll dan memuat kartu kacamata.

### Dipertahankan
- Seluruh fitur lokal yang sudah berfungsi; papan tugas lokal lama tetap dapat dibuka dari layar Enterprise.

## [0.1.0]
- Aplikasi Android awal (UI, fitur lokal, privasi, reminder, memori).
