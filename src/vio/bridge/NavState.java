package vio.bridge;

import android.graphics.Bitmap;

/** Latest Google Maps navigation step, shared between the listener and the renderer. */
public final class NavState {
    public static volatile boolean active;
    public static volatile String distance = "";
    public static volatile String instruction = "";
    public static volatile String eta = "";
    public static volatile Bitmap arrow;
    public static volatile long updatedAt;
    /** Raw notification extras of the last Maps update, for debugging the parser. */
    public static volatile String lastRaw = "";

    /** True while Google Maps is recalculating after a wrong turn. */
    public static boolean rerouting() {
        String i = instruction.toLowerCase(java.util.Locale.ROOT);
        return i.startsWith("rerouting") || i.startsWith("route wordt") || i.startsWith("opnieuw route");
    }

    private NavState() {}

    public static void update(String distance, String instruction, String eta, Bitmap arrow, String raw) {
        NavState.distance = distance == null ? "" : distance;
        NavState.instruction = instruction == null ? "" : instruction;
        NavState.eta = eta == null ? "" : eta;
        NavState.arrow = arrow;
        NavState.lastRaw = raw;
        NavState.updatedAt = System.currentTimeMillis();
        NavState.active = true;
    }

    public static void clear() {
        active = false;
        arrow = null;
        updatedAt = System.currentTimeMillis();
    }
}
