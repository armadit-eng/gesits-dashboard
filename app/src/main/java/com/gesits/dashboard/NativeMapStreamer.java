package com.gesits.dashboard;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PETA LIVE NATIVE untuk MID (AT+MAPS) — pengganti captureAndSend() di WebView.
 *
 * Semuanya digambar di Java pada thread sendiri: tile raster (256 px, sumber sama dengan style OSM di aplikasi),
 * garis rute, panah posisi (heading-up), dan bar navigasi (panah belok, jarak, instruksi, nama jalan, sisa/ETA/tiba).
 * Posisi dibaca langsung dari GPS native; langkah navigasi (step) maju otomatis. Jadi tetap jalan walau WebView
 * di-pause (Maps di depan / layar terkunci) dan tidak ada render WebGL / toDataURL di thread UI.
 *
 * Frame dikirim lewat BluetoothService yang sama dengan ScreenCaptureService (ScreenCaptureService.btRef),
 * dengan pengecekan antrean tepat sebelum kirim.
 *
 * Dipanggil dari JS lewat JsBridge (lihat potongan MainActivity):
 *   start(ctx, cfgJson)  stop()  setRoute(json)  update(json)
 */
public final class NativeMapStreamer {
    private static final String TAG = "NativeMap";
    private static final int MAX_CHARS = 7000;        // sama dengan MAPS_MAX_CHARS di JS
    private static final int TILE = 256;

    private NativeMapStreamer() {}

    // ── lifecycle / thread ──
    private static HandlerThread ht;
    private static Handler h;
    private static ExecutorService pool;
    private static Context app;
    private static volatile boolean running = false;
    private static LocationManager lm;
    private static PowerManager.WakeLock wakeLock;   // CPU tetap hidup saat layar mati / terkunci

    // ── konfigurasi (dari JS) ──
    private static volatile String[] tileUrls = { "https://a.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png" };
    private static volatile String styleId = "day";   // dipakai untuk nama cache disk
    private static volatile double zoom = 16.5;
    private static volatile int outW = 350, outH = 225;
    private static volatile boolean night = false;

    // ── posisi ──
    private static volatile double lat = Double.NaN, lng = Double.NaN;
    private static volatile float hdg = 0f;
    private static volatile double spdKmh = 0;       // kecepatan GPS (km/j)
    private static volatile double accM = 0;           // akurasi horizontal fix terakhir (m); 0 = tidak diketahui
    private static String fixProv = "";
    private static long lastAccLogMs = 0, lastDiffLogMs = 0;
    private static volatile long preferJsUntil = 0;     // sampai kapan fix JS (fused WebView) dipakai karena lebih akurat
    private static volatile double motorKmh = -1;    // kecepatan motor dari JS (opsional)
    // ── layout split: "full" = peta penuh, "split" = separuh kiri peta + separuh kanan info ──
    private static volatile String layout = "full";
    // posisi rider saat NAVIGASI: true = agak ke bawah (jalan di depan lebih luas), false = tengah peta
    private static volatile boolean riderLow = true;
    // Profil ketinggian + daftar tanjakan SELURUH rute (dikirim JS sekali per rute). Posisi pada rute dihitung di Java dari GPS
    // native, jadi grafik profil & label tanjakan di panel split tetap bergerak walau JS/WebView terjeda saat layar mati.
    private static volatile double[] elevD = null, elevH = null;        // jarak kumulatif (m), tinggi (m)
    private static volatile double[][] hillsArr = null;                 // {startM, endM, avg%, up(1/0), notable(1/0), steep(1/0)}
    // Kunci rute dari JS: profil hanya dipakai kalau kuncinya sama dengan rute yang sedang aktif di Java. Tidak ada lagi
    // penghapusan saat rute di-push ulang (yang dulu bisa menghapus profil yang baru tiba, atau tidak pernah dikirim ulang).
    private static volatile String routeKey = null, elevKey = null;
    private static boolean elevValid() { String a = elevKey, b = routeKey; return elevD != null && elevH != null && a != null && a.length() > 0 && a.equals(b); }

    /** Penerus fix GPS native ke JS (geolocation WebView ikut terjeda saat layar mati). Diset oleh MainActivity. */
    public interface GpsSink { void onFix(double lat, double lng, float spdMs, boolean hasSpd, float hdg, boolean hasHdg, float acc, double alt, boolean hasAlt); }
    private static volatile GpsSink gpsSink = null;
    private static long lastSinkMs = 0;
    public static void setGpsSink(GpsSink s) { gpsSink = s; }
    private static volatile double pSoc = -1, pRng = -1;     // SOC (%) dan range (km) dari JS; -1 = belum ada
    private static volatile String pHill = "";               // teks tanjakan/turunan di depan (<=20 char ASCII), kosong = tidak ada
    private static volatile boolean pHillUp = true;
    private static volatile double pAlt = Double.NaN, pEff = -1;   // elevasi GPS (m) & efisiensi (Wh/km) dari JS
    // Elevasi dari GPS native (dihaluskan) — dipakai panel bila segar, jadi elevasi tetap bergerak walau JS/WebView tidak memperbarui pAlt.
    private static volatile double natAlt = Double.NaN;
    private static volatile long natAltTs = 0, lastJsUpdMs = 0;
    private static double altNow() {
        return (!Double.isNaN(natAlt) && System.currentTimeMillis() - natAltTs < 8000) ? natAlt : pAlt;
    }
    private static volatile double pArrSoc = Double.NaN;            // perkiraan SOC saat tiba (%), dari JS
    private static volatile String pSpklu = "";                      // "SPKLU 4.2km" bila SOC tiba rendah
    // Kartu ringkasan trip (tiba / akhiri trip): menutupi seluruh frame selama sumUntil, dikirim dari JS lewat update({sum:...}).
    private static volatile String sumTitle = "";
    private static volatile String[] sumTxt = null;
    private static volatile long[] sumCol = null;
    private static volatile long sumUntil = 0;
    private static boolean sumActive() { return sumTxt != null && System.currentTimeMillis() < sumUntil; }
    private static volatile String pRoad = "";                      // nama lokasi saat free ride
    private static volatile double[] pProf = null;                  // profil ketinggian rute ke depan (m), tiap pProfStep meter
    private static volatile int pProfStep = 150;
    private static volatile long gpsTs = 0;          // kapan fix GPS native terakhir
    private static volatile long posTs = 0;

    // ── rute ──
    private static final class Step {
        double lng, lat, dist; String type = "", mod = "", name = "";
        int ri = -1;                 // indeks titik rute terdekat dari titik manuver (-1 = tidak diketahui)
    }
    private static final class Route {
        double[] lng, lat; float[] cum; List<Step> steps = new ArrayList<>(); double[] suffix;
    }
    private static volatile Route route = null;
    private static int stepIdx = 0, rIdx = 0;        // hanya diakses dari thread worker
    private static int distStep = -1;                // step yang jarak tampilnya sedang dihitung
    private static double shownDist = 0;             // jarak ke belokan yang DITAMPILKAN (tidak boleh naik karena jitter)
    private static double minDist = 1e9;             // jarak terdekat yang pernah dicapai ke titik belok step ini
    private static double offRoute = 1e9;            // jarak user ke garis rute (m)

    // ── tile ──
    private static final LruCache<String, Bitmap> mem = new LruCache<>(56);
    private static final Set<String> loading = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<String, Long> failedAt = new ConcurrentHashMap<>();
    private static volatile int tilesLoaded = 0;     // naik tiap tile baru masuk → memicu render ulang
    private static File diskDir;

    // ── pengiriman ──
    private static boolean gpsWarned = false;
    private static String lastSig = "";
    private static long lastSendMs = 0;
    private static long lastWriteMs = 0;
    // ── diagnosa: alasan tick terakhir tidak/ sudah mengirim frame (dilog tiap 10 dtk sebagai "hb") ──
    private static volatile String why = "init";
    private static long lastHbMs = 0, ticks = 0, sentCount = 0;
    private static long busySince = 0;                   // sejak kapan antrean BT terlihat "sibuk" terus-menerus
    private static long disconnectedSince = 0;           // sejak kapan MID tidak tersambung
    private static volatile boolean timePending = false; // kirim AT+TIME setelah MID tersambung ulang (MID tanpa baterai CMOS lupa jam)
    private static volatile long reconnectAt = 0;
    private static final long IDLE_STOP_MS = 10 * 60 * 1000;   // MID putus > 10 menit → hentikan sendiri (hemat baterai HP)

    // ═════════════════════════ API publik ═════════════════════════

    public static synchronized void start(Context ctx, String cfgJson) {
        try {
            JSONObject c = new JSONObject(cfgJson == null ? "{}" : cfgJson);
            JSONArray t = c.optJSONArray("tiles");
            if (t != null && t.length() > 0) {
                String[] u = new String[t.length()];
                for (int i = 0; i < u.length; i++) u[i] = t.getString(i);
                tileUrls = u;
            }
            styleId = c.optString("style", "day").replaceAll("[^A-Za-z0-9_]", "");
            night = c.optBoolean("night", false);
            zoom = Math.max(3, Math.min(19.5, c.optDouble("zoom", 16.5)));
            outW = Math.max(64, Math.min(1024, c.optInt("w", 350)));
            outH = Math.max(64, Math.min(1024, c.optInt("h", 225)));
            layout = "split".equals(c.optString("layout", "full")) ? "split" : "full";
            riderLow = !"center".equals(c.optString("rider", "low"));
            final boolean useGps = c.optBoolean("gps", true);

            if (running) return;                      // sudah jalan: konfigurasi cukup diperbarui
            app = ctx.getApplicationContext();
            diskDir = new File(app.getCacheDir(), "ntiles");
            //noinspection ResultOfMethodCallIgnored
            diskDir.mkdirs();
            pruneDisk();

            ht = new HandlerThread("NativeMap");
            ht.start();
            h = new Handler(ht.getLooper());
            pool = Executors.newFixedThreadPool(3);
            running = true;
            lastSig = "";
            try {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gesits:nativemap");
                wakeLock.acquire(8 * 60 * 60 * 1000L);       // batas aman 8 jam (butuh izin WAKE_LOCK di manifest)
            } catch (Exception e) { Log.w(TAG, "wakelock gagal: " + e.getMessage()); }
            startLocationService();                    // service foreground tipe location → GPS tetap hidup di latar belakang
            if (useGps) h.post(NativeMapStreamer::registerGps);
            h.postDelayed(loop, 800);
            Log.i(TAG, "start zoom=" + zoom + " " + outW + "x" + outH + " tiles=" + tileUrls.length);
        } catch (Exception e) {
            Log.w(TAG, "start gagal: " + e.getMessage());
        }
    }

    public static synchronized void stop() {
        if (!running) return;
        running = false;
        try { if (h != null) h.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try { if (lm != null) lm.removeUpdates(gpsListener); } catch (Exception ignored) {}
        lm = null;
        try { if (pool != null) pool.shutdownNow(); } catch (Exception ignored) {}
        try { if (ht != null) ht.quitSafely(); } catch (Exception ignored) {}
        pool = null; ht = null; h = null;
        route = null;
        try { if (app != null) app.stopService(new Intent(app, NativeMapService.class)); } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
        Log.i(TAG, "stop");
    }

    public static boolean isRunning() { return running; }

    /** Dipanggil MainActivity saat BluetoothService melaporkan MID tersambung kembali (jalan walau WebView dijeda / layar mati). */
    public static void onBtReconnected() {
        if (!running) return;
        reconnectAt = System.currentTimeMillis();
        timePending = true;
        busySince = 0;
        lastSig = "";                                   // paksa kirim frame segera
        Log.i(TAG, "MID tersambung kembali → lanjut kirim peta (+ sinkron jam)");
    }

    public static void onBtDisconnected() {
        if (!running) return;
        lastSig = "";
        Log.i(TAG, "MID terputus → peta menunggu sambung ulang");
    }

    /** JSON: {pts:[lng,lat,lng,lat,...], steps:[{lo,la,t,m,n,d}], si:0}. Dipanggil tiap rute baru / reroute. */
    public static void setRoute(String json) {
        if (!running) return;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray p = o.optJSONArray("pts");
            if (p == null || p.length() < 4) { final Handler hh = h; if (hh != null) hh.post(() -> { route = null; routeKey = null; stepIdx = 0; rIdx = 0; distStep = -1; minDist = 1e9; }); return; }
            int n = p.length() / 2;
            Route r = new Route();
            r.lng = new double[n]; r.lat = new double[n]; r.cum = new float[n];
            for (int i = 0; i < n; i++) { r.lng[i] = p.getDouble(2 * i); r.lat[i] = p.getDouble(2 * i + 1); }
            for (int i = 1; i < n; i++) r.cum[i] = r.cum[i - 1] + (float) haversine(r.lat[i - 1], r.lng[i - 1], r.lat[i], r.lng[i]);
            JSONArray s = o.optJSONArray("steps");
            if (s != null) for (int i = 0; i < s.length(); i++) {
                JSONObject so = s.getJSONObject(i);
                Step st = new Step();
                st.lng = so.optDouble("lo"); st.lat = so.optDouble("la");
                st.type = so.optString("t", "").toLowerCase(Locale.US);
                st.mod = so.optString("m", "").toLowerCase(Locale.US);
                st.name = so.optString("n", "");
                st.dist = so.optDouble("d", 0);
                r.steps.add(st);
            }
            // Indeks titik rute tiap manuver: posisi kumulatif = jumlah jarak step sebelumnya, dihaluskan ke titik terdekat.
            double geomTotal = r.cum[n - 1], stepsTotal = 0;
            for (Step st0 : r.steps) stepsTotal += st0.dist;
            if (geomTotal > 0 && Math.abs(stepsTotal - geomTotal) <= 0.05 * geomTotal + 30) {
                double target = 0; int from = 0;
                for (Step st0 : r.steps) {
                    int k = from;
                    while (k < n - 1 && r.cum[k] < target) k++;
                    int lo = Math.max(from, k - 15), hi = Math.min(n - 1, k + 15);
                    int best = k; double bd = Double.MAX_VALUE;
                    for (int j = lo; j <= hi; j++) {
                        double dd = haversine(r.lat[j], r.lng[j], st0.lat, st0.lng);
                        if (dd < bd) { bd = dd; best = j; }
                    }
                    st0.ri = best; from = best;
                    target += st0.dist;
                }
            }
            r.suffix = new double[r.steps.size() + 1];
            for (int i = r.steps.size() - 1; i >= 0; i--) r.suffix[i] = r.suffix[i + 1] + r.steps.get(i).dist;
            final int si = Math.max(0, o.optInt("si", 0));
            final String rk = o.optString("rk", "");
            final Handler hh = h;
            if (hh != null) hh.post(() -> { route = r; routeKey = rk; stepIdx = Math.min(si, Math.max(0, r.steps.size() - 1)); rIdx = 0; lastSig = ""; distStep = -1; minDist = 1e9; });
        } catch (Exception e) {
            Log.w(TAG, "setRoute gagal: " + e.getMessage());
        }
    }

    /** JSON: {lat,lng,hdg,spd,mspd,zoom,night}. Posisi dari JS hanya dipakai kalau GPS native tidak ada fix baru. */
    public static void update(String json) {
        lastJsUpdMs = System.currentTimeMillis();
        if (!running) return;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("zoom")) zoom = Math.max(3, Math.min(19.5, o.optDouble("zoom", zoom)));
            if (o.has("night")) night = o.optBoolean("night", night);
            if (o.has("mspd")) motorKmh = o.optDouble("mspd", -1);
            if (o.has("layout")) layout = "split".equals(o.optString("layout", "full")) ? "split" : "full";
            if (o.has("rider")) riderLow = !"center".equals(o.optString("rider", "low"));
            if (o.has("soc")) pSoc = o.optDouble("soc", -1);
            if (o.has("rng")) pRng = o.optDouble("rng", -1);
            if (o.has("hill")) { pHill = o.optString("hill", ""); pHillUp = o.optBoolean("hillUp", true); }
            if (o.has("elev")) {
                elevKey = o.optString("rk", "");
                JSONObject eo = o.optJSONObject("elev");
                JSONArray da = eo == null ? null : eo.optJSONArray("d"), ha = eo == null ? null : eo.optJSONArray("h");
                if (da == null || ha == null || da.length() < 2 || da.length() != ha.length()) { elevD = null; elevH = null; }
                else {
                    double[] dd = new double[da.length()], hh2 = new double[ha.length()];
                    for (int i = 0; i < dd.length; i++) { dd[i] = da.optDouble(i); hh2[i] = ha.optDouble(i); }
                    elevD = dd; elevH = hh2;
                }
            }
            if (o.has("hills")) {
                JSONArray ha2 = o.optJSONArray("hills");
                if (ha2 == null) hillsArr = null;
                else {
                    double[][] hs = new double[ha2.length()][];
                    for (int i = 0; i < hs.length; i++) {
                        JSONArray row = ha2.optJSONArray(i);
                        hs[i] = row == null || row.length() < 6 ? new double[] { 0, 0, 0, 0, 0, 0 }
                                : new double[] { row.optDouble(0), row.optDouble(1), row.optDouble(2), row.optDouble(3), row.optDouble(4), row.optDouble(5) };
                    }
                    hillsArr = hs;
                }
            }
            if (o.has("sum")) {
                JSONObject so = o.optJSONObject("sum");
                if (so == null) { sumUntil = 0; sumTxt = null; }
                else {
                    JSONArray ra = so.optJSONArray("rows");
                    if (ra != null && ra.length() > 0) {
                        String[] tx = new String[ra.length()]; long[] cl = new long[ra.length()];
                        for (int i = 0; i < tx.length; i++) {
                            JSONObject ro = ra.optJSONObject(i);
                            tx[i] = ro == null ? "" : ro.optString("t", "");
                            cl[i] = ro == null ? 0xFFFFFFFFL : ro.optLong("c", 0xFFFFFFFFL);
                        }
                        sumTitle = so.optString("title", "SELESAI"); sumCol = cl; sumTxt = tx;
                        sumUntil = System.currentTimeMillis() + Math.max(1000L, o.optLong("sumMs", 20000L));
                    }
                }
            }
            if (o.has("alt")) pAlt = o.optDouble("alt", Double.NaN);
            if (o.has("arr")) pArrSoc = o.optDouble("arr", Double.NaN);
            if (o.has("spklu")) pSpklu = o.optString("spklu", "");
            if (o.has("eff")) pEff = o.optDouble("eff", -1);
            if (o.has("road")) pRoad = o.optString("road", "");
            if (o.has("prof")) {
                JSONArray pa = o.optJSONArray("prof");
                if (pa == null || pa.length() < 3) pProf = null;
                else { double[] pp = new double[pa.length()]; for (int i = 0; i < pp.length; i++) pp[i] = pa.optDouble(i); pProf = pp; }
                pProfStep = Math.max(20, o.optInt("profStep", 150));
            }
            long now = System.currentTimeMillis();
            if (o.has("lat") && o.has("lng")) {
                double jl = o.getDouble("lat"), jg = o.getDouble("lng"), jsAcc = o.optDouble("acc", 0);
                if (now - gpsTs > 3000) {                                    // GPS native sepi → pakai posisi dari JS
                    lat = jl; lng = jg; accM = jsAcc;
                    if (o.has("hdg")) hdg = (float) o.optDouble("hdg", hdg);
                    if (o.has("spd")) spdKmh = o.optDouble("spd", spdKmh);
                    posTs = now;
                    final Handler hh = h;
                    if (hh != null) hh.post(() -> { updateRouteIdx(); advanceStep(); });
                } else {
                    // Dua sumber posisi aktif (GPS native & fused WebView): catat selisihnya, dan pakai yang jauh lebih akurat.
                    double diff = haversine(lat, lng, jl, jg);
                    if (diff > 30 && now - lastDiffLogMs > 15000) {
                        lastDiffLogMs = now;
                        Log.w(TAG, "posisi native vs JS beda " + Math.round(diff) + " m (native " + fixProv + " ±" + Math.round(accM) + " m, JS ±" + Math.round(jsAcc) + " m)");
                    }
                    if (jsAcc > 0 && accM > 30 && accM > 2 * jsAcc) {
                        lat = jl; lng = jg; accM = jsAcc; posTs = now;
                        preferJsUntil = now + 3000;
                        final Handler hh = h;
                        if (hh != null) hh.post(() -> { updateRouteIdx(); advanceStep(); });
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // ═════════════════════════ GPS native ═════════════════════════

    private static void startLocationService() {
        try {
            Intent i = new Intent(app, NativeMapService.class);
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i); else app.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "service lokasi gagal dijalankan: " + e.getMessage() + " — cek deklarasi NativeMapService di AndroidManifest.xml");
        }
    }

    private static final LocationListener gpsListener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            long now = System.currentTimeMillis();
            String prov = String.valueOf(l.getProvider());
            float acc = l.hasAccuracy() ? l.getAccuracy() : 999f;
            if (now < preferJsUntil && acc > accM) return;   // posisi JS lebih akurat sedang dipakai
            // Dua provider (GPS + fused): jangan timpa fix segar yang lebih akurat dengan fix dari provider lain yang lebih kasar.
            if (now - gpsTs < 4000 && !prov.equals(fixProv) && accM > 0 && acc > accM * 1.3) return;
            fixProv = prov; accM = acc;
            if (now - lastAccLogMs > 30000) { lastAccLogMs = now; Log.i(TAG, "GPS " + prov + " akurasi ±" + Math.round(acc) + " m"); }
            lat = l.getLatitude(); lng = l.getLongitude();
            float sp = l.hasSpeed() ? l.getSpeed() : 0f;
            spdKmh = sp * 3.6;
            if (l.hasBearing() && sp > 1.5f) {
                float diff = ((l.getBearing() - hdg + 540f) % 360f) - 180f;
                hdg = (hdg + diff * 0.6f + 360f) % 360f;
            }
            gpsTs = now; posTs = now;
            updateRouteIdx(); advanceStep();           // evaluasi di SETIAP fix, bukan hanya tiap siklus kirim
            if (l.hasAltitude()) {                     // elevasi native (EMA ringan supaya tidak melompat)
                double a = l.getAltitude();
                natAlt = Double.isNaN(natAlt) ? a : natAlt * 0.7 + a * 0.3;
                natAltTs = now;
            }
            GpsSink gs = gpsSink;                      // teruskan ke JS (≤1x/dtk) supaya trip, tanjakan, AT+NAV tetap hidup saat layar mati
            if (gs != null && now - lastSinkMs >= 900) {
                lastSinkMs = now;
                try { gs.onFix(lat, lng, sp, l.hasSpeed(), l.getBearing(), l.hasBearing(), acc, l.getAltitude(), l.hasAltitude()); } catch (Throwable ignored) {}
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    private static void registerGps() {
        try {
            if (app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "izin lokasi belum ada → posisi hanya dari JS");
                return;
            }
            lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gpsListener, ht.getLooper());
                // Android 12+: provider "fused" (GPS + Wi-Fi + sensor, seperti Google Maps) sering jauh lebih akurat daripada GPS mentah.
                try {
                    if (Build.VERSION.SDK_INT >= 31 && lm.getAllProviders().contains("fused"))
                        lm.requestLocationUpdates("fused", 1000, 0, gpsListener, ht.getLooper());
                } catch (Exception e) { Log.w(TAG, "provider fused tidak tersedia: " + e.getMessage()); }
            }
        } catch (Exception e) {
            Log.w(TAG, "GPS native gagal: " + e.getMessage());
        }
    }

    // ═════════════════════════ loop kirim ═════════════════════════

    private static final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long delay = 1000;
            try { delay = tick(); ticks++; } catch (Throwable t) { why = "EXC " + t; Log.w(TAG, "tick: " + t); }
            heartbeat();
            Handler hh = h;
            if (running && hh != null) hh.postDelayed(this, delay);
        }
    };

    private static long tick() {
        if (Double.isNaN(lat)) { why = "belum ada fix posisi"; return 1000; }
        BluetoothService bt = ScreenCaptureService.btRef;
        long now = System.currentTimeMillis();
        if (bt == null || !bt.isConnected()) {
            if (disconnectedSince == 0) disconnectedSince = now;
            else if (now - disconnectedSince > IDLE_STOP_MS) {       // MID lama tidak ada → jangan boroskan baterai HP
                Log.i(TAG, "MID tidak tersambung > 10 menit → peta native berhenti (JS akan memulai lagi saat tersambung)");
                stop();
            }
            why = "MID/BT tidak tersambung";
            return 1500;
        }
        disconnectedSince = 0;
        if (ScreenCaptureService.mirrorActive) { why = "mirror aktif"; return 1500; }       // mirror layar HP lebih prioritas

        if (timePending && now - reconnectAt > 1500) {            // MID baru restart: jamnya reset → sinkron lagi (sama seperti JS sendTimeCmd)
            timePending = false;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            bt.send(String.format(Locale.US, "AT+TIME=\"%02d%02d\"", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)));
            return 800;
        }
        if (gpsTs > 0 && now - gpsTs > 8000) {
            if (!gpsWarned) { gpsWarned = true; Log.w(TAG, "GPS native tidak update >8 dtk (di latar belakang butuh service tipe location + izin lokasi)"); }
        } else gpsWarned = false;
        updateRouteIdx();
        advanceStep();

        // tanda tangan keadaan: kalau tidak berubah, kirim ulang paling cepat tiap 4 dtk
        Step st = currentStep();
        String sig = Math.round(lat * 1e5) + "," + Math.round(lng * 1e5) + "," + Math.round(hdg / 4f) + ","
                + stepIdx + "," + (st == null ? 0 : Math.round(distToStep(st) / 5.0)) + "," + tilesLoaded + "," + night + "," + zoom + panelSig();
        if (sig.equals(lastSig) && now - lastSendMs < 4000) { why = "sig sama (tidak ada perubahan)"; return 500; }

        int[] stt = queueStatus(bt);
        if (queueBusy(stt, now)) { why = "antrean BT sibuk pending=" + stt[0]; return 300; }                       // frame sebelumnya masih ditulis ke MID
        lastWriteMs = stt[1];

        Bitmap bmp = render();
        String b64 = encode(bmp);
        bmp.recycle();
        if (b64 == null) { why = "frame > batas karakter"; Log.w(TAG, "frame > batas karakter"); return 1500; }

        stt = queueStatus(bt);                                     // cek ULANG tepat sebelum kirim
        if (queueBusy(stt, System.currentTimeMillis())) { why = "antrean BT sibuk (cek ulang) pending=" + stt[0]; return 300; }
        bt.send("AT+MAPS=\"" + b64 + "\"");
        lastSig = sig;
        lastSendMs = now;
        sentCount++; why = "terkirim";
        return Math.max(1000, Math.round(lastWriteMs * 1.2));
    }

    /** Log ringkas tiap 10 dtk: kalau peta "macet", baris ini menunjukkan di mana berhentinya (thread mati? GPS? antrean BT? frame?). */
    private static void heartbeat() {
        long now = System.currentTimeMillis();
        if (now - lastHbMs < 10000) return;
        lastHbMs = now;
        BluetoothService bt = ScreenCaptureService.btRef;
        int[] q = bt == null ? new int[] { -1, -1 } : queueStatus(bt);
        Route r = route;
        Log.i(TAG, "hb ticks=" + ticks + " terkirim=" + sentCount + " alasan=[" + why + "]"
                + " gpsUmur=" + (gpsTs == 0 ? -1 : (now - gpsTs) / 1000) + "s kirimUmur=" + (lastSendMs == 0 ? -1 : (now - lastSendMs) / 1000) + "s"
                + " antrean=" + q[0] + "/" + q[1] + "ms bt=" + (bt != null && bt.isConnected())
                + " rute=" + (r == null ? "-" : r.lng.length + "pt/" + r.steps.size() + "step") + " step=" + stepIdx
                + " tile(muat/gagal)=" + loading.size() + "/" + failedAt.size()
                + " jsUpdUmur=" + (lastJsUpdMs == 0 ? -1 : (now - lastJsUpdMs) / 1000) + "s natAlt=" + (Double.isNaN(natAlt) ? "-" : String.valueOf(Math.round(natAlt))) + " pAlt=" + (Double.isNaN(pAlt) ? "-" : String.valueOf(Math.round(pAlt)))
                + " profil=" + (elevValid() ? "ok" : (elevD == null ? "belum-ada" : "tak-cocok-rute")) + " layout=" + layout);
    }

    /** Antrean dianggap sibuk selama ada tulisan tertunda; kalau "sibuk" terus > 10 dtk (mis. sisa antrean sebelum MID putus) → abaikan. */
    private static boolean queueBusy(int[] stt, long now) {
        if (stt[0] <= 0) { busySince = 0; return false; }
        if (busySince == 0) busySince = now;
        if (now - busySince < 10000) return true;
        Log.w(TAG, "antrean BT sibuk > 10 dtk — diabaikan (kemungkinan sisa dari koneksi sebelumnya)");
        busySince = 0;
        return false;
    }

    private static int[] queueStatus(BluetoothService bt) {
        try {
            String[] p = bt.mapsStatus().split(",");
            return new int[] { Integer.parseInt(p[0].trim()), p.length > 1 ? Integer.parseInt(p[1].trim()) : 0 };
        } catch (Exception e) { return new int[] { 0, 0 }; }
    }

    private static String encode(Bitmap bmp) {
        for (int q = 85; q >= 5; q -= 4) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos);
            String s = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            if (s.length() <= MAX_CHARS) return s;
        }
        return null;
    }

    // ═════════════════════════ navigasi ═════════════════════════

    private static Step currentStep() {
        Route r = route;
        if (r == null || r.steps.isEmpty() || stepIdx >= r.steps.size()) return null;
        return r.steps.get(stepIdx);
    }

    private static double straightTo(Step s) { return haversine(lat, lng, s.lat, s.lng); }

    /** Jarak ke belokan yang ditampilkan: sudah dihitung & dihaluskan oleh advanceStep() (tidak naik karena jitter / lengkungan). */
    private static double distToStep(Step s) { return distStep == stepIdx ? shownDist : straightTo(s); }

    /** Posisi user pada rute dalam meter kumulatif (diproyeksikan ke segmen terdekat, bukan ke titik terdekat). */
    private static double progressCum(Route r) {
        int n = r.lng.length;
        int i0 = Math.max(0, Math.min(rIdx, n - 1));
        double cosLat = Math.cos(Math.toRadians(lat)), best = Double.MAX_VALUE, cumAt = r.cum[i0];
        for (int sg = i0 - 1; sg <= i0; sg++) {
            if (sg < 0 || sg >= n - 1) continue;
            double bx = (r.lng[sg + 1] - r.lng[sg]) * 111320.0 * cosLat, by = (r.lat[sg + 1] - r.lat[sg]) * 110540.0;
            double px = (lng - r.lng[sg]) * 111320.0 * cosLat, py = (lat - r.lat[sg]) * 110540.0;
            double len2 = bx * bx + by * by;
            double t = len2 > 0 ? Math.max(0, Math.min(1, (px * bx + py * by) / len2)) : 0;
            double off = Math.hypot(px - t * bx, py - t * by);
            if (off < best) { best = off; cumAt = r.cum[sg] + t * Math.sqrt(len2); }
        }
        offRoute = best;
        return cumAt;
    }

    /**
     * Maju ke belokan berikutnya + hitung jarak tampil. Dievaluasi di setiap fix GPS dan tiap siklus kirim.
     * Step dianggap terlewati kalau: (a) < 20 m dari titik belok (aturan lama), (b) posisi pada rute sudah melewati titik belok,
     * atau (c) sudah sempat dekat (< 50 m) lalu menjauh > 30 m. Aturan (b)/(c) mencegah belokan "terlewat" saat sampel GPS jarang.
     */
    private static void advanceStep() {
        Route r = route;
        if (r == null || r.steps.isEmpty() || Double.isNaN(lat)) return;
        int last = r.steps.size() - 1;
        for (int guard = 0; guard < 8; guard++) {
            while (stepIdx < last && "depart".equals(r.steps.get(stepIdx).type)) { stepIdx++; minDist = 1e9; }
            if (stepIdx >= last) break;                          // step terakhir (tiba) tidak di-advance
            Step s = r.steps.get(stepIdx);
            double d = straightTo(s);
            if (distStep != stepIdx) minDist = 1e9;
            if (d < minDist) minDist = d;
            boolean passed = d < 20
                    || (s.ri >= 0 && rIdx > s.ri && rIdx - s.ri < 300)
                    || (minDist < 50 && d > minDist + 30);
            if (!passed) break;
            stepIdx++;
            minDist = 1e9;
        }
        Step s = r.steps.get(Math.min(stepIdx, last));
        double raw = straightTo(s);
        if (s.ri >= 0 && rIdx <= s.ri) {                         // di rute → pakai jarak SEPANJANG rute (turun monoton)
            double along = r.cum[s.ri] - progressCum(r);
            if (offRoute < 45 && along >= -5) raw = Math.max(0, along);
        }
        if (distStep != stepIdx) { distStep = stepIdx; shownDist = raw; }
        else if (raw <= shownDist) shownDist = raw;
        else if (raw - shownDist >= 40) shownDist = raw;         // naik ≥ 40 m = benar-benar menjauh / reroute; selain itu tahan
    }

    /** Titik terdekat pada rute (pencarian maju dari indeks terakhir; kalau jauh, cari ulang global). */
    private static void updateRouteIdx() {
        Route r = route;
        if (r == null) return;
        int n = r.lng.length;
        double cosLat = Math.cos(Math.toRadians(lat));
        double[] bd = new double[1];
        int best = nearest(r, Math.max(0, rIdx - 3), Math.min(n - 1, rIdx + 120), cosLat, bd);
        if (bd[0] > 5e-6) best = nearest(r, 0, n - 1, cosLat, bd);   // > ±250 m dari jendela → cari di seluruh rute
        if (best >= 0) rIdx = best;
    }

    private static int nearest(Route r, int from, int to, double cosLat, double[] outD) {
        int best = -1; double bd = Double.MAX_VALUE;
        for (int i = from; i <= to; i++) {
            double dx = (r.lng[i] - lng) * cosLat, dy = r.lat[i] - lat;
            double d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = i; }
        }
        outD[0] = bd;
        return best;
    }

    private static double remainingMeters() {
        Route r = route;
        Step s = currentStep();
        if (r == null || s == null) return -1;
        double total = r.suffix[stepIdx];
        double d = distToStep(s);
        return Math.max(0, total - (s.dist - d));
    }

    // ═════════════════════════ tile ═════════════════════════

    private static String key(int z, int x, int y) { return styleId + "/" + z + "/" + x + "/" + y; }

    /** Ambil dari memori; kalau belum ada, minta dimuat di latar belakang. */
    private static Bitmap tile(int z, int x, int y, boolean request) {
        String k = key(z, x, y);
        Bitmap b = mem.get(k);
        if (b != null) return b;
        if (request) requestTile(z, x, y, k);
        return null;
    }

    private static void requestTile(final int z, final int x, final int y, final String k) {
        Long f = failedAt.get(k);
        if (f != null && System.currentTimeMillis() - f < 15000) return;
        if (!loading.add(k)) return;
        final ExecutorService p = pool;
        if (p == null) { loading.remove(k); return; }
        try {
            p.execute(() -> {
                try {
                    File f2 = new File(diskDir, styleId + "_" + z + "_" + x + "_" + y + ".png");
                    Bitmap b = null;
                    if (f2.exists()) b = BitmapFactory.decodeFile(f2.getAbsolutePath());
                    if (b == null) {
                        String[] urls = tileUrls;
                        String u = urls[(x + y) % urls.length].replace("{z}", "" + z).replace("{x}", "" + x).replace("{y}", "" + y);
                        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                        c.setConnectTimeout(6000); c.setReadTimeout(8000);
                        c.setRequestProperty("User-Agent", "GesitsMID/1.0 (Android)");
                        if (c.getResponseCode() == 200) {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            try (InputStream in = c.getInputStream()) {
                                byte[] buf = new byte[8192]; int n;
                                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                            }
                            byte[] data = bos.toByteArray();
                            b = BitmapFactory.decodeByteArray(data, 0, data.length);
                            if (b != null) try (FileOutputStream fo = new FileOutputStream(f2)) { fo.write(data); } catch (Exception ignored) {}
                        }
                        c.disconnect();
                    }
                    if (b != null) { mem.put(k, b); tilesLoaded++; failedAt.remove(k); }
                    else failedAt.put(k, System.currentTimeMillis());
                } catch (Exception e) {
                    failedAt.put(k, System.currentTimeMillis());
                } finally {
                    loading.remove(k);
                }
            });
        } catch (Exception e) { loading.remove(k); }
    }

    private static void pruneDisk() {
        try {
            File[] fs = diskDir.listFiles();
            if (fs == null || fs.length < 4000) return;
            Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < 1000 && i < fs.length; i++) //noinspection ResultOfMethodCallIgnored
                fs[i].delete();
        } catch (Exception ignored) {}
    }

    // ═════════════════════════ proyeksi ═════════════════════════

    private static double worldX(double lo, int z) { return (lo + 180.0) / 360.0 * TILE * (1L << z); }
    private static double worldY(double la, int z) {
        double s = Math.sin(Math.toRadians(Math.max(-85.05, Math.min(85.05, la))));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * TILE * (1L << z);
    }

    static double haversine(double la1, double lo1, double la2, double lo2) {
        double R = 6371000.0;
        double dLa = Math.toRadians(la2 - la1), dLo = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLa / 2) * Math.sin(dLa / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * R * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // ═════════════════════════ render ═════════════════════════

    private static Bitmap render() {
        final int W = outW, H = outH;
        final float SC = W / 350f;
        final boolean split = "split".equals(layout);
        final int mapW = split ? W / 2 : W;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (sumActive()) { drawSummaryCard(c, W, H, SC); return bmp; }   // kartu ringkasan trip menggantikan peta sementara
        c.drawColor(night ? 0xFF111111 : 0xFFE9E5DC);

        final Step st = currentStep();
        final boolean navOn = st != null;
        // Posisi marker: saat navigasi rider agak ke bawah supaya jalan yang akan dilalui terlihat lebih luas.
        // Mode penuh: area peta efektif = di atas bar navigasi (78*SC); split: seluruh tinggi.
        final float visH = (navOn && !split) ? H - 78 * SC : H;
        final float mx = mapW / 2f, my = navOn ? visH * (riderLow ? 0.74f : 0.5f) : H * 0.5f;
        final float hd = hdg;
        // Zoom di aplikasi = zoom MapLibre GL (dunia 512 px × 2^z). Tile raster 256 px memakai zoom slippy = zoom GL + 1.
        // Tanpa +1, peta native tampil 2× lebih "jauh" dibanding peta di aplikasi pada zoom yang sama.
        final double zm = zoom + 1.0;
        final int tz = Math.max(3, Math.min(19, (int) Math.floor(zm)));
        final float sc = (float) Math.pow(2, zm - tz);
        final double cx = worldX(lng, tz), cy = worldY(lat, tz);
        final int n = 1 << tz;

        // batas tile yang terlihat: proyeksi balik 4 sudut kanvas ke ruang peta
        double th = Math.toRadians(hd), ct = Math.cos(th), sn = Math.sin(th);
        double minX = 1e18, maxX = -1e18, minY = 1e18, maxY = -1e18;
        float[][] corners = { { 0, 0 }, { mapW, 0 }, { 0, H }, { mapW, H } };
        for (float[] k : corners) {
            double vx = (k[0] - mx) / sc, vy = (k[1] - my) / sc;
            double px = cx + vx * ct - vy * sn, py = cy + vx * sn + vy * ct;
            minX = Math.min(minX, px); maxX = Math.max(maxX, px); minY = Math.min(minY, py); maxY = Math.max(maxY, py);
        }
        int x0 = (int) Math.floor(minX / TILE), x1 = (int) Math.floor(maxX / TILE);
        int y0 = (int) Math.floor(minY / TILE), y1 = (int) Math.floor(maxY / TILE);

        Paint tp = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.save();
        if (split) c.clipRect(0, 0, mapW, H);        // peta hanya di separuh kiri
        c.save();
        c.translate(mx, my);
        c.rotate(-hd);
        c.scale(sc, sc);
        for (int ty = y0; ty <= y1; ty++) {
            if (ty < 0 || ty >= n) continue;
            for (int tx = x0; tx <= x1; tx++) {
                int wx = ((tx % n) + n) % n;
                float dx = (float) (tx * TILE - cx), dy = (float) (ty * TILE - cy);
                Bitmap t = tile(tz, wx, ty, true);
                if (t != null) { c.drawBitmap(t, dx, dy, tp); continue; }
                // cadangan: tile induk (z-1, z-2) diperbesar sementara tile asli dimuat
                for (int up = 1; up <= 2 && tz - up >= 3; up++) {
                    int pz = tz - up, px = wx >> up, py = ty >> up;
                    Bitmap pt = tile(pz, px, py, up == 1);
                    if (pt != null) {
                        int sub = TILE >> up;                       // ukuran potongan di tile induk
                        int sx = (wx - (px << up)) * sub, sy = (ty - (py << up)) * sub;
                        c.drawBitmap(pt, new Rect(sx, sy, sx + sub, sy + sub), new RectF(dx, dy, dx + TILE, dy + TILE), tp);
                        break;
                    }
                }
            }
        }
        // minta juga ring tile di sekeliling (prefetch), supaya belokan berikutnya sudah siap
        for (int ty = y0 - 1; ty <= y1 + 1; ty++)
            for (int tx = x0 - 1; tx <= x1 + 1; tx++)
                if (ty >= 0 && ty < n) tile(tz, ((tx % n) + n) % n, ty, true);

        drawRoute(c, tz, cx, cy, sc);
        c.restore();

        final double mppScr = 156543.03392 * Math.cos(Math.toRadians(lat)) / (1L << tz) / sc;   // meter per piksel layar
        drawMarker(c, mx, my, SC, navOn, accM > 0 ? (float) (accM / mppScr) : 0f);
        c.restore();                                  // akhiri clip area peta
        if (split) drawSplitPanel(c, mapW, W, H, SC, st);
        else if (navOn) drawNavBar(c, W, H, SC, st);
        return bmp;
    }

    private static void drawRoute(Canvas c, int tz, double cx, double cy, float sc) {
        Route r = route;
        if (r == null || r.lng.length < 2) return;
        double mpp = 156543.03392 * Math.cos(Math.toRadians(lat)) / (1L << tz);       // meter per piksel tile
        double limitM = Math.hypot(mapWidth(), outH) / sc * mpp * 1.3;
        // Garis mulai dari posisi rider yang DIPROYEKSIKAN ke segmen rute, bukan dari vertex terdekat. Vertex di jalan lurus bisa
        // berjarak ratusan meter, sehingga dulu garis biru terlihat mulai jauh di belakang rider (jalan yang sudah dilewati ikut biru).
        final int n = r.lng.length;
        final int i0 = Math.max(0, Math.min(rIdx, n - 1));
        final double cosLat = Math.cos(Math.toRadians(lat));
        int bestSg = Math.max(0, Math.min(i0, n - 2));
        double bestT = 0, bestOff = Double.MAX_VALUE, bestProg = r.cum[bestSg];
        for (int sg = i0 - 1; sg <= i0; sg++) {
            if (sg < 0 || sg >= n - 1) continue;
            double bx = (r.lng[sg + 1] - r.lng[sg]) * 111320.0 * cosLat, by = (r.lat[sg + 1] - r.lat[sg]) * 110540.0;
            double px = (lng - r.lng[sg]) * 111320.0 * cosLat, py = (lat - r.lat[sg]) * 110540.0;
            double len2 = bx * bx + by * by;
            double t = len2 > 0 ? Math.max(0, Math.min(1, (px * bx + py * by) / len2)) : 0;
            double off = Math.hypot(px - t * bx, py - t * by);
            if (off < bestOff) { bestOff = off; bestSg = sg; bestT = t; bestProg = r.cum[sg] + t * Math.sqrt(len2); }
        }
        double sLng = r.lng[bestSg] + (r.lng[bestSg + 1] - r.lng[bestSg]) * bestT;
        double sLat = r.lat[bestSg] + (r.lat[bestSg + 1] - r.lat[bestSg]) * bestT;
        Path path = new Path();
        path.moveTo((float) (worldX(sLng, tz) - cx), (float) (worldY(sLat, tz) - cy));
        boolean started = true, reachedEnd = true;
        // Penting: vertex pertama yang melewati batas layar TIDAK boleh dibuang begitu saja. Di jalan lurus jarak antar vertex bisa
        // ratusan meter, jadi kalau vertex berikutnya > limitM, seluruh ruas di depan rider tidak tergambar (jalan di depan tidak
        // tertutup warna biru). Ruas terakhir dipotong tepat di batas layar dengan interpolasi.
        double prevLng = sLng, prevLat = sLat, prevAlong = 0;
        for (int i = bestSg + 1; i < n; i++) {
            double along = r.cum[i] - bestProg;
            if (along > limitM) {
                double f = (along - prevAlong) > 0 ? (limitM - prevAlong) / (along - prevAlong) : 1.0;
                double eLng = prevLng + (r.lng[i] - prevLng) * f, eLat = prevLat + (r.lat[i] - prevLat) * f;
                path.lineTo((float) (worldX(eLng, tz) - cx), (float) (worldY(eLat, tz) - cy));
                reachedEnd = false;
                break;
            }
            path.lineTo((float) (worldX(r.lng[i], tz) - cx), (float) (worldY(r.lat[i], tz) - cy));
            prevLng = r.lng[i]; prevLat = r.lat[i]; prevAlong = along;
        }
        if (!started) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0xFFFFFFFF);
        p.setStrokeWidth(10f / sc);
        c.drawPath(path, p);
        p.setColor(0xFF2979FF);
        p.setStrokeWidth(6f / sc);
        c.drawPath(path, p);
        if (reachedEnd) {                                                              // titik tujuan
            int e = r.lng.length - 1;
            float x = (float) (worldX(r.lng[e], tz) - cx), y = (float) (worldY(r.lat[e], tz) - cy);
            Paint d = new Paint(Paint.ANTI_ALIAS_FLAG);
            d.setColor(0xFFFFFFFF); c.drawCircle(x, y, 9f / sc, d);
            d.setColor(0xFFE53935); c.drawCircle(x, y, 6f / sc, d);
        }
    }

    private static void drawMarker(Canvas c, float cx, float cy, float SC, boolean navOn, float accPx) {
        final float R = 10f * SC;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (accPx > 12f * SC) {                         // halo akurasi GPS (seperti titik biru Google Maps): radius = akurasi sebenarnya
            float rr = Math.min(accPx, outH * 0.6f);
            p.setStyle(Paint.Style.FILL); p.setColor(0x302979FF); c.drawCircle(cx, cy, rr, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1f, SC)); p.setColor(0x802979FF); c.drawCircle(cx, cy, rr, p);
        }
        if (navOn) {                                   // panah biru (heading-up → selalu menghadap atas)
            float ah = R * 1.4f, aw = R * 0.9f;
            c.save(); c.translate(cx, cy);
            p.setColor(0x402979FF); p.setStyle(Paint.Style.FILL); c.drawCircle(0, 0, R * 0.45f, p);
            Path a = new Path();
            a.moveTo(0, -ah); a.lineTo(aw, ah * 0.5f); a.lineTo(0, ah * 0.2f); a.lineTo(-aw, ah * 0.5f); a.close();
            p.setColor(0xFF2979FF); p.setStyle(Paint.Style.FILL); c.drawPath(a, p);
            p.setColor(0xFFFFFFFF); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1f, R * 0.15f)); p.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(a, p);
            c.restore();
        } else {                                       // free drive: titik biru bercincin putih
            p.setStyle(Paint.Style.FILL); p.setColor(0x382196F3); c.drawCircle(cx, cy, R * 1.1f, p);
            p.setColor(0xFFFFFFFF); c.drawCircle(cx, cy, R * 0.82f, p);
            p.setColor(0xFF1E88E5); c.drawCircle(cx, cy, R * 0.55f, p);
        }
    }

    // ═════════════════════════ layout split (peta kiri + info kanan) ═════════════════════════

    /** Lebar area peta: seluruh layar (layout "full") atau separuh kiri (layout "split"). */
    private static int mapWidth() { return "split".equals(layout) ? outW / 2 : outW; }

    /** Kecepatan untuk panel: kecepatan motor kalau ada, selain itu GPS. */
    private static double panelSpeed() { return motorKmh >= 0 ? motorKmh : spdKmh; }

    /** Potongan tanda tangan keadaan panel supaya frame dikirim ulang saat angkanya berubah (hanya mode split). */
    private static String panelSig() {
        String base = (riderLow ? ",rL" : ",rC") + (sumActive() ? ",SUM" : "");
        if (!"split".equals(layout)) return base;
        double[] pr = elevValid() ? profileAhead(20, 150) : pProf;
        boolean[] hu = { false };
        String nh = hillTextNative(hu);
        String hill = nh != null ? nh : pHill;
        return base + ",S," + (pr == null ? 0 : pr.length) + "," + (pr == null ? 0 : Math.round(pr[0] * 2)) + "," + Math.round(altNow()) + ","
                + Math.round(pEff) + "," + hill + "," + (pRoad == null ? 0 : pRoad.hashCode()) + "," + Math.round(pArrSoc) + "," + pSpklu;
    }

    private static void fitText(TextPaint tp, String s, float maxW, float size, float min) {
        tp.setTextSize(size);
        while (size > min && tp.measureText(s) > maxW) { size -= 1f; tp.setTextSize(size); }
    }

    /** Kartu ringkasan trip (tiba / akhiri trip): judul hijau + 5 baris teks besar berwarna. Teks & warna dikirim dari JS. */
    private static void drawSummaryCard(Canvas c, int W, int H, float SC) {
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setStyle(Paint.Style.FILL);
        f.setColor(0xFF0B1620); c.drawRect(0, 0, W, H, f);
        f.setColor(0xFF00E676); c.drawRect(0, 0, W, Math.max(3f, 4 * SC), f);
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        thick(tp, SC);
        tp.setTextAlign(Paint.Align.CENTER);
        String[] tx = sumTxt; long[] cl = sumCol;
        if (tx == null || cl == null) return;
        final float maxW = W - 24 * SC;
        tp.setColor(0xFF00E676);
        fitText(tp, sumTitle, maxW, 26 * SC, 14 * SC);
        c.drawText(sumTitle, W / 2f, midBase(tp, 24 * SC), tp);
        final float[] sizes = { 30, 22, 22, 18, 16 };
        float y = 64 * SC;
        for (int i = 0; i < tx.length && i < sizes.length; i++) {
            if (i > 0) y += (sizes[i - 1] + sizes[i]) / 2f * SC + 10 * SC;
            if (tx[i] == null || tx[i].isEmpty()) continue;
            tp.setColor((int) cl[i]);
            fitText(tp, tx[i], maxW, sizes[i] * SC, 10 * SC);
            c.drawText(tx[i], W / 2f, midBase(tp, y), tp);
        }
    }

    /** Profil ketinggian n titik ke depan dari posisi sekarang (dihitung dari GPS native + profil rute). null = tidak ada data. */
    private static double[] profileAhead(int n, double stepM) {
        Route r = route; double[] D = elevD, H = elevH;
        if (r == null || !elevValid() || D.length < 2) return null;
        double prog = progressCum(r);
        double[] out = new double[n]; int cnt = 0, i = 1;
        for (int k = 0; k < n; k++) {
            double m = prog + k * stepM;
            if (m > D[D.length - 1]) break;
            while (i < D.length - 1 && D[i] < m) i++;
            double span = D[i] - D[i - 1];
            double t = span > 0 ? Math.max(0, Math.min(1, (m - D[i - 1]) / span)) : 0;
            out[cnt++] = H[i - 1] + (H[i] - H[i - 1]) * t;
        }
        return cnt < 3 ? null : java.util.Arrays.copyOf(out, cnt);
    }

    /** Teks tanjakan/turunan di depan (<=20 char ASCII, sama dengan hillShortText di JS). null = tidak ada data native; "" = tidak ada tanjakan dekat. */
    private static String hillTextNative(boolean[] up) {
        Route r = route; double[][] hs = hillsArr;
        if (r == null || hs == null || !elevValid()) return null;
        double prog = progressCum(r);
        for (double[] h : hs) {
            if (h[4] < 0.5 || h[1] <= prog + 15) continue;
            boolean inside = prog >= h[0];
            double dist = inside ? 0 : h[0] - prog;
            if (dist > 3000) return "";
            up[0] = h[3] > 0.5;
            int g = (int) Math.round(Math.abs(h[2]));
            double m = inside ? h[1] - prog : dist;
            String ds = m >= 1000 ? String.format(Locale.US, "%.1fkm", m / 1000.0) : Math.max(10L, Math.round(m / 10.0) * 10L) + "m";
            return inside ? (up[0] ? "Naik " : "Turun ") + g + "% sisa " + ds : (up[0] ? "Tanjakan " : "Turunan ") + g + "% " + ds;
        }
        return "";
    }

    /** Warna segmen profil/peta menurut kemiringan (sama dengan palet di app: hijau datar, kuning-oranye-merah naik, ungu turun). */
    private static int gradeColorInt(double g) {
        double a = Math.abs(g);
        if (a < 2) return 0xFF00E676;
        int t = a < 4 ? 0 : (a < 7 ? 1 : 2);
        if (g > 0) return t == 0 ? 0xFFFFD740 : (t == 1 ? 0xFFFF9100 : 0xFFFF3D00);
        return t == 0 ? 0xFFC792EA : (t == 1 ? 0xFFA24BF0 : 0xFF7C4DFF);
    }

    private static final String[] CARD8 = { "UTARA", "TIMUR LAUT", "TIMUR", "TENGGARA", "SELATAN", "BARAT DAYA", "BARAT", "BARAT LAUT" };

    /** Teks tebal & tajam: font paling tebal + goresan tipis supaya tidak lunak setelah kompres JPEG. */
    private static void thick(TextPaint tp, float SC) {
        tp.setStyle(Paint.Style.FILL_AND_STROKE);
        tp.setStrokeWidth(Math.max(0.6f, 0.75f * SC));
    }

    /** Profil ketinggian rute ke depan: area berwarna menurut kemiringan, titik putih = posisi sekarang. */
    private static void drawProfile(Canvas c, Paint f, double[] p, int stepM, float x1, float y1, float x2, float y2, float SC) {
        double mn = p[0], mx = p[0];
        for (double v : p) { if (v < mn) mn = v; if (v > mx) mx = v; }
        double range = Math.max(20.0, mx - mn);               // rute datar tidak tampak seperti gunung
        double base = mn - (range - (mx - mn)) / 2.0;
        int n = p.length;
        float w = x2 - x1, h = y2 - y1;
        float[] xs = new float[n], ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = x1 + w * i / (n - 1f);
            ys[i] = (float) (y2 - (p[i] - base) / range * h);
        }
        f.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 1; i < n; i++) {
            int col = gradeColorInt((p[i] - p[i - 1]) / stepM * 100.0);
            Path q = new Path();
            q.moveTo(xs[i - 1], y2); q.lineTo(xs[i - 1], ys[i - 1]); q.lineTo(xs[i] + 0.6f, ys[i]); q.lineTo(xs[i] + 0.6f, y2); q.close();
            f.setStyle(Paint.Style.FILL); f.setColor((col & 0x00FFFFFF) | 0x66000000); c.drawPath(q, f);
            f.setStyle(Paint.Style.STROKE); f.setColor(col); f.setStrokeWidth(Math.max(2f, 2.8f * SC));
            c.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i], f);
        }
        f.setStyle(Paint.Style.FILL); f.setColor(0xFFFFFFFF);
        c.drawCircle(xs[0], ys[0], 3.6f * SC, f);
    }

    /**
     * Panel kanan layout split. SOC, range, kecepatan, dan jam TIDAK ditampilkan di sini karena MID sudah memilikinya
     * di luar area AT+MAPS. Navigasi: belokan + jarak, instruksi, nama jalan, sisa/ETA/tiba, profil ketinggian ke depan
     * (dengan label tanjakan). Free ride: arah mata angin, elevasi, nama lokasi, efisiensi.
     */
    private static void drawSplitPanel(Canvas c, int x0, int W, int H, float SC, Step st) {
        final float pw = W - x0, L = x0 + 8 * SC, TW = pw - 14 * SC;
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setStyle(Paint.Style.FILL);
        f.setColor(0xFF0B1620); c.drawRect(x0, 0, W, H, f);
        f.setColor(0xFF456477); c.drawRect(x0, 0, x0 + Math.max(2f, 2 * SC), H, f);

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        thick(tp, SC);
        tp.setTextAlign(Paint.Align.LEFT);

        // kartu profil / info di bagian bawah
        final float cx1 = x0 + 6 * SC, cx2 = W - 6 * SC, cy1 = (st != null ? 148 : 100) * SC, cy2 = H - 6 * SC;
        boolean[] hu = { pHillUp };
        String nh = hillTextNative(hu);                 // dihitung native dari GPS (tetap jalan saat JS terjeda)
        String hill = nh != null ? nh : pHill;
        final boolean hillUp = nh != null ? hu[0] : pHillUp;

        if (st != null) {
            // panah belok + jarak (besar)
            final float ACX = x0 + 30 * SC, ACY = 31 * SC, S = (19 * SC) / 22f;
            float[] pts = arrowPts(st);
            Path ap = new Path();
            for (int i = 0; i < pts.length; i += 2) {
                float x = ACX + (pts[i] - 22) * S, y = ACY + (pts[i + 1] - 22) * S;
                if (i == 0) ap.moveTo(x, y); else ap.lineTo(x, y);
            }
            ap.close();
            f.setStyle(Paint.Style.FILL); f.setColor(st.type.equals("arrive") ? 0xFFFFD740 : 0xFF00F078); c.drawPath(ap, f);
            f.setStyle(Paint.Style.STROKE); f.setColor(0xFFFFFFFF); f.setStrokeWidth(Math.max(1.4f, 1.8f * SC)); f.setStrokeJoin(Paint.Join.ROUND);
            c.drawPath(ap, f);
            f.setStyle(Paint.Style.FILL);

            double d = distToStep(st);
            String distStr = d >= 1000 ? String.format(Locale.US, "%.1fkm", d / 1000) : Math.round(d) + "m";
            tp.setColor(0xFFFFFFFF);
            fitText(tp, distStr, pw - 66 * SC, 30 * SC, 18 * SC);
            c.drawText(distStr, x0 + 60 * SC, midBase(tp, 31 * SC), tp);

            String act = action(st);
            act = act.substring(0, 1).toUpperCase(Locale.US) + act.substring(1);
            fitText(tp, act, TW, 18 * SC, 12 * SC);
            c.save(); c.clipRect(L, 54 * SC, L + TW, 80 * SC);
            c.drawText(act, L, midBase(tp, 67 * SC), tp);
            c.restore();

            Route r = route;
            String road = "";
            if (r != null) {
                if (stepIdx + 1 < r.steps.size() && !r.steps.get(stepIdx + 1).name.isEmpty()) road = r.steps.get(stepIdx + 1).name;
                else road = st.name;
            }
            if (!road.isEmpty()) {
                tp.setColor(0xFFE3ECF2);
                tp.setTextSize(Math.max(11f, 13f * SC));
                CharSequence rt = TextUtils.ellipsize("KE " + road, tp, TW, TextUtils.TruncateAt.END);
                c.drawText(rt.toString(), L, midBase(tp, 87 * SC), tp);
            }

            double rem = remainingMeters();
            if (rem >= 0) {
                double sp = spdKmh > 3 ? spdKmh : (motorKmh > 3 ? motorKmh : 30);
                double min = Math.max(0, (rem / 1000.0) / sp * 60.0);
                String eta = min < 1 ? "<1 mnt" : (min < 60 ? Math.round(min) + " mnt" : ((int) Math.floor(min / 60)) + "j " + Math.round(min % 60) + "m");
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.add(java.util.Calendar.SECOND, (int) Math.round(min * 60));
                String arr = String.format(Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
                String remTxt = rem >= 1000 ? String.format(Locale.US, "%.1f km", rem / 1000) : Math.round(rem) + " m";
                tp.setColor(0xFFFFFFFF);
                fitText(tp, "SISA " + remTxt, TW, 15 * SC, 11 * SC);
                c.drawText("SISA " + remTxt, L, midBase(tp, 106 * SC), tp);
                tp.setColor(0xFF8BFF98);
                fitText(tp, "ETA " + eta + "  " + arr, TW, 15 * SC, 11 * SC);
                c.drawText("ETA " + eta + "  " + arr, L, midBase(tp, 122 * SC), tp);
            }
            // perkiraan SOC saat tiba (+ SPKLU terdekat di rute bila rendah)
            if (!Double.isNaN(pArrSoc)) {
                String t = "TIBA SOC " + Math.max(0, Math.round(pArrSoc)) + "%";
                String sp = pSpklu;
                if (sp != null && !sp.isEmpty()) t += "  " + sp;
                tp.setColor(pArrSoc < 5 ? 0xFFFF5252 : (pArrSoc < 15 ? 0xFFFFB300 : 0xFFFFFFFF));
                fitText(tp, t, TW, 15 * SC, 9 * SC);
                c.drawText(t, L, midBase(tp, 138 * SC), tp);
            }
        } else {
            // free ride: arah mata angin, elevasi, lokasi, efisiensi
            float h360 = ((hdg % 360f) + 360f) % 360f;
            String card = CARD8[((int) Math.floor((h360 + 22.5f) / 45f)) % 8];
            tp.setColor(0xFFFFFFFF);
            fitText(tp, card, TW, 28 * SC, 16 * SC);
            c.drawText(card, L, midBase(tp, 24 * SC), tp);
            tp.setColor(0xFFB8C7D1); tp.setTextSize(Math.max(12f, 14f * SC));
            c.drawText(String.format(Locale.US, "%03d°", Math.round(h360)), L, midBase(tp, 48 * SC), tp);

            tp.setColor(0xFFB8C7D1); tp.setTextSize(Math.max(11f, 12f * SC));
            c.drawText("ELEVASI", L, midBase(tp, 70 * SC), tp);
            tp.setColor(0xFFFFFFFF);
            double altV = altNow();
            String alt = Double.isNaN(altV) ? "--" : Math.round(altV) + " m";
            fitText(tp, alt, TW, 24 * SC, 14 * SC);
            c.drawText(alt, L, midBase(tp, 90 * SC), tp);

            String road = pRoad;
            if (road != null && !road.isEmpty()) {
                tp.setColor(0xFFE3ECF2); tp.setTextSize(Math.max(12f, 14f * SC));
                CharSequence rt = TextUtils.ellipsize(road, tp, TW, TextUtils.TruncateAt.END);
                c.drawText(rt.toString(), L, midBase(tp, 114 * SC), tp);
            }
            if (pEff >= 0) {
                tp.setColor(0xFFB8C7D1); tp.setTextSize(Math.max(11f, 12f * SC));
                c.drawText("EFISIENSI", L, midBase(tp, 140 * SC), tp);
                tp.setColor(0xFF8BFF98);
                String ef = Math.round(pEff) + " Wh/km";
                fitText(tp, ef, TW, 22 * SC, 14 * SC);
                c.drawText(ef, L, midBase(tp, 162 * SC), tp);
            }
        }

        // ── kartu profil ketinggian ke depan (navigasi) ──
        if (st != null) {
            f.setStyle(Paint.Style.FILL); f.setColor(0xFF10202B);
            c.drawRoundRect(new RectF(cx1, cy1, cx2, cy2), 5 * SC, 5 * SC, f);
            double[] pr = elevValid() ? profileAhead(20, 150) : pProf;
            if (pr != null && pr.length >= 3) {
                String label;
                int lc;
                if (hill != null && !hill.isEmpty()) { label = hill; lc = hillUp ? 0xFFFFB300 : 0xFFC9A8FF; }
                else {
                    double up = 0, dn = 0;
                    for (int i = 1; i < pr.length; i++) { double dd = pr[i] - pr[i - 1]; if (dd > 0) up += dd; else dn -= dd; }
                    double km = (pr.length - 1) * (double) pProfStep / 1000.0;
                    label = String.format(Locale.US, "%.1fkm: naik %dm turun %dm", km, Math.round(up), Math.round(dn));
                    lc = 0xFFD6E2EA;
                }
                tp.setColor(lc);
                fitText(tp, label, cx2 - cx1 - 10 * SC, 13 * SC, 9 * SC);
                c.drawText(label, cx1 + 6 * SC, midBase(tp, cy1 + 12 * SC), tp);
                drawProfile(c, f, pr, pProfStep, cx1 + 8 * SC, cy1 + 24 * SC, cx2 - 8 * SC, cy2 - 7 * SC, SC);
            } else {
                // tanpa data elevasi (offline / belum termuat): arah + elevasi GPS
                float h360 = ((hdg % 360f) + 360f) % 360f;
                tp.setColor(0xFFFFFFFF);
                String a1 = "ARAH " + CARD8[((int) Math.floor((h360 + 22.5f) / 45f)) % 8];
                fitText(tp, a1, cx2 - cx1 - 12 * SC, 15 * SC, 10 * SC);
                c.drawText(a1, cx1 + 6 * SC, midBase(tp, cy1 + 18 * SC), tp);
                if (!Double.isNaN(altNow())) {
                    tp.setColor(0xFFB8C7D1); tp.setTextSize(Math.max(12f, 14f * SC));
                    c.drawText("ELEV " + Math.round(altNow()) + " m", cx1 + 6 * SC, midBase(tp, cy1 + 42 * SC), tp);
                }
            }
        }
    }

    // ── bar navigasi (port dari snapMapToBase64 di JS) ──

    private static String action(Step s) {
        if (s == null) return "lurus terus";
        String t = s.type, m = s.mod;
        if (t.equals("arrive")) return "tiba di tujuan";
        if (m.contains("uturn")) return "putar balik";
        if (m.contains("sharp left")) return "belok tajam kiri";
        if (m.contains("sharp right")) return "belok tajam kanan";
        if (m.contains("slight left")) return "agak kiri";
        if (m.contains("slight right")) return "agak kanan";
        if (m.contains("left")) return "belok kiri";
        if (m.contains("right")) return "belok kanan";
        if (t.equals("roundabout") || t.equals("rotary")) return "masuk bundaran";
        return "lurus terus";
    }

    private static float[] arrowPts(Step s) {
        String t = s.type, m = s.mod;
        if (t.equals("arrive")) return new float[] { 22,3, 36,20, 29,20, 29,41, 15,41, 15,20, 8,20 };
        if (m.contains("uturn")) return new float[] { 22,39, 10,22, 16,22, 16,9, 28,9, 28,22, 34,22 };
        if (m.contains("sharp left")) return new float[] { 5,39, 5,5, 23,24, 14,24 };
        if (m.contains("sharp right")) return new float[] { 39,39, 39,5, 21,24, 30,24 };
        if (m.contains("slight left")) return new float[] { 7,7, 37,19, 26,22, 22,37 };
        if (m.contains("slight right")) return new float[] { 37,7, 7,19, 18,22, 22,37 };
        if (m.contains("left")) return new float[] { 3,22, 36,8, 27,22, 36,36 };
        if (m.contains("right")) return new float[] { 41,22, 8,8, 17,22, 8,36 };
        return new float[] { 22,3, 36,36, 22,27, 8,36 };
    }

    private static float midBase(Paint p, float y) { return y - (p.ascent() + p.descent()) / 2f; }   // baseline "middle"

    private static void drawNavBar(Canvas c, int W, int H, float SC, Step st) {
        final float BAR_H = 78 * SC, BAR_Y = H - BAR_H, ARROW_W = 62 * SC;
        Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
        f.setStyle(Paint.Style.FILL);
        f.setColor(0xFF071827); c.drawRect(0, BAR_Y, W, H, f);
        f.setColor(0xFF102F43); c.drawRect(0, BAR_Y, ARROW_W, H, f);
        f.setColor(0xFFFFFFFF); c.drawRect(0, BAR_Y, W, BAR_Y + Math.max(2f, 2 * SC), f);
        f.setColor(0xFF456477); c.drawRect(ARROW_W, BAR_Y + 7 * SC, ARROW_W + Math.max(1f, SC), BAR_Y + BAR_H - 7 * SC, f);

        // panah belok
        final float ACX = ARROW_W / 2f, ACY = BAR_Y + 27 * SC, S = (19 * SC) / 22f;
        float[] pts = arrowPts(st);
        Path ap = new Path();
        for (int i = 0; i < pts.length; i += 2) {
            float x = ACX + (pts[i] - 22) * S, y = ACY + (pts[i + 1] - 22) * S;
            if (i == 0) ap.moveTo(x, y); else ap.lineTo(x, y);
        }
        ap.close();
        f.setColor(st.type.equals("arrive") ? 0xFFFFD740 : 0xFF00F078); f.setStyle(Paint.Style.FILL); c.drawPath(ap, f);
        f.setColor(0xFFFFFFFF); f.setStyle(Paint.Style.STROKE); f.setStrokeWidth(Math.max(1.2f, 1.6f * SC)); f.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(ap, f);

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        tp.setColor(0xFFFFFFFF);

        // jarak ke manuver (di bawah panah)
        double d = distToStep(st);
        String distStr = d >= 1000 ? String.format(Locale.US, "%.1fkm", d / 1000) : Math.round(d) + "m";
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(Math.max(13f, Math.round(14 * SC)));
        c.drawText(distStr, ACX, midBase(tp, BAR_Y + 59 * SC), tp);
        tp.setTextAlign(Paint.Align.LEFT);

        final float TX = ARROW_W + 9 * SC, TW = W - TX - 7 * SC;

        // baris 1: instruksi
        String act = action(st);
        act = act.substring(0, 1).toUpperCase(Locale.US) + act.substring(1);
        float fs = Math.round(18 * SC), fsMin = Math.round(14 * SC);
        tp.setTextSize(fs);
        while (fs > fsMin && tp.measureText(act) > TW) { fs -= Math.max(1f, SC); tp.setTextSize(fs); }
        c.save(); c.clipRect(TX, BAR_Y + 3 * SC, TX + TW, BAR_Y + 26 * SC);
        c.drawText(act, TX, midBase(tp, BAR_Y + 15 * SC), tp);
        c.restore();

        // baris 2: "KE <nama jalan>" (jalan setelah manuver, fallback jalan step ini)
        Route r = route;
        String road = "";
        if (r != null) {
            if (stepIdx + 1 < r.steps.size() && !r.steps.get(stepIdx + 1).name.isEmpty()) road = r.steps.get(stepIdx + 1).name;
            else road = st.name;
        }
        if (!road.isEmpty()) {
            tp.setTextSize(Math.round(13 * SC));
            CharSequence rt = TextUtils.ellipsize("KE " + road, tp, TW, TextUtils.TruncateAt.END);
            c.save(); c.clipRect(TX, BAR_Y + 27 * SC, TX + TW, BAR_Y + 45 * SC);
            c.drawText(rt.toString(), TX, midBase(tp, BAR_Y + 36 * SC), tp);
            c.restore();
        }

        // baris 3: SISA · ETA · TIBA
        double rem = remainingMeters();
        double sp = spdKmh > 3 ? spdKmh : (motorKmh > 3 ? motorKmh : 30);
        String eta = "--", arr = "--:--", remTxt = "";
        if (rem >= 0) {
            double min = Math.max(0, (rem / 1000.0) / sp * 60.0);
            eta = min < 1 ? "<1 mnt" : (min < 60 ? Math.round(min) + " mnt" : ((int) Math.floor(min / 60)) + "j " + Math.round(min % 60) + "m");
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.add(java.util.Calendar.SECOND, (int) Math.round(min * 60));
            arr = String.format(Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
            remTxt = rem >= 1000 ? String.format(Locale.US, "%.1f km", rem / 1000) : Math.round(rem) + " m";
        }
        String meta = (remTxt.isEmpty() ? "" : "SISA " + remTxt + "   ") + "ETA " + eta + "   TIBA " + arr;
        float ms = Math.round(12 * SC);
        tp.setTextSize(ms);
        while (ms > 8 * SC && tp.measureText(meta) > TW) { ms -= 1f; tp.setTextSize(ms); }
        float metaY = road.isEmpty() ? 43 * SC : 62 * SC;
        c.save(); c.clipRect(TX, BAR_Y + (road.isEmpty() ? 31 : 47) * SC, TX + TW, H);
        c.drawText(meta, TX, midBase(tp, BAR_Y + metaY), tp);
        c.restore();
    }
}
