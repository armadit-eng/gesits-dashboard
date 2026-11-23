package com.gesits.dashboard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class BluetoothService extends Service {

    private static final String TAG        = "GesitsBT";
    private static final UUID   SPP_UUID   =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String CHANNEL_ID = "gesits_bt";
    private static final int    NOTIF_ID   = 1;

    // Bluetooth
    private BluetoothSocket  btSocket;
    private InputStream      btIn;
    private OutputStream     btOut;
    private Thread           readThread;
    private volatile boolean running         = false;

    // Reconnect
    private String           lastMac         = null;
    private volatile boolean shouldReconnect = false;
    private Thread           reconnectThread = null;
    private int              reconnectDelay  = 4000;

    // WakeLock — jaga CPU tetap jalan saat layar mati
    private PowerManager.WakeLock wakeLock;

    // Binder
    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        BluetoothService getService() { return BluetoothService.this; }
    }

    // Callback ke MainActivity
    public interface DataCallback {
        void onData(String line);
        void onDisconnected();
        void onReconnected();
    }
    private DataCallback callback;
    public void setCallback(DataCallback cb) { this.callback = cb; }

    // ══════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        // Cegah crash dari thread background BluetoothService
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            android.util.Log.e(TAG, "Uncaught exception in thread "
                + thread.getName() + ": " + throwable.getMessage());
            // Jangan re-throw — biarkan service tetap jalan
        });

        // WakeLock
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "GesitsDashboard:BluetoothWakeLock");
        wakeLock.setReferenceCounted(false);
    }

    // Action string buat tombol "Tutup Koneksi" di notifikasi — user bisa stop
    // total service ini (gak jalan lagi di background) langsung dari notifikasi,
    // tanpa perlu buka app dulu. Ini jawaban buat "gimana cara tutup app biar
    // gak jalan di belakang layar" — pilihannya: notifikasi utama (tap = buka
    // app, service tetap jalan di background) ATAU tombol ini (stop total).
    public static final String ACTION_STOP_SERVICE = "com.gesits.dashboard.ACTION_STOP_SERVICE";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // User pencet tombol "Tutup Koneksi" di notifikasi — matiin semuanya
        // total, JANGAN restart (makanya return START_NOT_STICKY, bukan
        // START_STICKY yang dipakai buat kondisi normal di bawah).
        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            Log.d(TAG, "Stop diminta dari notifikasi — matiin service total");
            shouldReconnect = false;
            if (reconnectThread != null) reconnectThread.interrupt();
            stopBgPolling();
            closeSocket();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            // Android 14+ wajib panggil startForeground dengan serviceType
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, buildNotification("Menunggu koneksi..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, buildNotification("Menunggu koneksi..."));
            }
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed: " + e.getMessage());
            // Jangan crash — service tetap jalan tanpa foreground notification
            // (kemungkinan akan di-kill OS lebih cepat, tapi app tidak FC)
        }

        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(12 * 60 * 60 * 1000L);
            }
        } catch (Exception e) {
            Log.e(TAG, "WakeLock acquire failed: " + e.getMessage());
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onDestroy() {
        shouldReconnect = false;
        // Sama kayak fix di disconnect() — tanpa ini, reconnectThread yang lagi
        // sleep() bisa terus jalan sampai 30 detik SETELAH service ini "destroyed",
        // padahal harusnya berhenti total begitu app ditutup paksa.
        if (reconnectThread != null) {
            reconnectThread.interrupt();
        }
        closeSocket();
        // Lepas WakeLock
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    // ══════════════════════════════════════
    // CONNECT
    // ══════════════════════════════════════

    public void connect(final String mac) {
        lastMac         = mac;
        shouldReconnect = true;
        reconnectDelay  = 4000;
        doConnect(mac, false);
    }

    private void doConnect(final String mac, final boolean isReconnect) {
        new Thread(() -> {
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null || !adapter.isEnabled()) {
                    Log.e(TAG, "Bluetooth tidak aktif");
                    onConnectFailed(isReconnect);
                    return;
                }

                closeSocket();
                adapter.cancelDiscovery();

                BluetoothDevice device = adapter.getRemoteDevice(mac);

                // ── PAKSA BLUETOOTH CLASSIC SPP ──
                // createRfcommSocketToServiceRecord kadang dialihkan ke BLE
                // oleh Android. Pakai reflection untuk paksa channel Classic.
                BluetoothSocket socket = null;

                // Metode 1: createRfcommSocketToServiceRecord (standar SPP)
                try {
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    socket.connect();
                    Log.d(TAG, "Connected via SPP UUID");
                } catch (IOException e1) {
                    Log.w(TAG, "SPP UUID failed, trying reflection: " + e1.getMessage());
                    try { socket.close(); } catch (Exception ignored) {}

                    // Metode 2: createRfcommSocket via reflection (paksa channel 1)
                    // Ini bypass mekanisme SDP sehingga langsung pakai Classic RFCOMM
                    try {
                        java.lang.reflect.Method m = device.getClass()
                            .getMethod("createRfcommSocket", new Class[]{int.class});
                        socket = (BluetoothSocket) m.invoke(device, 1);
                        socket.connect();
                        Log.d(TAG, "Connected via reflection channel 1");
                    } catch (Exception e2) {
                        Log.e(TAG, "Reflection connect failed: " + e2.getMessage());
                        throw new IOException("Both connect methods failed");
                    }
                }

                btSocket = socket;
                btIn  = btSocket.getInputStream();
                btOut = btSocket.getOutputStream();
                startWriter();

                reconnectDelay = 4000;
                updateNotification("Terhubung ke " + device.getName());
                Log.d(TAG, (isReconnect ? "Re" : "") + "Connected to " + mac);

                if (isReconnect && callback != null) {
                    new Handler(Looper.getMainLooper())
                        .post(() -> callback.onReconnected());
                }

                startBgPolling(); // mulai polling Java — jalan terus meski layar mati
                startReadLoop(mac);

            } catch (IOException e) {
                Log.e(TAG, "Connect failed: " + e.getMessage());
                closeSocket();
                onConnectFailed(isReconnect);
            }
        }).start();
    }

    private void onConnectFailed(boolean isReconnect) {
        updateNotification("Koneksi gagal — menunggu...");
        if (isReconnect && callback != null) {
            // Sudah notify disconnect sebelumnya, tidak perlu dobel
        } else if (!isReconnect && callback != null) {
            new Handler(Looper.getMainLooper())
                .post(() -> callback.onDisconnected());
        }
        if (shouldReconnect) startReconnectLoop();
    }

    // ══════════════════════════════════════
    // READ LOOP
    // ══════════════════════════════════════

    private void startReadLoop(final String mac) {
        running    = true;
        readThread = new Thread(() -> {
            BufferedReader reader =
                new BufferedReader(new InputStreamReader(btIn));
            try {
                String line;
                while (running && (line = reader.readLine()) != null) {
                    final String data = line.trim();
                    if (!data.isEmpty() && callback != null) {
                        new Handler(Looper.getMainLooper())
                            .post(() -> callback.onData(data));
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Read error: " + e.getMessage());
            } finally {
                closeSocket();
                updateNotification("Koneksi terputus");
                if (callback != null) {
                    new Handler(Looper.getMainLooper())
                        .post(() -> callback.onDisconnected());
                }
                if (shouldReconnect && lastMac != null) {
                    startReconnectLoop();
                }
            }
        });
        readThread.start();
    }

    // ══════════════════════════════════════
    // AUTO-RECONNECT LOOP
    // Coba terus tiap N detik (naik bertahap
    // hingga maks 30 detik) sampai berhasil
    // ══════════════════════════════════════

    private void startReconnectLoop() {
        if (reconnectThread != null && reconnectThread.isAlive()) return;

        reconnectThread = new Thread(() -> {
            int attempt = 0;
            while (shouldReconnect && lastMac != null) {
                attempt++;
                updateNotification("Mencoba ulang #" + attempt + "...");
                Log.d(TAG, "Reconnect attempt #" + attempt);

                try { Thread.sleep(reconnectDelay); }
                catch (InterruptedException e) { break; }

                if (!shouldReconnect) break;

                // Naikan delay bertahap: 4s → 8s → 12s → maks 30s
                reconnectDelay = Math.min(reconnectDelay + 4000, 30000);

                try {
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter == null || !adapter.isEnabled()) continue;

                    closeSocket();
                    adapter.cancelDiscovery();

                    BluetoothDevice device = adapter.getRemoteDevice(lastMac);
                    BluetoothSocket socket = null;

                    // Paksa Classic SPP — coba UUID dulu, fallback reflection
                    try {
                        socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                        socket.connect();
                    } catch (IOException re) {
                        try { if(socket!=null) socket.close(); } catch(Exception ignored){}
                        java.lang.reflect.Method m = device.getClass()
                            .getMethod("createRfcommSocket", new Class[]{int.class});
                        socket = (BluetoothSocket) m.invoke(device, 1);
                        socket.connect();
                    }

                    btSocket = socket;
                    btIn  = btSocket.getInputStream();
                    btOut = btSocket.getOutputStream();
                    startWriter();

                    reconnectDelay = 4000; // reset
                    updateNotification("Tersambung kembali!");
                    Log.d(TAG, "Reconnected on attempt #" + attempt);

                    if (callback != null) {
                        new Handler(Looper.getMainLooper())
                            .post(() -> callback.onReconnected());
                    }

                    startBgPolling();
                    startReadLoop(lastMac);
                    return;

                } catch (Exception e) {
                    Log.e(TAG, "Reconnect #" + attempt + " failed: " + e.getMessage());
                    closeSocket();
                }
            }
        });
        reconnectThread.start();
    }

    // ══════════════════════════════════════
    // SEND — SATU WRITER THREAD BUAT SEMUA
    // ══════════════════════════════════════
    // SEBELUMNYA: send() bikin Thread BARU tiap dipanggil (dari JS, lewat
    // MainActivity.send()), DAN startBgPolling() punya thread SENDIRI yang
    // jalan independen tiap 500ms nulis ke btOut YANG SAMA — dua-duanya
    // TANPA sinkronisasi sama sekali. Command dari JS & polling internal
    // Java ini bisa nabrak/interleave di level byte kapan aja, dan itu
    // SEPENUHNYA gak keliatan/gak kesentuh oleh fix antrian di JS (JS cuma
    // bisa ngatur urutan panggilan dari sisi JS-nya sendiri, gak bisa
    // ngatur race condition yang terjadi di antara thread-thread Java ini).
    //
    // FIX: semua command (dari JS via send(), maupun dari bgPollThread
    // internal via sendRaw()) sekarang cuma nge-antri ke SATU BlockingQueue.
    // Ada SATU writer thread yang nulis ke btOut satu-satu secara berurutan
    // — gak mungkin lagi ada dua tulisan yang overlap di socket yang sama,
    // dari sumber manapun.
    private final BlockingQueue<String> writeQueue = new LinkedBlockingQueue<>();
    private Thread  writerThread  = null;
    private volatile boolean writerRunning = false;
    private static final long WRITE_MIN_GAP_MS = 30; // jeda fisik antar tulisan byte
    private volatile boolean mapsWriting = false;   // lagi menulis frame AT+MAPS ke socket
    private volatile long lastMapsWriteMs = 0;      // durasi tulis frame MAPS terakhir
    private volatile long lastJsSendAt = 0;         // terakhir JS (foreground) kirim command

    private void startWriter() {
        stopWriter();
        writerRunning = true;
        writeQueue.clear();
        writerThread = new Thread(() -> {
            long lastWriteAt = 0;
            while (writerRunning) {
                String cmd;
                try {
                    cmd = writeQueue.take(); // blok sampai ada command masuk
                } catch (InterruptedException e) {
                    break;
                }
                if (btOut == null) continue;
                long wait = WRITE_MIN_GAP_MS - (System.currentTimeMillis() - lastWriteAt);
                if (wait > 0) {
                    try { Thread.sleep(wait); } catch (InterruptedException e) { break; }
                }
                final boolean isMaps = cmd.startsWith("AT+MAPS=");
                final long t0 = System.currentTimeMillis();
                if (isMaps) mapsWriting = true;
                try {
                    String full = cmd.endsWith("\r\n") ? cmd : cmd + "\r\n";
                    btOut.write(full.getBytes());   // satu kali tulis: MID tidak mengenali frame yang dipotong
                    btOut.flush();
                } catch (IOException e) {
                    Log.e(TAG, "Write failed: " + e.getMessage());
                } finally {
                    if (isMaps) { lastMapsWriteMs = System.currentTimeMillis() - t0; mapsWriting = false; }
                }
                lastWriteAt = System.currentTimeMillis();
                BleCoex.markClassicWrite();   // beri tahu manager BLE bahwa jalur Classic sedang sibuk
            }
        });
        writerThread.start();
        Log.d(TAG, "Writer thread started — semua kirim sekarang serial");
    }

    private void stopWriter() {
        writerRunning = false;
        if (writerThread != null) {
            writerThread.interrupt();
            writerThread = null;
        }
        writeQueue.clear();
    }

    public void send(final String command) {
        if (btOut == null) return;
        long now = System.currentTimeMillis();
        // lastJsSendAt dipakai bgPoll untuk tahu "JS foreground masih hidup". Dulu SEMUA send() menyetelnya,
        // termasuk frame AT+MAPS dari ScreenCaptureService (tiap 1,5 dtk) -> saat mirror aktif bgPoll tidak pernah
        // jalan walau WebView terredam/berhenti. Sekarang hanya query polling (AT+XXX?) dari JS yang dihitung.
        if (command.indexOf('?') > 0 && !command.startsWith("AT+MAPS=")) lastJsSendAt = now;
        if (command.startsWith("AT+NAV=")) lastJsNavAt = now;   // JS masih memutar AT+NAV sendiri
        enqueue(command);
    }

    // "Yang terbaru menang": frame AT+MAPS / AT+NAV / AT+TRIP yang masih antre
    // dibuang saat yang baru masuk, begitu juga query polling duplikat. Jadi
    // antrian tidak pernah menumpuk data basi (peta ketinggalan).
    private void enqueue(String command) {
        String key = null;
        if (command.startsWith("AT+MAPS=") || command.startsWith("AT+NAV=") || command.startsWith("AT+TRIP=")) {
            key = command.substring(0, command.indexOf('=') + 1);
        } else if (command.indexOf('?') > 0 && command.length() < 24) {
            key = command;
        }
        if (key != null) {
            final String k = key;
            writeQueue.removeIf(c -> c.startsWith(k));
        }
        writeQueue.offer(command);
    }

    // "jumlahMapsAntre,durasiTulisMapsTerakhirMs" — dibaca JS sebelum capture peta
    public String mapsStatus() {
        int n = mapsWriting ? 1 : 0;
        for (String c : writeQueue) if (c.startsWith("AT+MAPS=")) n++;
        return n + "," + lastMapsWriteMs;
    }

    // Kirim dari thread manapun (dulu sync langsung ke socket, sekarang
    // sama-sama lewat antrian yang sama kayak send() — satu jalur, satu
    // writer, gak ada lagi jalur "pintas" yang bisa nabrak jalur lain)
    private void sendRaw(String command) {
        if (btOut == null) return;
        enqueue(command);
    }

    // ══════════════════════════════════════
    // BACKGROUND POLLING
    // Jalan di Java thread — tidak terpengaruh layar mati
    // Kirim AT+SPD, AT+SOC, AT+NAV tiap 500ms
    // AT+MAPS tidak bisa dikirim dari sini karena butuh canvas JS
    // tapi data posisi GPS tetap di-update via AT+NAV
    // ══════════════════════════════════════

    private Thread  bgPollThread   = null;
    private volatile boolean bgPollRunning = false;

    // Data NAV terkini — di-update dari JS via setNavData()
    private volatile String lastNavData = null;
    // Teks AT+NAV yang SAMA jangan dikirim ulang terlalu sering: MID kemungkinan memutar ulang animasi/gulir teks dari awal
    // tiap AT+NAV masuk, sehingga kalau dikirim tiap ~3 dtk hanya 1–2 karakter pertama yang sempat terlihat ("t").
    private static final long NAV_SAME_RESEND_MS = 15000;
    private volatile long lastNavResendAt = 0;

    public void setNavData(String navData) {
        if (navData == null || !navData.equals(this.lastNavData)) lastNavResendAt = 0;   // teks baru → boleh kirim segera
        this.lastNavData = navData;
    }

    // Dua tingkat: cepat (akurasi energi/efisiensi) & lambat (jarang berubah)
    private static final String[] BG_FAST_CMDS = {
        "AT+SPD?\r\n", "AT+VBAT?\r\n", "AT+IBAT?\r\n",   // AT+PWR? dibuang: tidak dikenali MID
    };
    private static final String[] BG_SLOW_CMDS = {
        "AT+SOC?\r\n", "AT+ODO?\r\n", "AT+TINV?\r\n", "AT+TMOT?\r\n", "AT+TBAT?\r\n",
    };
    private int bgPollIdx = 0;
    private int bgSlowIdx = 0;

    private void startBgPolling() {
        stopBgPolling();
        bgPollRunning = true;
        bgPollThread  = new Thread(() -> {
            while (bgPollRunning && running) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    break;
                }
                if (!bgPollRunning || !running || btOut == null) continue;

                // JS foreground masih aktif kirim polling sendiri -> jangan dobel
                if (System.currentTimeMillis() - lastJsSendAt < 2000) continue;

                // Tingkat cepat: tiap perintah ~1 detik sekali
                sendRaw(BG_FAST_CMDS[bgPollIdx % BG_FAST_CMDS.length]);
                bgPollIdx++;

                // Tingkat lambat: 1 perintah tiap 3 detik, bergilir
                if (bgPollIdx % 12 == 0) {
                    sendRaw(BG_SLOW_CMDS[bgSlowIdx++ % BG_SLOW_CMDS.length]);
                }
                // Setiap 3 detik kirim AT+NAV dengan data terakhir dari JS
                if (bgPollIdx % 12 == 6 && lastNavData != null && navItems.length == 0
                        && System.currentTimeMillis() - lastNavResendAt >= NAV_SAME_RESEND_MS) {
                    lastNavResendAt = System.currentTimeMillis();
                    sendRaw("AT+NAV=\"" + lastNavData + "\"\r\n");
                }
            }
        });
        bgPollThread.start();
        Log.d(TAG, "Background polling started");
        startNavRotation();
    }

    private void stopBgPolling() {
        stopNavRotation();
        bgPollRunning = false;
        if (bgPollThread != null) {
            bgPollThread.interrupt();
            bgPollThread = null;
        }
    }

    // ══════════════════════════════════════
    // ROTASI AT+NAV NATIVE
    // JS (WebView) memutar AT+NAV sendiri saat dashboard aktif. Begitu layar dikunci, WebView dijeda atau
    // timernya diredam Chromium sehingga MID macet di teks terakhir. JS mengirim daftar item lewat setNavItems();
    // kalau JS berhenti mengirim AT+NAV, thread ini yang memutar daftar itu (plus info Maps live saat mirror aktif).
    // ══════════════════════════════════════
    private volatile String[] navItems = new String[0];
    private volatile int navRotateMs = 5000;
    private volatile long lastJsNavAt = 0;
    private int navRotIdx = 0;
    private Thread navRotThread = null;
    private volatile boolean navRotRunning = false;

    public void setNavItems(String[] items, int rotateSec) {
        java.util.ArrayList<String> clean = new java.util.ArrayList<>();
        if (items != null) for (String it : items) {
            String l = padNav20(asciiNav(it));
            if (!l.trim().isEmpty()) clean.add(l);
        }
        navRotateMs = Math.max(2, Math.min(60, rotateSec)) * 1000;
        navItems = clean.toArray(new String[0]);
    }

    private static String asciiNav(String s) {
        String d = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD);
        return d.replaceAll("\\p{M}+", "").replaceAll("[^\\x20-\\x7E]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String padNav20(String s) {
        if (s.length() > 20) s = s.substring(0, 20).trim();
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < 20) sb.append(' ');
        return sb.toString();
    }

    /** Info Google Maps live (arah + ETA) saat mirror aktif; dibaca langsung dari ScreenCaptureService. */
    private java.util.List<String> liveMapsLines() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (!ScreenCaptureService.mirrorActive) return out;
        if (System.currentTimeMillis() - ScreenCaptureService.navUpdatedMs > 120000) return out;
        String d = ScreenCaptureService.navDist, i = ScreenCaptureService.navInstr, e = ScreenCaptureService.navEta;
        String l1 = asciiNav((d == null || d.isEmpty() ? "" : d + " ") + (i == null ? "" : i));
        if (!l1.isEmpty()) out.add(padNav20(l1));
        String l2 = asciiNav(e);
        if (!l2.isEmpty()) out.add(padNav20(l2));
        return out;
    }

    private String nextNavLine() {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(liveMapsLines());
        for (String s : navItems) all.add(s);
        if (all.isEmpty()) return null;
        return all.get(Math.abs(navRotIdx++) % all.size());
    }

    private void startNavRotation() {
        stopNavRotation();
        navRotRunning = true;
        navRotThread = new Thread(() -> {
            long lastSentAt = 0;
            while (navRotRunning && running) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                if (!navRotRunning || !running || btOut == null) continue;
                long now = System.currentTimeMillis();
                int period = Math.max(4000, navRotateMs);      // beri MID waktu menyelesaikan animasi teks sebelum ganti
                // JS masih memutar AT+NAV sendiri -> biarkan (hindari dobel)
                if (now - lastJsNavAt < period * 2L + 1500) continue;
                if (now - lastSentAt < period) continue;
                String line = nextNavLine();
                if (line == null) continue;
                Log.d(TAG, "NAV native -> [" + line.trim() + "]");
                sendRaw("AT+NAV=\"" + line + "\"\r\n");
                lastSentAt = now;
            }
        });
        navRotThread.setDaemon(true);
        navRotThread.start();
    }

    private void stopNavRotation() {
        navRotRunning = false;
        if (navRotThread != null) {
            navRotThread.interrupt();
            navRotThread = null;
        }
    }

    // ══════════════════════════════════════
    // DISCONNECT (manual)
    // ══════════════════════════════════════

    public void disconnect() {
        shouldReconnect = false;
        lastMac         = null;
        running         = false;

        // ═══ FIX: "nunggu terus, gak bisa dibatalin" ═══
        // Loop reconnect di startReconnectLoop() pakai Thread.sleep(reconnectDelay)
        // yang bisa sampai 30 detik, dan cuma ngecek `shouldReconnect` SEBELUM/
        // SESUDAH sleep itu — bukan SELAMA sleep. Jadi sebelumnya, matiin flag
        // `shouldReconnect` doang gak langsung ngebangunin thread yang lagi
        // "tidur" di tengah delay; user kudu nunggu sampai delay itu abis
        // sendiri (bisa sampai 30 detik) baru koneksi bener2 berhenti nyoba.
        // interrupt() di sini nge-throw InterruptedException DI DALAM sleep()
        // itu juga, jadi loop-nya langsung break saat itu juga — user pencet
        // batal = langsung berhenti, gak perlu nunggu delay abis.
        if (reconnectThread != null) {
            reconnectThread.interrupt();
        }

        stopBgPolling();
        closeSocket();
        updateNotification("Terputus");
    }

    // Hapus MAC tersimpan (reset total)
    public void forgetDevice() {
        disconnect();
    }

    public boolean isConnected() {
        return btSocket != null && btSocket.isConnected() && running;
    }

    public String getLastMac() { return lastMac; }

    // ══════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════

    private void closeSocket() {
        running = false;
        stopWriter();
        try { if (btSocket != null) btSocket.close(); } catch (IOException ignored) {}
        btSocket = null; btIn = null; btOut = null;
    }

    // ══════════════════════════════════════
    // NOTIFICATION
    // ══════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "GESITS Bluetooth",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Koneksi Bluetooth motor GESITS");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class)
                .createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int flags = Build.VERSION.SDK_INT >= 23
            ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, flags);

        // Tombol aksi "Tutup Koneksi" — beda dari tap notifikasi utama (yang
        // cuma buka app, service tetap jalan di background). Ini beneran
        // matiin service + lepas WakeLock + hapus notifikasi, jadi user
        // punya 2 pilihan yang jelas: biarin jalan di background (biasa aja,
        // gak ngapa2in) atau tutup paksa lewat tombol ini.
        Intent stopIntent = new Intent(this, BluetoothService.class);
        stopIntent.setAction(ACTION_STOP_SERVICE);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GESITS Dashboard")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tutup Koneksi", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }
}
