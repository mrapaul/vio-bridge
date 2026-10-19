package vio.bridge;

import android.content.Context;

/** Which screen the VIO shows. Persisted, and cycled by tapping the VIO. */
public final class Mode {
    public static final int TURN = 0, MAP = 1, MIRROR = 2;
    private static final String[] NAMES = {"Turn", "Map", "Mirror"};

    public static volatile int current = TURN;
    /** Map zoom relative to the default, changed by swiping up/down on the VIO. */
    public static volatile int zoom;
    static final int MIN_ZOOM = -2, MAX_ZOOM = 2;

    private Mode() {}

    public static String name(int mode) {
        return NAMES[mode];
    }

    public static void load(Context ctx) {
        current = ctx.getSharedPreferences("vio", Context.MODE_PRIVATE).getInt("mode", TURN);
        zoom = ctx.getSharedPreferences("vio", Context.MODE_PRIVATE).getInt("zoom", 0);
    }

    public static void set(Context ctx, int mode) {
        current = mode;
        ctx.getSharedPreferences("vio", Context.MODE_PRIVATE).edit().putInt("mode", mode).apply();
        Log.add("Mode: " + name(mode));
    }

    public static void next(Context ctx) {
        step(ctx, 1);
    }

    public static void previous(Context ctx) {
        step(ctx, -1);
    }

    /** Mirror is skipped while no screen capture runs: it would only repeat the map. */
    private static void step(Context ctx, int dir) {
        int m = current;
        do {
            m = (m + dir + NAMES.length) % NAMES.length;
        } while (m == MIRROR && !ScreenMirror.active);
        set(ctx, m);
    }

    /** Returns false when already at the limit. */
    public static boolean zoomBy(Context ctx, int delta) {
        int z = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom + delta));
        if (z == zoom) return false;
        zoom = z;
        ctx.getSharedPreferences("vio", Context.MODE_PRIVATE).edit().putInt("zoom", z).apply();
        Log.add("Map zoom " + (z > 0 ? "+" : "") + z);
        return true;
    }
}
