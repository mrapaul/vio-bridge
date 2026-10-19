package vio.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.os.IBinder;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Plays the phone's role in the VIO "Forza" protocol: listens on the Forza
 * RFCOMM service, answers the VIO's sync with "FZA", runs the info handshake,
 * switches to checksummed ("robust") framing and streams the screen as palette
 * PNG frames on the rendering channel. Never uses the upgrade channel (5).
 */
public class VioService extends Service {
    static final UUID FORZA_UUID = UUID.fromString("e74e3827-fc47-4e5c-bcfa-be7a86d59331");
    static final byte[] SYNC_REQUEST = {(byte) 0xFF, 0x55, 0x02, 0x00, (byte) 0xEE, 0x10};
    static final byte[] SYNC_RESPONSE = {'F', 'Z', 'A'};

    static final int CH_SERVICE = 0, CH_EVENTS = 1, CH_RENDERING = 3;
    static final int F_CAPABILITIES = 0x0000, F_OPEN = 0x0001, F_CLOSE = 0x0002;
    static final int F_SET_BRIGHTNESS = 0x000E, F_GET_BRIGHTNESS = 0x000F;
    /** VIO brightness (%) while no route is being followed, to save its battery. */
    static final int IDLE_BRIGHTNESS = 20;
    /** A touch lights a dimmed VIO up for this long. */
    static final long TOUCH_WAKE_MS = 30000;
    /** Resend an unchanged screen this often, so the VIO knows we're still here. */
    static final long KEEPALIVE_MS = 2000;
    /** The VIO only reports its battery on request, so ask regularly. */
    static final long BATTERY_POLL_MS = 60000;
    static final int F_BATTERY = 0x000D, F_POWER_EVENT = 0x0010, F_BATTERY_EVENT = 0x0012;
    static final int F_SW_VERSION = 0x0013, F_HW_CREDENTIALS = 0x0014, F_CHECKSUM = 0x03FD;
    static final int R_FRAME = 0x0001, R_ACK = 0x000B;

    static final long MIN_FRAME_MS = 250, ACK_TIMEOUT_MS = 3000;
    static final int MAX_PNG = 60000;
    static final String EXTRA_CAPTURE_CODE = "capture_code", EXTRA_CAPTURE_DATA = "capture_data";
    /** Set when the user started the service from the app (not auto-start). */
    static final String EXTRA_FROM_UI = "from_ui";

    /** Status shown on the main screen. */
    public static volatile String status = "Stopped";
    public static volatile Bitmap lastFrame;
    public static volatile boolean running;

    private Thread worker;
    private volatile BluetoothServerSocket server;
    private volatile BluetoothSocket socket;
    private TileCache tiles;
    private LocationTracker location;
    private volatile boolean vioConnected;
    private final ScreenMirror mirror = new ScreenMirror();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent capture = intent == null ? null : intent.getParcelableExtra(EXTRA_CAPTURE_DATA, Intent.class);
        boolean fromUi = intent != null && intent.getBooleanExtra(EXTRA_FROM_UI, false);
        if (fromUi) getSharedPreferences("vio", MODE_PRIVATE).edit().putBoolean("autostart", true).apply();
        startAsForeground(capture != null, fromUi || capture != null);
        if (capture != null) {
            mirror.start(this, intent.getIntExtra(EXTRA_CAPTURE_CODE, 0), capture);
        }
        if (tiles == null) tiles = new TileCache(this);
        if (location == null) location = new LocationTracker(this);
        updateLocation();
        Navigator.onEnded = this::updateLocation;
        if (worker == null) {
            Mode.load(this);
            running = true;
            worker = new Thread(this::listenLoop, "vio-bridge");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeQuietly();
        mirror.stop();
        Navigator.onEnded = null;
        Navigator.stop();
        if (location != null) location.stop();
        if (tiles != null) tiles.shutdown();
        if (worker != null) worker.interrupt();
        status = "Stopped";
        Log.add("Service stopped");
        super.onDestroy();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** True only while a bike navigation is in progress (built-in or Google Maps). */
    private boolean navigating() {
        return Navigator.active || NavState.active;
    }

    /**
     * Collect location only while navigating by bike, never just because the VIO is
     * connected. Called whenever navigation or the connection state changes.
     */
    private void updateLocation() {
        if (location == null) return;
        if (navigating() && hasLocationPermission()) location.start();
        else location.stop();
    }

    /** Location in a service started at boot needs "Allow all the time". */
    private boolean hasBackgroundLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startAsForeground(boolean withCapture, boolean fromUi) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("vio", "VIO connection",
                NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "vio")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("VIO Bridge")
                .setContentText("Showing bike directions on the VIO")
                .setOngoing(true)
                .build();
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (hasLocationPermission() && (fromUi || hasBackgroundLocation())) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        if (withCapture || ScreenMirror.active) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        startForeground(1, n, types);
    }

    /** Google Maps directions with cycling preselected; the user only picks the destination. */
    static Intent bikeRouteIntent() {
        return new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&travelmode=bicycling"))
                .setPackage(MapsListener.MAPS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * Android does not let a background service open another app, so when the
     * VIO connects without an active route we offer a one-tap notification.
     */
    private void postBikeRoutePrompt() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("route", "Plan a bike route",
                NotificationManager.IMPORTANCE_HIGH));
        int flags = android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT;
        android.app.PendingIntent open = android.app.PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags);
        android.app.PendingIntent maps = android.app.PendingIntent.getActivity(this, 1, bikeRouteIntent(), flags);
        nm.notify(2, new Notification.Builder(this, "route")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("VIO connected")
                .setContentText("Tap to pick where you're cycling to")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Google Maps instead", maps).build())
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60 * 1000)
                .build());
    }

    private void setStatus(String s) {
        status = s;
        Log.add(s);
    }

    private void closeQuietly() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        try { if (server != null) server.close(); } catch (IOException ignored) {}
    }

    private void listenLoop() {
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        while (running) {
            try {
                if (adapter == null || !adapter.isEnabled()) {
                    status = "Bluetooth is off";
                    Thread.sleep(3000);
                    continue;
                }
                server = adapter.listenUsingRfcommWithServiceRecord("Forza App", FORZA_UUID);
                setStatus("Waiting for the VIO to connect…");
                socket = server.accept();
                server.close();
                setStatus("VIO connected: " + socket.getRemoteDevice().getName());
                vioConnected = true;
                // Location is gated on navigation, not on the VIO connection.
                updateLocation();
                if (!NavState.active && !Navigator.active) postBikeRoutePrompt();
                new Session(socket.getInputStream(), socket.getOutputStream()).run();
            } catch (SecurityException e) {
                setStatus("Bluetooth permission missing");
                running = false;
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (running) setStatus("Disconnected: " + e.getMessage());
            } finally {
                closeQuietly();
                vioConnected = false;
                updateLocation();
            }
            if (running) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }
    }

    // ---------------------------------------------------------------- framing

    static byte[] plain(int ch, byte[] payload) {
        byte[] out = new byte[payload.length + 3];
        out[0] = (byte) ch;
        out[1] = (byte) (payload.length >> 8);
        out[2] = (byte) payload.length;
        System.arraycopy(payload, 0, out, 3, payload.length);
        return out;
    }

    static byte[] robust(int ch, byte[] payload) {
        int n = payload.length;
        byte[] out = new byte[n + 10];
        out[0] = (byte) ch;
        out[1] = (byte) (n >> 8);
        out[2] = (byte) n;
        out[3] = 'T';
        out[4] = 'T';
        out[5] = 0;
        int hs = sum(out, 0, 6);
        out[6] = (byte) (hs >> 8);
        out[7] = (byte) hs;
        System.arraycopy(payload, 0, out, 8, n);
        int ps = sum(payload, 0, n);
        out[8 + n] = (byte) (ps >> 8);
        out[9 + n] = (byte) ps;
        return out;
    }

    static int sum(byte[] b, int off, int len) {
        int s = 0;
        for (int i = off; i < off + len; i++) s += b[i] & 0xFF;
        return s & 0xFFFF;
    }

    static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    static int s32(byte[] b, int off) {
        return (u16(b, off) << 16) | u16(b, off + 2);
    }

    static byte[] func(int f, int... args) {
        byte[] out = new byte[2 + args.length];
        out[0] = (byte) (f >> 8);
        out[1] = (byte) f;
        for (int i = 0; i < args.length; i++) out[2 + i] = (byte) args[i];
        return out;
    }

    /** One parsed message from the VIO. channel -1 = sync request, -2 = FZA. */
    static final class Msg {
        final int channel;
        final byte[] payload;
        Msg(int channel, byte[] payload) { this.channel = channel; this.payload = payload; }
    }

    // ---------------------------------------------------------------- session

    private final class Session {
        private final InputStream in;
        private final OutputStream out;
        private final LinkedBlockingQueue<Msg> queue = new LinkedBlockingQueue<>();
        private volatile boolean robustTx;
        private volatile Exception readError;
        private int screenW = 320, screenH = 320;
        private boolean haveCaps, haveSw, haveHw, checksumOk;
        private String battery;
        private int ackedFrame = -1, ackStatus;
        private long lastTap;
        private int debugEvents;

        Session(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        void run() throws Exception {
            Thread reader = new Thread(this::readLoop, "vio-reader");
            reader.start();
            try {
                handshake();
                stream();
            } finally {
                reader.interrupt();
            }
        }

        private void send(byte[] data) throws IOException {
            synchronized (out) {
                out.write(data);
                out.flush();
            }
        }

        private void service(byte[] payload) throws IOException {
            send(robustTx ? robust(CH_SERVICE, payload) : plain(CH_SERVICE, payload));
        }

        private void handshake() throws Exception {
            long deadline = System.currentTimeMillis() + 20000;
            boolean synced = false;
            while (!(haveCaps && haveSw && haveHw)) {
                if (System.currentTimeMillis() > deadline) throw new IOException("handshake timed out");
                Msg m = next(300);
                if (m == null) continue;
                if (m.channel == -1) {
                    send(SYNC_RESPONSE);
                    if (!synced) {
                        synced = true;
                        Log.add("Sync answered (FZA), requesting device info");
                        service(func(F_CAPABILITIES));
                        service(func(F_SW_VERSION));
                        service(func(F_HW_CREDENTIALS));
                    }
                } else {
                    handle(m);
                }
            }
            Log.add("VIO screen " + screenW + "x" + screenH);
            service(func(F_CHECKSUM, 1));
            robustTx = true;
            long end = System.currentTimeMillis() + 5000;
            while (!checksumOk && System.currentTimeMillis() < end) {
                Msg m = next(200);
                if (m != null) handle(m);
            }
            service(func(F_OPEN, CH_EVENTS));
            service(func(F_BATTERY));
            service(func(F_GET_BRIGHTNESS));
            service(func(F_OPEN, CH_RENDERING));
            drain(800);
        }

        private void stream() throws Exception {
            Renderer renderer = new Renderer(screenW, screenH, tiles);
            int frameId = 0;
            status = "Streaming to VIO";
            Log.add("Streaming frames");
            while (running) {
                long started = System.currentTimeMillis();
                Bitmap frame = renderer.draw(battery);
                lastFrame = frame.copy(Bitmap.Config.ARGB_8888, false);
                updateBrightness();
                updateLocation();
                byte[] png = IndexedPng.encode(frame, MAX_PNG);
                // An unchanged screen isn't resent (saves the VIO's battery), apart from a keepalive.
                if (java.util.Arrays.equals(png, lastPng) && started - lastSentAt < KEEPALIVE_MS) {
                    drain(MIN_FRAME_MS);
                    continue;
                }
                lastPng = png;
                lastSentAt = started;
                if (RouteSimulator.running) recordSimFrame(png);
                if (started - lastBatteryPoll > BATTERY_POLL_MS) {
                    lastBatteryPoll = started;
                    service(func(F_BATTERY));
                }
                // An occasional lost acknowledgement gets one retry before we reconnect.
                for (int attempt = 0; attempt < 2 && ackedFrame != frameId; attempt++) {
                    if (attempt > 0) Log.add("No ack for frame " + frameId + ", resending");
                    sendFrame(frameId, png);
                    long sent = System.currentTimeMillis();
                    while (ackedFrame != frameId && System.currentTimeMillis() - sent < ACK_TIMEOUT_MS) {
                        Msg m = next(50);
                        if (m != null) handle(m);
                    }
                }
                if (ackedFrame != frameId) throw new IOException("VIO did not acknowledge frame " + frameId);
                if (ackStatus != 0) throw new IOException("VIO rejected frame, status " + ackStatus);
                frameId++;
                long wait = MIN_FRAME_MS - (System.currentTimeMillis() - started);
                if (wait > 0) drain(wait);
            }
            service(func(F_CLOSE, CH_RENDERING));
        }

        private byte[] lastPng;
        private int brightnessSet = -1;

        /** Full brightness while navigating, dimmed otherwise. */
        private void updateBrightness() throws IOException {
            int normal = getSharedPreferences("vio", MODE_PRIVATE).getInt("brightness", 50);
            boolean awake = NavState.active || System.currentTimeMillis() - lastTouchAt < TOUCH_WAKE_MS;
            int want = awake ? normal : Math.min(normal, IDLE_BRIGHTNESS);
            if (want == brightnessSet) return;
            brightnessSet = want;
            service(func(F_SET_BRIGHTNESS, want));
            Log.add("VIO brightness set to " + want + "%" + (awake ? "" : " (no route, dimmed)"));
        }

        private long lastSentAt, lastBatteryPoll = System.currentTimeMillis();
        private int lastLevel = -1;

        /** Battery readings with time, to measure how fast the VIO drains. */
        private void logBattery(int level, int state, boolean charging) {
            if (level == lastLevel) return;
            lastLevel = level;
            String[] states = {"shutting down", "empty", "critical", "low", "ok"};
            String st = state >= 0 && state < states.length ? states[state] : "state " + state;
            Log.add("VIO battery " + level + "% (" + st + (charging ? ", charging" : "") + ")");
            try (java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(getExternalFilesDir(null), "battery.csv"), true)) {
                w.write(System.currentTimeMillis() / 1000 + "," + level + "," + state + "," + (charging ? 1 : 0)
                        + "," + Mode.current + "\n");
            } catch (IOException ignored) {}
        }

        private String lastRecorded = "";
        private int recorded;

        /** During a simulated ride, keep one copy of each distinct VIO screen. */
        private void recordSimFrame(byte[] png) {
            String key = NavState.distance + "|" + NavState.instruction + "|" + Mode.current;
            if (key.equals(lastRecorded)) return;
            lastRecorded = key;
            try {
                java.io.File dir = new java.io.File(getExternalFilesDir(null), "sim");
                dir.mkdirs();
                String name = String.format(java.util.Locale.US, "%03d.png", recorded++);
                try (java.io.FileOutputStream f = new java.io.FileOutputStream(new java.io.File(dir, name))) {
                    f.write(png);
                }
                try (java.io.FileWriter log = new java.io.FileWriter(new java.io.File(dir, "frames.txt"), true)) {
                    log.write(name + "\t" + NavState.distance + "\t" + NavState.instruction + "\t"
                            + NavState.eta + "\n");
                }
            } catch (IOException ignored) {
            }
        }

        private void sendFrame(int id, byte[] png) throws IOException {
            int headerLen = 14;
            byte[] first = new byte[Math.min(65535, headerLen + png.length)];
            first[0] = (byte) (R_FRAME >> 8);
            first[1] = (byte) R_FRAME;
            int total = png.length + 8;
            first[2] = (byte) (total >>> 24); first[3] = (byte) (total >>> 16);
            first[4] = (byte) (total >>> 8);  first[5] = (byte) total;
            first[6] = (byte) (id >>> 24);    first[7] = (byte) (id >>> 16);
            first[8] = (byte) (id >>> 8);     first[9] = (byte) id;
            // x = 0, y = 0 (bytes 10..13 already zero)
            int firstPng = first.length - headerLen;
            System.arraycopy(png, 0, first, headerLen, firstPng);
            send(robust(CH_RENDERING, first));
            for (int off = firstPng; off < png.length; off += 65535) {
                send(robust(CH_RENDERING, Arrays.copyOfRange(png, off, Math.min(png.length, off + 65535))));
            }
        }

        private Msg next(long ms) throws Exception {
            if (readError != null) throw readError;
            return queue.poll(ms, TimeUnit.MILLISECONDS);
        }

        private void drain(long ms) throws Exception {
            long end = System.currentTimeMillis() + ms;
            long left;
            while ((left = end - System.currentTimeMillis()) > 0) {
                Msg m = next(left);
                if (m != null) handle(m);
            }
        }

        private void handle(Msg m) throws IOException {
            byte[] p = m.payload;
            if (m.channel == -1) {
                send(SYNC_RESPONSE);
                return;
            }
            if (m.channel == CH_SERVICE && p.length >= 2) {
                int f = u16(p, 0);
                switch (f) {
                    case F_CAPABILITIES:
                        if (p.length >= 7) { screenW = u16(p, 3); screenH = u16(p, 5); }
                        haveCaps = true;
                        break;
                    case F_SW_VERSION:
                        if (p.length >= 46) {
                            Log.add("VIO firmware " + (p[40] & 0xFF) + "." + (p[41] & 0xFF) + "." + s32(p, 42));
                        }
                        haveSw = true;
                        break;
                    case F_HW_CREDENTIALS:
                        haveHw = true;
                        break;
                    case F_CHECKSUM:
                        checksumOk = true;
                        break;
                    case F_BATTERY:
                    case F_BATTERY_EVENT:
                        if (p.length >= 5) {
                            battery = (p[2] & 0xFF) + "%" + (p[4] != 0 ? "+" : "");
                            logBattery(p[2] & 0xFF, p[3] & 0xFF, p[4] != 0);
                        }
                        break;
                    case F_GET_BRIGHTNESS:
                        if (p.length >= 3) {
                            int b = p[2] & 0xFF;
                            Log.add("VIO brightness " + b + "%");
                            // A dimmed VIO from an earlier session isn't the rider's own setting.
                            if (b != IDLE_BRIGHTNESS && b > 0) {
                                getSharedPreferences("vio", MODE_PRIVATE).edit().putInt("brightness", b).apply();
                            }
                        }
                        break;
                    case F_SET_BRIGHTNESS:
                        break;
                    case F_POWER_EVENT: {
                        int ev = p.length >= 3 ? p[2] & 0xFF : -1;
                        String why = ev == 1 ? "switched off" : ev == 2 ? "battery empty" : ev == 10 ? "suspended"
                                : ev == 20 ? "rebooting" : "event " + ev;
                        try (java.io.FileWriter w = new java.io.FileWriter(
                                new java.io.File(getExternalFilesDir(null), "battery.csv"), true)) {
                            w.write(System.currentTimeMillis() / 1000 + ",off," + ev + ",,\n");
                        } catch (IOException ignored) {}
                        throw new IOException("VIO is powering off (" + why + ")");
                    }
                    default:
                        logUnknown(m);
                        break;
                }
            } else if (m.channel == CH_RENDERING && p.length >= 7 && u16(p, 0) == R_ACK) {
                ackStatus = p[2] & 0xFF;
                ackedFrame = s32(p, 3);
            } else if (m.channel != CH_EVENTS) {
                logUnknown(m);
            }
            if (m.channel == CH_EVENTS && p.length >= 8 && u16(p, 0) == 1) {
                // Touch: [00 01][00 action: ff down/move, 00 up][x u16][y u16][time u32][gesture u32]
                onTouch(p[3] == 0, u16(p, 4), u16(p, 6));
            }
        }

        private int touchX0 = -1, touchY0, touchX, touchY;
        private long touchStart, lastTouchAt;

        /** Tap: next screen. Swipe up/down: zoom. Swipe left/right: previous/next screen. */
        private void onTouch(boolean up, int x, int y) {
            long now = System.currentTimeMillis();
            lastTouchAt = now;
            if (touchX0 < 0) {
                touchX0 = x;
                touchY0 = y;
                touchStart = now;
            }
            touchX = x;
            touchY = y;
            if (!up) return;
            int dx = touchX - touchX0, dy = touchY - touchY0;
            Log.add(String.format(java.util.Locale.US, "Touch %d,%d -> %d,%d (dx %d, dy %d, %d ms)",
                    touchX0, touchY0, touchX, touchY, dx, dy, now - touchStart));
            touchX0 = -1;
            if (now - lastTap < 300) return;
            lastTap = now;
            int min = 20;
            if (Math.abs(dy) >= min && Math.abs(dy) > Math.abs(dx)) {
                // Swipe up = closer, as on a phone map.
                boolean closer = dy < 0;
                if (Mode.zoomBy(VioService.this, closer ? 1 : -1)) Renderer.toast(closer ? "Zoom in" : "Zoom out");
                else Renderer.toast(closer ? "Max zoom" : "Min zoom");
            } else if (Math.abs(dx) >= min) {
                if (dx < 0) Mode.next(VioService.this);
                else Mode.previous(VioService.this);
                Renderer.toast(Mode.name(Mode.current));
            } else {
                Mode.next(VioService.this);
                Renderer.toast(Mode.name(Mode.current));
            }
        }

        /** Logs messages we do not handle (first 30), to learn what the VIO sends. */
        private void logUnknown(Msg m) {
            if (debugEvents >= 30) return;
            debugEvents++;
            StringBuilder hex = new StringBuilder();
            for (byte b : m.payload) hex.append(String.format("%02x ", b));
            Log.add("VIO ch" + m.channel + ": " + hex);
        }

        private void readLoop() {
            byte[] buf = new byte[0];
            byte[] chunk = new byte[8192];
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int n = in.read(chunk);
                    if (n < 0) throw new IOException("VIO closed the connection");
                    byte[] merged = Arrays.copyOf(buf, buf.length + n);
                    System.arraycopy(chunk, 0, merged, buf.length, n);
                    buf = parse(merged);
                }
            } catch (Exception e) {
                readError = e;
            }
        }

        /** Extracts complete messages, returns the unconsumed tail. */
        private byte[] parse(byte[] b) {
            int pos = 0;
            while (true) {
                int left = b.length - pos;
                if (left >= 6 && startsWith(b, pos, SYNC_REQUEST)) {
                    queue.add(new Msg(-1, null));
                    pos += 6;
                    continue;
                }
                if (left >= 3 && startsWith(b, pos, SYNC_RESPONSE)) {
                    pos += 3;
                    continue;
                }
                if (left < 3) break;
                int ch = b[pos] & 0xFF, len = u16(b, pos + 1);
                boolean isRobust = left >= 5 && b[pos + 3] == 'T' && b[pos + 4] == 'T';
                int total = isRobust ? len + 10 : len + 3;
                if (left < total) break;
                int start = pos + (isRobust ? 8 : 3);
                queue.add(new Msg(ch, Arrays.copyOfRange(b, start, start + len)));
                pos += total;
            }
            return Arrays.copyOfRange(b, pos, b.length);
        }
    }

    static boolean startsWith(byte[] b, int off, byte[] prefix) {
        if (b.length - off < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (b[off + i] != prefix[i]) return false;
        return true;
    }
}
