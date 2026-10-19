package vio.bridge;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Reads Google Maps' turn-by-turn notification. During navigation Maps posts an
 * ongoing notification whose title is the distance to the next manoeuvre, whose
 * text is the instruction, whose sub-text holds the ETA, and whose large icon
 * is the manoeuvre arrow.
 */
public class MapsListener extends NotificationListenerService {
    static final String MAPS = "com.google.android.apps.maps";

    @Override
    public void onListenerConnected() {
        Log.add("Notification access active");
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) onNotificationPosted(sbn);
        } catch (RuntimeException e) {
            Log.add("Could not read active notifications: " + e);
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!MAPS.equals(sbn.getPackageName()) || !isNavigation(sbn)) return;
        if (Navigator.active) return;  // built-in navigation owns the VIO screen
        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        String title = text(x, Notification.EXTRA_TITLE);
        String body = text(x, Notification.EXTRA_TEXT);
        String big = text(x, Notification.EXTRA_BIG_TEXT);
        String sub = text(x, Notification.EXTRA_SUB_TEXT);
        String info = text(x, Notification.EXTRA_INFO_TEXT);
        // Newer Maps on Samsung (ProgressStyle / Now Bar): the title is the
        // instruction and the distance sits in the "ongoing activity" fields.
        String primary = text(x, "android.ongoingActivityNoti.primaryInfo");
        String distance, instruction;
        if (!primary.isEmpty() && Renderer.distanceMeters(primary) >= 0) {
            distance = primary;
            instruction = title;
        } else if (!primary.isEmpty()) {
            // Between steps Maps sometimes puts a street name in the distance field.
            distance = NavState.distance;
            instruction = title;
        } else if (Renderer.distanceMeters(title) >= 0 && title.length() < 14) {
            distance = title;  // older Maps: title "200 m", text "Turn right onto …"
            instruction = !big.isEmpty() ? big : body;
        } else {
            distance = "";
            instruction = title.isEmpty() ? body : title;
        }
        // Some updates prefix the instruction with the distance: "150 m · Turn left …".
        instruction = instruction.replaceFirst("^[0-9]+(?:[.,][0-9]+)?\\s*(?:km|m|mi|ft|yd)\\s*·\\s*", "");
        String eta = !sub.isEmpty() ? sub : info;
        Bitmap arrow = toBitmap(n.getLargeIcon());
        String modeIcon = travelModeIcon(x);
        if (!modeIcon.equals(lastModeIcon)) {
            lastModeIcon = modeIcon;
            Log.add("Maps travel-mode icon: " + modeIcon);
        }
        String raw = "distance=" + distance + "\ninstruction=" + instruction + "\neta=" + eta
                + "\narrow=" + (arrow != null);
        if (!raw.equals(NavState.lastRaw)) Log.add("Maps: " + distance + " | " + instruction);
        NavState.update(distance, instruction, eta, arrow, raw);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (MAPS.equals(sbn.getPackageName()) && isNavigation(sbn) && !Navigator.active) {
            Log.add("Maps navigation ended");
            NavState.clear();
        }
    }

    private String lastModeIcon = "";

    /**
     * Resource name of the small travel-mode icon Maps puts in its progress
     * notification (e.g. a bike or car glyph), or "" if there is none.
     */
    private String travelModeIcon(Bundle x) {
        Object o = x.get("android.progressTrackerIcon");
        if (!(o instanceof Icon)) return "";
        Icon icon = (Icon) o;
        if (icon.getType() != Icon.TYPE_RESOURCE) return "type" + icon.getType();
        try {
            return getPackageManager().getResourcesForApplication(MAPS)
                    .getResourceEntryName(icon.getResId());
        } catch (Exception e) {
            return "id" + Integer.toHexString(icon.getResId());
        }
    }

    private static boolean isNavigation(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        return Notification.CATEGORY_NAVIGATION.equals(n.category) || sbn.isOngoing();
    }

    private static String text(Bundle x, String key) {
        CharSequence cs = x.getCharSequence(key);
        return cs == null ? "" : cs.toString().trim();
    }

    private Bitmap toBitmap(Icon icon) {
        if (icon == null) return null;
        try {
            Drawable d = icon.loadDrawable(this);
            if (d == null) return null;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                return ((BitmapDrawable) d).getBitmap();
            }
            int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, w, h);
            d.draw(new Canvas(b));
            return b;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
