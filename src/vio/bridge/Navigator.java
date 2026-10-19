package vio.bridge;

import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in bike navigation, independent of Google Maps: follows a BikeRouter
 * route with the GPS position, announces the next manoeuvre through NavState
 * and fetches a new route when the rider leaves the planned one.
 * All state changes happen on the main thread.
 */
public final class Navigator {
    /** Further than this from the route (metres) counts as off route. */
    static final double OFF_ROUTE_M = 35;
    /** Consecutive off-route fixes before rerouting. */
    static final int OFF_ROUTE_FIXES = 3;
    static final long REROUTE_RETRY_MS = 10000;
    static final double ARRIVED_M = 20;
    static final long ARRIVED_HOLD_MS = 30000;

    public static volatile boolean active;
    public static volatile BikeRouter.Route route;
    /** Index of the route segment the rider is on (for drawing the rest of the route). */
    public static volatile int seg;
    public static volatile double destLat, destLon;
    public static volatile String destName = "";
    /** Called on the main thread when navigation ends. */
    static volatile Runnable onEnded;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService net = Executors.newSingleThreadExecutor();
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private static double along;
    private static int offFixes;
    private static boolean rerouting, arrived;
    private static long lastRerouteAttempt;
    private static int reroutes;
    private static BikeRouter.Step lastStep;

    private Navigator() {}

    /** Starts guidance along a route the user picked. Call on the main thread. */
    static void start(BikeRouter.Route r, double lat, double lon, String name) {
        main.removeCallbacksAndMessages(null);
        route = r;
        destLat = lat;
        destLon = lon;
        destName = name == null ? "" : name;
        seg = 0;
        along = 0;
        offFixes = 0;
        reroutes = 0;
        rerouting = arrived = false;
        active = true;
        Log.add(String.format(Locale.US, "Navigating by bike to %s: %s, %d min",
                destName, BikeRouter.formatDistance(r.length), Math.round(r.duration / 60)));
        BikeRouter.Step first = r.nextStep(0);
        NavState.update(BikeRouter.formatDistance(first.along), first.instruction, eta(r, 0),
                TurnIcons.get(first), "built-in");
        Location l = LocationTracker.last;
        if (l != null) onLocation(l);
    }

    static void stop() {
        main.removeCallbacksAndMessages(null);
        if (!active) return;
        active = false;
        route = null;
        NavState.clear();
        Log.add("Navigation stopped");
        Runnable r = onEnded;
        if (r != null) r.run();
    }

    /** Every GPS fix while navigating (main thread). */
    static void onLocation(Location l) {
        BikeRouter.Route r = route;
        if (!active || r == null || arrived) return;
        double lat = l.getLatitude(), lon = l.getLongitude();
        if (rerouting) {
            if (System.currentTimeMillis() - lastRerouteAttempt > REROUTE_RETRY_MS) reroute(lat, lon);
            return;
        }
        // Look just around the current position on the route first, so a
        // route that passes the same street twice doesn't make us jump ahead.
        BikeRouter.Projection pr = BikeRouter.project(r, lat, lon, seg - 3, seg + 150);
        if (pr.offset > OFF_ROUTE_M) {
            BikeRouter.Projection any = BikeRouter.project(r, lat, lon, 0, r.pts.size());
            if (any.offset <= OFF_ROUTE_M) pr = any;  // back on the route further along
        }
        boolean accurate = !l.hasAccuracy() || l.getAccuracy() <= 40;
        if (pr.offset > OFF_ROUTE_M && accurate) {
            if (++offFixes >= OFF_ROUTE_FIXES) {
                Log.add(String.format(Locale.US, "Off route by %.0f m", pr.offset));
                reroute(lat, lon);
                return;
            }
        } else if (pr.offset <= OFF_ROUTE_M) {
            offFixes = 0;
            seg = pr.seg;
            along = pr.along;
        }

        double toEnd = r.length - along;
        double direct = RouteSimulator.dist(new double[] {lat, lon}, new double[] {destLat, destLon});
        if (toEnd < ARRIVED_M || direct < ARRIVED_M) {
            arrive();
            return;
        }
        BikeRouter.Step next = r.nextStep(along);
        if (next != lastStep) {
            lastStep = next;
            Log.add("Next: " + next.instruction + " in " + BikeRouter.formatDistance(next.along - along));
        }
        NavState.update(BikeRouter.formatDistance(next.along - along), next.instruction, eta(r, along),
                TurnIcons.get(next), "built-in");
    }

    private static void arrive() {
        arrived = true;
        BikeRouter.Step s = new BikeRouter.Step();
        s.type = "arrive";
        s.modifier = "straight";
        NavState.update("", destName.isEmpty() ? "You have arrived" : "Arrived: " + destName, "",
                TurnIcons.get(s), "built-in");
        // Show the arrival screen for the hold, then let the VIO sleep (screen off) to save power.
        NavState.arrived = true;
        Log.add("Arrived at " + destName + (reroutes > 0 ? " after " + reroutes + " reroutes" : ""));
        main.postDelayed(Navigator::stop, ARRIVED_HOLD_MS);
    }

    private static void reroute(double lat, double lon) {
        rerouting = true;
        offFixes = 0;
        lastRerouteAttempt = System.currentTimeMillis();
        NavState.update("", "Rerouting…", NavState.eta, null, "built-in");
        Location l = LocationTracker.last;
        float heading = l != null && l.hasSpeed() && l.getSpeed() > 1.5f ? LocationTracker.heading : Float.NaN;
        final double tLat = destLat, tLon = destLon;
        net.execute(() -> {
            try {
                List<BikeRouter.Route> routes = BikeRouter.fetch(lat, lon, tLat, tLon, heading, false);
                main.post(() -> {
                    if (!active || !rerouting) return;
                    route = routes.get(0);
                    seg = 0;
                    along = 0;
                    rerouting = false;
                    reroutes++;
                    Log.add("Rerouted: " + BikeRouter.formatDistance(route.length) + " to go");
                    Location now = LocationTracker.last;
                    if (now != null) onLocation(now);
                });
            } catch (Exception e) {
                Log.add("Reroute failed (retrying): " + e.getMessage());
            }
        });
    }

    private static String eta(BikeRouter.Route r, double along) {
        double left = Math.max(0, r.length - along);
        double secs = r.length > 0 ? r.duration * left / r.length : 0;
        return BikeRouter.formatDistance(left) + " · Arrive "
                + CLOCK.format(new Date(System.currentTimeMillis() + (long) (secs * 1000)));
    }
}
