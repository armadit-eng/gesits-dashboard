package com.gesits.dashboard;

/**
 * Jembatan data telemetri antara WebView (JavaScript, tempat semua kalkulasi
 * SOC/efisiensi/dsb sekarang hidup) dan LiveStreamActivity (native, yang
 * menggambar overlay HUD di atas frame kamera buat di-push RTMP).
 *
 * Kenapa perlu ini: data motor/baterai cuma ada di memori JavaScript
 * (motoData, battery2Data, dst di GESITS_Dashboard_DualESP32.html). Native
 * Android sisi RTMP encoder gak bisa "nanya" ke WebView tiap frame — RTMP
 * push jalan di background/activity terpisah dan butuh baca data secepat
 * mungkin tanpa bolak-balik ke JS. Solusinya: WebView kirim update berkala
 * lewat JavascriptInterface (lihat MainActivity.updateLiveHud()), ditulis ke
 * sini, lalu LiveStreamActivity baca field-field volatile ini tiap kali mau
 * gambar overlay baru (lihat LiveStreamActivity.buildHudBitmap()).
 *
 * Data disimpan sbg SATU string JSON mentah (bukan field terpisah satu-satu),
 * supaya nambah field baru cukup diubah di sisi JS (broadcastLiveHud() di
 * dashboard) + di sisi baca (LiveStreamActivity), tanpa perlu ubah signature
 * method di file ini atau di MainActivity setiap kali. Parsing JSON dilakukan
 * pemanggil (LiveStreamActivity) pakai org.json.JSONObject bawaan Android.
 */
public final class LiveHudBus {

    // JSON mentah terakhir dari WebView, contoh:
    // {"spd":42,"soc":80,"b1Soc":78,"b2Soc":85,"tbat":29,"whKm":31.5,"ahKm":0.44,"trip":12.3,"gps":true}
    // null kalau belum pernah ada update sama sekali.
    public static volatile String lastJson = null;

    // Timestamp System.currentTimeMillis() saat terakhir update() dipanggil.
    // Dipakai LiveStreamActivity buat tahu data ini masih "segar" atau sudah
    // basi (misalnya WebView di-background / motor putus lama) — kalau basi,
    // overlay sebaiknya nampilin "—" bukan angka terakhir yang sudah usang.
    public static volatile long lastUpdateMs = 0;

    // true selama LiveStreamActivity terbuka. MainActivity membacanya di onPause(): kalau true, WebView
    // TIDAK di-pause, supaya JavaScript (broadcastLiveHud) terus mengirim data ke HUD selama live.
    public static volatile boolean liveActive = false;

    // HUD gaya "Rekam Video": gambar overlay 1280x720 (WebP/PNG transparan, base64) yang dirender JavaScript
    // lewat mvDrawHUDLandscape — dipakai LiveStreamActivity sebagai overlay kalau segar (lihat updateImage()).
    public static volatile String lastImage = null;
    public static volatile long lastImageMs = 0;

    // Diisi LiveStreamActivity: true kalau overlay bergaya Rekam sedang dipakai (landscape, HUD tidak mati).
    // JavaScript menanyakan ini sebelum merender, jadi tidak ada kerja sia-sia saat Live tidak dibuka.
    public static volatile boolean wantRecImage = false;

    public static void updateImage(String b64) {
        lastImage = b64;
        lastImageMs = System.currentTimeMillis();
    }

    public static boolean isImageFresh(long maxAgeMs) {
        return lastImage != null && lastImageMs > 0 && (System.currentTimeMillis() - lastImageMs) <= maxAgeMs;
    }

    // Dipanggil dari MainActivity.updateLiveHud() (JavascriptInterface).
    public static void update(String json) {
        lastJson = json;
        lastUpdateMs = System.currentTimeMillis();
    }

    // true kalau data terakhir diterima dalam N ms terakhir.
    public static boolean isFresh(long maxAgeMs) {
        return lastUpdateMs > 0 && (System.currentTimeMillis() - lastUpdateMs) <= maxAgeMs;
    }

    private LiveHudBus() {} // cuma static holder, gak perlu di-instantiate
}
