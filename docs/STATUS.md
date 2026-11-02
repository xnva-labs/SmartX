# Status implementasi (jujur)

Aturan proyek: **tidak ada fitur simulasi yang dipresentasikan sebagai fitur produksi.** Tabel ini adalah sumber kebenaran untuk apa yang boleh diklaim.

Legenda: ✅ ada dan diuji otomatis di lingkungan penulis · 🟡 ada, tetapi **belum dikompilasi/dijalankan** di lingkungan penulis (butuh CI atau perangkat) · 🔴 belum ada.

## Backend (`backend/`)

| Fitur | Status | Bukti / catatan |
|---|---|---|
| Autentikasi Personal & Enterprise, refresh rotasi, deteksi pemakaian ulang, penguncian, ganti password, sesi | ✅ | `tests/test_auth.py` |
| Pemisahan realm (DB, token, kunci) | ✅ | tes token silang-realm, tes isolasi |
| RBAC 5 peran, aturan penetapan peran, owner terakhir | ✅ | `tests/test_enterprise.py::RbacTests` |
| Isolasi tenant (semua sumber daya) | ✅ | `TenantIsolationTests` |
| Enkripsi saat tersimpan; teks asli tidak ada di berkas DB | ✅ | tes memori, tugas, KB, secret perangkat |
| Crypto-shredding saat hapus akun/organisasi | ✅ | tes hapus akun/organisasi |
| Audit rantai hash, verifikasi, ekspor, retensi dengan checkpoint | ✅ | `InsightTests`, `test_core.py` |
| Organisasi, divisi, karyawan, undangan, proyek, tugas, komentar, pengumuman | ✅ | `tests/test_enterprise.py` |
| Knowledge base (BM25 atas indeks buta, visibilitas per peran) | ✅ | `KnowledgeTests` |
| Rapat + ringkasan, laporan, analitik agregat, transparansi | ✅ | `InsightTests`, `test_xnai.py` |
| Registri kacamata, secret, rotasi, pencabutan, telemetri | ✅ | `tests/test_devices.py` |
| XNAI chat/vision/translate/transcribe/saran balasan: pembentukan permintaan, gerbang izin & kuota, pemetaan error, sanitasi gambar | ✅ | diuji terhadap **server penyedia tiruan lokal** (`tests/helpers.py::Upstream`) |
| XNAI **terhadap penyedia nyata** (OpenAI, Anthropic, vLLM, …) | 🟡 | membutuhkan kredensial operator; bentuk request mengikuti dokumentasi publik API tersebut |
| Backup/restore, rotasi kunci induk, CLI operator | ✅ | `tests/test_ops.py` |
| Dokumentasi API selalu sinkron dengan rute | ✅ | `tests/test_openapi.py` |
| Image Docker, compose, Caddy/HTTPS | 🟡 | tidak dijalankan di lingkungan penulis; CI membangun image |
| Notifikasi push (FCM) | 🔴 | hanya kotak masuk (polling) |
| Pengiriman email undangan/reset password | 🔴 | kode undangan ditampilkan ke admin; belum ada alur lupa password |
| PostgreSQL / multi-node | 🔴 | SQLite satu node |
| SSO (OIDC/SAML), MFA | 🔴 | |
| Pencarian semantik (embedding) | 🔴 | BM25 kata kunci |
| Unggah PDF/DOCX ke knowledge base | 🔴 | teks/markdown dan foto (OCR via XNAI) |
| Dashboard admin berbasis web | 🔴 | fungsi admin tersedia lewat API dan tab Enterprise di aplikasi |

## Aplikasi Android (`app/`)

| Fitur | Status | Catatan |
|---|---|---|
| Fitur lokal yang sudah ada (reminder, memori, privasi, translasi UI, darurat, dsb.) | ✅/🟡 | tidak diubah selain yang tercantum di bawah |
| PIN: PBKDF2 + HMAC Keystore + lockout; migrasi hash lama | 🟡 | |
| Memori terenkripsi Keystore; migrasi plaintext lama | 🟡 | |
| Login Personal/Enterprise, undangan, sesi terenkripsi, auto-refresh | 🟡 | |
| XNAI, Vision, Translation lewat server | 🟡 | |
| Layar Enterprise berbasis server: tugas, pengumuman, tim, knowledge base, wawasan/audit, notifikasi | 🟡 | papan lokal lama dipertahankan |
| Klien BLE kacamata: scan, bonding, autentikasi, perintah, foto, suara, speaker | 🟡 | codec protokol: tes unit paritas dengan firmware (jalan di CI) |
| Asisten gestur (tahan = bicara, tekan = lihat, dobel = baca teks) | 🟡 | |
| Hapus akun dalam aplikasi (syarat Google Play) | 🟡 | |
| Sinkronisasi memori/reminder ke server | 🔴 | API ada, aplikasi belum memakainya |
| Saran balasan pesan dari notifikasi via server | 🔴 | endpoint ada, belum dihubungkan ke UI |
| Layar manajemen proyek/rapat/laporan di aplikasi | 🔴 | tersedia lewat API |
| Push FCM | 🔴 | |

**Tentang 🟡 Android:** kode ditulis tanpa Android SDK/Gradle (tidak tersedia di lingkungan penulis), sehingga **belum pernah dikompilasi**. Jalankan `gradle :app:testDebugUnitTest :app:assembleDebug` (CI melakukannya otomatis); kesalahan kompilasi yang muncul pertama kali harus diperbaiki sebelum rilis.

## Firmware (`firmware/`)

| Fitur | Status | Catatan |
|---|---|---|
| Inti protokol: CRC32, HMAC-SHA256, IMA ADPCM, frame, autentikasi, penguncian, kurva baterai | ✅ | 539 pemeriksaan, ASan+UBSan, vektor dari implementasi referensi independen |
| Driver: kamera OV2640, mikrofon PDM, speaker I2S, GATT BLE, NVS, deep sleep, tombol, LED, ADC baterai | 🟡 | diperiksa sintaksnya terhadap header SDK tiruan; **belum dikompilasi dengan toolchain ESP32 dan belum dijalankan pada perangkat** |
| Konsol diagnostik (`info`, `cam`, `mic`, `spk`, `led`, `batt`, `pair`, `unpair`) | 🟡 | prosedur bring-up di HARDWARE.md |
| LED kamera sebelum sensor, saklar privasi | 🟡 | desain daya terikat-LED butuh keputusan PCB |
| OTA update | 🔴 | |
| Wake word / selalu mendengarkan | 🔴 | hanya push-to-talk |
| Output visual (display) | 🔴 | audio saja |
| Flash/NVS encryption, secure boot | 🔴 | langkah pengerasan produksi, bukan kode |

## Hardware

| | Status |
|---|---|
| Desain acuan A (XIAO ESP32S3 Sense + MAX98357A) | 🟡 tertulis dari datasheet/pinout; **belum dirakit/diuji** |
| Pengukuran daya, panas, bobot, ergonomi | 🔴 |

## Hukum dan publikasi

| | Status |
|---|---|
| Kebijakan Privasi, Ketentuan Layanan | 🟡 disusun dari perilaku kode; **perlu tinjauan hukum** dan pengisian kontak/penyedia model operator |
| Pemetaan Data Safety & izin Google Play | 🟡 [PLAY_STORE.md](PLAY_STORE.md); verifikasi terhadap kebijakan Play terkini |
| Lisensi kode | 🔴 belum dipilih (keputusan pemilik) |
| Audit keamanan independen | 🔴 |
