# Roadmap

Urutan menurut dampak terhadap kesiapan produksi. Belum ada janji tanggal.

**Sebelum rilis publik**
1. Kompilasi dan perbaiki aplikasi Android di CI (kesalahan kompilasi pertama); uji manual pada perangkat.
2. Rakit unit kacamata acuan; jalankan bring-up; perbaiki driver (mikrofon, daya kamera terikat-LED, speaker); ukur daya dan panas.
3. Uji end-to-end dengan penyedia model nyata (chat, vision, STT) dan catat batas latensi.
4. Tinjauan hukum Kebijakan Privasi/Ketentuan; pilih lisensi; halaman penghapusan akun.
5. Audit keamanan independen (backend + protokol BLE).

**Berikutnya**
- Sinkronisasi memori/reminder di aplikasi (API sudah ada), saran balasan notifikasi, layar proyek/rapat/laporan.
- Alur lupa password + email undangan (SMTP), MFA, SSO (OIDC).
- FCM untuk notifikasi; PostgreSQL + penyimpanan objek untuk skala; pembatas laju terdistribusi.
- Pencarian semantik; ingest PDF/DOCX; tag/kategori dokumen.
- Dashboard admin web.
- Firmware: OTA terenkripsi bertanda tangan; wake word on-device (ESP-SR); passkey/NFC out-of-band untuk pairing; kunci sesi per-frame; fuel gauge.
- Hardware: PCB dengan daya kamera terikat LED, saklar privasi memutus daya, rumah kacamata, sertifikasi.
