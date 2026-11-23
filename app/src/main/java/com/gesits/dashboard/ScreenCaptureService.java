package com.gesits.dashboard;

/* ScreenCaptureService — mirror layar HP ke MID lewat AT+MAPS (sudah terpasang di MainActivity/manifest/index.html). */

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.graphics.Typeface;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class ScreenCaptureService extends Service {
    private static final String TAG = "ScreenCapture";
    public static final String ACTION_START = "mirror_start";
    public static final String ACTION_STOP = "mirror_stop";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_CROP = "crop";

    /** Ukuran frame yang dikirim ke MID. Diisi JS (setMirrorSize) dari pengaturan Resolusi Maps; dibaca saat mirror mulai. */
    public static volatile int reqW = 350, reqH = 225;
    private int OUT_W = 350, OUT_H = 225;                   // ukuran aktif sesi ini
    private static final int MAX_CHARS = 7000;              // sama dengan MAPS_MAX_CHARS
    private static final long INTERVAL_MS = 1500;           // jeda antar frame
    private static final int CAP_WIDTH = 720;               // resolusi tangkap (hemat CPU)
    private static final String CHANNEL_ID = "screen_mirror";
    private static final int NOTIF_ID = 4711;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private float[] crop = {0f, 0.10f, 1f, 0.78f};
    private volatile boolean running = false;
    /** Dibaca MainActivity.onPause() supaya WebView tidak dijeda selama mirror aktif. */
    public static volatile boolean mirrorActive = false;
    /** Diisi MainActivity saat BluetoothService ter-bind; dipakai supaya frame dikirim tanpa lewat WebView. */
    public static volatile BluetoothService btRef = null;

    /** Strip info (arah + ETA) yang digambar di frame mirror. Diisi MapsNotifListener, diatur lewat JsBridge. */
    public static volatile boolean overlayEnabled = true;
    public static volatile boolean overlayTop = true;
    public static volatile String navDist = "", navInstr = "", navEta = "";
    public static volatile long navUpdatedMs = 0;

    /** Ikon belokan dari notifikasi Maps (opsional, diisi MapsNotifListener). Dipakai di kartu navigasi saat HP terkunci. */
    public static volatile Bitmap navIcon = null;
    /** Kalau ikon di kartu tampil aneh (kotak putih / tidak kelihatan), ubah ke false. */
    public static final boolean USE_NAV_ICON = true;

    // ── Mode HP terkunci / layar mati ──
    // Screen capture tidak bisa menangkap layar yang mati/terkunci, jadi saat itu frame diganti
    // KARTU NAVIGASI yang digambar dari data notifikasi Maps (jarak, jalan, ETA) — tanpa perlu layar.
    private static final long CARD_STALE_MS = 5 * 60 * 1000;   // data notif > 5 menit dianggap basi
    private static final long CARD_KEEPALIVE_MS = 4000;        // kirim ulang kartu yang sama tiap 4 dtk
    /** true = saat layar NYALA tapi masih terkunci (mis. Maps membangunkan layar di atas lockscreen) tetap tangkap layar asli.
     *  Kalau frame ternyata kosong/hitam → otomatis kembali ke kartu. false = selalu kartu selama terkunci. */
    public static final boolean CAPTURE_WHEN_LOCKED_SCREEN_ON = true;
    private int lastMode = -1;                                  // 0 = kartu, 1 = tangkapan layar (untuk log)
    private volatile boolean projectionDead = false;           // proyeksi dihentikan sistem saat terkunci
    private String lastCardKey = "";
    private long lastCardMs = 0;
    private PowerManager.WakeLock wakeLock;                    // CPU tetap jalan saat layar mati

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) { shutdown(); return START_NOT_STICKY; }
        if (!ACTION_START.equals(intent.getAction()) || running) return START_NOT_STICKY;

        // Android 14+: startForeground (tipe mediaProjection) WAJIB sebelum getMediaProjection.
        startAsForeground();
        try {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            float[] c = intent.getFloatArrayExtra(EXTRA_CROP);
            if (c != null && c.length == 4) crop = c;
            OUT_W = Math.max(64, Math.min(1024, reqW));
            OUT_H = Math.max(64, Math.min(1024, reqH));

            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(code, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    // Android baru bisa menghentikan proyeksi saat HP terkunci. Kalau itu terjadi,
                    // jangan matikan mirror: lanjut mode kartu navigasi. Begitu HP dibuka,
                    // frameLoop memanggil shutdown() (proyeksi baru butuh izin ulang dari user).
                    if (running && deviceLocked()) { projectionDead = true; releaseCapture(); }
                    else shutdown();
                }
            }, main);

            // Pakai ukuran layar ASLI (termasuk bar navigasi). getResources().getDisplayMetrics() di banyak HP
            // (mis. Infinix dgn tombol navigasi) lebih pendek dari layar sebenarnya, sehingga isi layar yang
            // di-mirror ke virtual display jadi mengecil/bergeser dan area crop tidak sama dgn di layar.
            DisplayMetrics dm = new DisplayMetrics();
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
            float scale = (float) CAP_WIDTH / dm.widthPixels;
            int capW = CAP_WIDTH, capH = Math.round(dm.heightPixels * scale);
            reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay("gesits-mirror", capW, capH, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);

            thread = new HandlerThread("mirror-worker");
            thread.start();
            worker = new Handler(thread.getLooper());
            running = true;
            mirrorActive = true;
            projectionDead = false;
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gesits:mirror");
                wakeLock.acquire(8 * 60 * 60 * 1000L);   // batas aman 8 jam
            } catch (Exception e) { Log.w(TAG, "wakelock gagal: " + e.getMessage()); }
            worker.postDelayed(frameLoop, 800);
        } catch (Exception e) {
            Log.e(TAG, "Gagal mulai capture: " + e.getMessage());
            shutdown();
        }
        return START_NOT_STICKY;
    }

    private final Runnable frameLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { grabAndSend(); } catch (Exception e) { Log.e(TAG, "frame gagal: " + e.getMessage()); }
            if (running) worker.postDelayed(this, INTERVAL_MS);
        }
    };

    private boolean screenOff() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && !pm.isInteractive();
    }

    private boolean keyguardLocked() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isKeyguardLocked();
    }

    private void logMode(int mode, String why) {
        if (mode != lastMode) { lastMode = mode; Log.i(TAG, (mode == 0 ? "MODE KARTU: " : "MODE LAYAR: ") + why); }
    }

    /** Frame (hampir) hitam semua → layar tidak benar-benar tertangkap (mati / terkunci / secure). */
    private static boolean looksBlank(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), dark = 0, n = 0;
        for (int y = 4; y < h; y += 12) {
            for (int x = 4; x < w; x += 12) {
                int px = b.getPixel(x, y);
                int lum = (android.graphics.Color.red(px) * 3 + android.graphics.Color.green(px) * 6 + android.graphics.Color.blue(px)) / 10;
                if (lum < 24) dark++;
                n++;
            }
        }
        return n > 0 && dark * 100 >= n * 97;
    }

    private boolean deviceLocked() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean off = pm != null && !pm.isInteractive();
        boolean kg = km != null && km.isKeyguardLocked();
        return off || kg;
    }

    private void releaseCapture() {
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        display = null; reader = null;
    }

    private void grabAndSend() {
        final boolean off = screenOff();
        final boolean kg = keyguardLocked();
        if (off || (kg && !CAPTURE_WHEN_LOCKED_SCREEN_ON)) {
            // Layar mati (atau terkunci & opsi tangkap dimatikan): buang frame hitam, kirim kartu navigasi.
            try { if (!projectionDead && reader != null) { Image d = reader.acquireLatestImage(); if (d != null) d.close(); } }
            catch (Exception ignored) {}
            logMode(0, off ? "layar mati" : "terkunci");
            sendNavCard();
            return;
        }
        if (projectionDead || reader == null) {
            if (kg) { logMode(0, "proyeksi mati, masih terkunci"); sendNavCard(); return; }
            shutdown();   // sudah dibuka tapi proyeksi mati → user harus mulai ulang
            return;
        }
        Image img = reader.acquireLatestImage();
        if (img == null) return;
        Bitmap full;
        try {
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int pixelStride = p.getPixelStride(), rowStride = p.getRowStride();
            int w = img.getWidth(), h = img.getHeight();
            Bitmap padded = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888);
            padded.copyPixelsFromBuffer(buf);
            full = Bitmap.createBitmap(padded, 0, 0, w, h);
            if (full != padded) padded.recycle();
        } finally { img.close(); }

        // Area crop (pecahan layar) → cover-crop ke rasio 350:225 → skala ke 350x225
        int fw = full.getWidth(), fh = full.getHeight();
        int l = Math.round(fw * crop[0]), t = Math.round(fh * crop[1]);
        int r = Math.round(fw * crop[2]), b = Math.round(fh * crop[3]);
        int rw = Math.max(1, r - l), rh = Math.max(1, b - t);
        float ratio = (float) OUT_W / OUT_H;
        int sw, sh;
        if ((float) rw / rh > ratio) { sh = rh; sw = Math.round(sh * ratio); }
        else { sw = rw; sh = Math.round(sw / ratio); }
        int sx = l + (rw - sw) / 2, sy = t + (rh - sh) / 2;

        Bitmap out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(full, new Rect(sx, sy, sx + sw, sy + sh), new Rect(0, 0, OUT_W, OUT_H), paint);
        full.recycle();
        if (kg && looksBlank(out)) {   // layar nyala tapi terkunci & yang tertangkap kosong → pakai kartu
            out.recycle();
            logMode(0, "layar nyala-terkunci, frame kosong");
            sendNavCard();
            return;
        }
        logMode(1, kg ? "layar nyala (terkunci) tertangkap" : "layar terbuka");
        if (overlayEnabled) drawInfoStrip(c);

        String b64 = encodeUnderLimit(out);
        out.recycle();
        if (b64 == null) { Log.w(TAG, "frame terlalu besar, dilewati"); return; }

        // Jalur utama: langsung ke BluetoothService (jalan walau WebView dijeda saat Maps di depan).
        BluetoothService bt = btRef;
        if (bt != null && bt.isConnected()) {
            try {
                if (Integer.parseInt(bt.mapsStatus().split(",")[0].trim()) > 0) return;   // masih ada AT+MAPS antre/ditulis
            } catch (Exception ignored) {}
            bt.send("AT+MAPS=\"" + b64 + "\"");
            return;
        }
        // Cadangan (mis. mode lain): lewat JS
        final String js = "window.onMirrorFrame&&window.onMirrorFrame('" + b64 + "')";
        main.post(() -> {
            if (MainActivity.webViewRef != null) MainActivity.webViewRef.evaluateJavascript(js, null);
        });
    }

    /** Kirim kartu navigasi (dipakai saat HP terkunci). Hanya dikirim ulang kalau isinya berubah / tiap 4 dtk. */
    private void sendNavCard() {
        long now = System.currentTimeMillis();
        if (navUpdatedMs == 0 || now - navUpdatedMs > CARD_STALE_MS) return;   // belum/tidak ada navigasi aktif
        String key = navDist + "|" + navInstr + "|" + navEta;
        if (key.equals(lastCardKey) && now - lastCardMs < CARD_KEEPALIVE_MS) return;

        BluetoothService bt = btRef;
        if (bt == null || !bt.isConnected()) return;
        try {
            if (Integer.parseInt(bt.mapsStatus().split(",")[0].trim()) > 0) return;   // masih ada AT+MAPS antre/ditulis
        } catch (Exception ignored) {}

        Bitmap card = renderNavCard();
        String b64 = encodeUnderLimit(card);
        card.recycle();
        if (b64 == null) { Log.w(TAG, "kartu terlalu besar, dilewati"); return; }
        bt.send("AT+MAPS=\"" + b64 + "\"");
        lastCardKey = key;
        lastCardMs = now;
    }

    /** Batas aman kartu (pecahan tinggi): MID menumpuk teks AT+NAV di atas & widget baterai di bawah gambar. Sesuaikan kalau perlu. */
    private static final float SAFE_TOP = 0.17f, SAFE_BOTTOM = 0.88f;

    /** Kartu hitam, SEMUA teks putih tebal (layar MID memantul → warna kuning/hijau jadi abu-abu & tidak terbaca).
     *  Blok atas: [ikon belokan] + jarak besar + "durasi · jarak sisa". Blok bawah: nama jalan/tujuan (2 baris). */
    private Bitmap renderNavCard() {
        final int w = OUT_W, h = OUT_H;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF000000);
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setFakeBoldText(true);          // goresan lebih tebal supaya tahan silau
        p.setColor(0xFFFFFFFF);

        final float pad = w * 0.04f;
        final float top = h * SAFE_TOP, bottom = h * SAFE_BOTTOM;
        final float rowH = (bottom - top) * 0.48f;      // tinggi blok atas
        float colX = pad;

        Bitmap icon = USE_NAV_ICON ? navIcon : null;
        if (icon != null && !icon.isRecycled() && icon.getWidth() > 0 && icon.getHeight() > 0) {
            float side = rowH;
            float sc = side / Math.max(icon.getWidth(), icon.getHeight());
            float dw = icon.getWidth() * sc, dh = icon.getHeight() * sc;
            float dx = pad + (side - dw) / 2f, dy = top + (side - dh) / 2f;
            Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG);
            ip.setColorFilter(new android.graphics.PorterDuffColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN));
            c.drawBitmap(icon, null, new android.graphics.RectF(dx, dy, dx + dw, dy + dh), ip);
            colX = pad + side + pad;
        }
        final float availW = w - colX - pad;

        // Jarak — besar, dikecilkan otomatis kalau tidak muat
        String dist = navDist.isEmpty() ? "NAV" : navDist;
        float size = rowH * 0.64f;
        p.setTextSize(size);
        while (size > 14f && p.measureText(dist) > availW) { size -= 2f; p.setTextSize(size); }
        c.drawText(dist, colX, top + size * 0.82f, p);
        float distBottom = top + size * 0.82f;

        // "7 mnt · 5,1 km" (tanpa jam tiba; jam sudah ada di MID)
        if (!navEta.isEmpty()) {
            String[] parts = navEta.split("\\s*[\u00B7\u2022]\\s*");
            String eta = parts.length >= 2 ? parts[0] + " \u00B7 " + parts[1] : navEta;
            p.setTextSize(h * 0.09f);
            c.drawText(TextUtils.ellipsize(eta, p, availW, TextUtils.TruncateAt.END).toString(), colX, distBottom + h * 0.115f, p);
        }

        // Nama jalan / tujuan — maks 2 baris, tebal
        if (!navInstr.isEmpty()) {
            p.setTextSize(h * 0.12f);
            drawWrapped(c, p, navInstr, pad, h * 0.655f, w - 2 * pad, h * 0.13f, 2);
        }
        return bmp;
    }

    private static void drawWrapped(Canvas c, TextPaint p, String text, float x, float y, float maxW, float lineH, int maxLines) {
        String rest = text.trim();
        for (int n = 0; n < maxLines && !rest.isEmpty(); n++) {
            float yy = y + n * lineH;
            if (n == maxLines - 1) {
                c.drawText(TextUtils.ellipsize(rest, p, maxW, TextUtils.TruncateAt.END).toString(), x, yy, p);
                break;
            }
            int cnt = p.breakText(rest, true, maxW, null);
            if (cnt >= rest.length()) { c.drawText(rest, x, yy, p); break; }
            int cut = rest.lastIndexOf(' ', cnt);
            if (cut <= 0) cut = cnt;
            c.drawText(rest.substring(0, cut), x, yy, p);
            rest = rest.substring(cut).trim();
        }
    }

    /** Strip solid kecil: baris 1 = jarak + arah, baris 2 = durasi · jarak · jam tiba. */
    private void drawInfoStrip(Canvas c) {
        if (System.currentTimeMillis() - navUpdatedMs > 120000) return;   // data basi → jangan tampilkan
        String dist = navDist, instr = navInstr, eta = navEta;
        if (instr.isEmpty() && eta.isEmpty()) return;

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        tp.setTextSize(13f);
        final int rowH = 16;
        boolean row1 = !(dist.isEmpty() && instr.isEmpty());
        boolean row2 = !eta.isEmpty();
        int h = ((row1 ? 1 : 0) + (row2 ? 1 : 0)) * rowH + 4;
        int y0 = overlayTop ? 0 : OUT_H - h;

        Paint bg = new Paint();
        bg.setColor(0xFF0E1620);
        c.drawRect(0, y0, OUT_W, y0 + h, bg);

        int base = y0 + 2 + rowH - 4;
        if (row1) {
            float x = 4;
            if (!dist.isEmpty()) {
                tp.setColor(0xFFFFC107);                       // jarak: kuning
                c.drawText(dist, x, base, tp);
                x += tp.measureText(dist) + 6;
            }
            tp.setColor(0xFFFFFFFF);
            CharSequence t = TextUtils.ellipsize(instr, tp, OUT_W - x - 4, TextUtils.TruncateAt.END);
            c.drawText(t.toString(), x, base, tp);
            base += rowH;
        }
        if (row2) {
            tp.setColor(0xFF80E27E);                           // ETA: hijau muda
            CharSequence t = TextUtils.ellipsize(eta, tp, OUT_W - 8, TextUtils.TruncateAt.END);
            c.drawText(t.toString(), 4, base, tp);
        }
    }

    /** JPEG kualitas adaptif sampai Base64 <= MAX_CHARS (logika sama dengan snapMapToBase64 di JS). */
    private String encodeUnderLimit(Bitmap bmp) {
        for (int q = 85; q >= 5; q -= 4) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos);
            String s = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            if (s.length() <= MAX_CHARS) return s;
        }
        return null;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Mirror layar ke MID",
                    NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Notification n = nb.setContentTitle("GESITS")
                .setContentText("Mirror layar ke MID aktif")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void shutdown() {
        boolean wasActive = mirrorActive;
        MirrorRegionOverlay.hide();
        running = false;
        mirrorActive = false;
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
        if (wasActive) main.post(() -> {
            if (MainActivity.webViewRef != null)
                MainActivity.webViewRef.evaluateJavascript("window.onMirrorStopped&&window.onMirrorStopped()", null);
        });
        try { if (display != null) display.release(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (projection != null) projection.stop(); } catch (Exception ignored) {}
        if (thread != null) thread.quitSafely();
        display = null; reader = null; projection = null; thread = null;
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() { shutdown(); super.onDestroy(); }
}
