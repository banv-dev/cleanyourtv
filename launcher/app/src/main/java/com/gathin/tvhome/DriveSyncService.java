package com.gathin.tvhome;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serial worker: separate jobs cannot modify the image manifest concurrently. */
public final class DriveSyncService extends JobService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<Integer, Task> tasks = new ConcurrentHashMap<>();
    private static final class Task { final AtomicBoolean stopped = new AtomicBoolean(); Future<?> future; }
    @Override public boolean onStartJob(JobParameters parameters) {
        Task task = new Task();
        tasks.put(parameters.getJobId(), task);
        task.future = worker.submit(() -> {
            DriveConfig config = DriveConfig.load(this);
            DriveImageRepository.Check check = () -> {
                if (task.stopped.get() || Thread.currentThread().isInterrupted()
                        || !config.enabled || !config.identity().equals(parameters.getExtras().getString("source"))
                        || !config.identity().equals(DriveConfig.load(this).identity())) throw new IOException("Obsolete sync");
            };
            long started = android.os.SystemClock.elapsedRealtime();
            String stage = "source.check";
            DriveLog.event("job=" + parameters.getJobId() + " theme=" + config.themeId
                    + " start deviceUtc=" + java.time.Instant.now() + " intervalMinutes=" + config.intervalMinutes
                    + " cachedImages=" + DriveImageCache.images(this, config).size());
            try {
                check.check();
                DriveSync.status(this, "Syncing");
                stage = "auth.load";
                DriveImageRepository repository = new DriveImageRepository(new DriveAuthManager(this), check);
                stage = "images.sync";
                boolean complete = DriveImageCache.sync(this, config, repository, check);
                DriveSync.status(this, complete ? "Synced" : "Partial sync: cache limit or unreadable image");
                DriveLog.event("job=" + parameters.getJobId() + " finished complete=" + complete
                        + " cachedImages=" + DriveImageCache.images(this, config).size()
                        + " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started));
                DriveSync.notifyImages();
            } catch (Exception failure) {
                if (!task.stopped.get()) {
                    String detail = DriveLog.failure(stage, failure);
                    DriveSync.status(this, "Sync failed; keeping cached images. " + detail);
                } else DriveLog.event("job=" + parameters.getJobId() + " cancelled; cache preserved");
            } finally {
                tasks.remove(parameters.getJobId(), task);
                if (!task.stopped.get()) jobFinished(parameters, false);
            }
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        DriveLog.event("job=" + parameters.getJobId() + " stopped by Android; reason=" + (android.os.Build.VERSION.SDK_INT >= 31 ? parameters.getStopReason() : -1));
        Task task = tasks.remove(parameters.getJobId());
        if (task != null) {
            task.stopped.set(true);
            if (task.future != null) task.future.cancel(true);
        }
        DriveConfig config = DriveConfig.load(this);
        return config.enabled && config.identity().equals(parameters.getExtras().getString("source"));
    }
    @Override public void onDestroy() {
        for (Task task : tasks.values()) { task.stopped.set(true); if (task.future != null) task.future.cancel(true); }
        worker.shutdownNow();
        super.onDestroy();
    }
}
