package com.gesits.dashboard;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

/**
 * WebView yang tetap dianggap "terlihat" oleh Chromium walau layar mati / Activity tidak di depan, selama
 * {@link #forceVisible} = true (diset MainActivity.onPause saat peta native / mirror / Live HUD aktif).
 *
 * Tanpa ini, begitu layar mati Android mengirim visibility window = GONE ke WebView. Chromium lalu menganggap halaman
 * tersembunyi: geolocation WebView dijeda, timer diredam, dan JS praktis berhenti. Akibatnya data AT+NAV, tanjakan, dan
 * reroute (semuanya di JS) membeku sampai layar dibuka lagi. Dengan override ini window tetap dilaporkan VISIBLE ke WebView.
 *
 * Konsekuensi: WebView terus berjalan saat layar mati → baterai lebih boros. Matikan dengan KeepAliveWebView.forceVisible = false
 * (atau ganti `new KeepAliveWebView(this)` di MainActivity kembali ke `new WebView(this)`).
 */
public class KeepAliveWebView extends WebView {
    public static volatile boolean forceVisible = false;

    public KeepAliveWebView(Context context) {
        super(context);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(forceVisible ? View.VISIBLE : visibility);
    }
}
