package vio.bridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OpenStreetMap raster tiles with a memory cache and a permanent disk cache,
 * so each tile is downloaded only once (as the OSM tile usage policy asks)
 * and areas ridden before also work offline. Tiles are turned into a dark
 * greyscale "night" map when loaded: light land becomes dark, dark labels
 * become light, and greys map exactly onto the VIO's palette.
 */
public final class TileCache {
    static final String URL_PATTERN = "https://tile.openstreetmap.org/%d/%d/%d.png";
    /** Contrast after inverting; higher = brighter streets and labels. */
    static final float NIGHT_CONTRAST = 1.8f;
    private static final android.graphics.Paint NIGHT = new android.graphics.Paint();
    static {
        float k = NIGHT_CONTRAST;
        NIGHT.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[] {
                -k * 0.299f, -k * 0.587f, -k * 0.114f, 0, 255 * k,
                -k * 0.299f, -k * 0.587f, -k * 0.114f, 0, 255 * k,
                -k * 0.299f, -k * 0.587f, -k * 0.114f, 0, 255 * k,
                0, 0, 0, 1, 0}));
    }
    static final String USER_AGENT = "VIOBridge/0.8 (personal TomTom VIO display bridge)";

    private final File dir;
    private final LruCache<String, Bitmap> memory = new LruCache<>(80);
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
    private final ExecutorService net = Executors.newFixedThreadPool(2);

    public TileCache(Context ctx) {
        dir = new File(ctx.getFilesDir(), "tiles");
    }

    /** Returns the tile if available now; otherwise starts loading it and returns null. */
    public Bitmap get(int z, int x, int y) {
        int n = 1 << z;
        x = ((x % n) + n) % n;
        if (y < 0 || y >= n) return null;
        String key = z + "/" + x + "/" + y;
        Bitmap b = memory.get(key);
        if (b != null) return b;
        File f = new File(dir, key + ".png");
        if (f.exists()) {
            b = night(BitmapFactory.decodeFile(f.getPath()));
            if (b != null) {
                memory.put(key, b);
                return b;
            }
        }
        if (pending.add(key)) {
            final int fz = z, fx = x, fy = y;
            net.execute(() -> download(fz, fx, fy, key, f));
        }
        return null;
    }

    private void download(int z, int x, int y, String key, File f) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(String.format(URL_PATTERN, z, x, y)).openConnection();
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() == 200) {
                f.getParentFile().mkdirs();
                File tmp = new File(f.getPath() + ".tmp");
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                if (tmp.renameTo(f)) {
                    Bitmap b = night(BitmapFactory.decodeFile(f.getPath()));
                    if (b != null) memory.put(key, b);
                }
            }
            c.disconnect();
        } catch (Exception ignored) {
            // Offline or server error: the tile stays blank and is retried later.
        } finally {
            pending.remove(key);
        }
    }

    private static Bitmap night(Bitmap src) {
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(out).drawBitmap(src, 0, 0, NIGHT);
        return out;
    }

    public void shutdown() {
        net.shutdownNow();
    }
}
