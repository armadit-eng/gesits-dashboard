package com.gesits.dashboard;

/**
 * Koordinasi radio Bluetooth: Classic (SPP ke MID: polling + frame AT+MAPS ±7 KB) dan LE (BMS, ESP32, ESP32 #2)
 * berbagi SATU radio 2,4 GHz di HP. Percobaan connectGatt() LE (apalagi yang gagal/timeout 15 dtk dan diulang
 * sampai 8x per perangkat, bergiliran lewat BleConnectGate) memakan waktu udara dan menurunkan throughput
 * Classic — frame peta jadi lambat/macet.
 *
 * Aturan sederhana: selama jalur Classic sedang aktif dipakai (ada tulisan ke MID dalam 5 detik terakhir),
 * jeda antar percobaan sambung ulang LE dibuat 3x lebih panjang (maks 60 detik). Saat motor tidak
 * terhubung / tidak ada trafik Classic, jeda tetap normal sehingga sambung ulang tetap cepat.
 */
public final class BleCoex {
    private BleCoex() {}
    private static volatile long lastClassicWriteAt = 0;

    /** Dipanggil writer thread Classic tiap selesai menulis ke MID. */
    public static void markClassicWrite() { lastClassicWriteAt = System.currentTimeMillis(); }

    public static boolean classicActive() {
        return System.currentTimeMillis() - lastClassicWriteAt < 5000;
    }

    /** Jeda sambung ulang LE yang sudah disesuaikan dengan trafik Classic. */
    public static long stretch(long delayMs) {
        return classicActive() ? Math.min(delayMs * 3, 60000L) : delayMs;
    }
}
