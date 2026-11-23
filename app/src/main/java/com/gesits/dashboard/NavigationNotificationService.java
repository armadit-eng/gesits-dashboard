package com.gesits.dashboard;

import android.app.Notification;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/** Reads only visible notification text from Google Maps; it does not access Maps internals. */
public class NavigationNotificationService extends NotificationListenerService {
    public static final String ACTION_NAV_UPDATE = "com.gesits.dashboard.ACTION_NAV_UPDATE";
    public static final String EXTRA_NAV_TEXT = "nav_text";
    private static final String TAG = "GesitsNavListener";
    private static final String MAPS_PACKAGE = "com.google.android.apps.maps";
    private String lastSent = "";
    private long lastSentAt = 0;

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !MAPS_PACKAGE.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        Bundle e = n.extras;
        List<String> parts = new ArrayList<>();
        add(parts, e.getCharSequence(Notification.EXTRA_TITLE));
        add(parts, e.getCharSequence(Notification.EXTRA_TEXT));
        add(parts, e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) for (CharSequence line : lines) add(parts, line);
        add(parts, n.tickerText);
        String text = TextUtils.join(" — ", parts).trim();
        if (text.isEmpty()) return;

        // Avoid sending identical notification refreshes repeatedly.
        long now = System.currentTimeMillis();
        if (text.equals(lastSent) && now - lastSentAt < 8000) return;
        lastSent = text;
        lastSentAt = now;
        Log.d(TAG, "Maps navigation notification: " + text);
        Intent intent = new Intent(ACTION_NAV_UPDATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_NAV_TEXT, text);
        sendBroadcast(intent);
    }

    private static void add(List<String> out, CharSequence value) {
        if (value == null) return;
        String s = value.toString().replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (!s.isEmpty() && !out.contains(s)) out.add(s);
    }
}
