package kr.classboard.os;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the core service when the board powers on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        CoreService.start(context);
    }
}
