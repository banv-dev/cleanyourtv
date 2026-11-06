package com.gathin.tvhome;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** One native scheduler for launch, manual and periodic sync, persisted over reboot. */
final class DriveSync {
    static final int PERIODIC = 43010, NOW = 43011;
    private static final Set<Runnable> LISTENERS = new CopyOnWriteArraySet<>();
    static void listen(Runnable listener) { LISTENERS.add(listener); }
    static void unlisten(Runnable listener) { LISTENERS.remove(listener); }
    static void notifyImages() { new Handler(Looper.getMainLooper()).post(() -> { for (Runnable r : LISTENERS) r.run(); }); }
    static void schedule(Context context, boolean launching) {
        DriveConfig config = DriveConfig.load(context);
        if (launching) DriveImageCache.markUsed(context, config);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) { DriveLog.event("stage=schedule JobScheduler unavailable"); return; }
        DriveLog.event("stage=schedule theme=" + config.themeId + " enabled=" + config.enabled
                + " intervalMinutes=" + config.intervalMinutes + " effectivePeriodicMinutes="
                + (config.intervalMinutes == 0 ? 0 : Math.max(15, config.intervalMinutes))
                + " syncOnLaunch=" + config.syncOnLaunch + " launching=" + launching);
        JobInfo immediate = scheduler.getPendingJob(NOW);
        if (immediate != null && !config.identity().equals(immediate.getExtras().getString("source"))) scheduler.cancel(NOW);
        if (!config.enabled || config.intervalMinutes == 0) scheduler.cancel(PERIODIC);
        else {
            JobInfo existing = scheduler.getPendingJob(PERIODIC);
            if (existing == null || !config.identity().equals(existing.getExtras().getString("source"))) {
                int result = scheduler.schedule(builder(context, config, PERIODIC)
                        .setPeriodic(Math.max(15L, config.intervalMinutes) * 60000L).setPersisted(true).build());
                DriveLog.event("stage=schedule periodic accepted=" + (result == JobScheduler.RESULT_SUCCESS));
            }
        }
        if (launching && config.enabled && config.syncOnLaunch) now(context);
    }
    static boolean now(Context context) {
        DriveConfig config = DriveConfig.load(context);
        if (!config.enabled) return false;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return false;
        JobInfo pending = scheduler.getPendingJob(NOW);
        if (pending != null && config.identity().equals(pending.getExtras().getString("source"))) {
            DriveProgress.queued(config);
            return true;
        }
        boolean accepted = scheduler.schedule(builder(context, config, NOW).setMinimumLatency(0).build()) == JobScheduler.RESULT_SUCCESS;
        DriveLog.event("stage=schedule immediate accepted=" + accepted + "; awaiting Android network constraint");
        if (accepted) DriveProgress.queued(config);
        else DriveProgress.finish(config, DriveProgress.Phase.ERROR);
        return accepted;
    }
    private static JobInfo.Builder builder(Context context, DriveConfig config, int id) {
        PersistableBundle extras = new PersistableBundle();
        extras.putString("source", config.identity());
        return new JobInfo.Builder(id, new ComponentName(context, DriveSyncService.class)).setExtras(extras)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
    }
    static void status(Context context, String value) {
        context.getSharedPreferences("drive_sync", Context.MODE_PRIVATE).edit()
                .putString("status", value).putLong("updated", System.currentTimeMillis()).apply();
    }
}
