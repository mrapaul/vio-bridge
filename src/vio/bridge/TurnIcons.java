package vio.bridge;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import java.util.HashMap;
import java.util.Map;

/**
 * Manoeuvre arrows for built-in navigation, drawn white on transparent; the
 * renderer tints them neon like Google Maps' icons.
 */
final class TurnIcons {
    private static final int S = 128;
    private static final Map<String, Bitmap> cache = new HashMap<>();

    private TurnIcons() {}

    static synchronized Bitmap get(BikeRouter.Step s) {
        String key = s.type + "|" + s.modifier;
        Bitmap b = cache.get(key);
        if (b == null) {
            b = draw(s);
            cache.put(key, b);
        }
        return b;
    }

    private static Bitmap draw(BikeRouter.Step s) {
        Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(Color.WHITE);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(S * 0.13f);
        line.setStrokeJoin(Paint.Join.ROUND);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.WHITE);
        float cx = S / 2f;

        if (s.type.equals("arrive")) {
            // A target: ring with a dot.
            line.setStrokeWidth(S * 0.1f);
            c.drawCircle(cx, S * 0.5f, S * 0.32f, line);
            c.drawCircle(cx, S * 0.5f, S * 0.13f, fill);
            return b;
        }
        if (s.type.equals("roundabout") || s.type.equals("rotary")) {
            // Ring at the top, entry from below, exit up and out.
            line.setStrokeWidth(S * 0.1f);
            c.drawCircle(cx, S * 0.42f, S * 0.18f, line);
            c.drawLine(cx, S * 0.95f, cx, S * 0.62f, line);
            arrowHead(c, fill, cx, S * 0.04f, 0);
            c.drawLine(cx, S * 0.24f, cx, S * 0.12f, line);
            return b;
        }
        if (s.modifier.equals("uturn")) {
            Path p = new Path();
            float r = S * 0.17f, right = cx + r, left = cx - r;
            p.moveTo(right, S * 0.95f);
            p.lineTo(right, S * 0.40f);
            p.arcTo(new RectF(left, S * 0.40f - r, right, S * 0.40f + r), 0, -180, false);
            p.lineTo(left, S * 0.62f);
            c.drawPath(p, line);
            arrowHead(c, fill, left, S * 0.80f, 180);
            return b;
        }
        float angle = angle(s.modifier);
        float bendY = S * 0.58f, len = S * 0.40f;
        double rad = Math.toRadians(angle);
        float ex = cx + (float) (len * Math.sin(rad)), ey = bendY - (float) (len * Math.cos(rad));
        // Stop the shaft short of the tip so the head stays sharp.
        float sx = cx + (float) (len * 0.6 * Math.sin(rad)), sy = bendY - (float) (len * 0.6 * Math.cos(rad));
        float shift = -(ex - cx) * 0.35f;  // keep the whole arrow centred
        Path p = new Path();
        p.moveTo(cx + shift, S * 0.95f);
        p.lineTo(cx + shift, bendY);
        p.lineTo(sx + shift, sy);
        c.drawPath(p, line);
        arrowHead(c, fill, ex + shift, ey, angle);
        return b;
    }

    private static float angle(String modifier) {
        switch (modifier) {
            case "slight left": return -45;
            case "left": return -90;
            case "sharp left": return -135;
            case "slight right": return 45;
            case "right": return 90;
            case "sharp right": return 135;
            default: return 0;
        }
    }

    /** Triangle with its tip at (x, y), pointing in the given direction (0 = up). */
    private static void arrowHead(Canvas c, Paint fill, float x, float y, float angle) {
        float hl = S * 0.30f, hw = S * 0.20f;
        Path h = new Path();
        h.moveTo(0, 0);
        h.lineTo(hw, hl);
        h.lineTo(-hw, hl);
        h.close();
        c.save();
        c.translate(x, y);
        c.rotate(angle);
        c.drawPath(h, fill);
        c.restore();
    }
}
