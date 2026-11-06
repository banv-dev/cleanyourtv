package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reconcile persisted jobs with new resources after an APK update. */
public final class DriveUpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) { DriveSync.schedule(context, false); }
}
