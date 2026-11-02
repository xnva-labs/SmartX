# Berkontribusi

Sambutan terbuka untuk laporan bug dan usulan lewat Issues. **Sebelum menerima kode dari luar, pemilik proyek perlu menetapkan lisensi** (repositori belum memuat `LICENSE`, sehingga Pull Request pihak ketiga belum dapat diterima secara hukum). Sampai itu ditetapkan, kirim laporan dan usulan, bukan kode.

## Aturan pengembangan

- Pertahankan fitur yang sudah berfungsi; jangan menghapus modul tanpa alasan teknis.
- Setiap modul baru wajib punya tes; setiap integrasi backend diuji; tidak boleh ada regresi.
- **Tidak ada fitur simulasi yang tampil sebagai fitur produksi.** Fitur yang belum ada jujur dilaporkan (`not_configured`, 503) dan dicatat di [docs/STATUS.md](docs/STATUS.md).
- Pemisahan Personal/Enterprise dijaga di backend. Query Enterprise **selalu** menyertakan `org_id`; tambahkan kasus ke `TenantIsolationTests` untuk setiap sumber daya baru.
- Jangan memasukkan API key, kredensial, private key, atau konfigurasi rahasia ke repositori.

## Menjalankan semua tes

```bash
(cd backend && python -m unittest discover -s tests -t .)        # backend + OpenAPI
make -C firmware/test/host test && python3 firmware/test/test_generated.py   # inti firmware + berkas hasil generator
gradle :app:testDebugUnitTest                                       # paritas protokol di Android
```

Mengubah protokol atau API: lihat bagian terakhir [docs/INSTALL.md](docs/INSTALL.md).

## Gaya

Python: PEP 8, tipe di antarmuka publik, SQL berparameter (tidak pernah string-format nilai pengguna). Kotlin: ikuti pola yang ada (object repository, Compose state). C++: C++11 yang kompatibel dengan Arduino-ESP32 2.0.x; logika murni tanpa dependensi Arduino di `firmware/src/proto/`. Pesan commit: kalimat perintah singkat.

## Keamanan

Laporkan kerentanan secara privat: [SECURITY.md](SECURITY.md).
