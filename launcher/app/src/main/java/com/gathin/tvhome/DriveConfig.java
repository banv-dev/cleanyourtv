package com.gathin.tvhome;

import android.content.Context;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Parameters come from res/values/drive.xml via Android resources; theme JSON cannot override them. */
final class DriveConfig {
    final boolean enabled, syncOnLaunch;
    final String folderId, themeId, error, libraryId;
    final int intervalMinutes, cacheRetentionDays;
    final long cacheBytes, maxFileBytes;
    final Set<String> mimeTypes;

    private DriveConfig(Context context, String folder, int interval, boolean launch, int retention, boolean selected) {
        folderId = folder;
        themeId = DriveThemes.selection(folder);
        intervalMinutes = interval;
        syncOnLaunch = launch;
        cacheRetentionDays = retention;
        String root = context.getString(R.string.drive_theme_dir).trim();
        libraryId = root;
        error = !validId(root) ? "drive_theme_dir" : interval < 0 ? "drive_sync_interval"
                : retention < 1 ? "drive_cache_retention_days" : !folder.isEmpty() && !validId(folder) ? "selected Drive folder" : "";
        enabled = selected && validId(folder) && error.isEmpty();
        cacheBytes = Math.max(1L, context.getResources().getInteger(R.integer.drive_cache_max_size_mb)) * 1048576L;
        maxFileBytes = Math.max(1L, context.getResources().getInteger(R.integer.drive_max_file_size_mb)) * 1048576L;
        mimeTypes = new HashSet<>(Arrays.asList(context.getString(R.string.drive_supported_mime_types).split("\\s*,\\s*")));
        mimeTypes.retainAll(Arrays.asList("image/jpeg", "image/png", "image/webp"));
    }
    static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]+"); }
    static DriveConfig load(Context context) {
        String selected = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString("selected_theme", "");
        String folder = DriveThemes.folderId(selected);
        DriveConfig config = forFolder(context, folder);
        return folder.isEmpty() || DriveThemes.fromCurrentLibrary(context, folder) ? config
                : new DriveConfig(context, folder, config.intervalMinutes, config.syncOnLaunch, config.cacheRetentionDays, false);
    }
    static DriveConfig forFolder(Context context, String folder) {
        return create(context, folder, context.getResources().getInteger(R.integer.drive_sync_interval),
                context.getResources().getBoolean(R.bool.drive_sync_on_launch),
                context.getResources().getInteger(R.integer.drive_cache_retention_days));
    }
    static DriveConfig create(Context context, String folder, int interval, boolean launch, int retention) {
        return new DriveConfig(context, folder, interval, launch, retention, !folder.isEmpty());
    }
    boolean displaysCache() { return validId(folderId); }
    String identity() { return "full-drive-theme-v3:" + libraryId + ":" + themeId + ":" + enabled + ":" + intervalMinutes + ":" + syncOnLaunch
            + ":" + cacheRetentionDays + ":" + cacheBytes + ":" + maxFileBytes + ":" + new java.util.TreeSet<>(mimeTypes); }
}
