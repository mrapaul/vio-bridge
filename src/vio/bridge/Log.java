package vio.bridge;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Small in-memory log shown on the status screen and mirrored to logcat. */
public final class Log {
    private static final int MAX = 60;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Log() {}

    public static synchronized void add(String msg) {
        android.util.Log.i("VioBridge", msg);
        LINES.addFirst(TIME.format(new Date()) + "  " + msg);
        while (LINES.size() > MAX) LINES.removeLast();
    }

    public static synchronized String text() {
        return String.join("\n", LINES);
    }
}
