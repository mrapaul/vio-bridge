package vio.bridge;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Destination search and the list of recent destinations. */
final class Places {
    static final class Place {
        final String name, address;
        final double lat, lon;

        Place(String name, String address, double lat, double lon) {
            this.name = name;
            this.address = address;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final int MAX_RECENT = 6;
    /** Search this far (degrees) around the current position first. */
    private static final double NEARBY = 0.4;

    private Places() {}

    /** Blocking; call off the main thread. */
    @SuppressWarnings("deprecation")
    static List<Place> search(Context ctx, String query, Location near) {
        List<Place> out = new ArrayList<>();
        if (Geocoder.isPresent()) {
            try {
                Geocoder g = new Geocoder(ctx, Locale.getDefault());
                List<Address> found = near == null ? g.getFromLocationName(query, 6)
                        : g.getFromLocationName(query, 6, near.getLatitude() - NEARBY, near.getLongitude() - NEARBY,
                                near.getLatitude() + NEARBY, near.getLongitude() + NEARBY);
                if ((found == null || found.isEmpty()) && near != null) found = g.getFromLocationName(query, 6);
                if (found != null) {
                    for (Address a : found) {
                        String line = a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : "";
                        String name = a.getFeatureName() != null && !line.startsWith(a.getFeatureName())
                                ? a.getFeatureName() : query;
                        out.add(new Place(name, line, a.getLatitude(), a.getLongitude()));
                    }
                }
            } catch (Exception e) {
                Log.add("Phone geocoder failed: " + e.getMessage());
            }
        }
        if (out.isEmpty()) {
            try {
                out.addAll(nominatim(query, near));
            } catch (Exception e) {
                Log.add("Address search failed: " + e.getMessage());
            }
        }
        return out;
    }

    private static List<Place> nominatim(String query, Location near) throws Exception {
        String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&q="
                + URLEncoder.encode(query, "UTF-8");
        if (near != null) {
            url += String.format(Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f",
                    near.getLongitude() - NEARBY, near.getLatitude() + NEARBY,
                    near.getLongitude() + NEARBY, near.getLatitude() - NEARBY);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", TileCache.USER_AGENT);
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) body.write(buf, 0, n);
        }
        JSONArray arr = new JSONArray(body.toString("UTF-8"));
        List<Place> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String display = o.optString("display_name");
            String name = o.optString("name");
            if (name.isEmpty()) name = display.split(",")[0];
            out.add(new Place(name, display, o.getDouble("lat"), o.getDouble("lon")));
        }
        return out;
    }

    static List<Place> recent(Context ctx) {
        List<Place> out = new ArrayList<>();
        String s = prefs(ctx).getString("recent", "");
        for (String line : s.split("\n")) {
            String[] f = line.split("\t");
            if (f.length == 4) {
                out.add(new Place(f[0], f[1], Double.parseDouble(f[2]), Double.parseDouble(f[3])));
            }
        }
        return out;
    }

    static void remember(Context ctx, Place p) {
        StringBuilder sb = new StringBuilder(entry(p));
        int n = 1;
        for (Place q : recent(ctx)) {
            if (n >= MAX_RECENT) break;
            if (RouteSimulator.dist(new double[] {p.lat, p.lon}, new double[] {q.lat, q.lon}) < 30) continue;
            sb.append('\n').append(entry(q));
            n++;
        }
        prefs(ctx).edit().putString("recent", sb.toString()).apply();
    }

    private static String entry(Place p) {
        return clean(p.name) + "\t" + clean(p.address) + "\t" + p.lat + "\t" + p.lon;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("vio", Context.MODE_PRIVATE);
    }
}
