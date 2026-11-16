# Web2APK

Aplikasi Android untuk mengubah **website menjadi aplikasi Android (APK)** —
tanpa coding, langsung dari HP.

## Cara kerja

1. Masukkan alamat website.
2. Ketuk **Ambil Info Situs** — judul, deskripsi, warna tema, dan ikon situs
   diambil otomatis (bisa diubah manual).
3. Atur nama aplikasi, Package ID, dan pilihan WebView (JavaScript, upload
   file, splash screen, tarik-untuk-segarkan, link luar di browser, cookie,
   zoom, sembunyikan bilah judul).
4. **Pratinjau** situs langsung di dalam aplikasi.
5. Ketuk **Buat Project ZIP** — dihasilkan project Android lengkap berisi
   aplikasi WebView untuk situs itu + workflow GitHub Actions.
6. Push ZIP yang sudah diekstrak ke repo GitHub, isi secret keystore sekali
   (panduan lengkap ada di README di dalam ZIP), jalankan workflow → APK jadi
   dan siap install. Update berikutnya menimpa normal karena keystore-nya tetap.

Project yang pernah dibuat tersimpan di daftar **Project saya** di aplikasi.

## Kenapa bukan compile langsung di HP?

Meng-compile APK dari nol butuh toolchain Android penuh (Gradle + SDK, ratusan
MB) yang tidak praktis dan tidak stabil di HP. Web2APK memakai jalur yang
terbukti: project digenerate di HP, APK dibangun oleh GitHub Actions dengan
keystore tetap — hasilnya APK rilis beneran yang bisa di-update, bukan APK
debug bertanda tangan acak.

## Teknis

- Package: `id.web2apk`, minSdk 26, targetSdk 35, Kotlin + Jetpack Compose.
- UI Bahasa Indonesia, gaya soft neobrutalism.
- Izin hanya INTERNET + ACCESS_NETWORK_STATE.
- Build hanya lewat GitHub Actions, keystore di-pin sejak rilis pertama
  (secret `WEB2APK_KEYSTORE_BASE64` dkk.).

## Build langsung (v0.2.0)

Kartu **4 · Build Langsung ke APK**: masukkan token GitHub milikmu
(classic, scope `repo` + `workflow` — disimpan terenkripsi Android Keystore,
hanya dikirim ke api.github.com), lalu ketuk **Build APK Sekarang**.
Aplikasi memicu workflow di repo builder `qioceky/web2apk-builder`,
memantau langkah build-nya, mengunduh APK dari Releases repo builder,
dan menawarkan pemasangan langsung.

## Status

v0.2.0 — build langsung dari aplikasi via repo builder.
v0.1.0 — rilis pertama: form + ambil info situs + pratinjau + generate
project ZIP + riwayat project.
