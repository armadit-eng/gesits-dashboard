package com.gesits.dashboard;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONObject;

/**
 * Baca notifikasi navigasi Google Maps. Format yang terlihat di log:
 *   judul = jarak ke belokan berikutnya ("0 m", "200 m")      → navDist
 *   teks  = "ke arah <jalan tujuan>"                          → navInstr
 *   sub   = "7 mnt · 5,1 km · PWT: 09.03"                     → navEta
 * Data disimpan di ScreenCaptureService (untuk strip info di frame mirror dan AT+NAV native)
 * dan diteruskan ke JS (window.onMapsNotif) untuk rotasi AT+NAV saat dashboard di depan.
 * Perlu izin "Akses notifikasi" untuk aplikasi ini di Pengaturan Android.
 */
public class MapsNotifListener extends NotificationListenerService {
    private static final String MAPS_PKG = "com.google.android.apps.maps";
    private final Handler main = new Handler(Looper.getMainLooper());
    private String last = "";

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        main.post(() -> {
            if (MainActivity.webViewRef != null)
                MainActivity.webViewRef.evaluateJavascript("log('Listener notifikasi Maps tersambung','sys')", null);
        });
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !MAPS_PKG.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        Bundle e = n.extras;
        String title = str(e.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(e.getCharSequence(Notification.EXTRA_TEXT));
        String sub = str(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        if (text.isEmpty()) text = str(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (title.isEmpty() && text.isEmpty() && sub.isEmpty()) return;

        String key = title + "|" + text + "|" + sub;
        if (key.equals(last)) return;      // Maps sering update notifikasi yang isinya sama
        last = key;

        // Simpan data terstruktur. Notifikasi Maps ada beberapa varian (judul bisa kosong atau
        // "Memulai navigasi..."), jadi hanya field yang valid yang menimpa nilai sebelumnya.
        if (!text.isEmpty()) ScreenCaptureService.navInstr = text.replaceFirst("(?i)^ke arah\\s+", "");
        if (!title.isEmpty() && Character.isDigit(title.charAt(0))) ScreenCaptureService.navDist = title;
        if (!sub.isEmpty()) ScreenCaptureService.navEta = sub.replaceAll("(?i)PWT:?\\s*", "tiba ");
        ScreenCaptureService.navUpdatedMs = System.currentTimeMillis();
        captureTurnIcon(n);

        // AT+NAV native: satu baris (jarak + tujuan, maks 20 karakter) yang dikirim ulang bgPoll,
        // jalan walau WebView dijeda. Saat dashboard di depan, rotasi JS menimpa dengan data lengkap.
        BluetoothService bt = ScreenCaptureService.btRef;
        if (bt != null && ScreenCaptureService.mirrorActive) {
            String line = navLine(ScreenCaptureService.navDist, ScreenCaptureService.navInstr);
            if (!line.isEmpty()) bt.setNavData(line);
        }

        final String js = "window.onMapsNotif&&window.onMapsNotif("
                + JSONObject.quote(title) + "," + JSONObject.quote(text) + "," + JSONObject.quote(sub) + ")";
        main.post(() -> {
            if (MainActivity.webViewRef != null) MainActivity.webViewRef.evaluateJavascript(js, null);
        });
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !MAPS_PKG.equals(sbn.getPackageName())) return;
        // Navigasi selesai/dibatalkan → bersihkan strip info
        ScreenCaptureService.navDist = "";
        ScreenCaptureService.navInstr = "";
        ScreenCaptureService.navEta = "";
        ScreenCaptureService.navUpdatedMs = 0;
        ScreenCaptureService.navIcon = null;
        last = "";
        main.post(() -> {
            if (MainActivity.webViewRef != null)
                MainActivity.webViewRef.evaluateJavascript("window.onMapsNotifCleared&&window.onMapsNotifCleared()", null);
        });
    }

    /** Ambil ikon belokan (large icon notifikasi Maps) → bitmap 96x96 untuk kartu navigasi saat HP terkunci. */
    private void captureTurnIcon(Notification n) {
        if (!ScreenCaptureService.USE_NAV_ICON || Build.VERSION.SDK_INT < 23) return;
        try {
            android.graphics.drawable.Icon ic = n.getLargeIcon();
            if (ic == null) return;
            Drawable d = ic.loadDrawable(this);
            if (d == null) return;
            final int sz = 96;
            Bitmap b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, sz, sz);
            d.draw(new Canvas(b));
            ScreenCaptureService.navIcon = b;
        } catch (Exception ignored) {}
    }

    /** ASCII murni, maks 20 karakter, dipad spasi (format sama dengan padNavText di JS). */
    private static String navLine(String dist, String instr) {
        String s = ascii((dist == null || dist.isEmpty() ? "" : dist + " ") + (instr == null ? "" : instr));
        if (s.isEmpty()) return "";
        if (s.length() > 20) s = s.substring(0, 20).trim();
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 20) sb.append(' ');
        return sb.toString();
    }

    private static String ascii(String s) {
        String d = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD);
        return d.replaceAll("\\p{M}+", "").replaceAll("[^\\x20-\\x7E]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String str(CharSequence cs) { return cs == null ? "" : cs.toString().trim(); }
}
