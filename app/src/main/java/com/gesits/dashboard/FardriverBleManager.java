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
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

/**
 * BLE GATT client khusus kontroler motor Nanjing Fardriver (dongle BLE UART).
 * Mendukung service FF00 (notify FF01, write FF02) serta fallback FFE0/FFE1
 * yang dipakai sebagian adapter BLE UART pihak ketiga.
 */
public class FardriverBleManager {

    private static final String TAG = "GesitsFardriverBle";

    private static final UUID FD_SERVICE_FF00 =
        UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb");
    private static final UUID FD_CHAR_FF01 =
        UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");
    private static final UUID FD_CHAR_FF02 =
        UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");

    private static final UUID FD_SERVICE_FFE0 =
        UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID FD_CHAR_FFE1 =
        UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID FD_CHAR_FFE2 =
        UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    private static final UUID FD_CHAR_FFF2 =
        UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");

    private static final UUID CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_TIMEOUT_MS = 10000;
    private static final long CONNECT_TIMEOUT_MS = 15000;
    private static final long WRITE_GAP_MS = 120;
    private static final long RSSI_INTERVAL_MS = 3000;

    public interface Callback {
        void onDeviceFound(String name, String mac);
        void onScanDone();
        void onConnected(String name);
        void onDisconnected();
        void onRawData(String hexString);
        void onLog(String message);
        default void onState(String state, String detail) {}
    }

    private final Context context;
    private final Callback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic notifyChar;
    private BluetoothGattCharacteristic writeChar;
    private final Set<String> seenMacs = new HashSet<>();
    private Runnable scanTimeoutRunnable;
    private boolean scanRetried = false;
    private volatile boolean scanAllBle = false;

    private final Queue<byte[]> writeQueue = new ArrayDeque<>();
    private boolean writeInFlight = false;
    private Runnable writePumpRunnable;
    private Runnable connectTimeoutRunnable;
    private int connectionGeneration = 0;

    private static final long[] RECONNECT_DELAYS_MS = {2000, 3000, 5000, 8000, 12000, 15000};
    private static final int RECONNECT_MAX = 8;
    private String lastMac = null;
    private boolean manualStop = false, fullyConnected = false, reconnecting = false, retryInFlight = false;
    private int reconnectAttempts = 0;
    private Runnable reconnectRunnable = null;
    private String setupStep = "";
    private Runnable rssiRunnable;

    public FardriverBleManager(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    public void startScan(boolean showAll) {
        this.scanAllBle = showAll;
        reconnecting = false;
        cancelReconnect();
        ++connectionGeneration;
        clearConnectTimeout();
        seenMacs.clear();

        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
            notifyChar = null;
            writeChar = null;
            clearWriteQueue();
            BleConnectGate.release("FARDRIVER");
        }

        BluetoothManager btManager =
            (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = btManager != null ? btManager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            callback.onScanDone();
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            callback.onScanDone();
            return;
        }

        if (scanTimeoutRunnable != null) {
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
        try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}

        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build();
        try {
            scanner.startScan(null, settings, scanCallback);
            emit("scan", "");
        } catch (SecurityException e) {
            callback.onLog("Fardriver: izin Bluetooth Scan belum diberikan");
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
        if (scanTimeoutRunnable != null) {
            mainHandler.removeCallbacks(scanTimeoutRunnable);
            scanTimeoutRunnable = null;
        }
        callback.onScanDone();
    }

    private boolean matchesTarget(ScanResult r, BluetoothDevice d) {
        if (scanAllBle) return true;
        try {
            android.bluetooth.le.ScanRecord rec = r.getScanRecord();
            String n = null;
            if (rec != null) {
                java.util.List<ParcelUuid> uuids = rec.getServiceUuids();
                if (uuids != null) {
                    for (ParcelUuid p : uuids) {
                        UUID u = p.getUuid();
                        if (FD_SERVICE_FF00.equals(u) || FD_SERVICE_FFE0.equals(u)) return true;
                    }
                }
                n = rec.getDeviceName();
            }
            if (n == null) {
                try { n = d.getName(); } catch (SecurityException e) { n = null; }
            }
            if (n != null) {
                String l = n.toLowerCase(Locale.ROOT);
                for (String k : new String[]{"fardriver", "fd-", "fd_", "yq", "bldc", "controller", "motor", "ble", "uart", "hc-", "dx-"}) {
                    if (l.contains(k)) return true;
                }
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
            if (name == null && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            if (name == null) name = "Fardriver BLE";
            callback.onDeviceFound(name, mac);
        }

        @Override
        public void onScanFailed(int errorCode) {
            if (errorCode == SCAN_FAILED_ALREADY_STARTED && !scanRetried) {
                scanRetried = true;
                try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
                mainHandler.postDelayed(() -> startScan(scanAllBle), 500);
                return;
            }
            scanRetried = false;
            callback.onLog("Fardriver: scan gagal (kode " + errorCode + ")");
            callback.onScanDone();
        }
    };

    public void connect(final String mac) {
        final BluetoothManager btManager =
            (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter adapter = btManager != null ? btManager.getAdapter() : null;
        if (adapter == null || mac == null || mac.trim().isEmpty()) {
            callback.onDisconnected();
            return;
        }

        final int generation = ++connectionGeneration;
        lastMac = mac;
        manualStop = false;
        cancelReconnect();
        if (!retryInFlight) {
            reconnectAttempts = 0;
            reconnecting = false;
        }
        retryInFlight = false;
        stopScan();
        clearConnectTimeout();
        clearWriteQueue();

        BluetoothGatt old = gatt;
        gatt = null;
        notifyChar = null;
        writeChar = null;
        if (old != null) {
            try { old.disconnect(); } catch (Exception ignored) {}
            try { old.close(); } catch (Exception ignored) {}
        }

        mainHandler.postDelayed(() -> {
            if (generation != connectionGeneration) return;
            BleConnectGate.setAbortHook("FARDRIVER", this::abortStuckSetup);
            emit("queue", BleConnectGate.waitingFor());
            BleConnectGate.acquire("FARDRIVER", () -> {
                if (generation != connectionGeneration) {
                    BleConnectGate.release("FARDRIVER");
                    return;
                }
                emit("connect", "");
                try {
                    BluetoothDevice device = adapter.getRemoteDevice(mac);
                    BluetoothGatt newGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                    gatt = newGatt;
                    if (newGatt == null) {
                        BleConnectGate.release("FARDRIVER");
                        callback.onDisconnected();
                        emit("fail", "gatt null");
                        return;
                    }
                    connectTimeoutRunnable = () -> {
                        if (generation != connectionGeneration || gatt != newGatt) return;
                        callback.onLog("Fardriver: timeout koneksi BLE > 15s");
                        try { newGatt.disconnect(); } catch (Exception ignored) {}
                        try { newGatt.close(); } catch (Exception ignored) {}
                        if (gatt == newGatt) { gatt = null; notifyChar = null; writeChar = null; }
                        BleConnectGate.release("FARDRIVER");
                        callback.onDisconnected();
                        emit("fail", "timeout 15s");
                        scheduleReconnect();
                    };
                    mainHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);
                } catch (SecurityException e) {
                    BleConnectGate.release("FARDRIVER");
                    callback.onDisconnected();
                    emit("fail", "izin BT");
                } catch (IllegalArgumentException e) {
                    BleConnectGate.release("FARDRIVER");
                    callback.onDisconnected();
                }
            });
        }, 350);
    }

    public void disconnect() {
        manualStop = true;
        reconnecting = false;
        fullyConnected = false;
        cancelReconnect();
        ++connectionGeneration;
        stopRssiPolling();
        clearConnectTimeout();
        clearWriteQueue();
        BluetoothGatt old = gatt;
        gatt = null;
        notifyChar = null;
        writeChar = null;
        if (old != null) {
            try { old.disconnect(); } catch (Exception ignored) {}
            try { old.close(); } catch (Exception ignored) {}
        }
        BleConnectGate.release("FARDRIVER");
    }

    public void writeRawHex(String hex) {
        if (hex == null || hex.isEmpty()) return;
        writeRaw(hexToBytes(hex));
    }

    public void writeRaw(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        if (gatt == null || writeChar == null) return;
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
            g = gatt;
            ch = writeChar;
            if (g == null || ch == null) {
                writeQueue.clear();
                return;
            }
            payload = writeQueue.peek();
            writeInFlight = true;
        }
        try {
            int props = ch.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            } else {
                ch.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
            ch.setValue(payload);
            if (!g.writeCharacteristic(ch)) {
                synchronized (writeQueue) { writeInFlight = false; }
                mainHandler.postDelayed(this::pumpWriteQueue, 180);
                return;
            }
            synchronized (writeQueue) {
                if (!writeQueue.isEmpty()) writeQueue.poll();
                writeInFlight = false;
            }
            if (!writeQueue.isEmpty()) {
                writePumpRunnable = this::pumpWriteQueue;
                mainHandler.postDelayed(writePumpRunnable, WRITE_GAP_MS);
            }
        } catch (Exception e) {
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

    private void failSetup() {
        BleConnectGate.release("FARDRIVER");
        callback.onDisconnected();
        emit("fail", setupStep.isEmpty() ? "setup" : setupStep);
        scheduleReconnect();
    }

    private void cancelReconnect() {
        if (reconnectRunnable != null) {
            mainHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }
    }

    private void scheduleReconnect() {
        if (manualStop || lastMac == null || reconnectRunnable != null) return;
        if (!(fullyConnected || reconnecting)) return;
        fullyConnected = false;
        if (reconnectAttempts >= RECONNECT_MAX) {
            reconnecting = false;
            emit("fail", "sambung ulang habis");
            return;
        }
        final long delay = BleCoex.stretch(RECONNECT_DELAYS_MS[Math.min(reconnectAttempts, RECONNECT_DELAYS_MS.length - 1)]);
        reconnectAttempts++;
        reconnecting = true;
        emit("retry", reconnectAttempts + "/" + RECONNECT_MAX);
        reconnectRunnable = () -> {
            reconnectRunnable = null;
            if (manualStop || lastMac == null) return;
            retryInFlight = true;
            connect(lastMac);
        };
        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    private void abortStuckSetup() {
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

    private void startRssiPolling() {
        stopRssiPolling();
        final int gen = connectionGeneration;
        rssiRunnable = new Runnable() {
            @Override
            public void run() {
                if (gen != connectionGeneration || !fullyConnected || gatt == null) {
                    rssiRunnable = null;
                    return;
                }
                try {
                    if (!(writeInFlight || !writeQueue.isEmpty())) gatt.readRemoteRssi();
                } catch (RuntimeException ignored) {}
                mainHandler.postDelayed(this, RSSI_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(rssiRunnable, 1000);
    }

    private void stopRssiPolling() {
        if (rssiRunnable != null) {
            mainHandler.removeCallbacks(rssiRunnable);
            rssiRunnable = null;
        }
    }

    private void emit(String state, String detail) {
        if ("connect".equals(state)) setupStep = "";
        if ("setup".equals(state)) setupStep = detail;
        try { callback.onState(state, detail); } catch (Exception ignored) {}
    }

    private boolean isCurrentGatt(BluetoothGatt g) { return g != null && g == gatt; }

    private static boolean isNotifyCapable(BluetoothGattCharacteristic ch) {
        if (ch == null) return false;
        int p = ch.getProperties();
        return (p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0;
    }

    private static boolean isWriteCapable(BluetoothGattCharacteristic ch) {
        if (ch == null) return false;
        int p = ch.getProperties();
        return (p & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0;
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (!isCurrentGatt(g)) {
                try { g.close(); } catch (Exception ignored) {}
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clearConnectTimeout();
                emit("setup", "service");
                try {
                    try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) {}
                    if (!g.requestMtu(247)) g.discoverServices();
                } catch (SecurityException e) {
                    failSetup();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                stopRssiPolling();
                clearConnectTimeout();
                clearWriteQueue();
                notifyChar = null;
                writeChar = null;
                BleConnectGate.release("FARDRIVER");
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
            try { g.discoverServices(); } catch (SecurityException e) { failSetup(); }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            emit("setup", "notif");
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failSetup();
                return;
            }
            notifyChar = null;
            writeChar = null;

            // 1) Prioritas FF00 (dongle Fardriver standar: FF01 notify, FF02 write)
            for (BluetoothGattService svc : g.getServices()) {
                if (!FD_SERVICE_FF00.equals(svc.getUuid())) continue;
                BluetoothGattCharacteristic n = svc.getCharacteristic(FD_CHAR_FF01);
                BluetoothGattCharacteristic w = svc.getCharacteristic(FD_CHAR_FF02);
                if (isNotifyCapable(n)) notifyChar = n;
                if (isWriteCapable(w)) writeChar = w;
                if (notifyChar != null && writeChar != null) break;
            }
            // 2) Fallback FFE0 (adapter BLE UART umum: FFE1 notify, FFE2/FFF2/FFE1 write)
            if (notifyChar == null) {
                for (BluetoothGattService svc : g.getServices()) {
                    if (!FD_SERVICE_FFE0.equals(svc.getUuid())) continue;
                    BluetoothGattCharacteristic n = svc.getCharacteristic(FD_CHAR_FFE1);
                    if (isNotifyCapable(n)) notifyChar = n;
                    for (UUID wUuid : new UUID[]{FD_CHAR_FFE2, FD_CHAR_FFF2, FD_CHAR_FFE1}) {
                        if (writeChar == null) {
                            BluetoothGattCharacteristic w = svc.getCharacteristic(wUuid);
                            if (isWriteCapable(w)) writeChar = w;
                        }
                    }
                    if (notifyChar != null) break;
                }
            }
            // 3) Fallback generik: cari characteristic notify & write non-standard-GAP/GATT
            if (notifyChar == null) {
                for (BluetoothGattService svc : g.getServices()) {
                    String su = svc.getUuid().toString().toLowerCase(Locale.US);
                    if (su.startsWith("00001800") || su.startsWith("00001801")) continue;
                    for (BluetoothGattCharacteristic ch : svc.getCharacteristics()) {
                        if (notifyChar == null && isNotifyCapable(ch)) notifyChar = ch;
                        if (writeChar == null && isWriteCapable(ch)) writeChar = ch;
                    }
                }
            }
            if (notifyChar == null) {
                callback.onLog("Fardriver: characteristic notify tidak ditemukan");
                failSetup();
                return;
            }
            if (writeChar == null && isWriteCapable(notifyChar)) {
                writeChar = notifyChar;
            }

            try {
                g.setCharacteristicNotification(notifyChar, true);
                BluetoothGattDescriptor descriptor = notifyChar.getDescriptor(CCCD_UUID);
                if (descriptor != null) {
                    int props = notifyChar.getProperties();
                    byte[] val = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                        ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                    descriptor.setValue(val);
                    if (!g.writeDescriptor(descriptor)) failSetup();
                } else {
                    BleConnectGate.release("FARDRIVER");
                    reconnectAttempts = 0; reconnecting = false; fullyConnected = true;
                    startRssiPolling();
                    String name = null;
                    try { name = g.getDevice().getName(); } catch (SecurityException ignored) {}
                    callback.onConnected(name != null ? name : "Fardriver BLE");
                }
            } catch (SecurityException e) {
                failSetup();
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt g, int rssi, int status) {
            if (g == gatt && status == BluetoothGatt.GATT_SUCCESS) {
                emit("rssi", String.valueOf(rssi));
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failSetup();
                return;
            }
            String name = null;
            try { name = g.getDevice().getName(); } catch (SecurityException ignored) {}
            BleConnectGate.release("FARDRIVER");
            reconnectAttempts = 0;
            reconnecting = false;
            fullyConnected = true;
            startRssiPolling();
            callback.onConnected(name != null ? name : "Fardriver BLE");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
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
}
