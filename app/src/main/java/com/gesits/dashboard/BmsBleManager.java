package com.gesits.dashboard;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

/**
 * BLE GATT client khusus BMS Jikong (JK-BMS).
 *
 * SENGAJA dibuat terpisah total dari BluetoothService.java (yang menangani
 * koneksi ke motor GESITS lewat Bluetooth Classic / RFCOMM). BMS Jikong
 * pakai BLE GATT — protokol beda, lifecycle beda, jadi lebih aman & lebih
 * gampang di-maintain kalau dipisah drpd dipaksa nempel di BluetoothService
 * yang sudah ada.
 *
 * Class ini TIDAK melakukan parsing byte JK-BMS sama sekali — itu sengaja,
 * biar logic parsing cukup ada SATU tempat (di JS/index.html, yang sudah
 * teruji jalan lewat Web Bluetooth). Class ini cuma jembatan mentah:
 * scan -> connect -> notify -> lempar raw bytes (hex string) ke JS,
 * dan terima command mentah (hex string) dari JS buat ditulis ke BLE.
 */
public class BmsBleManager {

    private static final String TAG = "GesitsBmsBle";

    // Sama persis dengan JK_SERVICE_UUID/JK_CHAR_NOTIFY_UUID/JK_CHAR_WRITE_UUID
    // di index.html — kalau salah satu diubah, ubah juga yang satunya.
    private static final UUID JK_SERVICE_UUID =
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    // FFE1: NOTIFY, READ, WRITE, WRITE_NO_RESPONSE — dipakai buat NOTIFY aja.
    private static final UUID JK_CHAR_NOTIFY_UUID =
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    // FFF2: WRITE, WRITE_NO_RESPONSE — channel command TERPISAH, ketemu via
    // inspeksi nRF Connect. Sebelumnya command dikirim ke FFE1 (yg jg support
    // write scr teknis, makanya gak error), tapi kemungkinan gak diproses
    // sbg command asli — makanya BMS keliatan cuma balikin frame default.
    private static final UUID JK_CHAR_WRITE_UUID =
        UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_TIMEOUT_MS = 10000; // auto-stop scan 10 detik

    public interface Callback {
        void onDeviceFound(String name, String mac);
        void onScanDone();
        void onConnected(String name);
        void onDisconnected();
        void onRawData(String hexString);
        void onLog(String message); // opsional, buat forward ke dev log JS kalau perlu
        default void onState(String state, String detail) {} // tahap koneksi: scan/queue/connect/setup/fail/off
    }

    private final Context  context;
    private final Callback callback;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner          scanner;
    private BluetoothGatt               gatt;
    private BluetoothGattCharacteristic notifyChar; // FFE1 — buat subscribe notify
    private BluetoothGattCharacteristic writeChar;  // FFF2 — buat kirim command
    private final Set<String>           seenMacs = new HashSet<>();
    private Runnable                    scanTimeoutRunnable;
    private boolean                     scanRetried = false;

    // Android BLE hanya aman menjalankan satu operasi GATT pada satu waktu.
    // JK-BMS cukup sensitif kalau command 0x97/0x96 bertabrakan atau dikirim
    // saat operasi sebelumnya belum benar-benar selesai.
    private final Queue<byte[]> writeQueue = new ArrayDeque<>();
    private boolean writeInFlight = false;
    private Runnable writePumpRunnable;
    private Runnable connectTimeoutRunnable;
    private int connectionGeneration = 0;
    private static final long CONNECT_TIMEOUT_MS = 15000;
    // WRITE_NO_RESPONSE tidak menjamin callback write yang konsisten di semua
    // stack Android. Karena itu queue memakai jeda kecil antar command, bukan
    // menggantung queue menunggu callback yang mungkin tidak datang.
    private static final long WRITE_GAP_MS = 140;

    public BmsBleManager(Context context, Callback callback) {
        this.context  = context;
        this.callback = callback;
    }

    // ══════════════════════════════════════════════
    // SCAN
    // ══════════════════════════════════════════════
    public void startScan() {
        reconnecting = false; cancelReconnect();
        ++connectionGeneration;
        clearConnectTimeout();
        seenMacs.clear();

        // Jaring pengaman: kalau ada sisa koneksi GATT lama yg belum bersih
        // (mis. disconnect callback sblmnya gak sempat jalan tuntas), tutup
        // paksa dulu sblm scan baru — device yg masih "dianggap connected"
        // oleh stack Android biasanya gak akan muncul di hasil scan.
        if (gatt != null) {
            Log.w(TAG, "Ada sisa koneksi GATT lama, ditutup dulu sblm scan baru");
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
            notifyChar = null;
            writeChar = null;
            clearWriteQueue();
            BleConnectGate.release("BMS");
        }

        BluetoothManager btManager =
            (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = btManager != null ? btManager.getAdapter() : null;

        if (adapter == null || !adapter.isEnabled()) {
            Log.w(TAG, "Bluetooth adapter null/disabled");
            callback.onScanDone();
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Log.w(TAG, "BLE scanner tidak tersedia di device ini");
            callback.onScanDone();
            return;
        }

        // Bersihkan scan sebelumnya yang masih terdaftar dengan callback yang sama —
        // kalau tidak, startScan() gagal dengan kode 1 (SCAN_FAILED_ALREADY_STARTED)
        // dan perangkat tidak pernah terdeteksi.
        if (scanTimeoutRunnable != null) { mainHandler.removeCallbacks(scanTimeoutRunnable); scanTimeoutRunnable = null; }
        try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}

        // TANPA filter hardware: filter UUID di level chip sering macet (slot filter bocor) sampai Bluetooth/HP di-restart,
        // sehingga perangkat tidak muncul walau terlihat di menu Bluetooth Android. Penyaringan dilakukan di onScanResult.
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();

        try {
            scanner.startScan(null, settings, scanCallback);
            emit("scan", "");
        } catch (SecurityException e) {
            // BLUETOOTH_SCAN (Android 12+) atau ACCESS_FINE_LOCATION belum di-grant.
            // MainActivity.requestBtPermissions() SEHARUSNYA sudah minta ini di awal,
            // tapi kalau user baru saja deny, ini jaring pengamannya.
            Log.e(TAG, "Scan gagal — permission belum di-grant: " + e.getMessage());
            callback.onLog("BMS: izin Bluetooth Scan belum diberikan — cek Pengaturan App");
            callback.onScanDone();
            return;
        }

        scanTimeoutRunnable = this::stopScan;
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
    }

    public void stopScan() {
        scanRetried = false;
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        if (scanTimeoutRunnable != null) { mainHandler.removeCallbacks(scanTimeoutRunnable); scanTimeoutRunnable = null; }
        callback.onScanDone();
    }

    // Perangkat dianggap cocok kalau iklannya memuat service UUID yang dicari ATAU namanya mengandung kata kunci.
    private boolean matchesTarget(ScanResult r, BluetoothDevice d) {
        try {
            android.bluetooth.le.ScanRecord rec = r.getScanRecord();
            String n = null;
            if (rec != null) {
                java.util.List<ParcelUuid> u = rec.getServiceUuids();
                if (u != null) for (ParcelUuid p : u) if (JK_SERVICE_UUID.equals(p.getUuid())) return true;
                n = rec.getDeviceName();
            }
            if (n == null) { try { n = d.getName(); } catch (SecurityException e) { n = null; } }
            if (n != null) {
                String l = n.toLowerCase(java.util.Locale.ROOT);
                for (String k : new String[]{"jk", "bms"}) if (l.contains(k)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            scanRetried = false;
            BluetoothDevice device = result.getDevice();
            String mac = device.getAddress();
            if (mac == null || seenMacs.contains(mac)) return;
            if (!matchesTarget(result, device)) return;
            seenMacs.add(mac);

            String name;
            try { name = device.getName(); } catch (SecurityException e) { name = null; }
            if (name == null && result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
            if (name == null) name = "BMS Jikong";

            callback.onDeviceFound(name, mac);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "onScanFailed: " + errorCode);
            if (errorCode == SCAN_FAILED_ALREADY_STARTED && !scanRetried) {
                // Scan lama masih nyangkut: hentikan lalu ulangi satu kali
                scanRetried = true;
                callback.onLog("BMS: scan lama masih aktif (kode 1), diulang otomatis");
                try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
                mainHandler.postDelayed(new Runnable() { @Override public void run() { startScan(); } }, 500);
                return;
            }
            scanRetried = false;
            callback.onLog("BMS: scan gagal (kode " + errorCode + ")");
            callback.onScanDone();
        }
    };

    // ══════════════════════════════════════════════
    // CONNECT / DISCONNECT
    // ══════════════════════════════════════════════
    public void connect(final String mac) {
        final BluetoothManager btManager =
            (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter adapter = btManager != null ? btManager.getAdapter() : null;
        if (adapter == null || mac == null || mac.trim().isEmpty()) { callback.onDisconnected(); return; }

        final int generation = ++connectionGeneration;
        lastMac = mac; manualStop = false; cancelReconnect();
        if (!retryInFlight) { reconnectAttempts = 0; reconnecting = false; }
        retryInFlight = false;
        stopScan();
        clearConnectTimeout();
        clearWriteQueue();
        BluetoothGatt old = gatt;
        gatt = null; notifyChar = null; writeChar = null;
        if (old != null) { try { old.disconnect(); } catch (Exception ignored) {} try { old.close(); } catch (Exception ignored) {} }

        mainHandler.postDelayed(() -> {
            if (generation != connectionGeneration) return;
            BleConnectGate.setAbortHook("BMS", this::abortStuckSetup);
            emit("queue", BleConnectGate.waitingFor());
            BleConnectGate.acquire("BMS", () -> {
                if (generation != connectionGeneration) { BleConnectGate.release("BMS"); return; }
                emit("connect", "");
                try {
                    BluetoothDevice device = adapter.getRemoteDevice(mac);
                    BluetoothGatt newGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                    gatt = newGatt;
                    if (newGatt == null) {
                        callback.onLog("BMS: connectGatt() mengembalikan null");
                        BleConnectGate.release("BMS"); callback.onDisconnected(); emit("fail", "gatt null"); return;
                    }
                    connectTimeoutRunnable = () -> {
                        if (generation != connectionGeneration || gatt != newGatt) return;
                        callback.onLog("BMS: timeout koneksi BLE > 15s — reset GATT");
                        try { newGatt.disconnect(); } catch (Exception ignored) {}
                        try { newGatt.close(); } catch (Exception ignored) {}
                        if (gatt == newGatt) { gatt = null; notifyChar = null; writeChar = null; }
                        BleConnectGate.release("BMS"); callback.onDisconnected(); emit("fail", "timeout 15s"); scheduleReconnect();
                    };
                    mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);
                } catch (SecurityException e) {
                    callback.onLog("BMS: izin Bluetooth Connect belum diberikan");
                    BleConnectGate.release("BMS"); callback.onDisconnected(); emit("fail", "izin BT");
                } catch (IllegalArgumentException e) {
                    callback.onLog("BMS: MAC address invalid: " + mac);
                    BleConnectGate.release("BMS"); callback.onDisconnected();
                }
            });
        }, 350);
    }

    public void disconnect() {
        manualStop = true; reconnecting = false; fullyConnected = false; cancelReconnect();
        ++connectionGeneration;
        stopRssiPolling();
        clearConnectTimeout();
        BluetoothGatt old = gatt;
        gatt = null; notifyChar = null; writeChar = null;
        if (old != null) { try { old.disconnect(); } catch (Exception ignored) {} try { old.close(); } catch (Exception ignored) {} }
        BleConnectGate.release("BMS");
    }

    // ══════════════════════════════════════════════
    // WRITE — dipanggil dari MainActivity.JsBridge.bmsWriteRaw(hexString)
    // ══════════════════════════════════════════════
    public void writeRaw(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        if (gatt == null || writeChar == null) {
            Log.w(TAG, "writeRaw dipanggil tapi belum connect/characteristic belum siap");
            return;
        }
        synchronized (writeQueue) {
            writeQueue.offer(bytes.clone());
        }
        pumpWriteQueue();
    }

    private void pumpWriteQueue() {
        final BluetoothGatt g;
        final BluetoothGattCharacteristic ch;
        final byte[] payload;
        synchronized (writeQueue) {
            if (writeInFlight || writeQueue.isEmpty()) return;
            g = gatt; ch = writeChar;
            if (g == null || ch == null) { writeQueue.clear(); return; }
            payload = writeQueue.peek();
            writeInFlight = true;
        }
        try {
            ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            ch.setValue(payload);
            boolean started = g.writeCharacteristic(ch);
            if (!started) {
                Log.w(TAG, "writeCharacteristic() return false — GATT sibuk, retry command");
                callback.onLog("BMS: GATT sedang sibuk — command tetap diantrikan");
                synchronized (writeQueue) { writeInFlight = false; }
                mainHandler.postDelayed(this::pumpWriteQueue, 180);
                return;
            }
            // Command sudah diterima oleh stack Android. Untuk WRITE_NO_RESPONSE
            // jangan menunggu onCharacteristicWrite(): pada sebagian perangkat
            // callback bisa tidak datang. Lepas item dan beri gap aman sebelum
            // command berikutnya agar JK-BMS tidak menerima burst.
            synchronized (writeQueue) {
                if (!writeQueue.isEmpty()) writeQueue.poll();
                writeInFlight = false;
            }
            if (!writeQueue.isEmpty()) {
                writePumpRunnable = this::pumpWriteQueue;
                mainHandler.postDelayed(writePumpRunnable, WRITE_GAP_MS);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Write gagal — permission: " + e.getMessage());
            synchronized (writeQueue) { writeInFlight = false; }
            mainHandler.postDelayed(this::pumpWriteQueue, 250);
        } catch (Exception e) {
            Log.e(TAG, "Write gagal: " + e.getMessage());
            synchronized (writeQueue) { writeInFlight = false; }
            mainHandler.postDelayed(this::pumpWriteQueue, 250);
        }
    }

    private void clearWriteQueue() {
        if (writePumpRunnable != null) {
            mainHandler.removeCallbacks(writePumpRunnable);
            writePumpRunnable = null;
        }
        synchronized (writeQueue) {
            writeQueue.clear();
            writeInFlight = false;
        }
    }

    private void clearConnectTimeout() {
        if (connectTimeoutRunnable != null) {
            mainHandler.removeCallbacks(connectTimeoutRunnable);
            connectTimeoutRunnable = null;
        }
    }

    // ══════════════════════════════════════════════
    // GATT CALLBACK
    // ══════════════════════════════════════════════

    // Helper: setup gagal di titik manapun -> lepas gerbang koneksi bareng
    // (biar ESP32/device lain gak nunggu sia-sia) + laporkan disconnect ke JS.
    private void failSetup() {
        BleConnectGate.release("BMS");
        callback.onDisconnected();
        emit("fail", setupStep.isEmpty() ? "setup" : setupStep);
        scheduleReconnect();
    }

    // Status tahap koneksi -> JS (chip status per perangkat)
    private String setupStep = "";

    // ── Sambung ulang otomatis (khusus kalau sempat tersambung lalu putus tak sengaja, mis. status 133) ──
    private static final long[] RECONNECT_DELAYS_MS = {2000, 3000, 5000, 8000, 12000, 15000};
    private static final int RECONNECT_MAX = 8;
    private String  lastMac = null;
    private boolean manualStop = false, fullyConnected = false, reconnecting = false, retryInFlight = false;
    private int     reconnectAttempts = 0;
    private Runnable reconnectRunnable = null;

    private void cancelReconnect() {
        if (reconnectRunnable != null) { mainHandler.removeCallbacks(reconnectRunnable); reconnectRunnable = null; }
    }

    private void scheduleReconnect() {
        if (manualStop || lastMac == null || reconnectRunnable != null) return;
        if (!(fullyConnected || reconnecting)) return;
        fullyConnected = false;
        if (reconnectAttempts >= RECONNECT_MAX) {
            reconnecting = false;
            callback.onLog("BMS: sambung ulang otomatis berhenti setelah " + RECONNECT_MAX + " percobaan");
            emit("fail", "sambung ulang habis");
            return;
        }
        // Jeda diperpanjang bila jalur Classic ke MID sedang sibuk (lihat BleCoex): percobaan LE yang gagal berulang
        // memakan waktu udara dan membuat frame peta macet.
        final long delay = BleCoex.stretch(RECONNECT_DELAYS_MS[Math.min(reconnectAttempts, RECONNECT_DELAYS_MS.length - 1)]);
        reconnectAttempts++;
        reconnecting = true;
        callback.onLog("BMS: sambung ulang otomatis " + reconnectAttempts + "/" + RECONNECT_MAX + " dalam " + (delay / 1000) + "s");
        emit("retry", reconnectAttempts + "/" + RECONNECT_MAX);
        reconnectRunnable = () -> {
            reconnectRunnable = null;
            if (manualStop || lastMac == null) return;
            retryInFlight = true;
            connect(lastMac);
        };
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    // Dipanggil watchdog BleConnectGate: setup macet > 25s -> tutup GATT, status gagal (gerbang dilepas oleh gate)
    private void abortStuckSetup() {
        callback.onLog("BMS: setup macet di '" + setupStep + "' > 25s — reset GATT");
        ++connectionGeneration;
        clearConnectTimeout();
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try { g.disconnect(); } catch (Exception ignored) {}
            try { g.close(); } catch (Exception ignored) {}
        }
        callback.onDisconnected();
        emit("fail", "macet di " + (setupStep.isEmpty() ? "connect" : setupStep));
        scheduleReconnect();
    }
    // ── KUALITAS SINYAL (RSSI) ──
    // Dibaca berkala selama koneksi aktif dan dikirim ke JS lewat onState("rssi", dBm)
    // -> window.onBleState(id,'rssi',dBm) -> chip status (4 batang + Kuat/Sedang/Lemah).
    private static final long RSSI_INTERVAL_MS = 3000;
    private Runnable rssiRunnable;
    private void startRssiPolling() {
        stopRssiPolling();
        final int gen = connectionGeneration;
        rssiRunnable = new Runnable() {
            @Override public void run() {
                // Berhenti sendiri kalau koneksi sudah ganti generasi / putus
                if (gen != connectionGeneration || !fullyConnected || gatt == null) { rssiRunnable = null; return; }
                try {
                    // Jangan ganggu operasi GATT yang sedang jalan
                    if (!(writeInFlight || !writeQueue.isEmpty())) gatt.readRemoteRssi();
                } catch (RuntimeException ignored) {}
                mainHandler.postDelayed(this, RSSI_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(rssiRunnable, 1000);
    }
    private void stopRssiPolling() {
        if (rssiRunnable != null) { mainHandler.removeCallbacks(rssiRunnable); rssiRunnable = null; }
    }

    private void emit(String state, String detail) {
        if ("connect".equals(state)) setupStep = "";
        if ("setup".equals(state)) setupStep = detail;
        try { callback.onState(state, detail); } catch (Exception ignored) {}
    }

    private boolean isCurrentGatt(BluetoothGatt g) { return g != null && g == gatt; }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (!isCurrentGatt(g)) {
                Log.w(TAG, "Mengabaikan callback GATT lama, status=" + status + ", state=" + newState);
                try { g.close(); } catch (Exception ignored) {}
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clearConnectTimeout();
                emit("setup", "service");
                try {
                    try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) {}
                    // Bersihin cache GATT lama dulu SEBELUM discoverServices —
                    // lihat komentar refreshGattCache() di atas kenapa ini penting.
                    // PENTING: minta MTU lebih besar SEBELUM discoverServices().
                    // Tanpa ini, Android pakai MTU default 23 byte (cuma 20 byte
                    // payload per notify) — beberapa periferal BLE (termasuk
                    // BMS ini kelihatannya) cuma mau ngirim beberapa paket lalu
                    // berhenti kalau ketemu MTU sekecil itu. 247 adalah nilai
                    // umum yg aman & didukung luas (butuh >= Android 5.0/API21,
                    // BLE 4.2+ di sisi periferal).
                    boolean requested = g.requestMtu(247);
                    if (!requested) {
                        // requestMtu() gagal dipanggil (jarang) — lanjut apa
                        // adanya drpd macet nungguin callback yg gak akan datang.
                        Log.w(TAG, "requestMtu() gagal dipanggil, lanjut discoverServices tanpa MTU custom");
                        g.discoverServices();
                    }
                    // Kalau requested==true, discoverServices() dipanggil di
                    // onMtuChanged() di bawah, bukan di sini.
                } catch (SecurityException e) {
                    failSetup();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // FIX: sebelumnya cuma writeChar yg di-null-kan di sini —
                // notifyChar & field gatt itu sendiri gak pernah dibersihkan,
                // jadi objek GATT lama masih "nyangkut" di memory walau udah
                // di-close(). Ini bisa bikin state gak bersih pas scan/connect
                // berikutnya (device kadang gak ke-detect lagi di scan sampai
                // Bluetooth di-toggle, krn stack Android masih anggap ada sisa
                // koneksi ke MAC itu).
                stopRssiPolling();
                clearConnectTimeout();
                clearWriteQueue();
                notifyChar = null;
                writeChar  = null;
                // status di sini adalah kode GATT penyebab disconnect — 0 =
                // disconnect normal/diminta, selain itu biasanya error (mis.
                // 133 = GATT_ERROR generik, 8 = timeout koneksi, 62 = dll).
                // Sebelumnya kode gak pernah log ini, jadi kalau BMS putus
                // sendiri kita gak pernah tau kenapa.
                callback.onLog("BMS: GATT disconnected, status=" + status +
                    (status == 0 ? " (normal)" : " (kemungkinan error — cek dokumentasi kode GATT status)"));
                BleConnectGate.release("BMS"); // aman dipanggil meski gate udah gak dipegang BMS
                callback.onDisconnected();
                emit("off", status == 0 ? "" : "kode " + status);
                scheduleReconnect();
                try { g.close(); } catch (Exception ignored) {}
                if (g == gatt) gatt = null;
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            emit("setup", "service");
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU berhasil dinaikkan ke " + mtu);
            } else {
                Log.w(TAG, "MTU request gagal (status=" + status + "), lanjut pakai MTU default");
            }
            // Lanjut discover services baik MTU berhasil ATAU gagal — jangan
            // sampai koneksi macet cuma gara2 negosiasi MTU ditolak device.
            try {
                g.discoverServices();
            } catch (SecurityException e) {
                failSetup();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            emit("setup", "notif");
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onServicesDiscovered gagal, status=" + status);
                failSetup();
                return;
            }

            // PENTING: device ini mendeklarasikan service FFE0 DUA KALI
            // (ketemu via nRF Connect — 2 entri "Unknown Service UUID 0xFFE0").
            // Characteristic FFF2/FFE1 cuma ada di instance KEDUA.
            // g.getService(uuid) cuma ambil match PERTAMA, jadi kalau itu yg
            // kosong kita bakal gagal nemuin characteristic-nya. Makanya di
            // sini kita iterate SEMUA service yg UUID-nya cocok, bukan cuma
            // ambil satu.
            notifyChar = null;
            writeChar  = null;
            for (BluetoothGattService svc : g.getServices()) {
                if (!JK_SERVICE_UUID.equals(svc.getUuid())) continue;
                if (notifyChar == null) notifyChar = svc.getCharacteristic(JK_CHAR_NOTIFY_UUID);
                if (writeChar  == null) writeChar  = svc.getCharacteristic(JK_CHAR_WRITE_UUID);
                if (notifyChar != null && writeChar != null) break;
            }

            if (notifyChar == null) {
                Log.e(TAG, "Characteristic FFE1 (notify) tidak ditemukan di service manapun");
                callback.onLog("BMS: characteristic FFE1 gak ketemu di service FFE0 manapun");
                failSetup();
                return;
            }
            if (writeChar == null) {
                // Fallback: kalau FFF2 beneran gak ada, coba tetep pakai FFE1
                // buat write (device lama mungkin cuma punya 1 characteristic).
                Log.w(TAG, "Characteristic FFF2 (write) tidak ditemukan — fallback pakai FFE1 utk write juga");
                callback.onLog("BMS: FFF2 gak ketemu, fallback command lewat FFE1");
                writeChar = notifyChar;
            }

            // PENTING (bug utama): operasi GATT di Android WAJIB diantri satu
            // per satu — gak boleh mulai operasi baru sblm operasi sblmnya
            // beneran KELAR (dikonfirmasi lewat callback-nya sendiri).
            // Sebelumnya di sini kita langsung panggil callback.onConnected()
            // begitu writeDescriptor() DIPANGGIL, padahal itu operasi ASYNC —
            // hasil suksesnya baru dikonfirmasi di onDescriptorWrite() di
            // bawah. Kalau JS langsung kirim handshake 0x97/0x96 SBLM CCCD
            // write ini beneran kelar, GATT queue-nya masih sibuk & write
            // command kita bisa DITOLAK DIAM-DIAM (gak exception, gak error,
            // writeCharacteristic() cuma return false). Ini kemungkinan besar
            // penyebab kenapa native gagal dapet Cell Info padahal Web
            // Bluetooth (yg otomatis antri operasi GATT-nya) berhasil.
            // Makanya sekarang callback.onConnected() DIPINDAH ke
            // onDescriptorWrite(), bukan di sini lagi.
            try {
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor descriptor = notifyChar.getDescriptor(CCCD_UUID);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    boolean started = g.writeDescriptor(descriptor);
                    if (!started) {
                        Log.e(TAG, "writeDescriptor() gagal DIMULAI (return false)");
                        callback.onLog("BMS: gagal mulai enable notification (writeDescriptor return false)");
                        failSetup();
                    }
                    // Kalau started==true, tunggu onDescriptorWrite() di bawah —
                    // JANGAN panggil onConnected() di sini.
                } else {
                    Log.e(TAG, "CCCD descriptor (0x2902) tidak ditemukan di characteristic FFE1");
                    callback.onLog("BMS: descriptor CCCD gak ketemu — notification gak bisa diaktifkan");
                    failSetup();
                }
            } catch (SecurityException e) {
                failSetup();
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
            if (g != gatt || status != BluetoothGatt.GATT_SUCCESS) return;
            emit("rssi", String.valueOf(rssi));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            // Ini konfirmasi ASLI bahwa notification udah beneran aktif di
            // sisi BMS — baru dari sini aman buat mulai kirim command.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onDescriptorWrite gagal, status=" + status);
                callback.onLog("BMS: enable notification gagal, status=" + status);
                failSetup();
                return;
            }
            String name;
            try { name = g.getDevice().getName(); } catch (SecurityException e) { name = null; }
            BleConnectGate.release("BMS"); // setup selesai sukses - device lain boleh mulai connect
            reconnectAttempts = 0; reconnecting = false; fullyConnected = true;
            startRssiPolling();
            callback.onConnected(name != null ? name : "BMS Jikong");
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int status) {
            // Diagnostik: konfirmasi command kita (0x96/0x97) BENERAN sukses
            // ditulis ke GATT, bukan cuma "diminta" doang. Sebelumnya gak ada
            // logging ini sama sekali, jadi kalau writeCharacteristic() gagal
            // di level GATT kita gak akan pernah tau.
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onCharacteristicWrite gagal, status=" + status);
                callback.onLog("BMS: write command gagal di level GATT, status=" + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g,
                                             BluetoothGattCharacteristic characteristic) {
            byte[] data = characteristic.getValue();
            if (data == null || data.length == 0) return;
            callback.onRawData(bytesToHex(data));
        }
    };

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    // Helper publik biar MainActivity.JsBridge.bmsWriteRaw() tinggal pakai ini
    public void writeRawHex(String hex) {
        writeRaw(hexToBytes(hex));
    }
}
