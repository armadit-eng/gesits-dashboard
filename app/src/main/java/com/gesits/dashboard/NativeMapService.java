package com.gesits.dashboard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Service foreground bertipe LOCATION untuk peta native.
 * Sejak Android 10, aplikasi yang tidak tampil hanya dapat update GPS kalau punya service foreground bertipe location.
 * Tanpa ini, saat layar mati / aplikasi di-minimize, GPS native berhenti dan peta di MID membeku di posisi terakhir.
 * Dijalankan / dihentikan oleh NativeMapStreamer.start() / stop() — tidak berisi logika lain.
 */
public class NativeMapService extends Service {
    private static final String TAG = "NativeMapSvc";
    private static final String CHANNEL = "gesits_nativemap";
    private static final int NOTIF_ID = 7311;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "Peta navigasi ke MID", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
            b.setContentTitle("Peta navigasi aktif")
             .setContentText("Mengirim peta ke MID (GPS tetap jalan di latar belakang)")
             .setSmallIcon(android.R.drawable.ic_dialog_map)
             .setOngoing(true);
            Notification n = b.build();
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            Log.w(TAG, "startForeground gagal: " + e.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        try { stopForeground(true); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
