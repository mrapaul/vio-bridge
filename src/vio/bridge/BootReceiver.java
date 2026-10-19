package vio.bridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Auto-start: after a reboot or an app update, start listening for the VIO
 * again if the user has started VIO Bridge before. The VIO then connects by
 * itself when it is switched on.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!ctx.getSharedPreferences("vio", Context.MODE_PRIVATE).getBoolean("autostart", false)) return;
        try {
            ctx.startForegroundService(new Intent(ctx, VioService.class));
        } catch (RuntimeException e) {
            Log.add("Auto-start failed: " + e.getMessage());
        }
    }
}
