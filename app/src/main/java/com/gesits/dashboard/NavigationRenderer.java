package com.gesits.dashboard;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Draws a compact, high-contrast navigation card and returns PNG Base64 (no data: prefix). */
public final class NavigationRenderer {
    private NavigationRenderer() {}
    public static final int MAX_BASE64_CHARS = 7000;
    private static final Pattern DISTANCE = Pattern.compile("(?i)\\b(\\d+(?:[.,]\\d+)?)\\s*(km|m|mi|ft)\\b");

    public static String renderBase64(String raw) {
        if (raw == null) return null;
        String text = clean(raw);
        int[][] sizes = {{350,220},{320,200},{280,176},{240,150}};
        for (int[] size : sizes) {
            Bitmap bitmap = render(text, size[0], size[1]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            bitmap.recycle();
            String encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            if (encoded.length() <= MAX_BASE64_CHARS) return encoded;
        }
        return null;
    }

    private static Bitmap render(String text, int w, int h) {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.BLACK);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, w / 100f));
        c.drawRoundRect(w*0.015f,h*0.035f,w*0.985f,h*0.965f, w*0.035f,h*0.05f,p);

        String lower = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        String direction = direction(lower);
        Matcher m = DISTANCE.matcher(text);
        String distance = "";
        if (m.find()) distance = m.group(1).replace(',', '.') + " " + m.group(2).toLowerCase(Locale.ROOT);
        if (distance.isEmpty()) distance = "NAVIGASI";

        // Left-hand vector arrow, intentionally simple so it survives small MID screens.
        float cx = w * 0.23f, cy = h * 0.48f, unit = Math.min(w,h) * 0.31f;
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(6f, w*0.025f));
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        if ("left".equals(direction)) {
            path.moveTo(cx+unit*0.42f, cy+unit*0.70f); path.lineTo(cx+unit*0.42f,cy-unit*0.05f); path.lineTo(cx-unit*0.30f,cy-unit*0.05f);
            path.moveTo(cx-unit*0.30f,cy-unit*0.05f); path.lineTo(cx+unit*0.02f,cy-unit*0.38f);
            path.moveTo(cx-unit*0.30f,cy-unit*0.05f); path.lineTo(cx+unit*0.02f,cy+unit*0.28f);
        } else if ("right".equals(direction)) {
            path.moveTo(cx-unit*0.42f, cy+unit*0.70f); path.lineTo(cx-unit*0.42f,cy-unit*0.05f); path.lineTo(cx+unit*0.30f,cy-unit*0.05f);
            path.moveTo(cx+unit*0.30f,cy-unit*0.05f); path.lineTo(cx-unit*0.02f,cy-unit*0.38f);
            path.moveTo(cx+unit*0.30f,cy-unit*0.05f); path.lineTo(cx-unit*0.02f,cy+unit*0.28f);
        } else if ("uturn".equals(direction)) {
            path.moveTo(cx+unit*0.35f,cy+unit*0.5f); path.lineTo(cx+unit*0.35f,cy-unit*0.15f);
            path.cubicTo(cx+unit*0.35f,cy-unit*0.55f,cx-unit*0.35f,cy-unit*0.55f,cx-unit*0.35f,cy-unit*0.15f);
            path.moveTo(cx-unit*0.35f,cy-unit*0.15f); path.lineTo(cx-unit*0.62f,cy-unit*0.15f); path.moveTo(cx-unit*0.35f,cy-unit*0.15f); path.lineTo(cx-unit*0.35f,cy+unit*0.12f);
        } else {
            path.moveTo(cx,cy+unit*0.65f); path.lineTo(cx,cy-unit*0.35f);
            path.moveTo(cx,cy-unit*0.35f); path.lineTo(cx-unit*0.32f,cy-unit*0.02f);
            path.moveTo(cx,cy-unit*0.35f); path.lineTo(cx+unit*0.32f,cy-unit*0.02f);
        }
        c.drawPath(path,p);

        p.setStyle(Paint.Style.FILL); p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        p.setTextAlign(Paint.Align.LEFT); p.setTextSize(h*0.23f);
        float tx=w*0.47f; c.drawText(distance,tx,h*0.36f,p);
        p.setTextSize(h*0.105f); p.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        c.drawText("ARAH BERIKUTNYA",tx,h*0.46f,p);

        String road = roadText(text);
        p.setTextSize(h*0.105f); p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
        drawWrapped(c,p,road,tx,h*0.60f,w-tx-w*0.055f,h*0.13f,2);
        p.setTextSize(h*0.075f); p.setColor(0xFFB8B8B8);
        c.drawText("GOOGLE MAPS",w*0.05f,h*0.91f,p);
        return bmp;
    }

    private static String direction(String s) {
        if (s.contains("putar balik") || s.contains("u-turn") || s.contains("uturn") || s.contains("make a u turn")) return "uturn";
        if (s.contains("belok kiri") || s.contains("turn left") || s.contains("keep left") || s.contains("slight left") || s.contains("sharp left") || s.contains("left onto")) return "left";
        if (s.contains("belok kanan") || s.contains("turn right") || s.contains("keep right") || s.contains("slight right") || s.contains("sharp right") || s.contains("right onto")) return "right";
        return "straight";
    }
    private static String roadText(String s) {
        String t = s.replaceAll("(?i)Google Maps", "").replaceAll("(?i)\\b\\d+(?:[.,]\\d+)?\\s*(km|m|mi|ft)\\b", "");
        t = t.replaceAll("(?i)belok kiri|belok kanan|terus|ikuti|ambil|turn left|turn right|keep left|keep right|continue|then|in ", " ");
        t = t.replaceAll("[—|•]+", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 68) t = t.substring(0,65) + "…";
        return t.isEmpty() ? "Ikuti petunjuk Google Maps" : t;
    }
    private static void drawWrapped(Canvas c, Paint p, String text, float x, float y, float maxW, float lineH, int maxLines) {
        String[] words=text.split("\\s+"); String line=""; int lines=0;
        for (String word:words) {
            String candidate=line.isEmpty()?word:line+" "+word;
            if (p.measureText(candidate)>maxW && !line.isEmpty()) {
                c.drawText(line,x,y+lines*lineH,p); lines++; line=word;
                if(lines>=maxLines) return;
            } else line=candidate;
        }
        if(!line.isEmpty() && lines<maxLines) c.drawText(line,x,y+lines*lineH,p);
    }
    private static String clean(String s) { return s.replace('\n',' ').replaceAll("\\s+"," ").trim(); }
}
