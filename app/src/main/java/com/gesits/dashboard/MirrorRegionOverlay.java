package com.gesits.dashboard;

/*
 * MirrorRegionOverlay — menandai area layar yang benar-benar dikirim ke MID saat mirror layar.
 *
 * Digambar sebagai jendela overlay (di atas semua aplikasi, tidak bisa disentuh):
 *   - bagian DI LUAR area diredupkan
 *   - garis tepi hijau digambar di LUAR area (bukan di dalamnya)
 * Jadi overlay ini tidak ikut tertangkap ke frame mirror.
 *
 * Area yang digambar = area EFEKTIF, yaitu hasil crop yang sudah dipangkas ke rasio ukuran kirim persis
 * seperti di ScreenCaptureService.grabAndSend() — bukan sekadar angka batas yang diisi user.
 *
 * Butuh izin "Tampil di atas aplikasi lain": tambahkan di AndroidManifest.xml
 *   <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>
 */

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

public final class MirrorRegionOverlay {
    private static final String TAG = "MirrorRegion";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable autoHide = MirrorRegionOverlay::hideNow;
    private static WindowManager wm;
    private static View view;

    private MirrorRegionOverlay() {}

    public static boolean canDraw(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    /** crop = {kiri, atas, kanan, bawah} sebagai pecahan 0..1. autoHideMs 0 = tampil terus. */
    public static void show(final Context ctx, final float[] crop, final long autoHideMs) {
        final Context app = ctx.getApplicationContext();
        main.post(() -> showNow(app, crop, autoHideMs));
    }

    public static void hide() { main.post(MirrorRegionOverlay::hideNow); }

    // ── internal (selalu di main thread) ──

    @SuppressWarnings("deprecation")
    private static void showNow(Context app, float[] crop, long autoHideMs) {
        hideNow();
        if (!canDraw(app) || crop == null || crop.length != 4) return;
        try {
            wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics real = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(real);
            float[] eff = effective(crop, real.widthPixels, real.heightPixels);   // basis sama dgn ScreenCaptureService (ukuran asli layar)

            RegionView v = new RegionView(app, eff, real.density);
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    real.widthPixels, real.heightPixels, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0; lp.y = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            wm.addView(v, lp);
            view = v;
            main.removeCallbacks(autoHide);
            if (autoHideMs > 0) main.postDelayed(autoHide, autoHideMs);
        } catch (Exception e) {
            Log.e(TAG, "gagal tampilkan overlay: " + e.getMessage());
            view = null;
        }
    }

    private static void hideNow() {
        main.removeCallbacks(autoHide);
        if (view != null && wm != null) {
            try { wm.removeView(view); } catch (Exception ignored) {}
        }
        view = null;
    }

    /** Area efektif (pecahan layar) setelah cover-crop ke rasio ukuran kirim — logika sama dgn grabAndSend(). */
    static float[] effective(float[] c, int fw, int fh) {
        int l = Math.round(fw * c[0]), t = Math.round(fh * c[1]);
        int r = Math.round(fw * c[2]), b = Math.round(fh * c[3]);
        int rw = Math.max(1, r - l), rh = Math.max(1, b - t);
        int outW = Math.max(64, Math.min(1024, ScreenCaptureService.reqW));   // sama dgn ScreenCaptureService
        int outH = Math.max(64, Math.min(1024, ScreenCaptureService.reqH));
        float ratio = (float) outW / outH;
        int sw, sh;
        if ((float) rw / rh > ratio) { sh = rh; sw = Math.round(sh * ratio); }
        else { sw = rw; sh = Math.round(sw / ratio); }
        int sx = l + (rw - sw) / 2, sy = t + (rh - sh) / 2;
        return new float[]{(float) sx / fw, (float) sy / fh, (float) (sx + sw) / fw, (float) (sy + sh) / fh};
    }

    private static final class RegionView extends View {
        private final float[] eff;
        private final float dp;
        private final Paint dim = new Paint();
        private final Paint edge = new Paint();
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);

        RegionView(Context c, float[] eff, float density) {
            super(c);
            this.eff = eff;
            this.dp = density;
            dim.setColor(0x66000000);
            edge.setColor(0xFF00E676);
            txt.setColor(Color.WHITE);
            txt.setTextSize(10f * density);
            txt.setFakeBoldText(true);
            txt.setShadowLayer(3f * density, 0, 0, Color.BLACK);
        }

        @Override protected void onDraw(Canvas cv) {
            float W = getWidth(), H = getHeight();
            float L = eff[0] * W, T = eff[1] * H, R = eff[2] * W, B = eff[3] * H;
            // Garis tepi dijauhkan dari area (celah g) supaya selisih beberapa piksel antara overlay dan
            // hasil capture tidak membuat garisnya ikut terkirim ke MID.
            float g = 5f * dp, s = 3f * dp;
            float oL = L - g, oT = T - g, oR = R + g, oB = B + g;   // batas dalam garis

            cv.drawRect(0, 0, W, oT - s, dim);
            cv.drawRect(0, oB + s, W, H, dim);
            cv.drawRect(0, oT - s, oL - s, oB + s, dim);
            cv.drawRect(oR + s, oT - s, W, oB + s, dim);

            cv.drawRect(oL - s, oT - s, oR + s, oT, edge);
            cv.drawRect(oL - s, oB, oR + s, oB + s, edge);
            cv.drawRect(oL - s, oT, oL, oB, edge);
            cv.drawRect(oR, oT, oR + s, oB, edge);

            String label = String.format(Locale.US, "AREA REKAM  kiri %d%% | kanan %d%% | atas %d%% | bawah %d%%",
                    Math.round(eff[0] * 100), Math.round(eff[2] * 100),
                    Math.round(eff[1] * 100), Math.round(eff[3] * 100));
            float x = Math.max(8f * dp, L);
            if (oT - s > 22f * dp) cv.drawText(label, x, oT - s - 6f * dp, txt);
            else if (H - oB - s > 28f * dp) cv.drawText(label, x, oB + s + 16f * dp, txt);
        }
    }
}
