package com.gesits.dashboard;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.RemoteViews;

/**
 * Widget home screen GESITS Dashboard — nampilin SOC baterai, estimasi
 * jarak sisa, kecepatan, dan status koneksi motor secara glanceable tanpa
 * perlu buka app.
 *
 * Widget ini TIDAK narik data sendiri (gak ada BLE/koneksi motor di proses
 * widget) — data-nya di-push dari WebView (index.html) tiap kali telemetri
 * diperbarui, lewat MainActivity.JsBridge#updateWidgetData(), yang nyimpen
 * ke SharedPreferences lalu manggil pushUpdate() di sini buat re-render
 * semua instance widget yg lagi terpasang di home screen user.
 *
 * Widget juga RESPONSIF terhadap ukuran — kalau user resize widget jadi
 * kecil (2x1), field sekunder (range, kecepatan, progress bar, timestamp)
 * disembunyikan otomatis biar gak overflow; makin gede widget-nya, makin
 * banyak info yg ditampilin. Ini ditangani di onAppWidgetOptionsChanged().
 *
 * updatePeriodMillis di widget_gesits_info.xml cuma fallback (widget tetap
 * dapat data terakhir kalau app lama gak dibuka) — update realtime murni
 * dari push, bukan dari AppWidgetProvider#onUpdate polling.
 */
public class GesitsWidgetProvider extends AppWidgetProvider {

    static final String PREFS_NAME = "gesits_widget_prefs";
    static final String KEY_SOC        = "soc";           // float, -1 = belum ada data
    static final String KEY_RANGE_KM   = "range_km";       // float, -1 = belum ada data
    static final String KEY_SPEED_KMH  = "speed_kmh";      // float, -1 = belum ada data
    static final String KEY_CONNECTED  = "connected";      // boolean
    static final String KEY_UPDATED_AT = "updated_at_txt"; // String label singkat, mis. "2 mnt lalu"

    // Ambang ukuran (dp) buat nentuin field mana yg pas ditampilin.
    // Nilai dari OPTION_APPWIDGET_MIN_WIDTH/HEIGHT (estimasi ukuran widget
    // saat ini di launcher user).
    private static final int WIDTH_THRESHOLD_SHOW_RANGE = 150; // dp
    private static final int HEIGHT_THRESHOLD_SHOW_EXTRA = 100; // dp — speed/socbar/timestamp

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            updateOne(context, appWidgetManager, id);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                           int appWidgetId, Bundle newOptions) {
        // Dipanggil tiap kali user resize widget di home screen — re-render
        // dengan visibilitas field yg sesuai ukuran baru.
        updateOne(context, appWidgetManager, appWidgetId);
    }

    /** Dipanggil dari MainActivity begitu ada data baru dari WebView. */
    static void pushUpdate(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName thisWidget = new ComponentName(context, GesitsWidgetProvider.class);
        int[] ids = mgr.getAppWidgetIds(thisWidget);
        for (int id : ids) {
            updateOne(context, mgr, id);
        }
    }

    private static void updateOne(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        float soc         = prefs.getFloat(KEY_SOC, -1f);
        float rangeKm      = prefs.getFloat(KEY_RANGE_KM, -1f);
        float speedKmh      = prefs.getFloat(KEY_SPEED_KMH, -1f);
        boolean connected  = prefs.getBoolean(KEY_CONNECTED, false);
        String updatedAt   = prefs.getString(KEY_UPDATED_AT, "—");

        // Ukuran widget saat ini (dp) — dipakai buat nentuin field mana yg
        // ditampilin. minWidth/minHeight adalah ukuran TERKECIL widget bisa
        // di-resize ke posisi saat ini, cukup representatif buat threshold.
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        int widthDp  = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250) : 250;
        int heightDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110) : 110;
        boolean showRange = widthDp >= WIDTH_THRESHOLD_SHOW_RANGE;
        boolean showExtra = heightDp >= HEIGHT_THRESHOLD_SHOW_EXTRA;

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_gesits);

        if (soc >= 0f) {
            int socRounded = Math.round(soc);
            views.setTextViewText(R.id.widgetSoc, socRounded + "%");
            views.setViewVisibility(R.id.widgetSocBar,
                showExtra ? android.view.View.VISIBLE : android.view.View.GONE);
            // RemoteViews native support buat ProgressBar — gak perlu drawable custom.
            views.setProgressBar(R.id.widgetSocBar, 100, socRounded, false);
            int socColor = soc <= 20 ? Color.parseColor("#ff5252")
                          : soc <= 40 ? Color.parseColor("#ffa726")
                          : Color.parseColor("#00e676");
            views.setTextColor(R.id.widgetSoc, socColor);
        } else {
            views.setTextViewText(R.id.widgetSoc, "—");
            views.setViewVisibility(R.id.widgetSocBar, android.view.View.GONE);
        }

        // Range: cuma muncul kalau widget cukup lebar
        views.setViewVisibility(R.id.widgetRangeBlock,
            showRange ? android.view.View.VISIBLE : android.view.View.GONE);
        if (showRange) {
            views.setTextViewText(R.id.widgetRange, rangeKm >= 0f ? Math.round(rangeKm) + " km" : "— km");
        }

        // Kecepatan: cuma muncul kalau widget cukup tinggi DAN motor lagi konek
        boolean showSpeed = showExtra && connected && speedKmh >= 0f;
        views.setViewVisibility(R.id.widgetSpeedRow,
            showSpeed ? android.view.View.VISIBLE : android.view.View.GONE);
        if (showSpeed) {
            views.setTextViewText(R.id.widgetSpeed, Math.round(speedKmh) + " km/h");
        }

        // Timestamp: cuma muncul kalau widget cukup tinggi
        views.setViewVisibility(R.id.widgetUpdatedAt,
            showExtra ? android.view.View.VISIBLE : android.view.View.GONE);
        if (showExtra) {
            views.setTextViewText(R.id.widgetUpdatedAt, updatedAt);
        }

        views.setTextViewText(R.id.widgetStatus, connected ? "🟢 Terhubung" : "⚪ Terputus");

        // Tap widget → buka app langsung ke Home
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.putExtra("open_tab", "home");
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            context, appWidgetId, launchIntent, flags);
        views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }
}
