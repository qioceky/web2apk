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

## Mode offline (v0.3.0)

Kartu **5 · Mode Offline (Tanpa GitHub)**: APK dibuat langsung di HP dari
`template.apk` yang dibundel aplikasi. Package ID, nama, versi, dan warna
ikon diedit di manifest biner + resources.arsc memakai ARSCLib,
`assets/config.json` + ikon diganti sekalian zipalign, lalu APK
ditandatangani keystore lokal (apksig + sertifikat self-signed
BouncyCastle) yang dibuat sekali di HP. Keystore bisa diekspor untuk
cadangan dari aplikasi — tanpa cadangan, ganti HP berarti APK baru
tidak bisa menimpa yang lama. Pipeline patch ini terverifikasi di JVM
(ARSCLib + zipalign -c + apksigner) sebelum diporting ke aplikasi.

## Mode HTML (v0.4.0)

Kartu 1 kini punya pilihan sumber: **Alamat Web** atau **HTML**. Mode HTML:
tempel kode HTML atau pilih file `.html` — aplikasi hasil build membukanya
dari `assets/page.html` di dalam APK sendiri (`file:///android_asset/`),
jadi jalan full offline tanpa hosting. Didukung Mode Offline dan Project
ZIP; Build Langsung GitHub tidak (kode HTML tidak bisa dikirim lewat input
workflow). Batas: satu file HTML (CSS/JS inline); file pendamping terpisah
belum ikut. Template runtime membaca `sourceType` dari config.json.

## Status

v0.4.3 — template v1.3: ikon status bar & navigation bar aplikasi hasil
generate dibuat gelap (isAppearanceLightStatusBars/NavigationBars) —
sebelumnya ikon putih di atas latar terang jadi tak terlihat (laporan
lapangan dari aplikasi API CHECKER user). Sekalian kabar baiknya:
rantai Mode Offline TERBUKTI PENUH di lapangan 2026-11-17 — aplikasi
HTML user terpasang & berjalan dari build offline v0.4.2.
v0.4.2 — fix APK offline ditolak installer ("App not installed"): file
hasil bedah menunjukkan ~110 entri STORED (termasuk resources.arsc)
meleset alignment 1–3 byte — ZipOutputStream di ART memperlakukan
extra field/buffering berbeda dari JVM sehingga hitungan offset
ZipPatch meleset. ZipPatch kini PENULIS ZIP MANUAL (offset aritmetika,
tanpa data descriptor, padding 0xD935 verbatim) + self-check alignment
di aplikasi sebelum APK ditawarkan ke pemasang. Terverifikasi JVM:
zipalign -c lulus sebelum & sesudah tanda tangan apksig.
v0.4.1 — fix uji lapangan v0.4.0: (1) error "cannot create signer: no such
algorithm SHA256WITHRSA for provider BC" — nama provider "BC" di Android
menunjuk BouncyCastle bawaan sistem yang dipangkas; LocalKeyStore kini
memakai INSTANCE provider penuh dari bcprov yang dibundel. (2) Fullscreen:
UI maker + aplikasi hasil generate kini menghormati window insets sistem
(konten tidak tertutup status bar di Android 15 edge-to-edge).
v0.4.0 — mode HTML (offline + ZIP).
v0.3.0 — mode offline tanpa GitHub.
v0.2.0 — build langsung dari aplikasi via repo builder.
v0.1.0 — rilis pertama: form + ambil info situs + pratinjau + generate
project ZIP + riwayat project.
