package vio.bridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.location.Location;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Draws the VIO screen for the current Mode. The VIO reports a square
 * 320x320 frame but its glass is round, so everything is laid out inside the
 * inscribed circle and the corners are painted black.
 */
public final class Renderer {
    // Black and white with one neon accent. Greys and this blue (0,204,255) are
    // exact palette entries, so the VIO shows them without colour banding.
    private static final int BG = Color.BLACK;
    private static final int ACCENT = Color.rgb(0, 204, 255);
    private static final int DIM = Color.rgb(160, 160, 160);
    private static final int BANNER = Color.argb(235, 0, 0, 0);
    private static final int MARKER = ACCENT;
    private static final long MIRROR_STALE_MS = 3000;
    private static final float TILE_SCALE = 1.3f;
    private static final Pattern DISTANCE = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*(km|m|mi|ft|yd)\\b");
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final int w, h;
    private final Bitmap bmp;
    private final Canvas c;
    private final TileCache tiles;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    // Maps' manoeuvre icons are single-colour (often black); draw them in the accent.
    private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    {
        arrowPaint.setColorFilter(new PorterDuffColorFilter(ACCENT, PorterDuff.Mode.SRC_IN));
    }
    private final Path outsideCircle = new Path();
    private static volatile String toast;
    private static volatile long toastUntil;

    /** Briefly shows a label on the VIO, e.g. after a gesture. */
    static void toast(String text) {
        toast = text;
        toastUntil = System.currentTimeMillis() + 1500;
    }

    public Renderer(int w, int h, TileCache tiles) {
        this.w = w;
        this.h = h;
        this.tiles = tiles;
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        c = new Canvas(bmp);
        outsideCircle.setFillType(Path.FillType.EVEN_ODD);
        outsideCircle.addRect(0, 0, w, h, Path.Direction.CW);
        outsideCircle.addCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, Path.Direction.CW);
    }

    /** Width of the round screen at height y, minus a margin on both sides. */
    private float chord(float y, float margin) {
        float r = Math.min(w, h) / 2f, dy = Math.abs(y - h / 2f);
        return dy >= r ? 0 : 2 * (float) Math.sqrt(r * r - dy * dy) - 2 * margin;
    }

    public Bitmap draw(String battery) {
        c.drawColor(BG);
        int mode = Mode.current;
        boolean mirrorLive = ScreenMirror.active && ScreenMirror.latest != null
                && System.currentTimeMillis() - ScreenMirror.latestAt < MIRROR_STALE_MS;
        if (mode == Mode.MIRROR && mirrorLive) {
            drawMirror();
            if (NavState.active) drawBanner();
        } else if ((mode == Mode.MAP || mode == Mode.MIRROR) && LocationTracker.last != null) {
            // Mirror without a live capture (screen off, phone in a pocket) shows the map.
            drawMap();
            if (NavState.active) drawBanner();
            drawStatus(battery, true);
        } else if (NavState.active) {
            drawTurn();
            drawStatus(battery, false);
        } else {
            drawIdle(mode);
            drawStatus(battery, false);
        }
        String t = toast;
        if (t != null && System.currentTimeMillis() < toastUntil) {
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(h * 0.08f);
            float tw = tp.measureText(t) + w * 0.08f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(BANNER);
            RectF r = new RectF((w - tw) / 2, h * 0.76f, (w + tw) / 2, h * 0.87f);
            c.drawRoundRect(r, h * 0.055f, h * 0.055f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(ACCENT);
            c.drawRoundRect(r, h * 0.055f, h * 0.055f, p);
            tp.setColor(ACCENT);
            c.drawText(t, w / 2f, h * 0.84f, tp);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.BLACK);
        c.drawPath(outsideCircle, p);
        return bmp;
    }

    // ------------------------------------------------------------------ Turn

    private void drawTurn() {
        if (NavState.rerouting()) {
            drawRerouteIcon(w / 2f, h * 0.36f, h * 0.17f);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setColor(ACCENT);
            tp.setTextSize(fit("Rerouting…", h * 0.11f, chord(h * 0.70f, w * 0.06f)));
            c.drawText("Rerouting…", w / 2f, h * 0.70f, tp);
            return;
        }
        float arrowSize = h * 0.32f;
        RectF box = new RectF((w - arrowSize) / 2, h * 0.14f, (w + arrowSize) / 2, h * 0.14f + arrowSize);
        drawArrow(box);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setColor(ACCENT);
        tp.setTextSize(fit(NavState.distance, h * 0.14f, chord(h * 0.60f, w * 0.06f)));
        c.drawText(NavState.distance, w / 2f, h * 0.60f, tp);
        // Two lines from 0.64h to ~0.80h: limited by the narrower bottom line.
        drawWrapped(NavState.instruction, h * 0.062f, Color.WHITE, h * 0.64f, chord(h * 0.80f, w * 0.05f), 2);
        if (!NavState.eta.isEmpty()) {
            tp.setTypeface(Typeface.DEFAULT);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setColor(DIM);
            tp.setTextSize(fit(NavState.eta, h * 0.05f, chord(h * 0.89f, w * 0.04f)));
            c.drawText(NavState.eta, w / 2f, h * 0.89f, tp);
        }
    }

    private void drawIdle(int mode) {
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setColor(ACCENT);
        tp.setTextSize(h * 0.11f);
        c.drawText("VIO Bridge", w / 2f, h * 0.43f, tp);
        String hint = "Pick a destination in VIO Bridge";
        if (mode == Mode.MAP) hint = "Map: waiting for GPS…";
        else if (mode == Mode.MIRROR) hint = "Mirror: turn the phone screen on";
        drawWrapped(hint, h * 0.065f, DIM, h * 0.52f, chord(h * 0.66f, w * 0.06f), 2);
    }

    // ------------------------------------------------------------------- Map

    private void drawMap() {
        Location loc = LocationTracker.last;
        int meters = distanceMeters(NavState.distance);
        int z = (NavState.active && meters >= 0 && meters < 150 ? 17 : 16) + Mode.zoom;
        z = Math.max(12, Math.min(18, z));
        double world = 256.0 * (1 << z);
        double gx = (loc.getLongitude() + 180.0) / 360.0 * world;
        double latRad = Math.toRadians(loc.getLatitude());
        double gy = (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * world;

        // The rider sits just below the middle of the visible map (under the
        // turn banner when navigating) so a little more road ahead shows.
        float ax = w / 2f, ay = h * (NavState.active ? 0.62f : 0.50f);
        float tile = 256 * TILE_SCALE;
        float reach = (float) Math.hypot(w, h);
        c.drawColor(BG);
        c.save();
        c.rotate(-LocationTracker.heading, ax, ay);
        int tx0 = (int) Math.floor((gx - reach / TILE_SCALE) / 256);
        int tx1 = (int) Math.floor((gx + reach / TILE_SCALE) / 256);
        int ty0 = (int) Math.floor((gy - reach / TILE_SCALE) / 256);
        int ty1 = (int) Math.floor((gy + reach / TILE_SCALE) / 256);
        RectF dst = new RectF();
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                float left = ax + (float) ((tx * 256 - gx) * TILE_SCALE);
                float top = ay + (float) ((ty * 256 - gy) * TILE_SCALE);
                Bitmap t = tiles.get(z, tx, ty);
                if (t != null) {
                    dst.set(left, top, left + tile, top + tile);
                    c.drawBitmap(t, null, dst, p);
                }
            }
        }
        drawRoute(gx, gy, world, ax, ay);
        c.restore();

        // Rider marker: an arrow pointing up (direction of travel).
        float s = w * 0.045f;
        Path m = new Path();
        m.moveTo(ax, ay - s * 1.4f);
        m.lineTo(ax + s, ay + s);
        m.lineTo(ax, ay + s * 0.4f);
        m.lineTo(ax - s, ay + s);
        m.close();
        // Neon arrow on a black disc with a neon ring: stands out on the dark map.
        p.setStyle(Paint.Style.FILL);
        p.setColor(BG);
        c.drawCircle(ax, ay, s * 1.8f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(s * 0.3f);
        p.setColor(MARKER);
        c.drawCircle(ax, ay, s * 1.8f, p);
        p.setStyle(Paint.Style.FILL);
        c.drawPath(m, p);
    }

    /** The rest of the built-in route as a neon line, with the destination marked. */
    private void drawRoute(double gx, double gy, double world, float ax, float ay) {
        BikeRouter.Route r = Navigator.route;
        if (!Navigator.active || r == null) return;
        Path line = new Path();
        int from = Math.max(0, Math.min(Navigator.seg, r.pts.size() - 1));
        // The rider's own position starts the line so it never lags behind the marker.
        line.moveTo(ax, ay);
        for (int i = from + 1; i < r.pts.size(); i++) {
            double[] pt = r.pts.get(i);
            line.lineTo(ax + (float) ((mercX(pt[1], world) - gx) * TILE_SCALE),
                    ay + (float) ((mercY(pt[0], world) - gy) * TILE_SCALE));
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(BG);
        p.setStrokeWidth(w * 0.055f);
        c.drawPath(line, p);
        p.setColor(ACCENT);
        p.setStrokeWidth(w * 0.032f);
        c.drawPath(line, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        float dx = ax + (float) ((mercX(Navigator.destLon, world) - gx) * TILE_SCALE);
        float dy = ay + (float) ((mercY(Navigator.destLat, world) - gy) * TILE_SCALE);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        c.drawCircle(dx, dy, w * 0.035f, p);
        p.setColor(ACCENT);
        c.drawCircle(dx, dy, w * 0.018f, p);
    }

    private static double mercX(double lon, double world) {
        return (lon + 180.0) / 360.0 * world;
    }

    private static double mercY(double lat, double world) {
        double latRad = Math.toRadians(lat);
        return (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * world;
    }

    static int distanceMeters(String text) {
        if (text == null) return -1;
        Matcher mt = DISTANCE.matcher(text);
        if (!mt.find()) return -1;
        double v = Double.parseDouble(mt.group(1).replace(',', '.'));
        switch (mt.group(2)) {
            case "km": return (int) (v * 1000);
            case "mi": return (int) (v * 1609);
            case "ft": return (int) (v * 0.3048);
            case "yd": return (int) (v * 0.9144);
            default: return (int) v;
        }
    }

    // ---------------------------------------------------------------- Mirror

    private void drawMirror() {
        Bitmap src = ScreenMirror.latest;
        int sw = src.getWidth(), sh = src.getHeight();
        int side = Math.min(sw, sh);
        // Google Maps keeps its instruction card at the top; the map and the
        // current position sit just below the middle of the screen.
        int top = Math.max(0, Math.min(sh - side, (int) (sh * 0.55f) - side / 2));
        int left = (sw - side) / 2;
        c.drawBitmap(src, new Rect(left, top, left + side, top + side), new RectF(0, 0, w, h), p);
    }

    // --------------------------------------------------------------- Shared

    /** Neon circular arrow: an open ring with an arrowhead at its end. */
    private void drawRerouteIcon(float cx, float cy, float r) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.28f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(ACCENT);
        c.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -60, 300, false, p);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
        // Arrowhead at the -60° end of the arc, pointing clockwise.
        double a = Math.toRadians(-60);
        float ex = cx + (float) (r * Math.cos(a)), ey = cy + (float) (r * Math.sin(a));
        Path head = new Path();
        head.moveTo(ex + r * 0.45f, ey - r * 0.05f);
        head.lineTo(ex - r * 0.25f, ey - r * 0.45f);
        head.lineTo(ex - r * 0.05f, ey + r * 0.35f);
        head.close();
        c.drawPath(head, p);
    }

    /** Turn arrow, distance and street in a cap across the top of the round screen. */
    private void drawBanner() {
        float bh = h * 0.38f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(BANNER);
        c.drawRect(0, 0, w, bh, p);
        if (NavState.rerouting()) {
            drawRerouteIcon(w / 2f, h * 0.15f, h * 0.075f);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTypeface(Typeface.DEFAULT_BOLD);
            tp.setColor(ACCENT);
            tp.setTextSize(h * 0.09f);
            c.drawText("Rerouting…", w / 2f, h * 0.32f, tp);
            return;
        }
        // Arrow and distance side by side on the row where the circle is wide enough.
        float row = h * 0.20f, as = h * 0.15f;
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setColor(ACCENT);
        float rowWidth = chord(row - as / 2, w * 0.03f);
        float size = fit(NavState.distance, h * 0.12f, rowWidth - as - w * 0.03f);
        tp.setTextSize(size);
        float total = as + w * 0.03f + tp.measureText(NavState.distance);
        float x = (w - total) / 2;
        drawArrow(new RectF(x, row - as * 0.85f, x + as, row + as * 0.15f));
        c.drawText(NavState.distance, x + as + w * 0.03f, row, tp);
        // Instruction centred on one or two lines under it.
        drawWrapped(NavState.instruction, h * 0.052f, Color.WHITE, h * 0.235f, chord(h * 0.235f, w * 0.05f), 2);
    }

    /** Clock and VIO battery, centred where the round screen still shows them. */
    private void drawStatus(String battery, boolean onMap) {
        String text = CLOCK.format(new Date()) + (battery != null ? "  ·  " + battery : "");
        float y = onMap && NavState.active ? h * 0.955f : h * 0.10f;
        if (onMap) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(BANNER);
            if (NavState.active) c.drawRect(0, h * 0.91f, w, h, p);
            else c.drawRect(0, 0, w, h * 0.13f, p);
        }
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setColor(DIM);
        tp.setTextSize(fit(text, h * 0.05f, chord(y - h * 0.025f, w * 0.02f)));
        c.drawText(text, w / 2f, y, tp);
    }

    private void drawArrow(RectF box) {
        Bitmap arrow = NavState.arrow;
        if (arrow != null) {
            c.drawBitmap(arrow, new Rect(0, 0, arrow.getWidth(), arrow.getHeight()), box, arrowPaint);
            return;
        }
        Path a = new Path();
        float cx = box.centerX(), t = box.width() * 0.16f, mid = box.top + box.height() * 0.45f;
        a.moveTo(cx, box.top);
        a.lineTo(box.right - box.width() * 0.1f, mid);
        a.lineTo(cx + t, mid);
        a.lineTo(cx + t, box.bottom);
        a.lineTo(cx - t, box.bottom);
        a.lineTo(cx - t, mid);
        a.lineTo(box.left + box.width() * 0.1f, mid);
        a.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(ACCENT);
        c.drawPath(a, p);
    }

    /** Centred text on up to maxLines lines, ellipsised to fit the given width. */
    private void drawWrapped(String text, float size, int color, float top, float width, int maxLines) {
        if (text == null || text.isEmpty() || width <= 0) return;
        tp.setTypeface(Typeface.DEFAULT);
        tp.setTextAlign(Paint.Align.LEFT);
        tp.setTextSize(size);
        tp.setColor(color);
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), tp, (int) width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
        c.save();
        c.translate((w - width) / 2f, top);
        layout.draw(c);
        c.restore();
    }

    private float fit(String text, float size, float maxWidth) {
        tp.setTextSize(size);
        float tw = tp.measureText(text == null ? "" : text);
        return tw > maxWidth ? size * maxWidth / tw : size;
    }
}
