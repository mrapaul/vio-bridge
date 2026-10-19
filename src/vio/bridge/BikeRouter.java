package vio.bridge;

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
 * Cycling routes from the OpenStreetMap bike router (OSRM, bicycle profile,
 * hosted by FOSSGIS). Returns the route line plus turn-by-turn steps.
 */
public final class BikeRouter {
    static final String ROUTER = "https://routing.openstreetmap.de/routed-bike/route/v1/driving/%s"
            + "?overview=full&geometries=geojson&steps=true&alternatives=%s%s";

    /** One manoeuvre: where along the route it happens and what to do there. */
    public static final class Step {
        public double along;
        public String type, modifier, name, instruction;
        public int exit;
    }

    public static final class Route {
        /** lat, lon per point. */
        public final List<double[]> pts = new ArrayList<>();
        /** Distance from the start to each point, in metres. */
        public double[] cum;
        public final List<Step> steps = new ArrayList<>();
        public double length, duration;
        public String summary = "";

        /** First real manoeuvre after the given progress, or the arrival. */
        public Step nextStep(double along) {
            for (Step s : steps) if (s.along > along + 3) return s;
            return steps.get(steps.size() - 1);
        }
    }

    private BikeRouter() {}

    /**
     * @param heading direction of travel at the start (degrees), or NaN; the
     *                router then prefers routes that don't start with a U-turn.
     */
    public static List<Route> fetch(double fromLat, double fromLon, double toLat, double toLon,
                                    float heading, boolean alternatives) throws Exception {
        String coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", fromLon, fromLat, toLon, toLat);
        String bearings = Float.isNaN(heading) ? ""
                : String.format(Locale.US, "&bearings=%d,60;", Math.round(heading) % 360);
        URL url = new URL(String.format(Locale.US, ROUTER, coords, alternatives ? "true" : "false", bearings));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestProperty("User-Agent", TileCache.USER_AGENT);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) body.write(buf, 0, n);
        }
        JSONObject json = new JSONObject(body.toString("UTF-8"));
        if (!"Ok".equals(json.optString("code"))) {
            throw new IllegalStateException("router: " + json.optString("message", json.optString("code")));
        }
        JSONArray routes = json.getJSONArray("routes");
        List<Route> out = new ArrayList<>();
        for (int i = 0; i < routes.length(); i++) out.add(parse(routes.getJSONObject(i)));
        return out;
    }

    private static Route parse(JSONObject jr) throws Exception {
        Route r = new Route();
        r.duration = jr.getDouble("duration");
        JSONArray coords = jr.getJSONObject("geometry").getJSONArray("coordinates");
        for (int i = 0; i < coords.length(); i++) {
            JSONArray p = coords.getJSONArray(i);
            r.pts.add(new double[] {p.getDouble(1), p.getDouble(0)});
        }
        r.cum = new double[r.pts.size()];
        for (int i = 1; i < r.pts.size(); i++) {
            r.cum[i] = r.cum[i - 1] + RouteSimulator.dist(r.pts.get(i - 1), r.pts.get(i));
        }
        r.length = r.cum[r.cum.length - 1];
        JSONObject leg = jr.getJSONArray("legs").getJSONObject(0);
        r.summary = leg.optString("summary", "");
        JSONArray steps = leg.getJSONArray("steps");
        int seg = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject js = steps.getJSONObject(i);
            JSONObject m = js.getJSONObject("maneuver");
            Step s = new Step();
            s.type = m.optString("type");
            s.modifier = m.optString("modifier", "straight");
            s.exit = m.optInt("exit", 0);
            s.name = js.optString("name", "");
            if (!keep(s)) continue;
            JSONArray loc = m.getJSONArray("location");
            // Manoeuvres come in route order, so search forward from the last one.
            double lat = loc.getDouble(1), lon = loc.getDouble(0);
            Projection pr = project(r, lat, lon, seg, r.pts.size() - 1);
            seg = pr.seg;
            s.along = pr.along;
            s.instruction = instruction(s);
            r.steps.add(s);
        }
        if (r.steps.isEmpty() || !"arrive".equals(r.steps.get(r.steps.size() - 1).type)) {
            Step s = new Step();
            s.type = "arrive";
            s.modifier = "straight";
            s.name = "";
            s.along = r.length;
            s.instruction = instruction(s);
            r.steps.add(s);
        }
        return r;
    }

    /** Leave out steps that don't need any action, so the next real turn shows. */
    private static boolean keep(Step s) {
        switch (s.type) {
            case "depart":
            case "new name":
            case "notification":
                return false;
            case "continue":
                return !s.modifier.equals("straight");
            default:
                return true;
        }
    }

    static String instruction(Step s) {
        String onto = s.name.isEmpty() ? "" : " onto " + s.name;
        String dir = s.modifier.replace("slight ", "slightly ");
        switch (s.type) {
            case "arrive":
                return "Arrive at your destination";
            case "roundabout":
            case "rotary":
                return s.exit > 0 ? "At the roundabout take exit " + s.exit + onto : "Enter the roundabout" + onto;
            case "exit roundabout":
            case "exit rotary":
                return "Leave the roundabout" + onto;
            case "fork":
                return "Keep " + side(s.modifier) + " at the fork" + onto;
            case "end of road":
                return "At the end of the road turn " + side(s.modifier) + onto;
            case "merge":
                return "Merge " + side(s.modifier) + onto;
            default:
                if (s.modifier.equals("uturn")) return "Make a U-turn" + onto;
                if (s.modifier.equals("straight")) return s.name.isEmpty() ? "Go straight on" : "Continue onto " + s.name;
                if (s.type.equals("continue")) return "Bear " + side(s.modifier) + onto;
                return "Turn " + dir + onto;
        }
    }

    private static String side(String modifier) {
        return modifier.contains("left") ? "left" : modifier.contains("right") ? "right" : "straight";
    }

    // ------------------------------------------------------------ geometry

    public static final class Projection {
        public int seg;
        public double along, offset;
    }

    /** Nearest point on the route between segments from..to (inclusive). */
    public static Projection project(Route r, double lat, double lon, int from, int to) {
        Projection best = new Projection();
        best.offset = Double.MAX_VALUE;
        double kx = Math.cos(Math.toRadians(lat)) * 111320, ky = 110574;
        from = Math.max(0, from);
        to = Math.min(r.pts.size() - 2, to);
        for (int i = from; i <= to; i++) {
            double[] a = r.pts.get(i), b = r.pts.get(i + 1);
            double ax = (a[1] - lon) * kx, ay = (a[0] - lat) * ky;
            double bx = (b[1] - lon) * kx, by = (b[0] - lat) * ky;
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double px = ax + t * dx, py = ay + t * dy;
            double d = Math.hypot(px, py);
            if (d < best.offset) {
                best.offset = d;
                best.seg = i;
                best.along = r.cum[i] + t * (r.cum[i + 1] - r.cum[i]);
            }
        }
        return best;
    }

    static String formatDistance(double m) {
        if (m >= 1000) return String.format(Locale.US, "%.1f km", m / 1000);
        if (m >= 100) return Math.round(m / 10) * 10 + " m";
        return Math.max(0, Math.round(m / 5) * 5) + " m";
    }
}
