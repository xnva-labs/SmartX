# Kebijakan Privasi SmartEyeX

*Berlaku sejak rilis 0.2.0.* Dokumen ini disusun dari perilaku nyata perangkat lunak dalam repositori ini. Operator layanan wajib meninjaunya dengan penasihat hukum dan melengkapi bagian "Penyedia model AI" dan "Kontak" sesuai kondisi layanan yang benar-benar dijalankan sebelum dipublikasikan di toko aplikasi.

## 1. Siapa yang bertanggung jawab

SmartEyeX adalah perangkat lunak dari **XNVA Labs**. Pihak yang menjalankan server tempat Anda masuk (XNVA Labs untuk layanan resmi, atau organisasi/individu lain untuk instalasi mandiri) adalah **pengendali data** atas data yang diproses di server tersebut. Aplikasi menampilkan alamat server yang Anda pakai (Akun & Server).

## 2. Data apa yang diproses, untuk apa

**Tetap di ponsel Anda (tanpa akun)**: profil, preferensi, memori, pengingat, jadwal, kebiasaan, pengaturan privasi, dan isi notifikasi aplikasi lain yang Anda izinkan dibaca. Memori disimpan terenkripsi dengan kunci Android Keystore. Versi ini **tidak** menyinkronkan memori atau pengingat ke server (API-nya ada di server tetapi tidak dipakai aplikasi) dan **tidak** mengirim isi notifikasi ke server.

**Saat Anda masuk ke server**

| Data | Tujuan | Catatan |
|---|---|---|
| Email, nama, hash password (scrypt), waktu pembuatan | Akun dan autentikasi | password tidak pernah disimpan dalam bentuk asli |
| Sesi: label perangkat, waktu | Menjaga Anda tetap masuk, pencabutan sesi | |
| Peristiwa keamanan (masuk, ganti password, perubahan privasi, ekspor) | Keamanan akun, transparansi | Personal: alamat IP dipotong (/24 atau /48) |
| Pengaturan privasi sisi server (Cloud Processing, retensi) | Menegakkan izin Anda di server | default: mati |
| Pesan ke XNAI, riwayat singkat percakapan, ringkasan profil/preferensi | Menjawab pertanyaan | **hanya bila Cloud Processing ON**; tidak disimpan di server |
| Foto dari kamera (ponsel atau kacamata) | Deskripsi/pembacaan teks | **hanya bila Camera dan Cloud Processing ON**; EXIF dibuang; tidak disimpan |
| Rekaman suara dari kacamata | Transkripsi | **hanya bila Microphone dan Cloud Processing ON**; tidak disimpan |
| Teks untuk diterjemahkan | Terjemahan | tidak disimpan |
| Metadata penggunaan AI (waktu, jenis, ukuran, nama model) | Kuota, transparansi aktivitas AI, pencegahan penyalahgunaan | Personal: dapat Anda lihat di aplikasi/API; masa simpan dapat Anda atur (bawaan 90 hari) |
| Data kacamata: ID perangkat, nama, versi firmware, level baterai, terakhir terlihat; secret pairing (terenkripsi) | Pairing, status | |
| Log server standar (waktu, alamat IP, jalur, kode status) | Keamanan dan operasional | dikelola operator; periode simpan ditetapkan operator |

**Akun Enterprise** menambahkan: nama, email kerja, peran, divisi; data kerja yang Anda atau tim masukkan (tugas, komentar, pengumuman, catatan rapat, dokumen knowledge base — terenkripsi di server); catatan audit tindakan administratif (siapa, apa, kapan, alamat IP); jumlah permintaan AI per divisi per hari (tanpa isi dan tanpa nama). Organisasi **tidak** menerima isi percakapan AI Anda, gambar, atau suara. Halaman *Wawasan → Transparansi data* di aplikasi menyatakan apa yang dikumpulkan dan tidak dikumpulkan; administrator organisasi dapat melihat data kerja organisasi sesuai peran.

## 3. Penyedia model AI

Untuk menjawab, server meneruskan teks, gambar yang sudah dibersihkan metadata-nya, atau audio yang diperlukan ke **penyedia model yang dikonfigurasi operator** (variabel `XNAI_PROVIDER`). Penyedia memproses data sesuai kebijakannya sendiri dan dapat berada di luar Indonesia. *Operator mencantumkan nama penyedia, lokasi pemrosesan, dan tautan kebijakannya di halaman informasi layanan.* Server tidak menyimpan isi permintaan tersebut.

## 4. Dasar dan izin

Pemrosesan untuk AI di cloud berdasar **persetujuan Anda** (tombol Cloud Processing, Camera, Microphone; dapat dimatikan kapan saja dan **ditegakkan lagi oleh server**). Pemrosesan akun Enterprise berdasar hubungan kerja/perjanjian dengan organisasi Anda.

## 5. Masa simpan

Sesi: sampai kedaluwarsa (30 hari sejak aktivitas terakhir) atau dicabut. Peristiwa keamanan akun Personal: 365 hari. Metadata aktivitas AI Personal: 90 hari atau sesuai pengaturan Anda. Notifikasi: 90 hari. Catatan audit organisasi: menurut kebijakan organisasi (bawaan 365 hari, minimum 90). Penghapusan akun menghapus datanya dan kunci enkripsinya seketika; salinan di cadangan operator hilang saat cadangan itu dihapus sesuai kebijakan operator.

## 6. Hak Anda

Akses dan salinan data (**Ekspor**: `GET /v1/personal/export`), perbaikan profil, **penghapusan akun** (Akun & Server → Hapus akun), menarik persetujuan (matikan Cloud Processing/Camera/Microphone), keberatan, dan mengajukan keluhan ke lembaga pelindung data pribadi yang berwenang. Untuk akun Enterprise, hubungi administrator organisasi Anda.

## 7. Keamanan

Enkripsi saat transit (HTTPS) dan saat tersimpan (AES-256-GCM); kunci per pengguna/organisasi; kontrol akses berbasis peran; pemisahan Personal dan Enterprise; jejak audit antipemalsuan; token di ponsel terenkripsi Android Keystore. Tidak ada sistem yang sepenuhnya kebal; lihat [SECURITY.md](../SECURITY.md).

## 8. Anak-anak

Pengguna di bawah 18 tahun memerlukan persetujuan orang tua/wali. SmartEyeX tidak sengaja mengumpulkan data anak tanpa persetujuan tersebut; hubungi kami untuk menghapusnya.

## 9. Merekam orang lain

Kacamata dapat mengambil gambar dan suara di sekitar Anda. LED kamera menyala saat gambar diambil dan **tidak boleh ditutup**. Anda bertanggung jawab mematuhi hukum setempat dan menghormati privasi orang lain (lihat Ketentuan Layanan).

## 10. Perubahan dan kontak

Perubahan material diumumkan di aplikasi/repositori. Pertanyaan privasi: buka *Issue* di repositori proyek (tanpa data pribadi) atau gunakan kontak resmi XNVA Labs yang tercantum pada halaman toko aplikasi dan situs resmi.
