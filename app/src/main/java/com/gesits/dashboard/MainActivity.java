package com.gesits.dashboard;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.webkit.WebViewAssetLoader;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

public class MainActivity extends Activity {

    public static WebView webViewRef;

    private static final String PREFS_NAME = "gesits_prefs";
    private static final String KEY_MAC    = "bt_mac";
    private static final String KEY_BATT2_MAC = "battery2_esp32_mac";
    // ESP32 utama (CAN sniffer / matrix) — sebelumnya MAC-nya gak pernah disimpan
    // sama sekali, makanya beda dgn B2: gak ada auto-reconnect pas app dibuka,
    // harus scan+pilih manual tiap kali putus. Disamakan pola-nya dgn KEY_BATT2_MAC.
    private static final String KEY_ESP32_MAC = "esp32_can_mac";

    private WebView           webView;
    private BluetoothService  btService;
    private boolean           serviceBound = false;
    private boolean           serviceBinding = false;
    private SharedPreferences prefs;

    // BLE GATT client khusus BMS Jikong — terpisah dari btService (Classic/RFCOMM,
    // dipakai buat motor). Lihat BmsBleManager.java.
    private BmsBleManager bmsManager;

    // BLE GATT client khusus ESP32 CAN Sniffer — pola identik BmsBleManager,
    // service/characteristic beda. Lihat Esp32BleManager.java.
    private Esp32BleManager esp32Manager;
    // Esp32BleManager gak punya method isConnected() (beda dari
    // Esp32Battery2BleManager yg punya) — status disimpan manual di sini,
    // diisi dari callback onConnected/onDisconnected-nya di bawah.
    private volatile boolean esp32Connected = false;

    // BLE GATT client khusus ESP32 #2 — BMS ORI. Terpisah dari ESP32 CAN utama.
    private Esp32Battery2BleManager esp32Battery2Manager;

    // BLE GATT client khusus kontroler Fardriver (dongle BLE UART FF00/FFE0).
    private FardriverBleManager fardriverManager;

    // File chooser (buat <input type="file"> di WebView, mis. upload gambar custom AT+MAPS)
    private ValueCallback<Uri[]> filePathCallback;
    private static final int REQUEST_CODE_FILE_CHOOSER = 51;
    private static final int REQUEST_CODE_MIC_PERMISSION = 52;
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 53;
    private float[] mirrorCrop = {0f, 0.10f, 1f, 0.78f};
    /** True kalau toggle Matrix "Notifikasi Google Maps" aktif: WebView tetap jalan saat Maps di depan. */
    public static volatile boolean keepAliveMatrixMaps = false;

    // ── Native mic capture (AudioRecord) — dipakai Motovlog Cam ──
    // WebView getUserMedia() audio punya keterbatasan platform yg gak
    // konsisten (video via getUserMedia lancar, audio sering NotReadableError
    // walau izin OS udah Allow — sudah dikonfirmasi ini murni batasan WebView,
    // bukan bug kode: kode+origin yg SAMA lancar total kalau dibuka via
    // Chrome tab biasa di device yg SAMA). Solusinya: tangkap audio LANGSUNG
    // dari native pakai AudioRecord (di luar WebView sama sekali), lalu PCM
    // mentahnya di-stream ke JS lewat evaluateJavascript utk disambung ke
    // AudioEncoder+mp4-muxer yg udah dibangun di web app.
    private AudioRecord       micRecorder;
    private Thread            micThread;
    private volatile boolean  micCapturing = false;
    private static final int  MIC_SAMPLE_RATE = 44100;
    private static final int  MIC_CHANNELS = 1; // mono

    // ── Simpan video Motovlog per-chunk (lihat startVideoWrite/appendVideoChunk/
    // finalizeVideoWrite di JsBridge) — stream dibuka sekali di startVideoWrite(),
    // ditulis potong-potong, ditutup di finalizeVideoWrite(). Ini gantiin cara
    // lama yang kirim SATU string base64 raksasa lewat JS-bridge, yang batas
    // ukuran panggilannya beda-beda tiap device & bisa gagal silent.
    private java.io.OutputStream videoWriteStream = null;
    private Uri                  videoWriteUri    = null;

    // TTS
    private TextToSpeech tts;
    private boolean      ttsReady = false;

    // ── Update APK in-app (download+install langsung dari GitHub Releases) ──
    private static final String APK_UPDATE_FILENAME = "gesits-update.apk";
    private long             apkDownloadId       = -1L;
    private BroadcastReceiver apkDownloadReceiver = null;
    private File             lastDownloadedApkFile = null;
    private final Handler    apkProgressHandler  = new Handler(Looper.getMainLooper());
    private Runnable         apkProgressRunnable = null;

    // ── Background sync / remote text fetch (bebas CORS untuk data SPKLU/Molis) ──
    private static final int MAX_REMOTE_FETCH_BYTES = 15 * 1024 * 1024;
    private final ConcurrentHashMap<String, byte[]> fetchedPayloads = new ConcurrentHashMap<>();
    private final OkHttpClient syncHttpClient = new OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build();
    private volatile boolean esp32WifiBound = false;

    // ══════════════════════════════════════════════
    // SERVICE CONNECTION
    // ══════════════════════════════════════════════
    private final ServiceConnection serviceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            BluetoothService.LocalBinder lb = (BluetoothService.LocalBinder) binder;
            btService    = lb.getService();
            serviceBound = true;
            serviceBinding = false;
            MapActivity.btServiceRef = btService;
            ScreenCaptureService.btRef = btService;

            btService.setCallback(new BluetoothService.DataCallback() {
                @Override
                public void onData(String line) {
                    runOnUiThread(() -> {
                        webView.evaluateJavascript(
                            "window.onBtData('" +
                            line.replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace("\r", "")
                                .replace("\n", "") + "')", val -> {
                            // Forward data SOC/Voltase ke MapActivity overlay
                            if (MapActivity.instance != null) {
                                try {
                                    if (line.startsWith("AT+SOC=")) {
                                        double soc = Double.parseDouble(
                                            line.substring(7).trim());
                                        MapActivity.instance.updateSocOverlay(soc, -1.0);
                                    } else if (line.startsWith("AT+VBAT=")) {
                                        double vbat = Double.parseDouble(
                                            line.substring(8).trim());
                                        MapActivity.instance.updateSocOverlay(-1.0, vbat);
                                    }
                                } catch (Exception ignored) {}
                            }
                        });
                    });
                }
                @Override
                public void onDisconnected() {
                    NativeMapStreamer.onBtDisconnected();      // peta native menunggu sambung ulang (tanpa WebView)
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onBtDisconnected()", null));
                    // Update widget LANGSUNG dari native, JANGAN cuma andelin
                    // JS di atas — kalau app lagi di-background/WebView-nya
                    // idle, evaluateJavascript bisa gak sempat jalan (atau
                    // JS-nya sendiri gak fungsi krn tab lain lagi aktif),
                    // dan widget bakal "nyangkut" nunjukin status lama
                    // (mis. masih "Terhubung" padahal udah putus beneran).
                    // BluetoothService ini foreground service yg tetap
                    // jalan independen dari WebView, jadi push ini selalu
                    // reliable gak peduli app lagi di foreground/background.
                    pushWidgetConnectionState(false);
                }
                @Override
                public void onReconnected() {
                    NativeMapStreamer.onBtReconnected();       // kirim frame segera + sinkron jam MID
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onBtReconnected && window.onBtReconnected()", null));
                    pushWidgetConnectionState(true);
                }
            });

            // AUTO-CONNECT saat app dibuka
            String savedMac = prefs.getString(KEY_MAC, "");
            if (!savedMac.isEmpty() && !btService.isConnected()) {
                final String mac = savedMac;
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onAutoConnecting && window.onAutoConnecting('" + mac + "')", null));
                btService.connect(mac);
                webView.postDelayed(() -> {
                    if (btService.isConnected()) {
                        webView.evaluateJavascript(
                            "window.onBtAutoConnected && window.onBtAutoConnected()", null);
                    }
                }, 2500);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            serviceBinding = false;
            ScreenCaptureService.btRef = null;
        }
    };

    // ── Native mic capture (AudioRecord) — implementasi ──
    // Baca lebih lengkap di komentar field micRecorder di atas soal kenapa
    // ini perlu ada sama sekali (WebView getUserMedia audio gak reliable).
    private void startNativeMicCaptureInternal() {
        if (micCapturing) return; // sudah jalan, gak perlu start ulang
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            webView.post(() -> webView.evaluateJavascript(
                "window.onNativeMicError && window.onNativeMicError('permission_denied')", null));
            return;
        }
        int minBuf = AudioRecord.getMinBufferSize(MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            android.util.Log.e("NativeMic", "getMinBufferSize gagal: " + minBuf);
            webView.post(() -> webView.evaluateJavascript(
                "window.onNativeMicError && window.onNativeMicError('unsupported_config')", null));
            return;
        }
        final int bufferBytes = Math.max(minBuf, 4096 * 2); // minimal ~4096 sample (2 byte/sample)
        try {
            micRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes);
        } catch (Exception e) {
            android.util.Log.e("NativeMic", "Init AudioRecord gagal: " + e.getMessage());
            micRecorder = null;
            webView.post(() -> webView.evaluateJavascript(
                "window.onNativeMicError && window.onNativeMicError('init_failed')", null));
            return;
        }
        if (micRecorder.getState() != AudioRecord.STATE_INITIALIZED) {
            android.util.Log.e("NativeMic", "AudioRecord state != INITIALIZED");
            micRecorder.release();
            micRecorder = null;
            webView.post(() -> webView.evaluateJavascript(
                "window.onNativeMicError && window.onNativeMicError('not_initialized')", null));
            return;
        }

        micCapturing = true;
        try {
            micRecorder.startRecording();
        } catch (Exception e) {
            android.util.Log.e("NativeMic", "startRecording gagal: " + e.getMessage());
            micCapturing = false;
            micRecorder.release();
            micRecorder = null;
            webView.post(() -> webView.evaluateJavascript(
                "window.onNativeMicError && window.onNativeMicError('start_failed')", null));
            return;
        }

        micThread = new Thread(() -> {
            final int CHUNK_SAMPLES = 4096; // ~93ms per chunk @ 44100Hz — cukup kecil buat latensi rendah
            short[] buf = new short[CHUNK_SAMPLES];
            while (micCapturing && micRecorder != null) {
                int read;
                try {
                    read = micRecorder.read(buf, 0, CHUNK_SAMPLES);
                } catch (Exception e) {
                    break; // recorder ke-release di thread lain (stop dipanggil) — keluar dgn tenang
                }
                if (read > 0) {
                    byte[] bytes = new byte[read * 2];
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buf, 0, read);
                    final String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    webView.post(() -> webView.evaluateJavascript(
                        "window.onNativeAudioChunk && window.onNativeAudioChunk('" + b64 + "', "
                            + MIC_SAMPLE_RATE + ", " + MIC_CHANNELS + ")", null));
                }
            }
        }, "NativeMicCaptureThread");
        micThread.start();
    }

    private void stopNativeMicCaptureInternal() {
        micCapturing = false;
        if (micThread != null) {
            try { micThread.join(300); } catch (InterruptedException ignored) {}
            micThread = null;
        }
        if (micRecorder != null) {
            try {
                if (micRecorder.getState() == AudioRecord.STATE_INITIALIZED) micRecorder.stop();
            } catch (Exception ignored) {}
            micRecorder.release();
            micRecorder = null;
        }
    }

    // ══════════════════════════════════════════════
    // JAVASCRIPT BRIDGE
    // ══════════════════════════════════════════════
    public class JsBridge {

        @JavascriptInterface
        public String getAppVersion() {
            // Dipakai JS buat bandingin versi APK yang ke-install sekarang vs
            // versi terbaru di GitHub Releases (armadit-eng/gesits-dashboard),
            // biar status "Aplikasi" di Akun & Home selalu cek update APK asli.
            try {
                PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                return pInfo.versionName != null ? pInfo.versionName : "";
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public long getAppVersionCode() {
            try {
                PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                if (Build.VERSION.SDK_INT >= 28) {
                    return pInfo.getLongVersionCode();
                }
                return pInfo.versionCode;
            } catch (Exception e) {
                return 0L;
            }
        }

        @JavascriptInterface
        public void openAppDetailsSettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Gagal buka info aplikasi", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void retryInstallDownloadedApk() {
            runOnUiThread(() -> {
                File f = lastDownloadedApkFile;
                if (f == null || !f.exists()) {
                    File destDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (destDir != null) f = new File(destDir, APK_UPDATE_FILENAME);
                }
                if (f != null && f.exists()) {
                    launchApkInstallerIntent(f);
                } else {
                    Toast.makeText(MainActivity.this, "File APK update belum tersedia", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ── Resolve link pendek Google Maps (maps.app.goo.gl / goo.gl/maps) ──
        // Dipanggil dari JS: Android.resolveUrl(url). Mengikuti redirect di sisi
        // native sehingga tidak kena CORS. Hasil: URL lengkap, atau "" kalau gagal
        // (JS lalu pakai cara cadangan). Jalan di thread JavaBridge, bukan UI thread.
        private boolean isAllowedMapsHost(String host) {
            if (host == null) return false;
            String h = host.toLowerCase(Locale.US);
            return h.equals("goo.gl") || h.equals("maps.app.goo.gl") || h.equals("g.co") || h.equals("share.google")
                    || h.equals("google.com") || h.endsWith(".google.com")
                    || h.startsWith("google.") || h.contains(".google.");
        }

        private boolean urlHasCoords(String u) {
            return java.util.regex.Pattern.compile(
                    "@-?\\d+\\.\\d+,-?\\d+\\.\\d+|!3d-?\\d+\\.\\d+!4d-?\\d+\\.\\d+"
                    + "|[?&](ll|q|destination)=-?\\d+\\.\\d+,-?\\d+\\.\\d+").matcher(u).find();
        }

        // ── Cari koordinat lewat ID tempat (feature id "0x..:0x..") ──
        // Alamat berkoordinat di browser dibuat oleh JavaScript, tapi ID tempat sudah ada di URL hasil
        // redirect. Dengan ID itu kita minta data tempat ke endpoint internal Google Maps yang sama
        // dengan yang dipakai situs Maps (/maps/preview/place), lalu baca koordinatnya. Endpoint ini
        // tidak resmi/tanpa dokumentasi: bisa berubah sewaktu-waktu. Bila gagal, jatuh ke cara cadangan.
        private String resolveDebug = "";

        @JavascriptInterface
        public String lastResolveDebug() { return resolveDebug; }

        private void dbg(String s) {
            resolveDebug = resolveDebug.isEmpty() ? s : resolveDebug + " | " + s;
            if (resolveDebug.length() > 600) resolveDebug = resolveDebug.substring(0, 600);
        }

        private static final String PLACE_UA =
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

        private String httpGetText(String url, int timeoutMs) throws Exception {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            try {
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(timeoutMs);
                c.setReadTimeout(timeoutMs);
                c.setRequestProperty("User-Agent", PLACE_UA);
                c.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
                c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg");
                int code = c.getResponseCode();
                if (code != 200) { dbg("HTTP " + code); return null; }
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[16384];
                int n;
                while ((n = br.read(buf)) > 0 && sb.length() < 3000000) sb.append(buf, 0, n);
                br.close();
                String t = sb.toString();
                if (t.startsWith(")]}'")) {            // buang prefix anti-XSSI Google
                    int nl = t.indexOf('\n');
                    t = nl >= 0 ? t.substring(nl + 1) : t;
                }
                return t;
            } finally {
                c.disconnect();
            }
        }

        private String findFeatureId(String url) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?:!1s|ftid=)(0x[0-9a-fA-F]+:0x[0-9a-fA-F]+)").matcher(url);
            return m.find() ? m.group(1).toLowerCase(Locale.US) : null;
        }

        private String placeNameFromUrl(String url) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("/maps/place/([^/@]+)").matcher(url);
                if (m.find()) return java.net.URLDecoder.decode(m.group(1).replace("+", " "), "UTF-8");
            } catch (Exception e) { }
            return null;
        }

        private String coordsAfter(String text, int from, int span) {
            int end = Math.min(text.length(), from + span);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\[null,\\s*null,\\s*(-?\\d{1,3}\\.\\d+),\\s*(-?\\d{1,3}\\.\\d+)\\]")
                    .matcher(text.substring(Math.max(0, from), end));
            while (m.find()) {
                double la = Double.parseDouble(m.group(1)), lo = Double.parseDouble(m.group(2));
                if (Math.abs(la) <= 90 && Math.abs(lo) <= 180) return m.group(1) + "," + m.group(2);
            }
            return null;
        }

        private String lookupPlaceByFid(String fid, String nameHint) {
            String fidB = fid.substring(fid.indexOf(':') + 1);
            // (1) Detail tempat
            try {
                String pb = "!1m14!1s" + fid
                        + "!3m9!1m3!1d5000!2d0!3d0!2m0!3m2!1i1024!2i768!4f13.1!4m2!3d0!4d0!13m1!2m0"
                        + "!15m47!1m8!4e2!18m5!3b0!6b0!14b1!17b1!20b1!20e2!4b1!10m1!8e3!11m1!3e1!17b1"
                        + "!20m2!1e3!1e6!24b1!25b1!26b1!29b1!30m1!2b1!36b1!43b1!52b1!55b1!56m1!1b1"
                        + "!65m5!3m4!1m3!1m2!1i224!2i298!22m1!1e81!29m0!30m6!3b1!6m1!2b1!7m1!2b1!9b1!32b1!37i771";
                String url = "https://www.google.com/maps/preview/place?authuser=0&hl=en&gl=us&pb="
                        + java.net.URLEncoder.encode(pb, "UTF-8");
                String t = httpGetText(url, 6000);
                if (t == null) {
                    dbg("detail: gagal");
                } else if (!t.toLowerCase(Locale.US).contains(fidB)) {
                    dbg("detail: ID tempat tidak ada di respons");
                } else {
                    String ll = coordsAfter(t, 0, t.length());
                    if (ll != null) {
                        String out = "ll=" + ll;
                        java.util.regex.Matcher nm = java.util.regex.Pattern
                                .compile("\"" + java.util.regex.Pattern.quote(fid) + "\",\\s*\"([^\"]{2,120})\"",
                                        java.util.regex.Pattern.CASE_INSENSITIVE).matcher(t);
                        if (nm.find()) out += "&q=" + java.net.URLEncoder.encode(nm.group(1), "UTF-8");
                        dbg("detail: OK");
                        return out;
                    }
                    dbg("detail: koordinat tidak ditemukan");
                }
            } catch (Exception e) {
                dbg("detail: " + e.getClass().getSimpleName());
            }
            // (2) Cadangan: cari lewat nama tempat, ambil hasil yang ID-nya sama persis
            if (nameHint != null && nameHint.length() > 2) {
                try {
                    String pbS = "!1s" + java.net.URLEncoder.encode(nameHint, "UTF-8")
                            + "!7i20!10b1!12m6!1m1!18b1!2m1!20e3!6m1!114b1!17m1!3e1"
                            + "!20m57!2m2!1i203!2i100!3m2!2i4!5b1!6m6!1m2!1i86!2i86!1m2!1i408!2i240"
                            + "!7m33!1m3!1e1!2b0!3e3!1m3!1e2!2b1!3e2!1m3!1e2!2b0!3e3!1m3!1e8!2b0!3e3"
                            + "!1m3!1e10!2b0!3e3!1m3!1e10!2b1!3e2!1m3!1e10!2b0!3e4!1m3!1e9!2b1!3e2!2b1!9b0"
                            + "!15m8!1m7!1m2!1m1!1e2!2m2!1i195!2i195!3i20";
                    String url = "https://www.google.com/search?tbm=map&hl=en&gl=us&q="
                            + java.net.URLEncoder.encode(nameHint, "UTF-8")
                            + "&pb=" + java.net.URLEncoder.encode(pbS, "UTF-8");
                    String t = httpGetText(url, 6000);
                    if (t == null) {
                        dbg("search: gagal");
                    } else {
                        int idx = t.toLowerCase(Locale.US).indexOf("\"" + fid + "\"");
                        if (idx < 0) {
                            dbg("search: ID tempat tidak ada di hasil");
                        } else {
                            String ll = coordsAfter(t, Math.max(0, idx - 500), 5500);
                            if (ll != null) { dbg("search: OK"); return "ll=" + ll + "&q=" + java.net.URLEncoder.encode(nameHint, "UTF-8"); }
                            dbg("search: koordinat tidak ditemukan");
                        }
                    }
                } catch (Exception e) {
                    dbg("search: " + e.getClass().getSimpleName());
                }
            }
            return null;
        }

        // Ambil halaman Google Maps dan cari koordinat (+ nama) di isinya.
        // Penting: alamat lengkap berkoordinat (/@-6.28,106.90,15z) yang tampil di browser
        // dibuat oleh JavaScript Google Maps, BUKAN oleh redirect server. Jadi koordinat
        // harus dibaca dari isi halaman. Google memberi HTML "pratinjau link" (og:image
        // berisi center=lat,lng) kepada crawler seperti facebookexternalhit/Twitterbot,
        // jadi dicoba beberapa User-Agent berurutan.
        // Hasil: "ll=lat,lng" atau "ll=lat,lng&q=Nama", atau null kalau tidak ketemu.
        private String metaContent(String html, String prop) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "<meta[^>]+(?:property|name)=[\"']" + prop + "[\"'][^>]*content=[\"']([^\"']*)[\"']",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
            if (m.find()) return m.group(1);
            m = java.util.regex.Pattern.compile(
                    "<meta[^>]+content=[\"']([^\"']*)[\"'][^>]*(?:property|name)=[\"']" + prop + "[\"']",
                    java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
            return m.find() ? m.group(1) : null;
        }

        private String findCoordsInHtml(String html) {
            String[] pats = new String[] {
                // lat lalu lng
                "center=(-?\\d+\\.\\d+)(?:%2C|%2c|,)(-?\\d+\\.\\d+)",
                "!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)",
                "@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)",
                "[?&;]ll=(-?\\d+\\.\\d+)(?:%2C|%2c|,)(-?\\d+\\.\\d+)",
                "markers=(-?\\d+\\.\\d+)(?:%2C|%2c|,)(-?\\d+\\.\\d+)",
            };
            for (String p : pats) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(p).matcher(html);
                if (m.find()) return m.group(1) + "," + m.group(2);
            }
            // APP_INITIALIZATION_STATE=[[[zoom,LNG,LAT
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "APP_INITIALIZATION_STATE\\s*=\\s*\\[\\[\\[\\s*-?[\\d.]+\\s*,\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)").matcher(html);
            if (m.find()) return m.group(2) + "," + m.group(1);
            return null;
        }

        private String fetchCoordsFromPage(String pageUrl) {
            String[] agents = new String[] {
                "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
                "Twitterbot/1.0",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            };
            for (String ua : agents) {
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(pageUrl).openConnection();
                    try {
                        c.setInstanceFollowRedirects(true);
                        c.setConnectTimeout(4000);
                        c.setReadTimeout(5000);
                        c.setRequestProperty("User-Agent", ua);
                        c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
                        c.setRequestProperty("Accept-Language", "id,en;q=0.8");
                        c.setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg");
                        if (c.getResponseCode() != 200) continue;
                        java.io.BufferedReader br = new java.io.BufferedReader(
                                new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        char[] buf = new char[16384];
                        int n;
                        while ((n = br.read(buf)) > 0 && sb.length() < 1500000) sb.append(buf, 0, n);
                        br.close();
                        String html = sb.toString().replace("&amp;", "&").replace("\\u003d", "=").replace("\\u0026", "&");
                        // Validasi: halaman harus milik tempat yang dimaksud. Halaman generik
                        // Google memuat titik DEFAULT (perkiraan lokasi IP) — itu harus ditolak.
                        boolean specific = false;
                        java.util.regex.Matcher fm = java.util.regex.Pattern
                                .compile("!1s0x[0-9a-fA-F]+:(0x[0-9a-fA-F]+)").matcher(pageUrl);
                        if (fm.find() && html.toLowerCase().contains(fm.group(1).toLowerCase())) specific = true;
                        if (!specific) {
                            java.util.regex.Matcher nm = java.util.regex.Pattern
                                    .compile("/maps/place/([^/@]+)").matcher(pageUrl);
                            String og = metaContent(html, "og:title");
                            if (nm.find() && og != null) {
                                String a = java.net.URLDecoder.decode(nm.group(1).replace("+", " "), "UTF-8")
                                        .toLowerCase().replaceAll("[^a-z0-9]", "");
                                String b = og.toLowerCase().replaceAll("[^a-z0-9]", "");
                                if (a.length() >= 6 && b.contains(a.substring(0, Math.min(a.length(), 10)))) specific = true;
                            }
                        }
                        if (!specific) continue;
                        String ll = findCoordsInHtml(html);
                        if (ll == null) continue;
                        String out = "ll=" + ll;
                        // Nama tempat dari og:title, kalau URL belum memuat nama
                        if (!java.util.regex.Pattern.compile("/maps/place/[^/@]").matcher(pageUrl).find()) {
                            String name = metaContent(html, "og:title");
                            if (name != null) {
                                name = name.replace("&#39;", "'").replace("&quot;", "\"").replace("&amp;", "&");
                                name = name.replaceAll("\\s*[-\\u2013]\\s*Google Maps.*$", "");
                                int dot = name.indexOf(" \u00b7 ");
                                if (dot > 0) name = name.substring(0, dot);
                                name = name.trim();
                                if (!name.isEmpty() && !name.equalsIgnoreCase("Google Maps")) {
                                    out += "&q=" + java.net.URLEncoder.encode(name, "UTF-8");
                                }
                            }
                        }
                        return out;
                    } finally {
                        c.disconnect();
                    }
                } catch (Exception e) {
                    // coba User-Agent berikutnya
                }
            }
            return null;
        }

        // ── Strip info (arah + ETA) di frame mirror ──
        @JavascriptInterface
        public void setMirrorOverlay(boolean enabled, boolean top) {
            ScreenCaptureService.overlayEnabled = enabled;
            ScreenCaptureService.overlayTop = top;
        }

        // ── Akses notifikasi (baca arah belok Google Maps untuk AT+NAV) ──
        @JavascriptInterface
        public boolean isNotifListenerEnabled() {
            String s = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return s != null && s.contains(getPackageName());
        }

        @JavascriptInterface
        public void openNotifListenerSettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) {}
            });
        }

        // ── Mirror layar HP ke MID (screen capture → AT+MAPS) ──
        @JavascriptInterface
        public void startScreenMirror(double l, double t, double r, double b) {
            mirrorCrop = new float[]{(float) l, (float) t, (float) r, (float) b};
            runOnUiThread(() -> {
                try {
                    android.media.projection.MediaProjectionManager m =
                        (android.media.projection.MediaProjectionManager)
                            getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                    startActivityForResult(m.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE);
                } catch (Exception e) {
                    webView.evaluateJavascript("window.onMirrorStopped&&window.onMirrorStopped()", null);
                }
            });
        }

        @JavascriptInterface
        public void stopScreenMirror() {
            startService(new Intent(MainActivity.this, ScreenCaptureService.class)
                .setAction(ScreenCaptureService.ACTION_STOP));
        }

        // ── Buka aplikasi Google Maps langsung dari panel Mirror ──
        @JavascriptInterface
        public void openGoogleMaps() {
            runOnUiThread(() -> {
                final String pkg = "com.google.android.apps.maps";
                try {
                    // setPackage: tidak butuh <queries> di manifest, langsung ke aplikasi Maps
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps"));
                    i.setPackage(pkg);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e1) {
                    try {
                        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                        if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps"));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e2) {
                        Toast.makeText(MainActivity.this, "Tidak bisa membuka Google Maps", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        // Ukuran layar asli (px, termasuk bar navigasi) "lebar,tinggi" — dipakai tombol samakan rasio di panel Mirror
        @JavascriptInterface
        @SuppressWarnings("deprecation")
        public String getRealScreenSize() {
            try {
                android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                ((android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(m);
                return m.widthPixels + "," + m.heightPixels;
            } catch (Exception e) { return "0,0"; }
        }

        // ── Ukuran frame mirror (ikut pengaturan Resolusi Maps) ──
        @JavascriptInterface
        public void setMirrorSize(int w, int h) {
            ScreenCaptureService.reqW = Math.max(64, Math.min(1024, w));
            ScreenCaptureService.reqH = Math.max(64, Math.min(1024, h));
        }

        // ── Overlay penanda area rekam mirror ──
        @JavascriptInterface
        public boolean canDrawOverlay() {
            return MirrorRegionOverlay.canDraw(MainActivity.this);
        }

        @JavascriptInterface
        public void requestOverlayPermission() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public void showMirrorRegion(double l, double t, double r, double b, int autoHideMs) {
            MirrorRegionOverlay.show(getApplicationContext(),
                    new float[]{(float) l, (float) t, (float) r, (float) b}, autoHideMs);
        }

        @JavascriptInterface
        public void hideMirrorRegion() {
            MirrorRegionOverlay.hide();
        }

        // ── Matrix: notifikasi Google Maps (WebView harus tetap jalan saat Maps di depan) ──
        @JavascriptInterface
        public void setMatrixMapsNotif(boolean on) {
            keepAliveMatrixMaps = on;
        }

        @JavascriptInterface
        public String resolveUrl(String shortUrl) {
            resolveDebug = "";
            if (shortUrl == null || shortUrl.trim().isEmpty()) return "";
            String current = shortUrl.trim();
            try {
                if (!isAllowedMapsHost(new java.net.URL(current).getHost())) return "";
                String ua = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
                for (int hop = 0; hop < 6; hop++) {
                    java.net.HttpURLConnection conn =
                            (java.net.HttpURLConnection) new java.net.URL(current).openConnection();
                    try {
                        conn.setInstanceFollowRedirects(false);
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(4000);
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("User-Agent", ua);
                        conn.setRequestProperty("Accept-Language", "id,en;q=0.8");
                        int code = conn.getResponseCode();
                        if (code >= 300 && code < 400) {
                            String loc = conn.getHeaderField("Location");
                            if (loc == null) return current;
                            String next = new java.net.URL(new java.net.URL(current), loc).toString();
                            if (!isAllowedMapsHost(new java.net.URL(next).getHost())) return current;
                            current = next;
                            continue;
                        }
                        // Halaman akhir. Kalau URL belum memuat koordinat, cari di isi halaman
                        // (pakai User-Agent desktop + cookie consent supaya Google memberi HTML lengkap).
                        if (!urlHasCoords(current)) {
                            String extra = null;
                            String fid = findFeatureId(current);
                            dbg("akhir: " + (current.length() > 110 ? current.substring(0, 110) + "..." : current));
                            dbg("fid=" + fid);
                            if (fid != null) extra = lookupPlaceByFid(fid, placeNameFromUrl(current));
                            if (extra == null && code == 200) {
                                extra = fetchCoordsFromPage(current);
                                dbg("halaman: " + (extra != null ? "OK" : "tidak ada"));
                            }
                            if (extra != null) {
                                current = current + (current.contains("?") ? "&" : "?") + extra;
                            }
                        }
                        return current;
                    } finally {
                        conn.disconnect();
                    }
                }
                return current;
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void connect(String mac) {
            if (!mac.isEmpty()) prefs.edit().putString(KEY_MAC, mac).apply();
            if (serviceBound) btService.connect(mac);
        }

        @JavascriptInterface
        public String btMapsStatus() {
            return btService != null ? btService.mapsStatus() : "0,0";
        }

        @JavascriptInterface
        public void send(String command) {
            if (serviceBound) btService.send(command);
        }

        @JavascriptInterface
        public void disconnect() {
            if (serviceBound) btService.disconnect();
        }

        @JavascriptInterface
        public void forgetDevice() {
            prefs.edit().remove(KEY_MAC).apply();
            if (serviceBound) btService.forgetDevice();
        }

        @JavascriptInterface
        public void setNavData(String navData) {
            try {
                if (serviceBound && btService != null) btService.setNavData(navData);
            } catch (Exception ignored) {}
        }

        // ── Peta native → MID (NativeMapStreamer.java): digambar & dikirim di Java, tanpa WebView ──
        @JavascriptInterface
        public void nativeMapStart(String cfgJson) {
            try { NativeMapStreamer.start(getApplicationContext(), cfgJson); } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void nativeMapStop() {
            try { NativeMapStreamer.stop(); } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean nativeMapRunning() {
            return NativeMapStreamer.isRunning();
        }

        @JavascriptInterface
        public void nativeMapUpdate(String json) {
            try { NativeMapStreamer.update(json); } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void nativeMapRoute(String json) {
            try { NativeMapStreamer.setRoute(json); } catch (Exception ignored) {}
        }

        // Layar boleh mati selama peta native jalan: lepas KEEP_SCREEN_ON / tampil di atas lockscreen.
        @JavascriptInterface
        public void allowScreenOff() {
            runOnUiThread(() -> {
                try {
                    getWindow().clearFlags(
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
                    if (android.os.Build.VERSION.SDK_INT >= 27) {
                        setShowWhenLocked(false);
                        setTurnScreenOn(false);
                    }
                } catch (Exception ignored) {}
            });
        }

        // Daftar item rotasi AT+NAV (JSON array string). Dipakai BluetoothService untuk memutar AT+NAV secara
        // native saat JS terhenti/terredam (layar dikunci).
        @JavascriptInterface
        public void setNavItems(String json, int rotateSec) {
            try {
                if (!(serviceBound && btService != null)) return;
                org.json.JSONArray a = new org.json.JSONArray(json);
                String[] items = new String[a.length()];
                for (int i = 0; i < a.length(); i++) items[i] = a.optString(i, "");
                btService.setNavItems(items, rotateSec);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public boolean isConnected() {
            return serviceBound && btService.isConnected();
        }

        @JavascriptInterface
        public String getSavedMac() {
            return prefs.getString(KEY_MAC, "");
        }

        // Scan paired Bluetooth devices
        @JavascriptInterface
        public void startScan() {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                        "Bluetooth tidak tersedia.", Toast.LENGTH_LONG).show();
                    webView.evaluateJavascript("window.onBtScanDone()", null);
                });
                return;
            }
            if (!adapter.isEnabled()) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                        "Bluetooth belum aktif.", Toast.LENGTH_LONG).show();
                    webView.evaluateJavascript("window.onBtScanDone()", null);
                });
                return;
            }
            Set<BluetoothDevice> paired = adapter.getBondedDevices();
            if (paired == null || paired.isEmpty()) {
                runOnUiThread(() ->
                    webView.evaluateJavascript("window.onBtScanDone()", null));
                return;
            }
            for (BluetoothDevice device : paired) {
                String n = device.getName();
                if (n == null) n = "Unknown";
                final String fn  = n.replace("\\","\\\\").replace("'","\\'");
                final String mac = device.getAddress();
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onBtDevice('" + fn + "','" + mac + "')", null));
            }
            runOnUiThread(() ->
                webView.evaluateJavascript("window.onBtScanDone()", null));
        }

        // TTS
        @JavascriptInterface
        public void speak(String text) {
            if (!ttsReady || tts == null || text == null || text.isEmpty()) return;
            tts.stop();
            if (Build.VERSION.SDK_INT >= 21) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gesits_tts");
            } else {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        }

        @JavascriptInterface
        public void stopSpeak() {
            if (tts != null) tts.stop();
        }

        @JavascriptInterface
        public boolean isTtsReady() {
            return ttsReady;
        }

        // Chime 8 nada saat splash (4 naik + 4 turun — Do-Re-Mi-Sol lalu turun,
        // disamakan dengan pola chime versi web di index.html)
        @JavascriptInterface
        public void playChime() {
            new Thread(() -> {
                try {
                    // C5-D5-E5-G5 naik, lalu G5-E5-D5-C5 turun
                    int[] freqs = {523, 587, 659, 784, 784, 659, 587, 523};
                    for (int freq : freqs) {
                        playToneNative(freq, 140);
                        Thread.sleep(90);
                    }
                } catch (Exception e) {
                    android.util.Log.e("Chime", "playChime error: " + e.getMessage());
                }
            }).start();
        }

        // Batalkan auto-connect
        @JavascriptInterface
        public void cancelConnect() {
            if (serviceBound) btService.disconnect();
        }

        // Coba connect ulang dengan MAC tersimpan
        @JavascriptInterface
        public void reconnect() {
            String mac = prefs.getString(KEY_MAC, "");
            if (!mac.isEmpty() && serviceBound) btService.connect(mac);
        }

        // Nyalakan layar HP secara paksa — dipanggil saat reconnect agar
        // AT+MAPS bisa lanjut stream (WebView JS pause kalau layar mati)
        @JavascriptInterface
        public void wakeScreen() {
            runOnUiThread(() -> {
                getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

                if (Build.VERSION.SDK_INT >= 27) {
                    setShowWhenLocked(true);
                    setTurnScreenOn(true);
                }

                // Acquire PowerManager wake lock sesaat untuk paksa nyala
                android.os.PowerManager pm =
                    (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null) {
                    android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "GesitsDashboard:ScreenWake");
                    wl.acquire(10000); // nyala 10 detik lalu lepas otomatis
                }
            });
        }

        // Buka MapActivity native
        @JavascriptInterface
        public void openNativeMap(double destLat, double destLng,
                                  String destLabel, boolean nightMode) {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, MapActivity.class);
                intent.putExtra("destLat",   destLat);
                intent.putExtra("destLng",   destLng);
                intent.putExtra("destLabel", destLabel);
                intent.putExtra("nightMode", nightMode);
                startActivity(intent);
            });
        }

        @JavascriptInterface
        public void openNativeMapFreeDrive(boolean nightMode) {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, MapActivity.class);
                intent.putExtra("nightMode", nightMode);
                startActivity(intent);
            });
        }

        // Share teks
        @JavascriptInterface
        public void shareText(String text, String title) {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, text);
                intent.putExtra(Intent.EXTRA_SUBJECT, title);
                startActivity(Intent.createChooser(intent, "Bagikan via"));
            });
        }

        // Buka URL eksternal (WhatsApp wa.me, telepon tel:, peta geo:/https:)
        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null || url.trim().isEmpty()) return;
            final String u = url.trim();
            final String low = u.toLowerCase(Locale.US);
            if (!low.startsWith("https://") && !low.startsWith("http://")
                    && !low.startsWith("tel:") && !low.startsWith("geo:")
                    && !low.startsWith("mailto:")) {
                return;
            }
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this,
                        "Tidak ada aplikasi untuk membuka tautan ini", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Export file
        @JavascriptInterface
        public void exportFile(String filename, String content, String mimeType) {
            new Thread(() -> {
                try {
                    File file = new File(getCacheDir(), filename);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(content.getBytes("UTF-8"));
                    fos.close();
                    Uri uri = FileProvider.getUriForFile(
                        MainActivity.this,
                        "com.gesits.dashboard.fileprovider", file);
                    runOnUiThread(() -> {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType(mimeType != null ? mimeType : "text/plain");
                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent, "Export " + filename));
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Export gagal: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        // Simpan gambar PNG (base64) langsung ke galeri via MediaStore
        // Tidak butuh permission WRITE_EXTERNAL_STORAGE di Android 10+ (Scoped Storage)
        @JavascriptInterface
        public void savePng(String base64Data, String filename) {
            new Thread(() -> {
                try {
                    byte[] bytes = android.util.Base64.decode(base64Data,
                        android.util.Base64.DEFAULT);

                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename);
                    values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png");

                    if (Build.VERSION.SDK_INT >= 29) {
                        // Android 10+ — Scoped Storage, simpan ke Pictures/GESITS
                        values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                            "Pictures/GESITS");
                        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
                    }

                    Uri collection = android.provider.MediaStore.Images.Media
                        .EXTERNAL_CONTENT_URI;
                    Uri itemUri = getContentResolver().insert(collection, values);

                    if (itemUri == null) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Gagal membuat file gambar", Toast.LENGTH_LONG).show());
                        return;
                    }

                    java.io.OutputStream os = getContentResolver().openOutputStream(itemUri);
                    if (os != null) {
                        os.write(bytes);
                        os.close();
                    }

                    if (Build.VERSION.SDK_INT >= 29) {
                        values.clear();
                        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                        getContentResolver().update(itemUri, values, null, null);
                    }

                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Tersimpan ke galeri", Toast.LENGTH_SHORT).show());

                } catch (Exception e) {
                    android.util.Log.e("savePng", "Error: " + e.getMessage());
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Gagal simpan gambar: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        // Simpan video Motovlog Cam (base64 dari MediaRecorder) ke galeri
        // via MediaStore — pola identik savePng() di atas.
        // CATATAN: base64 nambah ukuran ~33% dari file asli, dan buat video
        // durasi panjang (beberapa menit) ini bisa jadi lumayan besar & agak
        // lambat pas encode/decode base64-nya. Cukup OK buat klip motovlog
        // pendek-menengah (di bawah beberapa menit); kalau nanti mau dukung
        // rekaman lebih panjang, perlu direfactor pakai chunked write biar
        // gak sekali kirim base64 raksasa lewat JS bridge.
        @JavascriptInterface
        public void saveVideo(String base64Data, String filename, String mimeType) {
            // JS sekarang prioritaskan MP4 (H.264/AAC) karena WhatsApp &
            // banyak app lain gak bisa play/terima .webm sebagai video —
            // MIME_TYPE MediaStore WAJIB ikut file aslinya, bukan hardcode,
            // supaya galeri & app lain kenali formatnya dengan benar.
            final String mime = (mimeType != null && !mimeType.isEmpty())
                ? mimeType : "video/webm";
            new Thread(() -> {
                try {
                    byte[] bytes = android.util.Base64.decode(base64Data,
                        android.util.Base64.DEFAULT);

                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, filename);
                    values.put(android.provider.MediaStore.Video.Media.MIME_TYPE, mime);

                    if (Build.VERSION.SDK_INT >= 29) {
                        values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                            "Movies/GESITS Motovlog");
                        values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 1);
                    }

                    Uri collection = android.provider.MediaStore.Video.Media
                        .EXTERNAL_CONTENT_URI;
                    Uri itemUri = getContentResolver().insert(collection, values);

                    if (itemUri == null) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Gagal membuat file video", Toast.LENGTH_LONG).show());
                        return;
                    }

                    java.io.OutputStream os = getContentResolver().openOutputStream(itemUri);
                    if (os != null) {
                        os.write(bytes);
                        os.close();
                    }

                    if (Build.VERSION.SDK_INT >= 29) {
                        values.clear();
                        values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
                        getContentResolver().update(itemUri, values, null, null);
                    }

                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Video motovlog tersimpan ke galeri", Toast.LENGTH_SHORT).show());

                } catch (Exception e) {
                    android.util.Log.e("saveVideo", "Error: " + e.getMessage());
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Gagal simpan video: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        // ── Simpan video PER-CHUNK (dipanggil dari mvSaveViaChunkedBridge di
        // JS) — gantiin saveVideo() sekali-kirim di atas. saveVideo() lama
        // kirim SELURUH video sebagai satu string base64 raksasa lewat
        // @JavascriptInterface; panggilan JS-bridge punya batas ukuran yang
        // beda-beda tiap device/versi WebView (~1MB, gak resmi
        // didokumentasikan) — klip pendek lolos, klip lebih panjang di
        // sebagian device GAGAL TOTAL tanpa exception di kedua sisi (gak ada
        // toast error, video-nya cuma gak pernah sampai). Solusinya: native
        // buka OutputStream sekali di awal, JS kirim per-potongan kecil
        // (~300KB) yang langsung ditulis & dibuang dari memori, baru
        // di-finalize di akhir. saveVideo() lama TETAP DIPERTAHANKAN sebagai
        // fallback kalau ada build JS lama yang belum ke-update.
        @JavascriptInterface
        public void startVideoWrite(String filename, String mimeType) {
            final String mime = (mimeType != null && !mimeType.isEmpty())
                ? mimeType : "video/mp4";
            try {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, filename);
                values.put(android.provider.MediaStore.Video.Media.MIME_TYPE, mime);
                if (Build.VERSION.SDK_INT >= 29) {
                    values.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                        "Movies/GESITS Motovlog");
                    values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 1);
                }

                Uri collection = android.provider.MediaStore.Video.Media
                    .EXTERNAL_CONTENT_URI;
                videoWriteUri = getContentResolver().insert(collection, values);

                if (videoWriteUri == null) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Gagal membuat file video", Toast.LENGTH_LONG).show());
                    return;
                }
                videoWriteStream = getContentResolver().openOutputStream(videoWriteUri);
            } catch (Exception e) {
                android.util.Log.e("saveVideo", "startVideoWrite error: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Gagal mulai simpan video: " + e.getMessage(),
                    Toast.LENGTH_LONG).show());
                videoWriteStream = null;
                videoWriteUri = null;
            }
        }

        @JavascriptInterface
        public void appendVideoChunk(String base64Chunk) {
            // Kalau startVideoWrite() gagal (stream null), diamkan di sini —
            // toast error udah ditampilkan di startVideoWrite(), gak perlu
            // spam toast lagi tiap chunk yang kebuang.
            if (videoWriteStream == null) return;
            try {
                byte[] bytes = android.util.Base64.decode(base64Chunk,
                    android.util.Base64.DEFAULT);
                videoWriteStream.write(bytes);
            } catch (Exception e) {
                android.util.Log.e("saveVideo", "appendVideoChunk error: " + e.getMessage());
                try { videoWriteStream.close(); } catch (Exception ignored) {}
                videoWriteStream = null;
                if (videoWriteUri != null) {
                    // Buang file setengah-jadi biar gak nyampah entry rusak di galeri.
                    try { getContentResolver().delete(videoWriteUri, null, null); } catch (Exception ignored) {}
                    videoWriteUri = null;
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Gagal simpan video: " + e.getMessage(),
                    Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void finalizeVideoWrite() {
            try {
                if (videoWriteStream != null) {
                    videoWriteStream.close();
                    videoWriteStream = null;
                }
                if (videoWriteUri != null) {
                    if (Build.VERSION.SDK_INT >= 29) {
                        android.content.ContentValues values = new android.content.ContentValues();
                        values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
                        getContentResolver().update(videoWriteUri, values, null, null);
                    }
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Video motovlog tersimpan ke galeri", Toast.LENGTH_SHORT).show());
                    videoWriteUri = null;
                }
            } catch (Exception e) {
                android.util.Log.e("saveVideo", "finalizeVideoWrite error: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Gagal menyelesaikan simpan video: " + e.getMessage(),
                    Toast.LENGTH_LONG).show());
            }
        }

        // Minta ulang izin RECORD_AUDIO secara eksplisit — dipanggil dari JS
        // kalau getUserMedia({audio:true}) gagal di Motovlog Cam. Ini
        // PENTING karena requestBtPermissions() di onCreate cuma minta
        // sekali di awal (batch bareng CAMERA/LOKASI/dll); Android nampilin
        // dialog izin SATU-SATU per jenis, jadi kalau user gak sengaja tap
        // "Deny" khusus di dialog Microphone (walau Camera di-Allow), app
        // gak akan pernah minta lagi tanpa fungsi ini — audio bakal gagal
        // terus selamanya walau kamera jalan normal.
        @JavascriptInterface
        public void requestMicPermission() {
            runOnUiThread(() -> {
                if (ContextCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    // Sudah granted (misal user kasih dari Setelan manual) — langsung
                    // kabari JS biar retry ambil audio tanpa perlu restart app.
                    webView.evaluateJavascript(
                        "window.onMicPermissionResult && window.onMicPermissionResult(true)", null);
                    return;
                }
                ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{ Manifest.permission.RECORD_AUDIO },
                    REQUEST_CODE_MIC_PERMISSION);
            });
        }

        // Mulai/stop tangkap audio LANGSUNG dari native (AudioRecord), gak
        // lewat getUserMedia() WebView sama sekali — dipanggil JS pas mulai/
        // stop rekam Motovlog Cam. Baca komentar di deklarasi field
        // micRecorder di atas soal alasannya.
        @JavascriptInterface
        public void startNativeMicCapture() {
            startNativeMicCaptureInternal();
        }

        @JavascriptInterface
        public void stopNativeMicCapture() {
            stopNativeMicCaptureInternal();
        }
        // Share video Motovlog Cam langsung lewat Android share sheet (WA,
        // Telegram, dll) — sebelumnya TIDAK ADA fungsi ini sama sekali,
        // jadi user harus buka Galeri manual dulu, dan kalau formatnya webm
        // banyak app (termasuk WA) menolak/gagal muter filenya. Ditulis ke
        // cache dir + FileProvider (pola sama persis seperti sharePng()).
        @JavascriptInterface
        public void shareVideo(String base64Data, String filename, String mimeType) {
            final String mime = (mimeType != null && !mimeType.isEmpty())
                ? mimeType : "video/mp4";
            new Thread(() -> {
                try {
                    byte[] bytes = android.util.Base64.decode(base64Data,
                        android.util.Base64.DEFAULT);
                    File file = new File(getCacheDir(), filename);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(bytes);
                    fos.close();

                    Uri uri = FileProvider.getUriForFile(
                        MainActivity.this,
                        "com.gesits.dashboard.fileprovider", file);

                    runOnUiThread(() -> {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType(mime);
                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent, "Bagikan video motovlog"));
                    });
                } catch (Exception e) {
                    android.util.Log.e("shareVideo", "Error: " + e.getMessage());
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Share gagal: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        // Share gambar PNG (base64) via Android share sheet
        @JavascriptInterface
        public void sharePng(String base64Data, String filename, String title) {
            new Thread(() -> {
                try {
                    byte[] bytes = android.util.Base64.decode(base64Data,
                        android.util.Base64.DEFAULT);
                    File file = new File(getCacheDir(), filename);
                    FileOutputStream fos = new FileOutputStream(file);
                    fos.write(bytes);
                    fos.close();

                    Uri uri = FileProvider.getUriForFile(
                        MainActivity.this,
                        "com.gesits.dashboard.fileprovider", file);

                    runOnUiThread(() -> {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("image/png");
                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent,
                            title != null ? title : "Bagikan gambar"));
                    });
                } catch (Exception e) {
                    android.util.Log.e("sharePng", "Error: " + e.getMessage());
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Share gagal: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }).start();
        }

        // ══════════════════════════════════════════════
        // BMS JIKONG — BLE GATT, terpisah dari koneksi motor di atas.
        // Semua parsing byte JK-BMS dilakukan di JS (index.html), method
        // di sini cuma jembatan mentah scan/connect/write/notify.
        // ══════════════════════════════════════════════
        @JavascriptInterface
        public void bmsStartScan() {
            if (bmsManager != null) bmsManager.startScan();
        }

        @JavascriptInterface
        public void bmsConnectMac(String mac) {
            if (bmsManager != null) bmsManager.connect(mac);
        }

        @JavascriptInterface
        public void bmsDisconnect() {
            if (bmsManager != null) bmsManager.disconnect();
        }

        @JavascriptInterface
        public void bmsWriteRaw(String hexString) {
            if (bmsManager != null) bmsManager.writeRawHex(hexString);
        }

        @JavascriptInterface
        public void bmsSetType(String type) {
            if (bmsManager != null) bmsManager.setBmsType(type);
        }

        // ══════════════════════════════════════════════
        // FARDRIVER CONTROLLER — BLE GATT (FF00 / FFE0)
        // ══════════════════════════════════════════════
        @JavascriptInterface
        public void fardriverStartScan(boolean showAll) {
            if (fardriverManager != null) fardriverManager.startScan(showAll);
        }

        @JavascriptInterface
        public void fardriverConnectMac(String mac) {
            if (fardriverManager != null) fardriverManager.connect(mac);
        }

        @JavascriptInterface
        public void fardriverDisconnect() {
            if (fardriverManager != null) fardriverManager.disconnect();
        }

        @JavascriptInterface
        public void fardriverWriteRaw(String hexString) {
            if (fardriverManager != null) fardriverManager.writeRawHex(hexString);
        }

        // ══════════════════════════════════════════════
        // ESP32 CAN Sniffer — jembatan mentah scan/connect/notify, pola
        // identik BMS di atas. Lihat Esp32BleManager.java.
        // ══════════════════════════════════════════════
        @JavascriptInterface
        public void esp32StartScan() {
            if (esp32Manager != null) esp32Manager.startScan();
        }

        @JavascriptInterface
        public void esp32ConnectMac(String mac) {
            if (mac != null && !mac.trim().isEmpty()) {
                prefs.edit().putString(KEY_ESP32_MAC, mac.trim()).apply();
            }
            if (esp32Manager != null) esp32Manager.connect(mac);
        }

        @JavascriptInterface
        public void esp32ForgetDevice() {
            prefs.edit().remove(KEY_ESP32_MAC).apply();
            esp32Connected = false; // jaga-jaga: disconnect() gak selalu memicu callback onDisconnected sinkron
            if (esp32Manager != null) esp32Manager.disconnect();
        }

        @JavascriptInterface
        public String esp32GetSavedMac() {
            return prefs.getString(KEY_ESP32_MAC, "");
        }

        @JavascriptInterface
        public void esp32Disconnect() {
            esp32Connected = false; // jaga-jaga: disconnect() gak selalu memicu callback onDisconnected sinkron
            if (esp32Manager != null) esp32Manager.disconnect();
        }

        @JavascriptInterface
        public void esp32SendCommand(String command) {
            if (esp32Manager != null) esp32Manager.sendCommand(command);
        }
        // ══════════════════════════════════════════════
        // ESP32 #2 — BMS ORI / Battery 2
        // Koneksi GATT ini sengaja dibuat manager terpisah supaya dua ESP32
        // bisa hidup bersamaan tanpa menimpa data Battery 1 atau CAN utama.
        // ══════════════════════════════════════════════
        @JavascriptInterface
        public void battery2Esp32StartScan() {
            if (esp32Battery2Manager != null) esp32Battery2Manager.startScan();
        }

        @JavascriptInterface
        public void battery2Esp32ConnectMac(String mac) {
            if (mac != null && !mac.trim().isEmpty()) {
                prefs.edit().putString(KEY_BATT2_MAC, mac.trim()).apply();
            }
            if (esp32Battery2Manager != null) esp32Battery2Manager.connect(mac);
        }

        @JavascriptInterface
        public void battery2Esp32Disconnect() {
            if (esp32Battery2Manager != null) esp32Battery2Manager.disconnect();
        }

        @JavascriptInterface
        public void battery2Esp32ForgetDevice() {
            prefs.edit().remove(KEY_BATT2_MAC).apply();
            if (esp32Battery2Manager != null) esp32Battery2Manager.disconnect();
        }

        // ── LIVE STREAMING (kamera + HUD -> RTMP) ────────────────────────
        // Dipanggil WebView tiap tick telemetri (lihat dashboard: broadcastLiveHud()),
        // supaya LiveStreamActivity (native, terpisah dari WebView) selalu punya
        // angka terbaru buat digambar di overlay. Dibungkus try/catch di sisi JS,
        // jadi aman dipanggil terus-menerus walau LiveStreamActivity belum dibuka.
        @JavascriptInterface
        public void updateLiveHud(String json) {
            LiveHudBus.update(json);
        }

        // HUD gaya "Rekam Video": JS merender overlay jadi gambar (base64) tiap detik selama Live terbuka.
        // isLiveImageWanted() dicek dulu oleh JS supaya tidak merender kalau tidak perlu.
        @JavascriptInterface
        public boolean isLiveImageWanted() {
            return LiveHudBus.liveActive && LiveHudBus.wantRecImage;
        }

        @JavascriptInterface
        public void updateLiveHudImage(String b64) {
            LiveHudBus.updateImage(b64);
        }

        // Buka layar Live Streaming (kamera + HUD -> RTMP). Dipisah dari WebView
        // karena WebView/JS gak punya kemampuan encode+push RTMP sama sekali —
        // ini murni native (lihat LiveStreamActivity.java).
        @JavascriptInterface
        public void openLiveStream() {
            runOnUiThread(() -> {
                try {
                    // WAJIB di-set SEBELUM startActivity: MainActivity.onPause() dipanggil lebih dulu daripada
                    // LiveStreamActivity.onCreate(), jadi kalau flag baru dinyalakan di sana, WebView sudah terlanjur
                    // di-pause dan data HUD berhenti (HUD jadi "tertunda" / berpindah ke gaya ringkas sendiri).
                    LiveHudBus.liveActive = true;
                    startActivity(new Intent(MainActivity.this, LiveStreamActivity.class));
                } catch (Exception e) {
                    LiveHudBus.liveActive = false;
                    android.util.Log.e("LiveStream", "Gagal buka LiveStreamActivity: " + e.getMessage());
                }
            });
        }

        // Kirim command teks ke ESP32 #2 (mis. "CAN_MODE:NORMAL", "MID:ON", "CAN_STATUS?").
        @JavascriptInterface
        public boolean battery2Esp32SendCommand(String command) {
            if (command == null || command.trim().isEmpty() || esp32Battery2Manager == null) return false;
            return esp32Battery2Manager.sendCommand(command.trim());
        }

        @JavascriptInterface
        public void battery2Esp32Reconnect() {
            String mac = prefs.getString(KEY_BATT2_MAC, "");
            if (!mac.isEmpty() && esp32Battery2Manager != null) {
                esp32Battery2Manager.connect(mac);
            }
        }

        @JavascriptInterface
        public boolean battery2Esp32IsConnected() {
            return esp32Battery2Manager != null && esp32Battery2Manager.isConnected();
        }

        @JavascriptInterface
        public String battery2Esp32GetSavedMac() {
            return prefs.getString(KEY_BATT2_MAC, "");
        }

        // ── Push data telemetri terbaru ke home screen widget ──
        // Dipanggil dari index.html (updateRangeEstimate/status koneksi)
        // tiap kali SOC/estimasi jarak/kecepatan/status berubah. Data
        // disimpan ke SharedPreferences terpisah (dibaca
        // GesitsWidgetProvider), lalu semua instance widget yg terpasang
        // di-refresh.
        // soc/rangeKm/speedKmh: pakai -1 kalau belum ada data valid.
        @JavascriptInterface
        public void updateWidgetData(float soc, float rangeKm, float speedKmh,
                                      boolean connected, String updatedAtLabel) {
            SharedPreferences wPrefs = getSharedPreferences(
                GesitsWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
            wPrefs.edit()
                .putFloat(GesitsWidgetProvider.KEY_SOC, soc)
                .putFloat(GesitsWidgetProvider.KEY_RANGE_KM, rangeKm)
                .putFloat(GesitsWidgetProvider.KEY_SPEED_KMH, speedKmh)
                .putBoolean(GesitsWidgetProvider.KEY_CONNECTED, connected)
                .putString(GesitsWidgetProvider.KEY_UPDATED_AT,
                    updatedAtLabel != null ? updatedAtLabel : "—")
                .apply();
            runOnUiThread(() -> GesitsWidgetProvider.pushUpdate(MainActivity.this));
        }

        // ── Download + install APK update langsung dari dalam app ──
        // Dipanggil dari index.html (menu "Aplikasi" di tab Akun) begitu JS
        // udah tau ada versi baru di GitHub Releases (lewat getAppVersion()
        // vs GitHub API). url = link .apk asset dari rilis terbaru.
        @JavascriptInterface
        public void downloadAndInstallApk(String url) {
            if (url == null || url.isEmpty()) return;
            runOnUiThread(() -> {
                // Android 8+ butuh izin "Install aplikasi tidak dikenal"
                // khusus buat app ini sebelum installer APK bisa jalan.
                if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                    Toast.makeText(MainActivity.this,
                        "Izinkan \"Install aplikasi tidak dikenal\" untuk GESITS Dashboard, lalu ketuk Aplikasi lagi.",
                        Toast.LENGTH_LONG).show();
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception ignored) {}
                    webView.evaluateJavascript(
                        "window.onApkDownloadFailed && window.onApkDownloadFailed('permission')", null);
                    return;
                }
                startApkDownload(url);
            });
        }

        // Fetch teks (JSON/GeoJSON/KML/CSV/GPX) dari URL remote secara asinkron via OkHttp,
        // lalu hasil mentahnya diambil JS lewat https://appassets.androidplatform.net/molis-fetch/<id>
        // (menghindari batas ukuran IPC bridge dan bebas hambatan CORS WebView).
        @JavascriptInterface
        public void fetchRemoteText(final String requestId, final String urlString, final String etag) {
            if (requestId == null || requestId.trim().isEmpty()) return;
            final String cleanUrl = urlString == null ? "" : urlString.trim();
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                deliverNativeFetchResult(requestId, false, 0, "", "URL harus diawali http:// atau https://");
                return;
            }
            new Thread(() -> {
                Response response = null;
                try {
                    Request.Builder rb = new Request.Builder()
                        .url(cleanUrl)
                        .header("User-Agent", "GesitsDashboard/1.0 (Android)")
                        .header("Accept", "application/json, application/geo+json, application/vnd.google-earth.kml+xml, application/gpx+xml, text/csv, text/plain, */*");
                    if (etag != null && !etag.trim().isEmpty()) {
                        rb.header("If-None-Match", etag.trim());
                    }
                    response = syncHttpClient.newCall(rb.build()).execute();
                    int code = response.code();
                    if (code == 304) {
                        deliverNativeFetchResult(requestId, true, 304, etag != null ? etag : "", "");
                        return;
                    }
                    if (!response.isSuccessful()) {
                        deliverNativeFetchResult(requestId, false, code, "", "HTTP " + code);
                        return;
                    }
                    ResponseBody body = response.body();
                    if (body == null) {
                        deliverNativeFetchResult(requestId, false, code, "", "Respon kosong");
                        return;
                    }
                    InputStream is = body.byteStream();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int read;
                    int total = 0;
                    while ((read = is.read(buf)) != -1) {
                        total += read;
                        if (total > MAX_REMOTE_FETCH_BYTES) {
                            deliverNativeFetchResult(requestId, false, code, "", "File terlalu besar (>15 MB)");
                            return;
                        }
                        baos.write(buf, 0, read);
                    }
                    if (fetchedPayloads.size() >= 8) {
                        fetchedPayloads.clear();
                    }
                    fetchedPayloads.put(requestId, baos.toByteArray());
                    String newEtag = response.header("ETag", "");
                    deliverNativeFetchResult(requestId, true, code, newEtag != null ? newEtag : "", "");
                } catch (Exception e) {
                    deliverNativeFetchResult(requestId, false, 0, "",
                        e.getMessage() != null ? e.getMessage() : "Gagal terhubung");
                } finally {
                    if (response != null) {
                        try { response.close(); } catch (Exception ignored) {}
                    }
                }
            }, "MolisSyncFetch").start();
        }

        // Query khusus Cloudflare GraphQL Analytics API (bebas CORS WebView).
        // URL dikunci ke https://api.cloudflare.com/client/v4/graphql dan token hanya berasal dari input developer lokal.
        @JavascriptInterface
        public void postCloudflareGraphql(final String requestId, final String cfToken, final String jsonBody) {
            if (requestId == null || requestId.trim().isEmpty()) return;
            final String token = cfToken == null ? "" : cfToken.trim();
            final String payload = jsonBody == null ? "" : jsonBody;
            if (token.isEmpty() || payload.isEmpty()) {
                deliverNativeFetchResult(requestId, false, 400, "", "Token atau query Cloudflare kosong");
                return;
            }
            new Thread(() -> {
                Response response = null;
                try {
                    MediaType jsonType = MediaType.parse("application/json; charset=utf-8");
                    RequestBody reqBody = RequestBody.create(payload, jsonType);
                    Request req = new Request.Builder()
                        .url("https://api.cloudflare.com/client/v4/graphql")
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .post(reqBody)
                        .build();
                    response = syncHttpClient.newCall(req).execute();
                    int code = response.code();
                    ResponseBody body = response.body();
                    byte[] raw = body != null ? body.bytes() : new byte[0];
                    if (fetchedPayloads.size() >= 8) {
                        fetchedPayloads.clear();
                    }
                    fetchedPayloads.put(requestId, raw);
                    if (!response.isSuccessful()) {
                        deliverNativeFetchResult(requestId, false, code, "", "HTTP " + code);
                        return;
                    }
                    deliverNativeFetchResult(requestId, true, code, "", "");
                } catch (Exception e) {
                    deliverNativeFetchResult(requestId, false, 0, "",
                        e.getMessage() != null ? e.getMessage() : "Gagal terhubung ke Cloudflare");
                } finally {
                    if (response != null) {
                        try { response.close(); } catch (Exception ignored) {}
                    }
                }
            }, "CloudflareAnalyticsFetch").start();
        }

        // Ikat proses app sementara ke jaringan Wi-Fi aktif (mis. AP "Gesits CAN"
        // tanpa internet) supaya 192.168.4.1 tidak dialihkan ke data seluler.
        @JavascriptInterface
        public String bindEsp32Wifi(final boolean enable) {
            return applyEsp32WifiBinding(enable);
        }

        // Request GET HTTP langsung ke http://192.168.4.1<path> lewat SocketFactory
        // milik interface Wi-Fi aktif (menembus routing seluler & CORS WebView).
        @JavascriptInterface
        public void esp32WifiGet(final String requestId, final String path) {
            if (requestId == null || requestId.trim().isEmpty()) return;
            final String cleanPath = (path == null || !path.startsWith("/") || path.contains(" ")
                || path.contains("\r") || path.contains("\n")) ? "" : path;
            if (cleanPath.isEmpty()) {
                deliverEsp32WifiResult(requestId, false, 400, "Path ESP32 tidak valid");
                return;
            }
            new Thread(() -> {
                Response response = null;
                try {
                    Network wifiNet = findActiveWifiNetwork();
                    if (wifiNet == null) {
                        deliverEsp32WifiResult(requestId, false, 0,
                            "Wi-Fi tidak aktif. Sambungkan HP ke AP \"Gesits CAN\" (12345678).");
                        return;
                    }
                    OkHttpClient wifiClient = syncHttpClient.newBuilder()
                        .socketFactory(wifiNet.getSocketFactory())
                        .connectTimeout(2500, TimeUnit.MILLISECONDS)
                        .readTimeout(4500, TimeUnit.MILLISECONDS)
                        .writeTimeout(3000, TimeUnit.MILLISECONDS)
                        .retryOnConnectionFailure(false)
                        .build();
                    Request req = new Request.Builder()
                        .url("http://192.168.4.1" + cleanPath)
                        .header("Connection", "close")
                        .header("Cache-Control", "no-store")
                        .build();
                    response = wifiClient.newCall(req).execute();
                    int code = response.code();
                    ResponseBody body = response.body();
                    String txt = "";
                    if (body != null) {
                        InputStream is = body.byteStream();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int r;
                        int total = 0;
                        while ((r = is.read(buf)) != -1) {
                            total += r;
                            if (total > 512 * 1024) break;
                            baos.write(buf, 0, r);
                        }
                        txt = baos.toString("UTF-8");
                    }
                    deliverEsp32WifiResult(requestId, response.isSuccessful(), code, txt);
                } catch (Exception e) {
                    deliverEsp32WifiResult(requestId, false, 0,
                        e.getMessage() != null ? e.getMessage() : "Gagal menghubungi 192.168.4.1");
                } finally {
                    if (response != null) {
                        try { response.close(); } catch (Exception ignored) {}
                    }
                }
            }, "Esp32WifiHttp").start();
        }

    } // ← TUTUP JsBridge

    private Network findActiveWifiNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            for (Network net : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return net;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String applyEsp32WifiBinding(boolean enable) {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "no_cm";
            if (!enable) {
                cm.bindProcessToNetwork(null);
                esp32WifiBound = false;
                return "unbound";
            }
            Network wifiNet = findActiveWifiNetwork();
            if (wifiNet == null) {
                esp32WifiBound = false;
                return "no_wifi";
            }
            boolean ok = cm.bindProcessToNetwork(wifiNet);
            esp32WifiBound = ok;
            return ok ? "bound" : "failed";
        } catch (Exception e) {
            esp32WifiBound = false;
            return "error";
        }
    }

    private void deliverEsp32WifiResult(String requestId, boolean ok, int status, String payload) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String js = "window.__onEsp32WifiDone && window.__onEsp32WifiDone("
                + JSONObject.quote(requestId) + ","
                + ok + ","
                + status + ","
                + JSONObject.quote(payload != null ? payload : "") + ")";
            webView.evaluateJavascript(js, null);
        });
    }

    private void deliverNativeFetchResult(String requestId, boolean ok, int status, String etag, String errorMsg) {
        runOnUiThread(() -> {
            if (webView == null) return;
            String js = "window.__onNativeFetchDone && window.__onNativeFetchDone("
                + JSONObject.quote(requestId) + ","
                + ok + ","
                + status + ","
                + JSONObject.quote(etag != null ? etag : "") + ","
                + JSONObject.quote(errorMsg != null ? errorMsg : "") + ")";
            webView.evaluateJavascript(js, null);
        });
    }

    // ── Update APK: mulai download via DownloadManager sistem ──
    // Pakai DownloadManager (bukan HttpURLConnection manual) biar dapet
    // notifikasi progress bawaan Android, jalan di background, dan tahan
    // kalau koneksi putus-nyambung pas download file besar.
    private void startApkDownload(String url) {
        try {
            File destDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (destDir == null) throw new Exception("Penyimpanan eksternal tidak tersedia");
            File dest = new File(destDir, APK_UPDATE_FILENAME);
            if (dest.exists()) dest.delete(); // hapus sisa download lama biar gak konflik

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Update GESITS Dashboard");
            request.setDescription("Mengunduh versi terbaru…");
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                APK_UPDATE_FILENAME);
            request.setMimeType("application/vnd.android.package-archive");
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            if (apkDownloadReceiver != null) {
                try { unregisterReceiver(apkDownloadReceiver); } catch (Exception ignored) {}
            }
            apkDownloadId = dm.enqueue(request);

            apkDownloadReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                    if (id != apkDownloadId) return;
                    try { unregisterReceiver(this); } catch (Exception ignored) {}
                    apkDownloadReceiver = null;
                    onApkDownloadFinished(dm, id, dest);
                }
            };
            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(apkDownloadReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(apkDownloadReceiver, filter);
            }

            webView.evaluateJavascript("window.onApkDownloadStarted && window.onApkDownloadStarted()", null);
            startApkProgressPolling(dm, apkDownloadId);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal mulai download update: " + e.getMessage(), Toast.LENGTH_LONG).show();
            webView.evaluateJavascript(
                "window.onApkDownloadFailed && window.onApkDownloadFailed('start')", null);
        }
    }

    // Poll status DownloadManager tiap 400ms selama masih berjalan, kirim
    // persentase ke JS supaya progress bar in-app (di tab Akun) update
    // realtime. DownloadManager gak nyediain broadcast per-progress, jadi
    // ini satu2nya cara selain ContentObserver (polling lebih simpel & udah
    // cukup halus buat progress bar UI).
    private void startApkProgressPolling(DownloadManager dm, long id) {
        stopApkProgressPolling();
        apkProgressRunnable = new Runnable() {
            @Override public void run() {
                if (id != apkDownloadId) return; // download ini udah dibatalkan/diganti
                DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
                boolean keepPolling = false;
                try (Cursor c = dm.query(q)) {
                    if (c != null && c.moveToFirst()) {
                        int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        long downloaded = c.getLong(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = c.getLong(
                            c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        if (total > 0) {
                            int pct = (int) Math.min(100L, downloaded * 100L / total);
                            webView.evaluateJavascript(
                                "window.onApkDownloadProgress && window.onApkDownloadProgress(" + pct + ")", null);
                        }
                        keepPolling = (status == DownloadManager.STATUS_RUNNING
                            || status == DownloadManager.STATUS_PENDING);
                    }
                } catch (Exception ignored) {}
                if (keepPolling) apkProgressHandler.postDelayed(this, 400);
            }
        };
        apkProgressHandler.post(apkProgressRunnable);
    }

    private void stopApkProgressPolling() {
        if (apkProgressRunnable != null) {
            apkProgressHandler.removeCallbacks(apkProgressRunnable);
            apkProgressRunnable = null;
        }
    }

    // Dipanggil dari BroadcastReceiver pas DownloadManager selesai (sukses/gagal)
    private void onApkDownloadFinished(DownloadManager dm, long id, File dest) {
        stopApkProgressPolling();
        boolean success = false;
        DownloadManager.Query q = new DownloadManager.Query().setFilterById(id);
        try (Cursor c = dm.query(q)) {
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                success = (status == DownloadManager.STATUS_SUCCESSFUL);
            }
        } catch (Exception ignored) {}

        if (!success || !dest.exists()) {
            Toast.makeText(this, "Download update gagal, coba lagi.", Toast.LENGTH_LONG).show();
            webView.evaluateJavascript(
                "window.onApkDownloadFailed && window.onApkDownloadFailed('download')", null);
            return;
        }
        webView.evaluateJavascript("window.onApkDownloadComplete && window.onApkDownloadComplete()", null);
        installApk(dest);
    }

    @SuppressWarnings("deprecation")
    private String extractCertSha256Short(PackageInfo pInfo) {
        if (pInfo == null) return "";
        try {
            Signature[] sigs = null;
            if (Build.VERSION.SDK_INT >= 28 && pInfo.signingInfo != null) {
                sigs = pInfo.signingInfo.hasMultipleSigners()
                    ? pInfo.signingInfo.getApkContentsSigners()
                    : pInfo.signingInfo.getSigningCertificateHistory();
                if (sigs == null || sigs.length == 0) {
                    sigs = pInfo.signingInfo.getApkContentsSigners();
                }
            }
            if ((sigs == null || sigs.length == 0) && pInfo.signatures != null) {
                sigs = pInfo.signatures;
            }
            if (sigs == null || sigs.length == 0 || sigs[0] == null) return "";
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(sigs[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(6, dig.length); i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format(Locale.US, "%02X", dig[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void launchApkInstallerIntent(File file) {
        try {
            Uri apkUri = FileProvider.getUriForFile(
                MainActivity.this, "com.gesits.dashboard.fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal buka installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
            webView.evaluateJavascript(
                "window.onApkDownloadFailed && window.onApkDownloadFailed('install')", null);
        }
    }

    // Buka installer APK sistem lewat FileProvider (wajib content:// di Android 7+)
    // Cek kecocokan signature keystore & versionCode dulu agar user tahu penyebab pasti jika "bentrok".
    @SuppressWarnings("deprecation")
    private void installApk(File file) {
        lastDownloadedApkFile = file;
        try {
            PackageManager pm = getPackageManager();
            int flags = (Build.VERSION.SDK_INT >= 28)
                ? (PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_SIGNATURES)
                : PackageManager.GET_SIGNATURES;
            PackageInfo installedInfo = pm.getPackageInfo(getPackageName(), flags);
            PackageInfo apkInfo = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            if (apkInfo == null) {
                Toast.makeText(this, "File APK tidak valid atau unduhan belum lengkap. Coba ulangi.", Toast.LENGTH_LONG).show();
                if (webView != null) {
                    runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onApkDownloadFailed && window.onApkDownloadFailed('install')", null));
                }
                return;
            }
            long curVc = (Build.VERSION.SDK_INT >= 28)
                ? installedInfo.getLongVersionCode()
                : installedInfo.versionCode;
            long apkVc = (Build.VERSION.SDK_INT >= 28)
                ? apkInfo.getLongVersionCode()
                : apkInfo.versionCode;
            String curSig = extractCertSha256Short(installedInfo);
            String apkSig = extractCertSha256Short(apkInfo);
            String conflictReason = "";
            if (!curSig.isEmpty() && !apkSig.isEmpty() && !curSig.equals(apkSig)) {
                conflictReason = "SIGNATURE_MISMATCH";
            } else if (apkVc > 0 && curVc > 0 && apkVc < curVc) {
                conflictReason = "DOWNGRADE";
            }
            if (!conflictReason.isEmpty() && webView != null) {
                final String js = "window.onApkInstallConflict && window.onApkInstallConflict("
                    + JSONObject.quote(conflictReason) + ","
                    + JSONObject.quote(curSig) + ","
                    + JSONObject.quote(apkSig) + ","
                    + curVc + ","
                    + apkVc + ")";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
                return;
            }
        } catch (Exception ignored) {}
        launchApkInstallerIntent(file);
    }

    // Push status koneksi ke widget LANGSUNG dari native code (dipanggil
    // dari callback BluetoothService.DataCallback#onDisconnected/onReconnected
    // di outer class ini) — TIDAK lewat WebView/JS sama sekali. Ini beda
    // dari JsBridge#updateWidgetData yg butuh WebView aktif; method ini
    // jalan gak peduli app lagi foreground/background, krn BluetoothService
    // adalah foreground service yg independen dari lifecycle WebView.
    private void pushWidgetConnectionState(boolean connected) {
        SharedPreferences wPrefs = getSharedPreferences(
            GesitsWidgetProvider.PREFS_NAME, Context.MODE_PRIVATE);
        wPrefs.edit()
            .putBoolean(GesitsWidgetProvider.KEY_CONNECTED, connected)
            .putString(GesitsWidgetProvider.KEY_UPDATED_AT,
                connected ? "Tersambung baru saja" : "Terputus baru saja")
            .apply();
        runOnUiThread(() -> GesitsWidgetProvider.pushUpdate(MainActivity.this));
    }

    // ── AudioTrack tone generator ──
    private void playToneNative(int freqHz, int durationMs) {
        try {
            int sampleRate = 44100;
            int numSamples = sampleRate * durationMs / 1000;

            // Pastikan buffer tidak di bawah minimum hardware
            int minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(numSamples * 2, minBuf);

            short[] samples = new short[numSamples];
            for (int i = 0; i < numSamples; i++) {
                double angle = 2.0 * Math.PI * freqHz * i / sampleRate;
                double env = 1.0;
                if (i < sampleRate * 0.01)
                    env = i / (sampleRate * 0.01);
                else if (i > numSamples - sampleRate * 0.03)
                    env = (numSamples - i) / (sampleRate * 0.03);
                samples[i] = (short)(Math.sin(angle) * env * 0.7 * Short.MAX_VALUE);
            }

            AudioTrack track = new AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
                AudioTrack.MODE_STATIC);
            track.write(samples, 0, numSamples);
            track.play();
            try { Thread.sleep(durationMs + 30); } catch (Exception ignored) {}
            track.stop();
            track.release();
        } catch (Exception e) {
            android.util.Log.e("Chime", "playToneNative error: " + e.getMessage());
        }
    }

    // launchMode singleTop → tap widget saat app udah jalan di background
    // masuk sini (bukan onCreate lagi). Pindah ke tab Home biar user
    // langsung liat dashboard yg datanya sesuai widget.
    // ── Link Google Maps yang dibagikan / diketuk dari app lain (WhatsApp, dll.) ──
    private String pendingMapsLink = null;     // menunggu WebView selesai dimuat
    private boolean webPageLoaded = false;

    /** Ambil link Maps dari intent SEND (teks bagikan) atau VIEW (link diketuk). null = bukan link Maps. */
    private String extractMapsLink(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        String text = null;
        if (Intent.ACTION_SEND.equals(action)) {
            text = intent.getStringExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            text = intent.getData().toString();
        }
        if (text == null) return null;
        if (text.startsWith("geo:")) return text;
        Matcher m = Pattern.compile("https?://\\S+").matcher(text);
        while (m.find()) {
            String u = m.group();
            String low = u.toLowerCase(Locale.US);
            if (low.contains("maps.app.goo.gl") || low.contains("goo.gl/maps") || low.contains("share.google/")
                    || low.matches("https?://(www\\.)?google\\.[a-z.]+/maps.*")
                    || low.matches("https?://maps\\.google\\.[a-z.]+/.*")) {
                return u;
            }
        }
        return null;
    }

    /** Kirim link ke halaman (tab Navigasi + kolom pencarian). Kalau halaman belum siap, ditunda. */
    private void deliverMapsLink(String link) {
        if (link == null) return;
        if (webView == null || !webPageLoaded) { pendingMapsLink = link; return; }
        pendingMapsLink = null;
        webView.evaluateJavascript(
            "window.handleIncomingMapsLink && window.handleIncomingMapsLink(" + JSONObject.quote(link) + ")",
            null);
    }

    private void handleLinkIntent(Intent intent) {
        String link = extractMapsLink(intent);
        if (link != null) {
            deliverMapsLink(link);
            // Kosongkan agar tidak diproses ulang saat app dibuka lagi dari riwayat
            intent.setAction(Intent.ACTION_MAIN);
            intent.setData(null);
            intent.removeExtra(Intent.EXTRA_TEXT);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLinkIntent(intent);
        if ("home".equals(intent.getStringExtra("open_tab")) && webView != null) {
            webView.evaluateJavascript(
                "if(typeof switchTab==='function'){switchTab('home',document.querySelectorAll('.tab')[0]);}",
                null);
        }
    }

    // ══════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Global crash logger — simpan ke file agar bisa dibaca walau app FC
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                java.io.File logFile = new File(getExternalFilesDir(null), "crash_log.txt");
                java.io.FileWriter fw = new java.io.FileWriter(logFile, true);
                fw.write("=== CRASH " + new java.util.Date() + " ===\n");
                fw.write("Thread: " + thread.getName() + "\n");
                java.io.StringWriter sw = new java.io.StringWriter();
                throwable.printStackTrace(new java.io.PrintWriter(sw));
                fw.write(sw.toString() + "\n\n");
                fw.close();
            } catch (Exception ignored) {}
            // Setelah log, tetap crash normal (sistem akan handle)
            System.exit(1);
        });

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // Init TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("id", "ID"));
                if (r == TextToSpeech.LANG_MISSING_DATA ||
                    r == TextToSpeech.LANG_NOT_SUPPORTED)
                    tts.setLanguage(Locale.ENGLISH);
                tts.setSpeechRate(0.95f);
                ttsReady = true;
            }
        });

        // Setup WebView dulu — supaya UI tetap muncul meski service gagal start
        webView    = new KeepAliveWebView(this);   // tetap "terlihat" saat layar mati (lihat KeepAliveWebView.java)
        // GPS native → JS: saat layar mati geolocation WebView ikut terjeda, jadi fix dari NativeMapStreamer diteruskan ke
        // window.onNativeGps() supaya trip, tanjakan, progres rute, dan data AT+NAV tetap hidup.
        NativeMapStreamer.setGpsSink((lat, lng, spd, hasSpd, hdg, hasHdg, acc, alt, hasAlt) -> {
            final String js = "window.onNativeGps&&window.onNativeGps(" + lat + "," + lng + "," + (hasSpd ? spd : 0) + ","
                    + (hasHdg ? hdg : 0) + "," + acc + "," + (hasAlt ? alt : 0) + "," + hasSpd + "," + hasHdg + "," + hasAlt + ")";
            runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(js, null); });
        });
        webViewRef = webView;
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setGeolocationEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBlockNetworkImage(false);
        ws.setBlockNetworkLoads(false);

        WebView.setWebContentsDebuggingEnabled(true); // TEMPORARY ON — buat debug getUserMedia() via chrome://inspect, matikan lagi (false) setelah masalah kamera kelar
        if (Build.VERSION.SDK_INT >= 19) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        // Immersive fullscreen
        webView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        webView.addJavascriptInterface(new JsBridge(), "Android");

        // ══════════════════════════════════════════════
        // Init BMS Jikong BLE manager — callback-nya forward semua event
        // ke JS lewat evaluateJavascript, mirror pola window.onBtXxx yg
        // sudah ada buat motor (onBtData/onBtDisconnected/dst).
        // ══════════════════════════════════════════════
        bmsManager = new BmsBleManager(this, new BmsBleManager.Callback() {
            @Override
            public void onDeviceFound(String name, String mac) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onBmsDeviceFound('" +
                    name.replace("\\", "\\\\").replace("'", "\\'") +
                    "','" + mac + "')", null));
            }

            @Override
            public void onScanDone() {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onBmsScanDone && window.onBmsScanDone()", null));
            }

            @Override
            public void onConnected(String name) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onBmsConnected && window.onBmsConnected('" +
                    name.replace("\\", "\\\\").replace("'", "\\'") + "')", null));
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onBmsDisconnected && window.onBmsDisconnected()", null));
            }

            @Override
            public void onRawData(String hexString) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onBmsRawData && window.onBmsRawData('" + hexString + "')", null));
            }

            @Override
            public void onState(String state, String detail) {
                final String js = "window.onBleState && window.onBleState('bms','" + state + "','" + detail + "')";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            }

            @Override
            public void onLog(String message) {
                android.util.Log.d("GesitsBmsBle", message);
                // Forward jg ke dev log JS di layar — sebelumnya cuma masuk
                // adb logcat yg gak keliatan dari HP tanpa kabel USB.
                final String safe = message.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", " ").replace("\r", "");
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.log && window.log('" + safe + "','err')", null));
            }
        });

        // ══════════════════════════════════════════════
        // Init ESP32 CAN Sniffer BLE manager — pola identik bmsManager di
        // atas, cuma nama callback JS beda (onEsp32Xxx, bukan onBmsXxx).
        // ══════════════════════════════════════════════
        esp32Manager = new Esp32BleManager(this, new Esp32BleManager.Callback() {
            @Override
            public void onDeviceFound(String name, String mac) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onEsp32DeviceFound && window.onEsp32DeviceFound('" +
                    name.replace("\\", "\\\\").replace("'", "\\'") +
                    "','" + mac + "')", null));
            }

            @Override
            public void onScanDone() {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onEsp32ScanDone && window.onEsp32ScanDone()", null));
            }

            @Override
            public void onConnected(String name) {
                esp32Connected = true;
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onEsp32Connected && window.onEsp32Connected('" +
                    name.replace("\\", "\\\\").replace("'", "\\'") + "')", null));
            }

            @Override
            public void onDisconnected() {
                esp32Connected = false;
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onEsp32Disconnected && window.onEsp32Disconnected()", null));
            }

            @Override
            public void onRawData(String hexString) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onEsp32RawData && window.onEsp32RawData('" + hexString + "')", null));
            }

            @Override
            public void onState(String state, String detail) {
                final String js = "window.onBleState && window.onBleState('esp32','" + state + "','" + detail + "')";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            }

            @Override
            public void onLog(String message) {
                android.util.Log.d("GesitsEsp32Ble", message);
                final String safe = message.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", " ").replace("\r", "");
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.log && window.log('" + safe + "','err')", null));
            }
        });

        // ══════════════════════════════════════════════
        // Init ESP32 #2 — BMS ORI / Battery 2
        // UUID BLE sama dengan ESP32 utama, tetapi manager + callback
        // terpisah sehingga telemetry Battery 2 tidak pernah masuk motoData.
        // ══════════════════════════════════════════════
        esp32Battery2Manager = new Esp32Battery2BleManager(this, new Esp32Battery2BleManager.Callback() {
            @Override
            public void onDeviceFound(String name, String mac) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onBattery2Esp32DeviceFound && window.onBattery2Esp32DeviceFound('" +
                    name.replace("\\", "\\\\").replace("'", "\\'") +
                    "','" + mac + "')", null));
            }

            @Override
            public void onScanDone() {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onBattery2Esp32ScanDone && window.onBattery2Esp32ScanDone()", null));
            }

            @Override
            public void onConnected(String name) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onBattery2Esp32Connected && window.onBattery2Esp32Connected('" +
                    name.replace("\\", "\\\\").replace("'", "\\'") + "')", null));
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onBattery2Esp32Disconnected && window.onBattery2Esp32Disconnected()", null));
            }

            @Override
            public void onRawData(String hexString) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onBattery2Esp32RawData && window.onBattery2Esp32RawData('" +
                    hexString + "')", null));
            }

            @Override
            public void onState(String state, String detail) {
                final String js = "window.onBleState && window.onBleState('b2','" + state + "','" + detail + "')";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            }

            @Override
            public void onLog(String message) {
                android.util.Log.d("GesitsEsp32Battery2Ble", message);
                final String safe = message.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", " ").replace("\r", "");
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.log && window.log('" + safe + "','err')", null));
            }
        });

        // ══════════════════════════════════════════════
        // Init Fardriver BLE manager — kontroler motor Fardriver
        // ══════════════════════════════════════════════
        fardriverManager = new FardriverBleManager(this, new FardriverBleManager.Callback() {
            @Override
            public void onDeviceFound(String name, String mac) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onFardriverDeviceFound && window.onFardriverDeviceFound('" +
                    name.replace("\\", "\\\\").replace("'", "\\'") +
                    "','" + mac + "')", null));
            }

            @Override
            public void onScanDone() {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onFardriverScanDone && window.onFardriverScanDone()", null));
            }

            @Override
            public void onConnected(String name) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onFardriverConnected && window.onFardriverConnected('" +
                    name.replace("\\", "\\\\").replace("'", "\\'") + "')", null));
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onFardriverDisconnected && window.onFardriverDisconnected()", null));
            }

            @Override
            public void onRawData(String hexString) {
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.onFardriverRawData && window.onFardriverRawData('" +
                    hexString + "')", null));
            }

            @Override
            public void onState(String state, String detail) {
                final String js = "window.onBleState && window.onBleState('fardriver','" + state + "','" + detail + "')";
                runOnUiThread(() -> webView.evaluateJavascript(js, null));
            }

            @Override
            public void onLog(String message) {
                android.util.Log.d("GesitsFardriverBle", message);
                final String safe = message.replace("\\", "\\\\").replace("'", "\\'")
                    .replace("\n", " ").replace("\r", "");
                runOnUiThread(() -> webView.evaluateJavascript(
                    "window.log && window.log('" + safe + "','err')", null));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(
                    String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }
            @Override
            public boolean onJsAlert(WebView view, String url,
                    String message, android.webkit.JsResult result) {
                result.confirm();
                return true;
            }
            // Motovlog Cam — grant kamera & mic buat getUserMedia() di
            // WebView. Ini CUMA nge-forward permission yang app-nya
            // SENDIRI udah punya (CAMERA/RECORD_AUDIO, diminta di
            // requestBtPermissions()) — kalau app belum granted, request
            // ini tetap ditolak Android di level OS terlepas dari kode ini.
            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest request) {
                runOnUiThread(() -> {
                    java.util.List<String> toGrant = new java.util.ArrayList<>();
                    android.util.Log.d("GesitsPermission", "onPermissionRequest origin=" + request.getOrigin()
                        + " resources=" + java.util.Arrays.toString(request.getResources()));
                    for (String res : request.getResources()) {
                        if (android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)
                                && ContextCompat.checkSelfPermission(MainActivity.this,
                                    Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            toGrant.add(res);
                        } else if (android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)
                                && ContextCompat.checkSelfPermission(MainActivity.this,
                                    Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            toGrant.add(res);
                        }
                    }
                    android.util.Log.d("GesitsPermission", "toGrant=" + toGrant);
                    if (!toGrant.isEmpty()) {
                        request.grant(toGrant.toArray(new String[0]));
                    } else {
                        request.deny();
                    }
                });
            }
            @Override
            public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params) {
                android.util.Log.d("GesitsFileChooser", "onShowFileChooser dipanggil");
                // Batalkan callback lama kalau ada yg belum kepakai (mencegah leak)
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = callback;
                String[] acceptTypes = (params != null) ? params.getAcceptTypes() : null;
                boolean imageOnly = true;
                boolean hasAnyAccept = false;
                if (acceptTypes != null) {
                    for (String t : acceptTypes) {
                        if (t == null) continue;
                        for (String part : t.split(",")) {
                            String s = part.trim().toLowerCase(Locale.US);
                            if (s.isEmpty()) continue;
                            hasAnyAccept = true;
                            if (!s.startsWith("image/")) {
                                imageOnly = false;
                            }
                        }
                    }
                }
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType((hasAnyAccept && imageOnly) ? "image/*" : "*/*");
                String chooserTitle = (hasAnyAccept && imageOnly) ? "Pilih Gambar" : "Pilih File Data";
                try {
                    startActivityForResult(
                        Intent.createChooser(intent, chooserTitle),
                        REQUEST_CODE_FILE_CHOOSER);
                } catch (Exception e) {
                    android.util.Log.e("GesitsFileChooser", "Gagal buka chooser", e);
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // ── WebViewAssetLoader: serve index.html lewat origin https:// ASLI ──
        // (bukan file:///android_asset/...) — ini rekomendasi resmi Google
        // buat load konten lokal di WebView. Alasan kenapa ini WAJIB di sini:
        // getUserMedia() audio ('mic') di WebView Android punya keterbatasan
        // platform yang gak konsisten khusus utk origin file:// (video lolos,
        // audio sering NotReadableError walau izin OS sudah Allow) — sudah
        // dikonfirmasi terjadi di 2 device yg beda total (Pixel 3 & Infinix
        // Smart 8), dan TERBUKTI kode-nya sendiri gak salah karena lancar
        // total (termasuk audio) begitu dideploy ke server https:// beneran.
        // WebViewAssetLoader intercept request ke domain virtual
        // "appassets.androidplatform.net" dan disajikan dari folder assets/
        // lokal, tapi WebView menganggapnya origin https:// yg sah — jadi
        // fix ini TANPA perlu hosting online sungguhan.
        // PENTING: butuh dependency di app/build.gradle:
        //   implementation "androidx.webkit:webkit:1.11.0"
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request != null ? request.getUrl() : null;
                if (u != null && "appassets.androidplatform.net".equals(u.getHost())) {
                    String path = u.getPath();
                    if (path != null && path.startsWith("/molis-fetch/")) {
                        String reqId = Uri.decode(path.substring("/molis-fetch/".length()));
                        byte[] data = fetchedPayloads.remove(reqId);
                        if (data == null) {
                            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                                null, new ByteArrayInputStream(new byte[0]));
                        }
                        return new WebResourceResponse("text/plain", "UTF-8", 200, "OK",
                            null, new ByteArrayInputStream(data));
                    }
                }
                return assetLoader.shouldInterceptRequest(u);
            }
            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                if (failingUrl != null && (failingUrl.startsWith("file://")
                        || failingUrl.startsWith("https://appassets.androidplatform.net"))) {
                    view.loadData(
                        "<body style='font-family:sans-serif;background:#0d1117;" +
                        "color:#fff;display:flex;align-items:center;justify-content:center;" +
                        "height:100vh;margin:0;flex-direction:column;gap:12px'>" +
                        "<div style='font-size:40px'>⚠️</div>" +
                        "<b>index.html tidak ditemukan</b>" +
                        "<p style='color:#8b949e;font-size:13px;text-align:center'>" +
                        "Taruh file di <code>app/src/main/assets/</code></p>" +
                        "</body>", "text/html", "UTF-8");
                }
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                webPageLoaded = true;
                if (pendingMapsLink != null) {
                    final String lk = pendingMapsLink;
                    view.postDelayed(() -> deliverMapsLink(lk), 900); // beri waktu JS selesai inisialisasi
                }
                view.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

                // Setelah WebView benar-benar siap, sambungkan kembali ESP32 #2
                // yang terakhir dipilih. Callback telemetry tidak akan hilang
                // karena fungsi JS-nya sudah terdefinisi pada tahap ini.
                String savedBattery2Mac = prefs.getString(KEY_BATT2_MAC, "");
                if (!savedBattery2Mac.isEmpty() &&
                    esp32Battery2Manager != null && !esp32Battery2Manager.isConnected()) {
                    final String mac = savedBattery2Mac;
                    view.postDelayed(() -> {
                        if (esp32Battery2Manager != null && !esp32Battery2Manager.isConnected()) {
                            esp32Battery2Manager.connect(mac);
                        }
                    }, 500);
                }

                // Sama seperti B2 di atas, tapi buat ESP32 utama (CAN sniffer/matrix) —
                // sebelumnya gak ada blok ini sama sekali, jadi tiap BLE putus,
                // satu-satunya cara nyambung lagi adalah scan+pilih manual dari
                // daftar. Sekarang otomatis coba nyambung ke MAC terakhir juga.
                String savedEsp32Mac = prefs.getString(KEY_ESP32_MAC, "");
                if (!savedEsp32Mac.isEmpty() &&
                    esp32Manager != null && !esp32Connected) {
                    final String mac2 = savedEsp32Mac;
                    view.postDelayed(() -> {
                        if (esp32Manager != null && !esp32Connected) {
                            esp32Manager.connect(mac2);
                        }
                    }, 700); // digeser dikit dari B2 (500ms) biar gak baku rebut radio BLE bareng
                }
            }
        });

        // App dibuka dari link Maps / menu Share → simpan, kirim ke halaman setelah selesai dimuat
        if ((getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            pendingMapsLink = extractMapsLink(getIntent());
            if (pendingMapsLink != null) {
                getIntent().setAction(Intent.ACTION_MAIN);
                getIntent().setData(null);
                getIntent().removeExtra(Intent.EXTRA_TEXT);
            }
        }
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        requestBtPermissions();

        // Android 14+ (targetSdk 34) mewajibkan minimal satu izin connectedDevice
        // (BLUETOOTH_CONNECT / BLUETOOTH_SCAN) sudah granted sebelum memanggil
        // startForegroundService untuk tipe connectedDevice; kalau belum, tunggu
        // hasil dialog izin di onRequestPermissionsResult / onResume.
        if (hasConnectedDevicePermission()) {
            startBluetoothServiceSafely();
        }
    }

    private static final int REQUEST_CODE_INIT_PERMS = 1;

    private boolean hasConnectedDevicePermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED
            || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
    }

    private void startBluetoothServiceSafely() {
        if (serviceBound || serviceBinding) return;
        if (Build.VERSION.SDK_INT >= 34 && !hasConnectedDevicePermission()) {
            android.util.Log.w("MainActivity",
                "Menunggu izin Bluetooth sebelum startForeground BluetoothService");
            return;
        }
        try {
            Intent svcIntent = new Intent(this, BluetoothService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svcIntent);
            } else {
                startService(svcIntent);
            }
            serviceBinding = bindService(svcIntent, serviceConn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            serviceBinding = false;
            android.util.Log.e("MainActivity",
                "Gagal start BluetoothService: " + e.getMessage());
            // App tetap jalan, hanya fitur Bluetooth yang tidak aktif
            runOnUiThread(() -> Toast.makeText(this,
                "Bluetooth service gagal start. Restart app jika perlu.",
                Toast.LENGTH_LONG).show());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (webView == null) return;
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            webView.evaluateJavascript(
                "window.onNativeOrientationChange &&" +
                "window.onNativeOrientationChange('landscape')", null);
        } else {
            webView.evaluateJavascript(
                "window.onNativeOrientationChange &&" +
                "window.onNativeOrientationChange('portrait')", null);
        }
    }

    private void requestBtPermissions() {
        List<String> needed = new ArrayList<>();

        // Bluetooth permissions
        if (Build.VERSION.SDK_INT >= 31) {
            if (ContextCompat.checkSelfPermission(this,
                    "android.permission.BLUETOOTH_CONNECT")
                    != PackageManager.PERMISSION_GRANTED)
                needed.add("android.permission.BLUETOOTH_CONNECT");
            if (ContextCompat.checkSelfPermission(this,
                    "android.permission.BLUETOOTH_SCAN")
                    != PackageManager.PERMISSION_GRANTED)
                needed.add("android.permission.BLUETOOTH_SCAN");
        }

        // Lokasi (wajib untuk BT Classic)
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);

        // Notifikasi Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this,
                    "android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED)
                needed.add("android.permission.POST_NOTIFICATIONS");
        }

        // Kamera & mic — dibutuhkan Motovlog Cam (getUserMedia di WebView).
        // Runtime permission ini WAJIB ADA duluan sebelum WebView bisa
        // grant getUserMedia lewat onPermissionRequest() di bawah — kalau
        // app-nya sendiri belum punya izin CAMERA/RECORD_AUDIO dari OS,
        // grant di level WebView bakal percuma (tetap gagal).
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CAMERA);
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO);

        if (!needed.isEmpty())
            ActivityCompat.requestPermissions(this,
                needed.toArray(new String[0]), REQUEST_CODE_INIT_PERMS);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && webView != null) {
            webView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent i = new Intent(this, ScreenCaptureService.class)
                    .setAction(ScreenCaptureService.ACTION_START)
                    .putExtra(ScreenCaptureService.EXTRA_CODE, resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_DATA, data)
                    .putExtra(ScreenCaptureService.EXTRA_CROP, mirrorCrop);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            } else if (webView != null) {
                webView.evaluateJavascript("window.onMirrorStopped&&window.onMirrorStopped()", null);
            }
            return;
        }
        if (requestCode != REQUEST_CODE_FILE_CHOOSER) return;
        if (filePathCallback == null) return;

        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                results = new Uri[]{ uri };
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    // Hasil dari requestMicPermission() (dipanggil JS pas Motovlog Cam gagal
    // buka mic) — kalau user akhirnya Allow, kabari JS biar retry ambil
    // audio track TANPA perlu user tutup-buka ulang Motovlog Cam / restart app.
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_INIT_PERMS) {
            if (!serviceBound && !serviceBinding && hasConnectedDevicePermission()) {
                startBluetoothServiceSafely();
            }
            return;
        }
        if (requestCode == REQUEST_CODE_MIC_PERMISSION) {
            boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (webView != null) {
                webView.evaluateJavascript(
                    "window.onMicPermissionResult && window.onMicPermissionResult("
                        + granted + ")", null);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Selama layar Live Streaming terbuka, WebView harus tetap jalan: data HUD datang dari JavaScript-nya.
        if (webView != null) {
            // Mirror layar aktif: WebView juga harus jalan walau Maps ada di depan (frame datang lewat JS).
            // Peta native jalan: WebView tetap hidup supaya reroute, AT+NAV, dan info tanjakan (semuanya di JS) tidak ikut berhenti.
            // Kalau ingin hemat baterai, hapus kondisi NativeMapStreamer.isRunning() di bawah (peta tetap jalan, tapi JS berhenti).
            boolean keep = LiveHudBus.liveActive || ScreenCaptureService.mirrorActive || keepAliveMatrixMaps || NativeMapStreamer.isRunning();
            // forceVisible: begitu layar mati Android menandai window GONE → Chromium menjeda geolocation & meredam timer WebView.
            // Dengan flag ini window tetap dilaporkan VISIBLE ke WebView sehingga JS (AT+NAV, tanjakan, reroute) tetap jalan.
            KeepAliveWebView.forceVisible = keep;
            if (keep) webView.resumeTimers();
            else webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        KeepAliveWebView.forceVisible = false;    // Activity di depan lagi: visibility normal
        if (webView != null) webView.onResume();
        if (!serviceBound && !serviceBinding && hasConnectedDevicePermission()) {
            startBluetoothServiceSafely();
        }
    }

    @Override
    protected void onDestroy() {
        if (esp32WifiBound) {
            applyEsp32WifiBinding(false);
        }
        NativeMapStreamer.setGpsSink(null);
        webViewRef = null;
        fetchedPayloads.clear();
        stopApkProgressPolling();
        stopNativeMicCaptureInternal(); // pastikan AudioRecord ke-release, jangan sampe mic "kekunci"
        if (videoWriteStream != null) {
            try { videoWriteStream.close(); } catch (Exception ignored) {}
            videoWriteStream = null;
        }
        if (videoWriteUri != null) {
            // App ke-kill/ditutup di tengah proses simpan — buang entry MediaStore
            // yang setengah-jadi (masih IS_PENDING) biar gak nyampah di galeri.
            try { getContentResolver().delete(videoWriteUri, null, null); } catch (Exception ignored) {}
            videoWriteUri = null;
        }
        if (apkDownloadReceiver != null) {
            try { unregisterReceiver(apkDownloadReceiver); } catch (Exception ignored) {}
            apkDownloadReceiver = null;
        }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        if (bmsManager != null) { bmsManager.disconnect(); bmsManager = null; }
        if (esp32Manager != null) { esp32Manager.disconnect(); esp32Manager = null; }
        if (esp32Battery2Manager != null) { esp32Battery2Manager.disconnect(); esp32Battery2Manager = null; }
        if (fardriverManager != null) { fardriverManager.disconnect(); fardriverManager = null; }
        if (serviceBound) {
            unbindService(serviceConn);
            serviceBound = false;
        }
        serviceBinding = false;
        super.onDestroy();
    }

} // ← TUTUP MainActivity
