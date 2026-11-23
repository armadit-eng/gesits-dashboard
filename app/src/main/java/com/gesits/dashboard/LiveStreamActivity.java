package com.gesits.dashboard;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64;
import android.webkit.WebView;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONException;
import org.json.JSONObject;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

// Package berubah dari com.pedro.rtplibrary.* (versi lama) jadi com.pedro.library.*
// di versi 2.8.1 — dikonfirmasi dari contoh resmi OldApiActivity.kt di repo RootEncoder.
import com.pedro.library.rtmp.RtmpCamera1;
import com.pedro.library.view.OpenGlView;
import com.pedro.common.ConnectChecker; // ganti nama dari ConnectCheckerRtmp
// Dikonfirmasi dari source resmi ImageFilterRender.kt: constructor kosong,
// method setImage(Bitmap) — persis dugaan awal, cuma package-nya kurang
// segmen ".object." (folder Kotlin "object", bukan reserved word di Java,
// jadi aman diimport apa adanya). ImageObjectFilterRender versi lama masih
// ada tapi @Deprecated -> pakai ImageFilterRender langsung.
import com.pedro.encoder.input.gl.render.filters.object.ImageFilterRender;

/**
 * LIVE STREAMING — kamera + HUD (kecepatan/SOC/suhu) -> RTMP (YouTube Live, dsb).
 *
 * ══════════════════════════════════════════════════════════════════════════
 * REVISI KE-3 — status per revisi ini:
 *  - Kamera+mic+RTMP (RtmpCamera1, OpenGlView, ConnectChecker dari package
 *    com.pedro.library.* / com.pedro.common.*): SUDAH DIKONFIRMASI JALAN,
 *    live pertama ke YouTube sukses pakai revisi sebelumnya (2).
 *  - Overlay HUD ke video (ImageFilterRender dari
 *    com.pedro.encoder.input.gl.render.filters.object.ImageFilterRender,
 *    constructor kosong, method setImage(Bitmap)): dikonfirmasi dari source
 *    resmi library-nya (ImageFilterRender.kt), BUKAN tebakan lagi. Class lama
 *    ImageObjectFilterRender masih ada tapi @Deprecated, jadi dipakai yg baru.
 *
 * MASIH BELUM PERNAH DI-COMPILE ULANG oleh saya sejak nyambungin overlay ini
 * (tetap gak ada akses internet/Android SDK/emulator di sisi saya) — jadi
 * bagian overlay ini kemungkinan compile-nya lolos (API-nya sudah dikonfirmasi
 * dari source asli, bukan ditebak), tapi HASIL VISUALNYA (posisi, ukuran,
 * transparansi HUD di video) belum pernah dilihat langsung. Kalau overlay-nya
 * kepasang tapi tampilannya aneh (misal warnanya salah, atau malah nutup satu
 * layar penuh bukan cuma kotak kecil di pojok), itu kemungkinan besar soal
 * halus di cara shader ImageFilterRender membaca alpha channel bitmap, bukan
 * error compile — kirim screenshot hasilnya kalau begitu, nanti disesuaikan.
 * ══════════════════════════════════════════════════════════════════════════
 *
 * REVISI KE-4 (UI + audio):
 *  - Kontrol dipindah ke panel kecil di tengah-atas yang otomatis menyembunyikan diri saat live
 *    (ketuk preview untuk memunculkan lagi), jadi tidak lagi menutupi HUD/video. Layar penuh + tetap menyala.
 *  - Dugaan penyebab audio putus-putus ditangani: sample rate 48 kHz (native HP, tanpa resampling) + mono,
 *    HUD digambar di thread latar dengan 2 bitmap bergantian (tidak lagi alokasi 3,7 MB tiap detik di main thread),
 *    encoder video dipaksa hardware kalau library mendukung. Ada kotak centang HUD untuk uji: matikan HUD,
 *    kalau audio jadi normal berarti beban overlay-lah penyebabnya.
 *
 * Alur kerja:
 *  1. Kamera diambil & di-preview lewat OpenGlView (bagian dari RootEncoder,
 *     bukan SurfaceView Android biasa) — karena overlay HUD digambar di
 *     tahap render GL-nya, BUKAN ditumpuk pakai View Android biasa. Kalau
 *     overlay cuma ditumpuk pakai TextView di atas SurfaceView, itu memang
 *     kelihatan di layar HP, TAPI TIDAK IKUT TERKIRIM ke RTMP — yang dikirim
 *     cuma isi asli buffer kamera. Makanya overlay HARUS lewat filter GL
 *     library ini (ImageObjectFilterRender) supaya benar-benar "dibakar" ke
 *     video yang di-stream.
 *  2. Tiap ~1 detik, buildHudBitmap() gambar ulang teks kecepatan/SOC/suhu
 *     jadi sebuah Bitmap transparan, lalu bitmap itu didorong ke GL filter
 *     supaya jadi lapisan di atas gambar kamera.
 *  3. Data kecepatan/SOC/suhu itu sendiri dibaca dari LiveHudBus — yang
 *     diisi WebView lewat Android.updateLiveHud() tiap tick telemetri.
 */
public class LiveStreamActivity extends AppCompatActivity implements ConnectChecker {

    private static final String TAG = "LiveStream";
    private static final int REQ_PERMS = 501;
    private static final String PREFS = "live_prefs";
    private static final int HUD_W = 1280, HUD_H = 720;

    // Ganti sesuai server RTMP tujuan. Default YouTube Live.
    private static final String YOUTUBE_RTMP_BASE = "rtmp://a.rtmp.youtube.com/live2/";

    private OpenGlView openGlView;
    private RtmpCamera1 rtmpCamera1;
    private ImageFilterRender hudFilter;

    private LinearLayout panel, setupRow;
    private EditText streamKeyInput;
    private TextView hudStyleBtn;
    private Button btnStartStop;
    private TextView statusText;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable hudTick, uiTick, autoHide;

    // HUD digambar di thread latar dengan 2 bitmap bergantian
    private HandlerThread hudThread;
    private Handler hudBg;
    private final Bitmap[] hudBufs = new Bitmap[2];
    private int hudBufIdx = 0;
    private volatile boolean hudBusy = false;
    private volatile boolean hudEnabled = true;
    private int hudStyle = 0;                // 0 = gaya Rekam Video (dirender dashboard), 1 = ringkas (digambar native), 2 = mati
    private Bitmap hudDecodeBuf;             // dipakai ulang oleh BitmapFactory (inBitmap) supaya tidak alokasi tiap detik
    private boolean hudBlankSet = false; // overlay transparan sudah dipasang (saat HUD dimatikan)

    private boolean portraitMode = false;  // true = siaran vertikal 720x1280 (stream key YouTube-nya berbeda dari landscape)
    private boolean live = false;          // sedang dalam sesi live (tombol STOP tampil)
    private boolean rtmpConnected = false; // server sudah menerima
    private long liveStartMs = 0;
    private volatile long lastBitrate = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        portraitMode = "port".equals(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", "land"));
        setRequestedOrientation(portraitMode ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(buildLayout()); // layout dibikin programatik, biar 1 file aja (gak perlu XML terpisah)
        applyImmersive();

        // Beri tahu MainActivity: jangan pause WebView selama layar live terbuka, supaya data HUD tetap mengalir
        LiveHudBus.liveActive = true;

        hudThread = new HandlerThread("live-hud");
        hudThread.start();
        hudBg = new Handler(hudThread.getLooper());

        rtmpCamera1 = new RtmpCamera1(openGlView, this);
        hudFilter = new ImageFilterRender();

        checkPermissionsThenPrepare();
        startHudLoop();
        startUiLoop();
    }

    // ── Layar penuh tanpa bar sistem ──
    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable pill(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    // ── Layout: preview penuh + panel kontrol kecil di tengah-atas (tidak menutupi HUD) ──
    private View buildLayout() {
        final SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        openGlView = new OpenGlView(this);
        root.addView(openGlView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        openGlView.setOnClickListener(v -> showPanel()); // ketuk preview = munculkan kontrol

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable bg = pill(Color.parseColor("#D90B1218"), 14);
        bg.setStroke(dp(1), Color.parseColor("#33FFFFFF"));
        panel.setBackground(bg);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(24)), FrameLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(10);
        root.addView(panel, plp);

        // Baris 1 (hanya sebelum live): stream key + kotak centang HUD
        setupRow = new LinearLayout(this);
        setupRow.setOrientation(LinearLayout.VERTICAL);
        panel.addView(setupRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Pilihan orientasi siaran: Landscape (16:9) atau Portrait (9:16). YouTube memberi stream key terpisah untuk keduanya.
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34));
        mlp.bottomMargin = dp(4);
        setupRow.addView(modeRow, mlp);
        modeRow.addView(modeButton("Landscape 16:9", !portraitMode, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        View gap = new View(this);
        modeRow.addView(gap, new LinearLayout.LayoutParams(dp(6), 1));
        modeRow.addView(modeButton("Portrait 9:16", portraitMode, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        setupRow.addView(keyRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        streamKeyInput = new EditText(this);
        streamKeyInput.setHint(portraitMode ? "Stream key Portrait" : "Stream key Landscape");
        streamKeyInput.setTextColor(Color.WHITE);
        streamKeyInput.setHintTextColor(Color.parseColor("#88FFFFFF"));
        streamKeyInput.setTextSize(13);
        streamKeyInput.setSingleLine(true);
        // Disamarkan: stream key itu rahasia (siapa pun yang punya bisa siaran ke kanalmu), jangan sampai kelihatan di screenshot
        streamKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        streamKeyInput.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // Satu key per mode (key lama sebelum fitur portrait dianggap key landscape)
        streamKeyInput.setText(prefs.getString(portraitMode ? "key_port" : "key_land", portraitMode ? "" : prefs.getString("key", "")));
        streamKeyInput.setOnEditorActionListener((v, actionId, ev) -> { hideKeyboard(); return false; });
        keyRow.addView(streamKeyInput, new LinearLayout.LayoutParams(0, dp(40), 1f));

        // Tombol siklus gaya HUD: Rekam -> Ringkas -> Mati. "Rekam" = HUD yang sama dengan fitur Rekam Video.
        hudStyleBtn = new TextView(this);
        hudStyleBtn.setGravity(Gravity.CENTER);
        hudStyleBtn.setTextSize(12);
        hudStyleBtn.setTextColor(Color.WHITE);
        hudStyleBtn.setBackground(pill(Color.parseColor("#33FFFFFF"), 10));
        hudStyleBtn.setPadding(dp(10), 0, dp(10), 0);
        hudStyleBtn.setOnClickListener(v -> applyHudStyle((hudStyle + 1) % 3, true));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        hlp.leftMargin = dp(6);
        keyRow.addView(hudStyleBtn, hlp);
        // Migrasi: sebelumnya hanya ada boolean "hud" (true = tampil)
        int initStyle = prefs.contains("hudStyle") ? prefs.getInt("hudStyle", 0) : (prefs.getBoolean("hud", true) ? 0 : 2);
        applyHudStyle(initStyle, false);

        // Baris 2: status + tombol utama
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams r2lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r2lp.topMargin = dp(4);
        panel.addView(row2, r2lp);

        statusText = new TextView(this);
        statusText.setText("Belum live");
        statusText.setTextColor(Color.parseColor("#FFD54F"));
        statusText.setTextSize(13);
        statusText.setSingleLine(true);
        row2.addView(statusText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        btnStartStop = new Button(this);
        btnStartStop.setAllCaps(false);
        btnStartStop.setTextColor(Color.WHITE);
        btnStartStop.setTextSize(13);
        btnStartStop.setMinHeight(0);
        btnStartStop.setMinimumHeight(0);
        btnStartStop.setPadding(dp(16), dp(6), dp(16), dp(6));
        btnStartStop.setOnClickListener(v -> onStartStopClicked());
        row2.addView(btnStartStop, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        styleStartButton(false);

        autoHide = () -> panel.animate().alpha(0f).setDuration(300).withEndAction(() -> {
            if (live && panel.getAlpha() <= 0.01f) panel.setVisibility(View.INVISIBLE);
        }).start();

        return root;
    }

    private void applyHudStyle(int style, boolean save) {
        hudStyle = style;
        hudEnabled = style != 2;
        // Gaya Rekam hanya untuk landscape; portrait memakai HUD native vertikal
        LiveHudBus.wantRecImage = (style == 0) && !portraitMode;
        if (hudStyleBtn != null) hudStyleBtn.setText(style == 0 ? "HUD: Rekam" : (style == 1 ? "HUD: Ringkas" : "HUD: Mati"));
        if (save) getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("hudStyle", style).apply();
        if (hudEnabled) hudBlankSet = false;
        refreshHudOverlay(); // mati: pasang overlay transparan; menyala: gambar HUD
    }

    private TextView modeButton(String label, boolean selected, final boolean toPortrait) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(12);
        t.setTextColor(selected ? Color.parseColor("#04210F") : Color.WHITE);
        t.setBackground(pill(Color.parseColor(selected ? "#00E676" : "#33FFFFFF"), 10));
        t.setOnClickListener(v -> setMode(toPortrait));
        return t;
    }

    // Ganti orientasi layar & siaran. Activity dibuat ulang supaya kamera, preview, dan HUD disiapkan sesuai orientasi baru.
    private void setMode(boolean toPortrait) {
        if (live || toPortrait == portraitMode) return;
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // Simpan dulu key yang sedang diketik ke mode lama
        prefs.edit()
                .putString(portraitMode ? "key_port" : "key_land", streamKeyInput.getText().toString().trim())
                .putString("mode", toPortrait ? "port" : "land")
                .apply();
        setRequestedOrientation(toPortrait ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        // Biasanya sistem sudah membuat ulang activity saat layar berputar; kalau belum (orientasi ditangani sendiri), paksa.
        uiHandler.postDelayed(() -> { if (!isFinishing() && !isDestroyed()) recreate(); }, 600);
    }

    // Rotasi video untuk encoder: 0 untuk landscape, 90 untuk portrait (270 kalau layar terbalik)
    private int streamRotation() {
        if (!portraitMode) return 0;
        int r = getWindowManager().getDefaultDisplay().getRotation();
        return r == android.view.Surface.ROTATION_180 ? 270 : 90;
    }

    private void styleStartButton(boolean isLive) {
        btnStartStop.setText(isLive ? "STOP" : "MULAI LIVE");
        btnStartStop.setBackground(pill(Color.parseColor(isLive ? "#E53935" : "#00A152"), 10));
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(streamKeyInput.getWindowToken(), 0);
            streamKeyInput.clearFocus();
        } catch (Exception ignored) {}
        applyImmersive();
    }

    // Panel tampil; kalau sedang live, sembunyi otomatis lagi setelah 5 detik
    private void showPanel() {
        panel.animate().cancel();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(1f);
        uiHandler.removeCallbacks(autoHide);
        if (live) uiHandler.postDelayed(autoHide, 5000);
    }

    private void applyLiveUi(boolean isLive) {
        live = isLive;
        if (!isLive) { rtmpConnected = false; liveStartMs = 0; }
        setupRow.setVisibility(isLive ? View.GONE : View.VISIBLE);
        styleStartButton(isLive);
        showPanel();
    }

    // ── Mulai / berhenti ──
    private void onStartStopClicked() {
        if (rtmpCamera1.isStreaming() || live) {
            if (rtmpCamera1.isStreaming()) rtmpCamera1.stopStream();
            applyLiveUi(false);
            statusText.setTextColor(Color.parseColor("#FFD54F"));
            statusText.setText("Berhenti");
            return;
        }
        String key = streamKeyInput.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(this, "Isi stream key dulu", Toast.LENGTH_SHORT).show();
            return;
        }
        hideKeyboard();
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(portraitMode ? "key_port" : "key_land", key).apply();
        String rtmpUrl = YOUTUBE_RTMP_BASE + key;

        // Resolusi/bitrate sengaja konservatif (720p, 2.5 Mbps) buat live di jalan yang sinyalnya naik-turun.
        // Encoder video dipaksa hardware (kalau library mendukung); kalau gagal, balik ke pilihan otomatis.
        setCodecForce("HARDWARE");
        boolean videoOk = rtmpCamera1.prepareVideo(1280, 720, 30, 2_500 * 1024, streamRotation());
        if (!videoOk) {
            setCodecForce("FIRST_COMPATIBLE_FOUND");
            videoOk = rtmpCamera1.prepareVideo(1280, 720, 30, 2_500 * 1024, streamRotation());
        }
        // 48 kHz mono: sample rate bawaan mic hampir semua HP (tanpa resampling 44,1 kHz yang rawan putus-putus)
        boolean audioOk = rtmpCamera1.prepareAudio(128 * 1024, 48000, false, false, false);
        if (!videoOk || !audioOk) {
            Toast.makeText(this, "Gagal siapkan kamera/mic (prepareVideo/prepareAudio gagal)", Toast.LENGTH_LONG).show();
            return;
        }

        rtmpCamera1.getGlInterface().setFilter(hudFilter);
        refreshHudOverlay();

        rtmpCamera1.startStream(rtmpUrl);
        liveStartMs = SystemClock.elapsedRealtime();
        applyLiveUi(true);
        statusText.setTextColor(Color.parseColor("#FFD54F"));
        statusText.setText("Menyambung…");
    }

    // setForce() dipanggil lewat refleksi: kalau versi library tidak punya method ini, diabaikan tanpa error build.
    private void setCodecForce(String videoMode) {
        try {
            Class<?> forceCls = Class.forName("com.pedro.encoder.utils.CodecUtil$Force");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object v = Enum.valueOf((Class<Enum>) forceCls, videoMode);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object a = Enum.valueOf((Class<Enum>) forceCls, "FIRST_COMPATIBLE_FOUND");
            rtmpCamera1.getClass().getMethod("setForce", forceCls, forceCls).invoke(rtmpCamera1, v, a);
            Log.i(TAG, "codec video: " + videoMode);
        } catch (Throwable t) {
            Log.w(TAG, "setForce tidak tersedia di versi library ini: " + t);
        }
    }

    // Teks status "● LIVE 05:12 · 2.4 Mbps", diperbarui tiap detik
    private void startUiLoop() {
        uiTick = () -> {
            if (live && rtmpConnected && liveStartMs > 0) {
                long s = (SystemClock.elapsedRealtime() - liveStartMs) / 1000;
                String t = String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
                String br = lastBitrate > 0 ? String.format(java.util.Locale.US, " · %.1fMbps", lastBitrate / 1_000_000.0) : "";
                statusText.setTextColor(Color.parseColor("#FF5252"));
                statusText.setText("● " + t + br + latencyText());
            }
            uiHandler.postDelayed(uiTick, 1000);
        };
        uiHandler.post(uiTick);
    }

    // Perkiraan "umur" angka kecepatan yang terlihat di HUD = umur balasan AT+SPD? dari MID saat HUD dirender
    // + berapa lama gambar/JSON HUD itu sudah menunggu di sisi native. Dipakai untuk menentukan di mana macetnya.
    private String latencyText() {
        try {
            String js = LiveHudBus.lastJson;
            if (js == null) return "";
            JSONObject o = new JSONObject(js);
            double spdAge = o.optDouble("spdAge", -1);
            long ts = o.optLong("ts", 0);
            if (spdAge < 0 || ts <= 0) return "";
            long now = System.currentTimeMillis();
            boolean img = hudStyle == 0 && !portraitMode && LiveHudBus.lastImage != null;
            double hudAge = img ? (now - LiveHudBus.lastImageMs) : (now - ts);
            double total = (spdAge + hudAge) / 1000.0;
            Log.i(TAG, String.format(java.util.Locale.US, "latensi kecepatan: MID %.1fs + HUD %.1fs (render JS %dms) = %.1fs",
                    spdAge / 1000.0, hudAge / 1000.0, o.optInt("rms", 0), total));
            return String.format(java.util.Locale.US, " · tunda %.1fs", total);
        } catch (Exception e) {
            return "";
        }
    }

    // ── Overlay HUD: digambar ulang berkala di thread latar, didorong ke GL filter ──
    private void startHudLoop() {
        // Refresh HUD terus selama kamera preview ATAU sedang live — bukan cuma
        // pas live, supaya kelihatan dari preview juga buat cek framing.
        hudTick = () -> {
            if (rtmpCamera1 != null && (rtmpCamera1.isStreaming() || rtmpCamera1.isOnPreview())) {
                // Suruh dashboard menghasilkan data/gambar HUD terbaru. evaluateJavascript TIDAK ikut dibatasi sistem
                // seperti timer JS di halaman tersembunyi, jadi HUD tetap berdetak walau WebView di belakang layar.
                try {
                    WebView wv = MainActivity.webViewRef;
                    if (wv != null && hudEnabled) wv.evaluateJavascript("if(window.broadcastLiveHud)broadcastLiveHud();", null);
                } catch (Throwable ignored) {}
                refreshHudOverlay();
            }
            uiHandler.postDelayed(hudTick, 1000);
        };
        uiHandler.post(hudTick);
    }

    private void refreshHudOverlay() {
        if (!hudEnabled) {
            // HUD dimatikan: pasang bitmap transparan kecil sekali saja (filter butuh gambar agar tidak error)
            if (!hudBlankSet && hudFilter != null) {
                hudFilter.setImage(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888));
                hudBlankSet = true;
            }
            return;
        }
        if (hudBusy || hudBg == null) return; // lewati kalau yang sebelumnya belum selesai
        hudBusy = true;
        hudBg.post(() -> {
            try {
                final Bitmap b = nextHudBuffer();
                drawHud(b);
                runOnUiThread(() -> {
                    try { if (hudEnabled && hudFilter != null) hudFilter.setImage(b); }
                    finally { hudBusy = false; }
                });
            } catch (Throwable t) {
                Log.e(TAG, "gambar HUD gagal", t);
                hudBusy = false;
            }
        });
    }

    // Dua bitmap dipakai bergantian: yang baru dikirim ke GL tidak disentuh lagi selama ±1 detik.
    // Kalau library ternyata me-recycle bitmap lama, dibuat ulang otomatis.
    private Bitmap nextHudBuffer() {
        hudBufIdx ^= 1;
        Bitmap b = hudBufs[hudBufIdx];
        if (b == null || b.isRecycled()) {
            b = portraitMode ? Bitmap.createBitmap(HUD_H, HUD_W, Bitmap.Config.ARGB_8888)   // 720x1280
                             : Bitmap.createBitmap(HUD_W, HUD_H, Bitmap.Config.ARGB_8888);  // 1280x720
            hudBufs[hudBufIdx] = b;
        } else {
            b.eraseColor(Color.TRANSPARENT);
        }
        return b;
    }

    // Menggambar HUD ke bitmap yang disediakan pemanggil (dipakai ulang bergantian, lihat nextHudBuffer()).
    // Dipanggil dari thread latar, jadi tidak boleh menyentuh View.
    private void drawHud(Bitmap bmp) {
        final boolean portrait = portraitMode;
        final int w = portrait ? HUD_H : HUD_W;   // portrait: 720 x 1280
        final int h = portrait ? HUD_W : HUD_H;
        // Gaya Rekam: tempel gambar HUD yang dirender dashboard. Kalau belum ada / basi, jatuh ke HUD ringkas di bawah.
        if (hudStyle == 0 && !portrait && drawRecStyleHud(bmp)) return;
        Canvas c = new Canvas(bmp);

        // Palet — disamakan dgn const C={...} di mvDrawHUDLandscape (dashboard HTML)
        final int CYAN  = Color.parseColor("#29b6f6");
        final int GREEN = Color.parseColor("#00e676");
        final int AMBER = Color.parseColor("#ffb74d");
        final int WHITE = Color.parseColor("#f7fafc");
        final int MUTED = Color.parseColor("#b4c9d8");
        final int RED   = Color.parseColor("#ff3548");
        final int DARK  = Color.parseColor("#99030a10"); // lebih transparan dari versi lama (C7 -> 99)
        final int TRACK = Color.parseColor("#33ffffff");
        final java.util.Locale loc = java.util.Locale.getDefault();

        JSONObject data = null;
        boolean fresh = LiveHudBus.isFresh(15_000); // data basi kalau app/motor gak update >15 dtk
        // Tetap tampilkan nilai terakhir walau basi (lebih baik daripada "—" semua di mata penonton);
        // status "data tertunda" ditandai kecil di tengah-atas.
        if (LiveHudBus.lastJson != null) {
            try { data = new JSONObject(LiveHudBus.lastJson); } catch (JSONException e) { data = null; }
        }
        final boolean stale = data != null && !fresh;
        final JSONObject d = data;
        java.util.function.Function<String, Integer> gi = key -> {
            if (d == null || d.isNull(key)) return null;
            try { return d.getInt(key); } catch (JSONException e) { return null; }
        };
        java.util.function.Function<String, Double> gd = key -> {
            if (d == null || d.isNull(key)) return null;
            try { return d.getDouble(key); } catch (JSONException e) { return null; }
        };
        Integer spd = gi.apply("spd");
        Integer soc = gi.apply("soc");
        Integer b1Soc = gi.apply("b1Soc");
        Integer b2Soc = gi.apply("b2Soc");
        Integer tbat = gi.apply("tbat");
        Integer hdg = gi.apply("hdg");
        Double whKm = gd.apply("whKm");
        Double ahKm = gd.apply("ahKm");
        Double trip = gd.apply("trip");
        Double rng = gd.apply("rng");
        boolean gps = d != null && d.optBoolean("gps", false);
        String road = d != null ? d.optString("road", "") : "";
        String area = d != null ? d.optString("area", "") : "";

        // ── Scrim gradien di atas & bawah: teks tetap terbaca di latar terang tanpa panel besar yang menutup video ──
        Paint scrim = new Paint();
        final int scrimH = portrait ? 440 : 300;
        scrim.setShader(new LinearGradient(0, h - scrimH, 0, h, Color.TRANSPARENT,
                Color.parseColor("#B3000000"), Shader.TileMode.CLAMP));
        c.drawRect(0, h - scrimH, w, h, scrim);
        Paint scrimTop = new Paint();
        scrimTop.setShader(new LinearGradient(0, 0, 0, 170, Color.parseColor("#80000000"),
                Color.TRANSPARENT, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, 170, scrimTop);

        // ══ KIRI-BAWAH: gauge kecepatan (busur 270°) ══
        final float gcx = portrait ? 190 : 215, gcy = portrait ? h - 150 : h - 175, gr = portrait ? 108 : 118;
        RectF arc = new RectF(gcx - gr, gcy - gr, gcx + gr, gcy + gr);
        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(16f);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(TRACK);
        c.drawArc(arc, 135f, 270f, false, track);

        final float maxSpd = 90f;
        float frac = spd != null ? Math.min(1f, Math.max(0f, spd / maxSpd)) : 0f;
        int gaugeColor = (spd == null) ? MUTED : (spd >= 70 ? RED : (spd >= 50 ? AMBER : GREEN));
        if (frac > 0.005f) {
            Paint val = new Paint(Paint.ANTI_ALIAS_FLAG);
            val.setStyle(Paint.Style.STROKE);
            val.setStrokeWidth(16f);
            val.setStrokeCap(Paint.Cap.ROUND);
            val.setColor(gaugeColor);
            val.setShadowLayer(14f, 0f, 0f, gaugeColor);
            c.drawArc(arc, 135f, 270f * frac, false, val);
        }
        Paint spdNum = paintNum(portrait ? 106 : 124, WHITE);
        spdNum.setTextAlign(Paint.Align.CENTER);
        c.drawText(spd != null ? String.valueOf(spd) : "—", gcx, gcy + (portrait ? 34 : 38), spdNum);
        Paint spdUnit = paintText(26, Typeface.BOLD, MUTED);
        spdUnit.setTextAlign(Paint.Align.CENTER);
        c.drawText("km/h", gcx, gcy + (portrait ? 92 : 100), spdUnit);

        // ══ TENGAH-BAWAH: SOC besar + bar bersegmen ══
        final float bx = portrait ? 36 : 395;
        final float socBase = portrait ? h - 345 : h - 130;
        final float bOff = portrait ? 200 : 190, tOff = portrait ? 400 : 345;
        int socColor = (soc == null) ? MUTED : (soc <= 15 ? RED : (soc <= 30 ? AMBER : GREEN));
        Paint socP = paintNum(76, socColor);
        c.drawText(soc != null ? soc + "%" : "—%", bx, socBase, socP);

        Paint rowP = paintText(26, Typeface.BOLD, WHITE);
        c.drawText("B1  " + (b1Soc != null ? b1Soc + "%" : "—"), bx + bOff, socBase - 30, rowP);
        c.drawText("B2  " + (b2Soc != null ? b2Soc + "%" : "—"), bx + bOff, socBase + 2, rowP);
        Paint tempP = paintNum(32, tbat != null && tbat >= 45 ? RED : AMBER);
        c.drawText(tbat != null ? tbat + "°C" : "—°C", bx + tOff, socBase - 30, tempP);
        Paint rngP = paintText(24, Typeface.BOLD, MUTED);
        c.drawText(rng != null ? String.format(loc, "~%.0f km", rng) : "~— km", bx + tOff, socBase + 2, rngP);

        final int segs = 20;
        final float segW = portrait ? 28f : 20f, segGap = 4f, segH = 22f, segTop = socBase + 22;
        int filled = soc != null ? Math.round(Math.max(0, Math.min(100, soc)) / 100f * segs) : 0;
        Paint segP = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int k = 0; k < segs; k++) {
            float sx = bx + k * (segW + segGap);
            segP.setColor(k < filled ? socColor : TRACK);
            c.drawRoundRect(new RectF(sx, segTop, sx + segW, segTop + segH), 4f, 4f, segP);
        }

        // ══ KANAN-BAWAH: efisiensi & trip (label kiri, nilai rata kanan) ══
        final float xr = portrait ? w - 36 : w - 40, xl = portrait ? 380 : w - 310;
        Paint stLbl = paintText(20, Typeface.BOLD, MUTED);
        Paint stVal = paintNum(portrait ? 34 : 38, WHITE);
        stVal.setTextAlign(Paint.Align.RIGHT);
        Paint div = new Paint(Paint.ANTI_ALIAS_FLAG);
        div.setColor(TRACK);
        div.setStrokeWidth(2f);
        String[] lbl = {"EFISIENSI", "ARUS / KM", "TRIP"};
        String[] vals = {
            whKm != null ? String.format(loc, "%.1f Wh/km", whKm) : "— Wh/km",
            ahKm != null ? String.format(loc, "%.2f Ah/km", ahKm) : "— Ah/km",
            trip != null ? String.format(loc, "%.1f km", trip) : "— km"
        };
        for (int k = 0; k < 3; k++) {
            float by = portrait ? h - 205 + k * 58 : h - 150 + k * 52;
            c.drawText(lbl[k], xl, by, stLbl);
            c.drawText(vals[k], xr, by + 2, stVal);
            if (k < 2) c.drawLine(xl, by + 18, xr, by + 18, div);
        }

        // ══ KIRI-ATAS: GPS + merek ══
        drawHudPanel(c, 36, 30, 250, 52, CYAN, DARK);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(gps ? GREEN : RED);
        dot.setShadowLayer(8f, 0f, 0f, gps ? GREEN : RED);
        c.drawCircle(64, 57, 7f, dot);
        c.drawText(gps ? "GPS FIX" : "GPS —", 80, 65, paintText(22, Typeface.BOLD, gps ? GREEN : RED));
        Paint brand = paintText(22, Typeface.BOLD, WHITE);
        brand.setLetterSpacing(0.18f);
        c.drawText("GESITS", 190, 65, brand);

        // ══ KANAN-ATAS: jam, lokasi, kompas ══
        String timeStr = new java.text.SimpleDateFormat("HH:mm", loc).format(new java.util.Date());
        drawHudPanel(c, w - 36 - 150, 30, 150, 52, CYAN, DARK);
        Paint clock = paintNum(38, WHITE);
        clock.setTextAlign(Paint.Align.CENTER);
        c.drawText(timeStr, w - 36 - 75, 69, clock);

        if (hdg != null) {
            final float ccx = w - 36 - 28, ccy = 150, cr = 28;
            Paint cbg = new Paint(Paint.ANTI_ALIAS_FLAG);
            cbg.setColor(DARK);
            c.drawCircle(ccx, ccy, cr, cbg);
            Paint cring = new Paint(Paint.ANTI_ALIAS_FLAG);
            cring.setStyle(Paint.Style.STROKE);
            cring.setStrokeWidth(2f);
            cring.setColor(Color.parseColor("#88ffffff"));
            c.drawCircle(ccx, ccy, cr, cring);
            c.save();
            c.translate(ccx, ccy);
            c.rotate(-hdg); // Utara berada di -arah hadap dari "atas" (video selalu menghadap depan motor)
            android.graphics.Path north = new android.graphics.Path();
            north.moveTo(0, -cr * 0.78f); north.lineTo(cr * 0.28f, 0); north.lineTo(-cr * 0.28f, 0); north.close();
            Paint np = new Paint(Paint.ANTI_ALIAS_FLAG);
            np.setColor(RED);
            c.drawPath(north, np);
            android.graphics.Path south = new android.graphics.Path();
            south.moveTo(0, cr * 0.78f); south.lineTo(cr * 0.28f, 0); south.lineTo(-cr * 0.28f, 0); south.close();
            np.setColor(WHITE);
            c.drawPath(south, np);
            c.restore();
            String[] dirs = {"U", "TL", "T", "TG", "S", "BD", "B", "BL"};
            String dirTxt = dirs[Math.round(hdg / 45f) % 8] + " " + hdg + "°";
            Paint dirP = paintText(20, Typeface.BOLD, WHITE);
            dirP.setTextAlign(Paint.Align.CENTER);
            c.drawText(dirTxt, ccx, ccy + cr + 24, dirP);

            // Nama jalan + area, rata kanan di sebelah kiri kompas
            final float tx = ccx - cr - 16, maxW = portrait ? 330 : 400;
            Paint roadP = paintText(26, Typeface.BOLD, WHITE);
            roadP.setTextAlign(Paint.Align.RIGHT);
            Paint areaP = paintText(21, Typeface.NORMAL, MUTED);
            areaP.setTextAlign(Paint.Align.RIGHT);
            if (road != null && !road.isEmpty()) c.drawText(fitText(road, roadP, maxW), tx, ccy - 2, roadP);
            if (area != null && !area.isEmpty()) c.drawText(fitText(area, areaP, maxW), tx, ccy + 26, areaP);
        }

        // Data belum masuk / basi: kasih petunjuk kecil supaya tidak dikira HUD rusak
        if (d == null || stale) {
            Paint wait = paintText(22, Typeface.BOLD, AMBER);
            wait.setTextAlign(Paint.Align.CENTER);
            c.drawText(d == null ? "menunggu data dari aplikasi…" : "data tertunda", w / 2f, portrait ? 112 : 64, wait);
        }
    }

    // Decode gambar overlay (WebP/PNG transparan, base64) dari dashboard lalu salin ke bitmap HUD. Thread latar.
    private boolean drawRecStyleHud(Bitmap bmp) {
        final String b64 = LiveHudBus.lastImage;
        // Toleransi 20 dtk: gambar yang agak tertinggal tetap dipakai (ditandai "data tertunda") daripada gaya HUD
        // loncat-loncat ke mode ringkas. Jatuh ke HUD ringkas hanya kalau gambar belum pernah ada / sangat lama.
        if (b64 == null || !LiveHudBus.isImageFresh(20_000)) return false;
        final boolean late = !LiveHudBus.isImageFresh(5_000);
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inMutable = true;
            if (hudDecodeBuf != null && !hudDecodeBuf.isRecycled()) o.inBitmap = hudDecodeBuf; // pakai ulang memori
            Bitmap dec;
            try {
                dec = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            } catch (IllegalArgumentException e) { // inBitmap tidak cocok: decode biasa
                o.inBitmap = null;
                dec = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            }
            if (dec == null) return false;
            hudDecodeBuf = dec;
            Canvas cv = new Canvas(bmp);
            if (dec.getWidth() == bmp.getWidth() && dec.getHeight() == bmp.getHeight()) cv.drawBitmap(dec, 0, 0, null);
            else cv.drawBitmap(dec, null, new Rect(0, 0, bmp.getWidth(), bmp.getHeight()), null);
            if (late) {
                Paint lp = paintText(18, Typeface.BOLD, Color.parseColor("#ffb74d"));
                lp.setTextAlign(Paint.Align.CENTER);
                cv.drawText("data tertunda", bmp.getWidth() / 2f, 100, lp);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "decode HUD gaya Rekam gagal: " + t);
            return false;
        }
    }

    // Potong teks yang kepanjangan dengan "…" supaya tidak keluar dari area HUD
    private String fitText(String s, Paint p, float maxW) {
        if (p.measureText(s) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && p.measureText(t + "…") > maxW) t = t.substring(0, t.length() - 1);
        return t + "…";
    }

    // Angka besar bergaya condensed (lebih mirip speedometer digital daripada font bawaan)
    private Paint paintNum(float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setShadowLayer(8f, 0f, 2f, Color.parseColor("#E6000000"));
        return p;
    }

    private Paint paintText(float size, int style, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        p.setShadowLayer(6f, 0f, 1f, Color.parseColor("#E6000000")); // niru shadowBlur di helper text() dashboard HTML
        return p;
    }

    // Panel gaya "sudut potong" (sama polanya dengan fungsi panel() di
    // mvDrawHUDLandscape — persegi dgn satu sudut kanan-bawah & kiri-atas
    // dipotong miring, plus garis aksen menyala di sisi atas).
    private void drawHudPanel(Canvas c, int x, int y, int pw, int ph, int accent, int bgColor) {
        float cut = 18f;
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x + cut, y);
        path.lineTo(x + pw - cut, y);
        path.lineTo(x + pw, y + cut);
        path.lineTo(x + pw, y + ph);
        path.lineTo(x, y + ph);
        path.lineTo(x, y + cut);
        path.close();

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(bgColor);
        bg.setStyle(Paint.Style.FILL);
        c.drawPath(path, bg);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(Color.parseColor("#4C2B5B78"));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(2f);
        c.drawPath(path, border);

        Paint accentLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        accentLine.setColor(accent);
        accentLine.setStrokeWidth(4f);
        accentLine.setShadowLayer(8f, 0, 0, accent);
        c.drawLine(x + cut, y + 1, x + pw * 0.46f, y + 1, accentLine);
    }

    // ── Izin kamera & mic (RECORD_AUDIO) — WRITE_EXTERNAL_STORAGE gak perlu, gak nyimpen file lokal ──
    private void checkPermissionsThenPrepare() {
        String[] needed = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        boolean allGranted = true;
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            openGlView.getHolder().addCallback(surfaceCallback);
        } else {
            ActivityCompat.requestPermissions(this, needed, REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Izin kamera/mic ditolak — live streaming gak bisa jalan", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
            }
            openGlView.getHolder().addCallback(surfaceCallback);
        }
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override public void surfaceCreated(SurfaceHolder holder) {
            rtmpCamera1.startPreview();
            // HUD ditempel dari sini juga (bukan cuma pas "MULAI LIVE"), supaya
            // kelihatan dari preview dulu buat ngecek framing sebelum live beneran.
            // Aman ditempel walau belum streaming — cuma nampilin overlay di
            // GL yang sama, gak ngirim apa-apa sebelum startStream() dipanggil.
            // (hudTick yang sudah jalan dari startHudLoop() di onCreate() akan
            // otomatis mulai gambar begitu isOnPreview() true — lihat kondisinya.)
            rtmpCamera1.getGlInterface().setFilter(hudFilter);
            refreshHudOverlay();
        }
        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            if (rtmpCamera1.isStreaming()) rtmpCamera1.stopStream();
            rtmpCamera1.stopPreview();
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LiveHudBus.liveActive = false;
        LiveHudBus.wantRecImage = false;
        uiHandler.removeCallbacks(hudTick);
        uiHandler.removeCallbacks(uiTick);
        uiHandler.removeCallbacks(autoHide);
        if (hudThread != null) hudThread.quitSafely();
        if (rtmpCamera1 != null) {
            if (rtmpCamera1.isStreaming()) rtmpCamera1.stopStream();
            if (rtmpCamera1.isOnPreview()) rtmpCamera1.stopPreview();
        }
    }

    // ── Callback status koneksi (interface: com.pedro.common.ConnectChecker) ──
    @Override public void onConnectionStarted(String url) {}
    @Override public void onConnectionSuccess() {
        runOnUiThread(() -> {
            rtmpConnected = true;
            statusText.setTextColor(Color.parseColor("#FF5252"));
            statusText.setText("● LIVE");
            showPanel(); // mulai hitung mundur sembunyi otomatis
        });
    }
    @Override public void onConnectionFailed(String reason) {
        Log.e(TAG, "RTMP gagal konek: " + reason);
        runOnUiThread(() -> {
            if (rtmpCamera1.isStreaming()) rtmpCamera1.stopStream();
            applyLiveUi(false);
            statusText.setTextColor(Color.parseColor("#FF8A80"));
            statusText.setText("Gagal konek: " + reason);
            Toast.makeText(this, "Live gagal: " + reason, Toast.LENGTH_LONG).show();
        });
    }
    @Override public void onNewBitrate(long bitrate) {
        lastBitrate = bitrate; // ditampilkan di uiTick
    }
    @Override public void onDisconnect() {
        runOnUiThread(() -> {
            boolean wasLive = live;
            if (rtmpCamera1.isStreaming()) rtmpCamera1.stopStream();
            applyLiveUi(false);
            statusText.setTextColor(Color.parseColor("#FF8A80"));
            statusText.setText(wasLive ? "Terputus — tekan MULAI LIVE untuk sambung lagi" : "Terputus");
        });
    }
    @Override public void onAuthError() {
        runOnUiThread(() -> Toast.makeText(this, "Stream key ditolak server", Toast.LENGTH_LONG).show());
    }
    @Override public void onAuthSuccess() {}
}
