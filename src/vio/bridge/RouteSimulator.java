package vio.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Test feature: replays a cycling route as mock GPS so Google Maps navigates
 * as if riding it. Triggered over adb only (the receiver requires the DUMP
 * permission, which only the shell holds), and only works while VIO Bridge is
 * allowed as mock location app.
 *
 *   adb shell am broadcast -a vio.bridge.SIMULATE -n vio.bridge/.RouteSimulator \
 *       --ef lat 52.3717 --ef lon 4.8948 --ef kmh 18
 *   adb shell am broadcast -a vio.bridge.SIMULATE_STOP -n vio.bridge/.RouteSimulator
 *
 * Optional --ef vlat/--ef vlon: a detour point the ride goes through first,
 * to test that Google Maps reroutes after a "wrong turn".
 *
 * Built-in navigation can be started the same way (first route offered):
 *   adb shell am broadcast -a vio.bridge.NAVIGATE -n vio.bridge/.RouteSimulator \
 *       --ef lat 52.3717 --ef lon 4.8948 --es name RUST
 */
public class RouteSimulator extends BroadcastReceiver {
    static final String ROUTER = "https://routing.openstreetmap.de/routed-bike/route/v1/driving/"
            + "%s?overview=full&geometries=geojson";
    static final String[] PROVIDERS = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};

    public static volatile boolean running;
    private static Thread thread;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        if ("vio.bridge.SIMULATE_STOP".equals(intent.getAction())) {
            stop();
            return;
        }
        if ("vio.bridge.NAVIGATE".equals(intent.getAction())) {
            navigate(app, intent.getFloatExtra("lat", 0), intent.getFloatExtra("lon", 0),
                    intent.getStringExtra("name"));
            return;
        }
        if (!"vio.bridge.SIMULATE".equals(intent.getAction()) || running) return;
        double lat = intent.getFloatExtra("lat", 0), lon = intent.getFloatExtra("lon", 0);
        double kmh = intent.getFloatExtra("kmh", 18);
        double viaLat = intent.getFloatExtra("vlat", 0), viaLon = intent.getFloatExtra("vlon", 0);
        if (lat == 0 || lon == 0) {
            Log.add("Simulation needs --ef lat and --ef lon");
            return;
        }
        running = true;
        thread = new Thread(() -> run(app, lat, lon, viaLat, viaLon, kmh), "vio-sim");
        thread.start();
    }

    private static void navigate(Context app, double lat, double lon, String name) {
        final Location from = bestLastKnown(app.getSystemService(LocationManager.class));
        if (from == null) {
            Log.add("Navigate: no GPS position yet");
            return;
        }
        new Thread(() -> {
            try {
                List<BikeRouter.Route> routes = BikeRouter.fetch(from.getLatitude(), from.getLongitude(),
                        lat, lon, Float.NaN, true);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    Navigator.start(routes.get(0), lat, lon, name == null ? "" : name);
                    app.startService(new Intent(app, VioService.class));
                });
            } catch (Exception e) {
                Log.add("Navigate failed: " + e.getMessage());
            }
        }, "vio-nav").start();
    }

    static synchronized void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    /** Most recent fix across the live tracker and every provider's last-known. */
    private static Location bestLastKnown(LocationManager lm) {
        Location best = LocationTracker.last;
        String[] all = {LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String p : all) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null
                        || l.getElapsedRealtimeNanos() > best.getElapsedRealtimeNanos())) {
                    best = l;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {}
        }
        return best;
    }

    private static void run(Context ctx, double destLat, double destLon, double viaLat, double viaLon,
                            double kmh) {
        LocationManager lm = ctx.getSystemService(LocationManager.class);
        try {
            Location start = bestLastKnown(lm);
            if (start == null) throw new IllegalStateException("no current position to start from");
            List<double[]> stops = new ArrayList<>();
            stops.add(new double[] {start.getLatitude(), start.getLongitude()});
            if (viaLat != 0 && viaLon != 0) {
                stops.add(new double[] {viaLat, viaLon});
                Log.add(String.format(Locale.US, "Simulation detours via %.5f,%.5f", viaLat, viaLon));
            }
            stops.add(new double[] {destLat, destLon});
            List<double[]> route = fetchRoute(stops);
            Log.add(String.format(Locale.US, "Simulating %d m to %.5f,%.5f at %.0f km/h",
                    (int) length(route), destLat, destLon, kmh));
            for (String p : PROVIDERS) {
                try { lm.removeTestProvider(p); } catch (IllegalArgumentException ignored) {}
                lm.addTestProvider(p, new ProviderProperties.Builder()
                        .setHasSpeedSupport(true).setHasBearingSupport(true)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW).build());
                lm.setTestProviderEnabled(p, true);
            }
            replay(lm, route, kmh / 3.6);
            Log.add("Simulation reached the destination");
            // Stay at the destination so Maps can announce the arrival.
            double[] end = route.get(route.size() - 1);
            for (int i = 0; i < 20 && running; i++) {
                push(lm, end[0], end[1], 0, 0);
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Log.add("Simulation stopped");
        } catch (SecurityException e) {
            Log.add("Simulation: VIO Bridge is not the mock location app");
        } catch (Exception e) {
            Log.add("Simulation failed: " + e.getMessage());
        } finally {
            for (String p : PROVIDERS) {
                try { lm.removeTestProvider(p); } catch (RuntimeException ignored) {}
            }
            running = false;
        }
    }

    private static void replay(LocationManager lm, List<double[]> route, double speed) throws InterruptedException {
        int seg = 0;
        double along = 0, done = 0, nextLog = 0, total = length(route);
        while (running && seg < route.size() - 1) {
            double[] a = route.get(seg), b = route.get(seg + 1);
            double segLen = dist(a, b);
            if (along >= segLen) {
                along -= segLen;
                seg++;
                continue;
            }
            double f = segLen == 0 ? 0 : along / segLen;
            push(lm, a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f, bearing(a, b), (float) speed);
            if (done >= nextLog) {
                Log.add(String.format(Locale.US, "Sim: %d / %d m", (int) done, (int) total));
                nextLog += 250;
            }
            Thread.sleep(1000);
            along += speed;
            done += speed;
        }
    }

    private static void push(LocationManager lm, double lat, double lon, float bearing, float speed) {
        for (String p : PROVIDERS) {
            Location l = new Location(p);
            l.setLatitude(lat);
            l.setLongitude(lon);
            l.setAccuracy(4);
            l.setAltitude(2);
            l.setBearing(bearing);
            l.setSpeed(speed);
            l.setBearingAccuracyDegrees(5);
            l.setSpeedAccuracyMetersPerSecond(0.5f);
            l.setVerticalAccuracyMeters(5);
            l.setTime(System.currentTimeMillis());
            l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            lm.setTestProviderLocation(p, l);
        }
    }

    private static List<double[]> fetchRoute(List<double[]> stops) throws Exception {
        StringBuilder query = new StringBuilder();
        for (double[] s : stops) {
            if (query.length() > 0) query.append(';');
            query.append(String.format(Locale.US, "%.6f,%.6f", s[1], s[0]));
        }
        URL url = new URL(String.format(Locale.US, ROUTER, query));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("User-Agent", TileCache.USER_AGENT);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) body.write(buf, 0, n);
        }
        JSONArray coords = new JSONObject(body.toString("UTF-8"))
                .getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < coords.length(); i++) {
            JSONArray pt = coords.getJSONArray(i);
            pts.add(new double[] {pt.getDouble(1), pt.getDouble(0)});
        }
        if (pts.size() < 2) throw new IllegalStateException("router returned no route");
        return pts;
    }

    static double dist(double[] a, double[] b) {
        float[] r = new float[1];
        Location.distanceBetween(a[0], a[1], b[0], b[1], r);
        return r[0];
    }

    static float bearing(double[] a, double[] b) {
        float[] r = new float[2];
        Location.distanceBetween(a[0], a[1], b[0], b[1], r);
        return (r[1] + 360) % 360;
    }

    static double length(List<double[]> pts) {
        double d = 0;
        for (int i = 1; i < pts.size(); i++) d += dist(pts.get(i - 1), pts.get(i));
        return d;
    }
}
