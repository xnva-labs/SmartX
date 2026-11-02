# Rilis Google Play

Daftar periksa berikut dipetakan dari perilaku kode yang sebenarnya. Kebijakan Play berubah; **verifikasi setiap butir terhadap dokumentasi Play Console saat mengirim.**

## Teknis

- [ ] `targetSdk = 36` (sudah). `versionCode`/`versionName` dinaikkan setiap rilis.
- [ ] Keystore rilis dibuat sendiri dan **tidak** masuk repositori. GitHub Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; variabel repositori `XNVA_API_BASE_URL` (https). Jalankan workflow *Release AAB*.
- [ ] Aktifkan **Play App Signing**. Simpan upload key di tempat aman.
- [ ] Uji pada beberapa perangkat/versi Android (31–36), termasuk Bluetooth dengan kacamata fisik, izin ditolak, mode hemat daya.
- [ ] Server produksi di HTTPS dengan `XNAI_*` terisi (tanpa itu AI menampilkan "belum dikonfigurasi").
- [ ] Siapkan akun uji dan instruksi untuk reviewer (akun Personal; bila perlu akun Enterprise dari organisasi demo yang Anda buat).

## Izin dan alasannya (untuk formulir deklarasi)

| Izin | Mengapa |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Komunikasi dengan server SmartEyeX |
| `CAMERA` | Analisis visual dengan kamera ponsel (diminta saat dipakai) |
| `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT` | Menemukan dan menyambung ke kacamata SmartEyeX; tidak dipakai untuk lokasi |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE` | Menjaga koneksi kacamata agar tombolnya tetap berfungsi saat layar mati; notifikasi persisten ditampilkan |
| `POST_NOTIFICATIONS` | Pengingat dan notifikasi koneksi kacamata |
| `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED` | Pengingat/alarm tepat waktu dan dijadwalkan ulang setelah reboot |
| Notification listener (`BIND_NOTIFICATION_LISTENER_SERVICE`) | Fitur inti: pengelolaan notifikasi WhatsApp/Instagram atas persetujuan pengguna. **Pengguna mengaktifkannya sendiri di pengaturan sistem;** isinya diproses di ponsel dan tidak dikirim ke server pada versi ini. Siapkan penjelasan prominent dan deklarasi sesuai kebijakan Play. |

## Data Safety (rancangan jawaban)

| Kategori Play | Dikumpulkan? | Dibagikan? | Catatan |
|---|---|---|---|
| Info pribadi: nama, email | Ya (akun) | Tidak | wajib untuk akun; bisa dihapus |
| Foto/video, audio | Diproses sementara untuk permintaan AI; tidak disimpan | Diteruskan ke penyedia model sebagai pemroses | Play mengecualikan pemrosesan ephemeral dari "dikumpulkan" — verifikasi definisi terbaru |
| Pesan/teks pengguna | Diproses sementara untuk AI/terjemahan | idem | tidak disimpan di server |
| Aktivitas aplikasi | Metadata penggunaan AI (waktu, jenis, ukuran) | Tidak | untuk kuota/transparansi |
| ID perangkat | ID kacamata (MAC eFuse), label | Tidak | untuk pairing |
| Keamanan | Data dienkripsi saat transit **dan** saat tersimpan | — | |
| Penghapusan | Pengguna dapat meminta penghapusan: ya (dalam aplikasi dan web) | — | |

Jika penyedia model yang Anda pakai **menyimpan** data, jawaban berubah: isi sesuai kebijakan penyedia.

## Penghapusan akun (wajib bila ada pembuatan akun)

1. Dalam aplikasi: *Privacy Control → Akun & Server → Hapus akun* (sudah ada).
2. Play juga mensyaratkan **URL web** untuk permintaan penghapusan. Terbitkan halaman (mis. GitHub Pages atau situs XNVA Labs) berisi: langkah menghapus akun dalam aplikasi; bagi yang sudah memasang ulang/kehilangan perangkat, cara meminta penghapusan lewat kontak resmi; data apa yang dihapus segera (akun, memori, perangkat, kunci enkripsi) dan apa yang bertahan (cadangan operator sampai kedaluwarsa); lalu masukkan URL itu di Play Console.

## Kelengkapan toko

- [ ] URL Kebijakan Privasi (terbitkan [PRIVACY_POLICY.md](PRIVACY_POLICY.md) setelah tinjauan hukum dan pengisian detail operator)
- [ ] Deskripsi, screenshot, ikon fitur, kategori, peringkat konten, kontak dukungan
- [ ] Deklarasi: aplikasi tidak menargetkan anak; pengguna < 18 butuh persetujuan wali
- [ ] Sistem dukungan pengguna dan penanganan bug (issue tracker/email dukungan)
