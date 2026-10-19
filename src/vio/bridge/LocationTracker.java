package vio.bridge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;

/** GPS position and direction of travel for the Map mode. */
public final class LocationTracker implements LocationListener {
    public static volatile Location last;
    /** Degrees clockwise from north; kept while standing still. */
    public static volatile float heading;

    /** Live providers we subscribe to: GPS leads, network (and the test mock) is a fallback. */
    private static final String[] LIVE = {
            LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};

    private final LocationManager lm;
    private boolean started;

    public LocationTracker(Context ctx) {
        lm = ctx.getSystemService(LocationManager.class);
    }

    @SuppressLint("MissingPermission")
    public void start() {
        if (started) return;
        try {
            // Subscribe to every live provider, so a fix still reaches navigation if one
            // drops out (and so the mock provider used in ride tests is picked up too).
            for (String p : LIVE) {
                try {
                    lm.requestLocationUpdates(p, 1000, 0, this, Looper.getMainLooper());
                } catch (IllegalArgumentException missing) {
                    // provider not present on this device
                }
            }
            last = freshestLastKnown();
            started = true;
            Log.add("GPS started");
        } catch (SecurityException e) {
            Log.add("Location permission missing");
        }
    }

    /** Best last-known fix across providers (fused is the one that survives provider churn). */
    @SuppressLint("MissingPermission")
    private Location freshestLastKnown() {
        Location best = last;
        for (String p : new String[] {LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
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

    public void stop() {
        if (started) lm.removeUpdates(this);
        started = false;
    }

    @Override
    public void onLocationChanged(Location l) {
        if (l.hasBearing() && l.hasSpeed() && l.getSpeed() > 1.5f) heading = l.getBearing();
        last = l;
        Navigator.onLocation(l);
    }
}
