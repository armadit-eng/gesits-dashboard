package com.gesits.dashboard;

import android.os.Handler;
import android.os.Looper;

/**
 * Gerbang sederhana buat serialize fase SETUP koneksi BLE (scan → connectGatt
 * → MTU negotiation → service discovery → enable notification) antara
 * BmsBleManager dan Esp32BleManager.
 *
 * KENAPA INI PERLU: Android BLE stack sering gak stabil kalau 2 koneksi GATT
 * dinegosiasi BERSAMAAN dari 1 app — biasanya muncul sebagai status 133
 * (GATT_ERROR) di salah satu koneksi yang sudah aktif duluan, persis pas
 * koneksi kedua lagi proses connect. Begitu KEDUA koneksi sudah settle
 * (notification aktif, gak ada operasi GATT lagi yg pending), mereka biasanya
 * bisa hidup berdampingan tanpa masalah — makanya lock ini CUMA berlaku
 * selama fase setup, bukan sepanjang siklus hidup koneksi.
 *
 * Cara pakai: panggil acquire() sebelum connectGatt(), lalu WAJIB panggil
 * release() setelah setup selesai (baik sukses di titik "fully connected",
 * maupun gagal/disconnect) — kalau lupa release(), device lain bakal
 * nunggu terus (deadlock).
 */
public final class BleConnectGate {
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final java.util.ArrayDeque<Pending> queue = new java.util.ArrayDeque<>();
    private static String holder = null;

    // Watchdog: kalau pemegang gerbang macet (mis. setup GATT berhenti di tengah jalan
    // setelah CONNECTED), gerbang dilepas paksa setelah HOLD_TIMEOUT_MS supaya perangkat
    // lain di antrean tidak menunggu selamanya.
    private static final long HOLD_TIMEOUT_MS = 25000;
    private static Runnable watchdog = null;
    private static final java.util.HashMap<String, Runnable> abortHooks = new java.util.HashMap<>();

    /** Manager mendaftarkan aksi "batalkan setup yang macet" untuk dirinya. */
    public static synchronized void setAbortHook(String who, Runnable r) {
        if (who != null) abortHooks.put(who, r);
    }

    /** "" kalau gerbang bebas, selain itu "menunggu <pemegang>" (buat status di UI). */
    public static synchronized String waitingFor() {
        return holder == null ? "" : "menunggu " + holder;
    }

    private static void cancelWatchdog() {
        if (watchdog != null) { mainHandler.removeCallbacks(watchdog); watchdog = null; }
    }

    private static void armWatchdog(final String who) {
        cancelWatchdog();
        watchdog = () -> {
            Runnable hook;
            synchronized (BleConnectGate.class) {
                if (holder == null || !holder.equals(who)) return;
                android.util.Log.w("BleConnectGate", who + " memegang gerbang > " + HOLD_TIMEOUT_MS + "ms — dilepas paksa");
                hook = abortHooks.get(who);
            }
            try { if (hook != null) hook.run(); } catch (Exception ignored) {}
            release(who);
        };
        mainHandler.postDelayed(watchdog, HOLD_TIMEOUT_MS);
    }

    public interface Ready { void onReady(); }
    private static final class Pending {
        final String who; final Ready ready;
        Pending(String who, Ready ready) { this.who = who; this.ready = ready; }
    }

    /** Strict FIFO: tidak ada force-bypass yang bisa membuat dua setup GATT bersamaan. */
    public static synchronized void acquire(String who, Ready ready) {
        if (who == null || ready == null) return;
        if (holder == null && queue.isEmpty()) {
            holder = who;
            armWatchdog(who);
            android.util.Log.d("BleConnectGate", who + " dapat gerbang koneksi");
            ready.onReady();
        } else {
            queue.offer(new Pending(who, ready));
            android.util.Log.d("BleConnectGate", who + " masuk antrean BLE (pemegang=" + holder + ", antre=" + queue.size() + ")");
        }
    }

    public static synchronized void release(String who) {
        if (who == null || holder == null || !holder.equals(who)) return;
        holder = null;
        cancelWatchdog();
        android.util.Log.d("BleConnectGate", who + " lepas gerbang koneksi");
        if (!queue.isEmpty()) {
            Pending next = queue.poll();
            holder = next.who;
            armWatchdog(next.who);
            android.util.Log.d("BleConnectGate", next.who + " dapat gerbang dari antrean (sisa=" + queue.size() + ")");
            mainHandler.post(next.ready::onReady);
        }
    }
}
