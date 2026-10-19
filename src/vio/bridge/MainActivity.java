package vio.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

/** Status screen: setup buttons, live preview of the VIO screen and a log. */
public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status, setup, maps, log;
    private ImageView preview;
    private Button startStop;
    private RadioGroup modes;
    private EditText query;
    private LinearLayout choices;
    private Button stopNav;
    private static final int REQ_CAPTURE = 7;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("VIO Bridge");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        setup = new TextView(this);
        root.addView(setup);

        Button perms = new Button(this);
        perms.setText("1. Allow Bluetooth, location & notifications");
        perms.setOnClickListener(v -> requestPermissions(new String[] {
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1));
        root.addView(perms);

        Button access = new Button(this);
        access.setText("2. Allow reading Google Maps notifications");
        access.setOnClickListener(v -> startActivity(
                new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(access);

        startStop = new Button(this);
        startStop.setOnClickListener(v -> {
            Intent svc = new Intent(this, VioService.class);
            if (VioService.running) stopService(svc);
            else startForegroundService(svc.putExtra(VioService.EXTRA_FROM_UI, true));
            ui.postDelayed(this::refresh, 300);
        });
        root.addView(startStop);

        TextView navLabel = new TextView(this);
        navLabel.setText("Where are you cycling to?");
        navLabel.setTextSize(18);
        navLabel.setPadding(0, dp(16), 0, dp(4));
        root.addView(navLabel);
        LinearLayout searchRow = new LinearLayout(this);
        query = new EditText(this);
        query.setHint("Address or place");
        query.setSingleLine(true);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, action, ev) -> {
            search(query.getText().toString());
            return true;
        });
        searchRow.addView(query, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button go = new Button(this);
        go.setText("Search");
        go.setOnClickListener(v -> search(query.getText().toString()));
        searchRow.addView(go);
        root.addView(searchRow);
        choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        root.addView(choices);
        stopNav = new Button(this);
        stopNav.setText("Stop navigation");
        stopNav.setOnClickListener(v -> {
            Navigator.stop();
            showRecent();
        });
        root.addView(stopNav);

        Button bike = new Button(this);
        bike.setText("Use Google Maps instead (bike)");
        bike.setOnClickListener(v -> startActivity(VioService.bikeRouteIntent()));
        root.addView(bike);

        TextView modeLabel = new TextView(this);
        modeLabel.setText("VIO screen (or tap the VIO to switch):");
        modeLabel.setPadding(0, dp(12), 0, 0);
        root.addView(modeLabel);
        modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        for (int m = Mode.TURN; m <= Mode.MIRROR; m++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(100 + m);
            rb.setText(Mode.name(m));
            modes.addView(rb);
        }
        Mode.load(this);
        modes.check(100 + Mode.current);
        modes.setOnCheckedChangeListener((g, id) -> {
            int m = id - 100;
            if (m == Mode.current) return;
            Mode.set(this, m);
            if (m == Mode.MIRROR && !ScreenMirror.active) requestCapture();
        });
        root.addView(modes);

        preview = new ImageView(this);
        preview.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(200));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(12);
        root.addView(preview, lp);

        maps = new TextView(this);
        maps.setTextSize(12);
        maps.setPadding(0, dp(8), 0, dp(8));
        root.addView(maps);

        log = new TextView(this);
        log.setTextSize(11);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(log);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        if (!handleShare(getIntent())) showRecent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }

    // ------------------------------------------------------- built-in navigation

    /** "Share" a place from Google Maps (or anything else) to VIO Bridge to ride there. */
    private boolean handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return false;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return false;
        // Maps shares "Name\nAddress\nhttps://maps.app.goo.gl/…": keep the words.
        String q = text.replaceAll("https?://\\S+", "").replaceAll("\\s*\n\\s*", ", ")
                .replaceAll("^[,\\s]+|[,\\s]+$", "");
        if (q.isEmpty()) return false;
        query.setText(q);
        search(q);
        return true;
    }

    private void showRecent() {
        choices.removeAllViews();
        if (Navigator.active) {
            note("Navigating to " + Navigator.destName);
            return;
        }
        List<Places.Place> recent = Places.recent(this);
        if (recent.isEmpty()) return;
        note("Recent:");
        for (Places.Place p : recent) choice(p.name, p.address, () -> planRoutes(p));
    }

    private void search(String q) {
        q = q.trim();
        if (q.isEmpty()) {
            showRecent();
            return;
        }
        choices.removeAllViews();
        note("Searching…");
        final String fq = q;
        Location near = here();
        new Thread(() -> {
            List<Places.Place> found = Places.search(this, fq, near);
            runOnUiThread(() -> {
                choices.removeAllViews();
                if (found.isEmpty()) note("Nothing found for \"" + fq + "\"");
                for (Places.Place p : found) choice(p.name, p.address, () -> planRoutes(p));
            });
        }, "vio-search").start();
    }

    private void planRoutes(Places.Place dest) {
        Location from = here();
        choices.removeAllViews();
        if (from == null) {
            note("No GPS position yet. Start the VIO connection, wait a moment and try again.");
            return;
        }
        note("Finding bike routes to " + dest.name + "…");
        new Thread(() -> {
            try {
                List<BikeRouter.Route> routes = BikeRouter.fetch(from.getLatitude(), from.getLongitude(),
                        dest.lat, dest.lon, Float.NaN, true);
                runOnUiThread(() -> showRoutes(dest, routes));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    choices.removeAllViews();
                    note("Could not get a route: " + e.getMessage());
                });
            }
        }, "vio-route").start();
    }

    private void showRoutes(Places.Place dest, List<BikeRouter.Route> routes) {
        choices.removeAllViews();
        note("Pick a route to " + dest.name + ":");
        for (int i = 0; i < routes.size(); i++) {
            BikeRouter.Route r = routes.get(i);
            String title = String.format(Locale.US, "%s · %d min%s", BikeRouter.formatDistance(r.length),
                    Math.round(r.duration / 60), i == 0 ? " (fastest)" : "");
            choice(title, r.summary.isEmpty() ? "" : "via " + r.summary, () -> startNavigation(dest, r));
        }
    }

    private void startNavigation(Places.Place dest, BikeRouter.Route r) {
        Places.remember(this, dest);
        Navigator.start(r, dest.lat, dest.lon, dest.name);
        // Make sure the service (and GPS) runs; it keeps going with the screen off.
        startForegroundService(new Intent(this, VioService.class).putExtra(VioService.EXTRA_FROM_UI, true));
        showRecent();
    }

    private Location here() {
        Location l = LocationTracker.last;
        if (l != null) return l;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        LocationManager lm = getSystemService(LocationManager.class);
        l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (l == null) l = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER);
        if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        return l;
    }

    private void note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(6), 0, dp(6));
        choices.addView(t);
    }

    private void choice(String title, String detail, Runnable action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setText(detail == null || detail.isEmpty() ? title : title + "\n" + detail);
        b.setOnClickListener(v -> action.run());
        choices.addView(b);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacksAndMessages(null);
    }

    private void refresh() {
        boolean bt = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        boolean notif = hasNotificationAccess();
        boolean loc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        setup.setText((bt ? "✓" : "✗") + " Bluetooth permission\n"
                + (loc ? "✓" : "✗") + " Location (Map mode)\n"
                + (notif ? "✓" : "✗") + " Google Maps notification access");
        status.setText("Status: " + VioService.status);
        startStop.setText(VioService.running ? "Stop" : "3. Start VIO connection");
        startStop.setEnabled(bt || VioService.running);
        if (VioService.lastFrame != null) preview.setImageBitmap(VioService.lastFrame);
        if (modes.getCheckedRadioButtonId() != 100 + Mode.current) modes.check(100 + Mode.current);
        stopNav.setVisibility(Navigator.active ? android.view.View.VISIBLE : android.view.View.GONE);
        if (Navigator.active) maps.setText("Built-in navigation: " + NavState.distance + " · " + NavState.instruction
                + "\n" + NavState.eta);
        else maps.setText(NavState.active ? "Google Maps:\n" + NavState.lastRaw : "Google Maps: no navigation active");
        log.setText(Log.text());
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(this::refresh, 500);
    }

    private void requestCapture() {
        if (!VioService.running) {
            Log.add("Start the VIO connection first, then pick Mirror");
            return;
        }
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_CAPTURE) return;
        if (result != RESULT_OK || data == null) {
            Log.add("Screen capture declined");
            return;
        }
        startForegroundService(new Intent(this, VioService.class)
                .putExtra(VioService.EXTRA_CAPTURE_CODE, result)
                .putExtra(VioService.EXTRA_CAPTURE_DATA, data));
    }

    private boolean hasNotificationAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (TextUtils.isEmpty(enabled)) return false;
        String me = new ComponentName(this, MapsListener.class).flattenToString();
        for (String s : enabled.split(":")) if (s.equals(me)) return true;
        return false;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
