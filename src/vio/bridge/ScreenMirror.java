package vio.bridge;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import java.nio.ByteBuffer;

/**
 * Captures the phone screen (normally Google Maps) for the Mirror mode, at a
 * reduced resolution to keep it cheap. The newest frame is kept in {@link #latest}.
 */
public final class ScreenMirror {
    public static volatile Bitmap latest;
    public static volatile long latestAt;
    public static volatile boolean active;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;

    public void start(Context ctx, int resultCode, Intent data) {
        stop();
        MediaProjectionManager mpm = ctx.getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            Log.add("Screen capture was not allowed");
            return;
        }
        thread = new HandlerThread("vio-mirror");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.add("Screen capture ended");
                active = false;
            }
        }, handler);

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = dm.widthPixels / 2, h = dm.heightPixels / 2;
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(r -> {
            try (Image img = r.acquireLatestImage()) {
                if (img == null) return;
                Image.Plane plane = img.getPlanes()[0];
                ByteBuffer buf = plane.getBuffer();
                int rowPixels = plane.getRowStride() / plane.getPixelStride();
                Bitmap b = Bitmap.createBitmap(rowPixels, img.getHeight(), Bitmap.Config.ARGB_8888);
                b.copyPixelsFromBuffer(buf);
                latest = rowPixels == img.getWidth() ? b
                        : Bitmap.createBitmap(b, 0, 0, img.getWidth(), img.getHeight());
                latestAt = System.currentTimeMillis();
            } catch (RuntimeException ignored) {
                // A frame can be dropped while the capture is shutting down.
            }
        }, handler);
        display = projection.createVirtualDisplay("vio-mirror", w, h, dm.densityDpi / 2,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        active = true;
        Log.add("Screen capture started (" + w + "x" + h + ")");
    }

    public void stop() {
        active = false;
        if (display != null) display.release();
        if (projection != null) projection.stop();
        if (reader != null) reader.close();
        if (thread != null) thread.quitSafely();
        display = null;
        projection = null;
        reader = null;
        thread = null;
    }
}
