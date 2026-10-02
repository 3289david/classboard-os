package kr.classboard.os;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the core service when the board powers on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        CoreService.start(context);
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            // after an automatic update, come back to the board screen
            try {
                context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
    }
}
