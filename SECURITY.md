# Kebijakan Keamanan

## Melaporkan kerentanan

Gunakan **Private vulnerability reporting** GitHub: tab *Security* → *Report a vulnerability* di repositori ini. Jangan membuka issue publik untuk kerentanan. Sertakan versi, langkah reproduksi, dan dampak. Kami mengakui laporan secepatnya dan mengoordinasikan perbaikan dan pengungkapan.

Cakupan: `backend/`, `app/`, `firmware/`, `deploy/`, protokol BLE. Di luar cakupan: kerentanan pada penyedia model pihak ketiga, serangan yang membutuhkan akses root ke server atau pembongkaran fisik chip tanpa flash encryption (dibahas di bawah sebagai risiko sisa).

Versi yang didukung: rilis terbaru di cabang `main`.

## Model ancaman ringkas

| Aset | Ancaman | Kontrol yang ada (dites di CI bila ditandai ✅) | Risiko sisa |
|---|---|---|---|
| Data pengguna di server | Berkas database dicuri | AES-256-GCM per kolom, kunci per pengguna/organisasi, kunci induk di luar database ✅ | Pencuri yang juga memiliki `XNVA_MASTER_KEY` |
| Akun | Tebak password, kredensial dibocorkan | scrypt, penguncian per akun ✅, respons login seragam + waktu disamakan ✅, rate limit ✅ | Tidak ada MFA/SSO |
| Sesi | Pencurian token | access 15 menit, refresh rotasi + deteksi pemakaian ulang mencabut sesi ✅, token disimpan terenkripsi Keystore di Android | Token aktif dapat dipakai sampai kedaluwarsa (≤ 15 menit) |
| Isolasi tenant | Organisasi A membaca B | semua query disaring `org_id`, referensi lintas-organisasi ditolak, tes lintas-tenant untuk setiap sumber daya ✅ | Bug logika baru — tes harus diperluas bersama fitur |
| Hak akses | Eskalasi peran | peran dibaca dari database tiap request ✅, aturan penetapan peran ✅, owner terakhir dilindungi ✅ | — |
| Jejak audit | Penghapusan/pengubahan jejak | rantai hash ✅, verifikasi terjadwal, checkpoint saat retensi ✅ | Penyerang yang mengakses database *dan* menulis ulang seluruh rantai: simpan ekspor audit di luar server |
| Gambar/audio | Kebocoran metadata, penyimpanan tak sengaja | EXIF dibuang ✅, tidak disimpan di server ✅, ukuran dibatasi ✅ | Penyedia model memproses isi sesuai kebijakannya |
| Knowledge base | Prompt injection dari dokumen | dokumen dibatasi sebagai data, izin disaring sebelum model melihat teks ✅ | Model dapat tetap keliru; jawaban diberi sitasi |
| Kacamata ↔ ponsel | Perangkat palsu / ponsel asing | autentikasi HMAC timbal-balik ✅, penguncian setelah 5 gagal ✅, link terenkripsi | Pairing "Just Works" 60 detik rentan MITM aktif (lihat BLE_PROTOCOL) |
| Kacamata | Perekaman diam-diam | LED menyala sebelum sensor, min. 0,7 dtk; saklar privasi; mic berlampu status | Mitigasi penuh membutuhkan desain daya kamera yang terikat LED (HARDWARE.md) |
| Secret di kacamata | Pembacaan flash | — (opsional) flash + NVS encryption | Tanpa pengerasan produksi, secret dapat dibaca dengan akses fisik |
| PIN aplikasi | Brute force offline | PBKDF2 + HMAC kunci Keystore yang tak dapat diekspor, lockout eksponensial | PIN 4 digit tetap lemah terhadap penyerang yang sudah mengendalikan perangkat |

## Daftar periksa produksi

- [ ] HTTPS aktif (Caddy otomatis) dan `XNVA_TRUSTED_PROXIES=1`
- [ ] `XNVA_MASTER_KEY` dicadangkan terpisah; pemulihan diuji
- [ ] `verify-audit` dijadwalkan dan memicu alert
- [ ] Cadangan disalin ke luar server dan diuji pulih
- [ ] Pendaftaran organisasi mandiri tetap mati kecuali memang dibutuhkan
- [ ] Rilis aplikasi ditandatangani; keystore tidak ada di repositori
- [ ] Unit kacamata produksi: flash encryption + NVS encryption + secure boot + JTAG nonaktif
- [ ] Jangan memasukkan API key, kredensial server, atau private key ke repositori (`.gitignore` sudah mengecualikan `.env`, `*.jks`)
