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
import java.util.UUID;

/**
 * BLE GATT client khusus ESP32 #2 Battery ORI (dashboard.gesits custom device).
 *
 * Dibuat dengan pola IDENTIK ke BmsBleManager.java (lifecycle, timing GATT
 * queue, MTU negotiation, refresh cache) karena sudah terbukti jalan stabil
 * di app ini utk kasus BLE serupa. Bedanya:
 *   - ESP32 cuma broadcast NOTIFY (telemetry "KEY=VALUE\r\n" text, sama
 *     persis format yg dipakai parseResponse() utk motor via LE/Serial).
 *     Tidak ada characteristic WRITE terpisah dipakai saat ini (ESP32
 *     tidak butuh command dari app), tapi disiapkan untuk future use.
 *   - Data yg dikirim ke JS BUKAN raw biner, tapi teks ASCII yg sudah di-
 *     hex-encode (sama treatment-nya sama BMS: dikirim sbg hex string,
 *     JS yg decode). Di sisi JS, hex di-decode balik jadi teks lalu
 *     langsung dilempar ke parseResponse() yg sudah ada.
 *
 * UUID di bawah HARUS SAMA PERSIS dengan yang di-set di firmware ESP32
 * (lihat BLE_SERVICE_UUID / BLE_CHARACTERISTIC_UUID di sketch .ino).
 */
public class Esp32Battery2BleManager {

    private static final String TAG = "GesitsEsp32Battery2Ble";

    private static final UUID ESP32_SERVICE_UUID =
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID ESP32_CHAR_NOTIFY_UUID =
        UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID ESP32_CHAR_WRITE_UUID =
        UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_TIMEOUT_MS = 10000; // auto-stop scan 10 detik

    public interface Callback {
        void onDeviceFound(String name, String mac);
        void onScanDone();
        void onConnected(String name);
        void onDisconnected();
        void onRawData(String hexString);
        void onLog(String message);
        default void onState(String state, String detail) {} // tahap koneksi: scan/queue/connect/setup/fail/off
    }

    private final Context  context;
    private final Callback callback;
    private final Handler  mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner          scanner;
    private BluetoothGatt               gatt;
    private BluetoothGattCharacteristic notifyChar;
    private BluetoothGattCharacteristic writeChar;
    private final Set<String>           seenMacs = new HashSet<>();
    private Runnable                    scanTimeoutRunnable;
    private boolean                     scanRetried = false;
    private Runnable                    connectTimeoutRunnable;
    private int                          connectionGeneration = 0;
    private static final long            CONNECT_TIMEOUT_MS = 15000;

    public Esp32Battery2BleManager(Context context, Callback callback) {
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

        if (gatt != null) {
            Log.w(TAG, "Ada sisa koneksi GATT lama, ditutup dulu sblm scan baru");
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
            notifyChar = null;
            writeChar = null;
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
            Log.e(TAG, "Scan gagal — permission belum di-grant: " + e.getMessage());
            callback.onLog("ESP32 #2: izin Bluetooth Scan belum diberikan — cek Pengaturan App");
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
                if (u != null) for (ParcelUuid p : u) if (ESP32_SERVICE_UUID.equals(p.getUuid())) return true;
                n = rec.getDeviceName();
            }
            if (n == null) { try { n = d.getName(); } catch (SecurityException e) { n = null; } }
            if (n != null) {
                String l = n.toLowerCase(java.util.Locale.ROOT);
                for (String k : new String[]{"esp32", "gesits"}) if (l.contains(k)) return true;
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
            if (name == null) name = "Gesits-ESP32-BMS2";

            callback.onDeviceFound(name, mac);
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "onScanFailed: " + errorCode);
            if (errorCode == SCAN_FAILED_ALREADY_STARTED && !scanRetried) {
                // Scan lama masih nyangkut: hentikan lalu ulangi satu kali
                scanRetried = true;
                callback.onLog("ESP32 #2: scan lama masih aktif (kode 1), diulang otomatis");
                try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
                mainHandler.postDelayed(new Runnable() { @Override public void run() { startScan(); } }, 500);
                return;
            }
            scanRetried = false;
            callback.onLog("ESP32 #2: scan gagal (kode " + errorCode + ")");
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
        BluetoothGatt old = gatt;
        gatt = null; notifyChar = null; writeChar = null;
        if (old != null) { try { old.disconnect(); } catch (Exception ignored) {} try { old.close(); } catch (Exception ignored) {} }

        mainHandler.postDelayed(() -> {
            if (generation != connectionGeneration) return;
            BleConnectGate.setAbortHook("ESP32_BAT2", this::abortStuckSetup);
            emit("queue", BleConnectGate.waitingFor());
            BleConnectGate.acquire("ESP32_BAT2", () -> {
                if (generation != connectionGeneration) { BleConnectGate.release("ESP32_BAT2"); return; }
                emit("connect", "");
                try {
                    BluetoothDevice device = adapter.getRemoteDevice(mac);
                    BluetoothGatt newGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                    gatt = newGatt;
                    if (newGatt == null) {
                        callback.onLog("ESP32 #2: connectGatt() mengembalikan null");
                        BleConnectGate.release("ESP32_BAT2"); callback.onDisconnected(); emit("fail", "gatt null"); return;
                    }
                    connectTimeoutRunnable = () -> {
                        if (generation != connectionGeneration || gatt != newGatt) return;
                        callback.onLog("ESP32 #2: timeout koneksi BLE > 15s — reset GATT");
                        try { newGatt.disconnect(); } catch (Exception ignored) {}
                        try { newGatt.close(); } catch (Exception ignored) {}
                        if (gatt == newGatt) { gatt = null; notifyChar = null; writeChar = null; }
                        BleConnectGate.release("ESP32_BAT2"); callback.onDisconnected(); emit("fail", "timeout 15s"); scheduleReconnect();
                    };
                    mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);
                } catch (SecurityException e) {
                    callback.onLog("ESP32 #2: izin Bluetooth Connect belum diberikan");
                    BleConnectGate.release("ESP32_BAT2"); callback.onDisconnected(); emit("fail", "izin BT");
                } catch (IllegalArgumentException e) {
                    callback.onLog("ESP32 #2: MAC address invalid: " + mac);
                    BleConnectGate.release("ESP32_BAT2"); callback.onDisconnected();
                }
            });
        }, 350);
    }

    /**
     * Kirim command teks (mis. "CONTACT_ON", "CONTACT_OFF") ke ESP32 lewat
     * characteristic RX. Dipanggil dari MainActivity.esp32SendCommand()
     * (bridge JS Android.esp32SendCommand()). Pakai WRITE_NR (no response)
     * karena firmware declare PROPERTY_WRITE_NR & lebih cepat/gak nunggu ack.
     */
    public boolean sendCommand(String command) {
        if (gatt == null || writeChar == null) {
            Log.w(TAG, "sendCommand gagal: gatt/writeChar belum siap (command=" + command + ")");
            callback.onLog("ESP32 #2: gagal kirim command '" + command + "' — characteristic command belum siap");
            return false;
        }
        try {
            byte[] payload = (command + "\n").getBytes();
            boolean ok;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                int result = gatt.writeCharacteristic(writeChar, payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                ok = (result == BluetoothGatt.GATT_SUCCESS);
            } else {
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                writeChar.setValue(payload);
                ok = gatt.writeCharacteristic(writeChar);
            }
            if (!ok) {
                Log.e(TAG, "writeCharacteristic() gagal DIMULAI untuk command " + command);
                callback.onLog("ESP32 #2: gagal mulai kirim command '" + command + "'");
            }
            return ok;
        } catch (SecurityException e) {
            Log.e(TAG, "sendCommand gagal — permission: " + e.getMessage());
            callback.onLog("ESP32 #2: izin Bluetooth Connect belum diberikan (kirim command)");
            return false;
        }
    }

    public boolean isConnected() {
        return gatt != null && notifyChar != null;
    }

    public void disconnect() {
        manualStop = true; reconnecting = false; fullyConnected = false; cancelReconnect();
        ++connectionGeneration;
        stopRssiPolling();
        clearConnectTimeout();
        BluetoothGatt old = gatt;
        gatt = null; notifyChar = null; writeChar = null;
        if (old != null) { try { old.disconnect(); } catch (Exception ignored) {} try { old.close(); } catch (Exception ignored) {} }
        BleConnectGate.release("ESP32_BAT2");
    }

    // ══════════════════════════════════════════════
    // GATT CALLBACK
    // ══════════════════════════════════════════════

    // Helper: setup gagal di titik manapun -> lepas gerbang koneksi (biar
    // BMS/device lain gak nunggu sia-sia) + laporkan disconnect ke JS.
    private void failSetup() {
        BleConnectGate.release("ESP32_BAT2");
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
            callback.onLog("ESP32_BAT2: sambung ulang otomatis berhenti setelah " + RECONNECT_MAX + " percobaan");
            emit("fail", "sambung ulang habis");
            return;
        }
        // Jeda diperpanjang bila jalur Classic ke MID sedang sibuk (lihat BleCoex): percobaan LE yang gagal berulang
        // memakan waktu udara dan membuat frame peta macet.
        final long delay = BleCoex.stretch(RECONNECT_DELAYS_MS[Math.min(reconnectAttempts, RECONNECT_DELAYS_MS.length - 1)]);
        reconnectAttempts++;
        reconnecting = true;
        callback.onLog("ESP32_BAT2: sambung ulang otomatis " + reconnectAttempts + "/" + RECONNECT_MAX + " dalam " + (delay / 1000) + "s");
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
        callback.onLog("ESP32_BAT2: setup macet di '" + setupStep + "' > 25s — reset GATT");
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
                    if (!(false)) gatt.readRemoteRssi();
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

    private void clearConnectTimeout() {
        if (connectTimeoutRunnable != null) { mainHandler.removeCallbacks(connectTimeoutRunnable); connectTimeoutRunnable = null; }
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
                emit("setup", "MTU");
                try {
                    try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) {}
                    boolean requested = g.requestMtu(247);
                    if (!requested) {
                        Log.w(TAG, "requestMtu() gagal dipanggil, lanjut discoverServices tanpa MTU custom");
                        g.discoverServices();
                    }
                } catch (SecurityException e) {
                    failSetup();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                stopRssiPolling();
                clearConnectTimeout();
                notifyChar = null;
                writeChar = null;
                callback.onLog("ESP32 #2: GATT disconnected, status=" + status +
                    (status == 0 ? " (normal)" : " (kemungkinan error)"));
                BleConnectGate.release("ESP32_BAT2"); // aman dipanggil meski gate udah gak dipegang ESP32
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

            notifyChar = null;
            writeChar = null;
            for (BluetoothGattService svc : g.getServices()) {
                if (!ESP32_SERVICE_UUID.equals(svc.getUuid())) continue;
                notifyChar = svc.getCharacteristic(ESP32_CHAR_NOTIFY_UUID);
                writeChar  = svc.getCharacteristic(ESP32_CHAR_WRITE_UUID);
                if (notifyChar != null) break;
            }

            if (notifyChar == null) {
                Log.e(TAG, "Characteristic notify ESP32 tidak ditemukan");
                callback.onLog("ESP32 #2: characteristic notify tidak ketemu di service manapun");
                failSetup();
                return;
            }

            // writeChar opsional (command kontak) — kalau firmware lama belum
            // punya RX char ini, telemetry (notify) tetap jalan normal, cuma
            // fitur kontak yang nonaktif. Jangan failSetup() karena ini.
            if (writeChar == null) {
                Log.w(TAG, "Characteristic write (command) ESP32 tidak ditemukan — fitur kontak nonaktif");
                callback.onLog("ESP32 #2: characteristic command (kontak) tidak ada di firmware ini");
            }

            // Sama seperti BmsBleManager: tunggu konfirmasi ASLI dari
            // onDescriptorWrite() sebelum panggil onConnected(), supaya
            // GATT queue tidak ketiban operasi lain sebelum notify aktif.
            try {
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor descriptor = notifyChar.getDescriptor(CCCD_UUID);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    boolean started = g.writeDescriptor(descriptor);
                    if (!started) {
                        Log.e(TAG, "writeDescriptor() gagal DIMULAI (return false)");
                        callback.onLog("ESP32 #2: gagal mulai enable notification");
                        failSetup();
                    }
                } else {
                    Log.e(TAG, "CCCD descriptor (0x2902) tidak ditemukan");
                    callback.onLog("ESP32 #2: descriptor CCCD gak ketemu");
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onDescriptorWrite gagal, status=" + status);
                callback.onLog("ESP32 #2: enable notification gagal, status=" + status);
                failSetup();
                return;
            }
            String name;
            try { name = g.getDevice().getName(); } catch (SecurityException e) { name = null; }
            BleConnectGate.release("ESP32_BAT2"); // setup selesai sukses - device lain boleh mulai connect
            reconnectAttempts = 0; reconnecting = false; fullyConnected = true;
            startRssiPolling();
            callback.onConnected(name != null ? name : "Gesits-ESP32-BMS2");
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
}
