package com.gathin.tvhome;

import android.content.Context;
import android.os.Environment;
import android.util.Log;
import org.json.JSONObject;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Strict content configuration; technical limits always come from resources. */
final class DriveConfig {
    final boolean enabled, syncOnLaunch;
    final String folderId, themeId, error;
    final int intervalMinutes, cacheRetentionDays;
    final long cacheBytes, maxFileBytes;
    final Set<String> mimeTypes;

    private DriveConfig(Context context, boolean enabled, String folder, String theme,
                        int interval, boolean onLaunch, int retentionDays, String error) {
        this.enabled = enabled;
        folderId = folder;
        themeId = theme;
        intervalMinutes = interval;
        cacheRetentionDays = retentionDays;
        syncOnLaunch = onLaunch;
        this.error = error;
        cacheBytes = Math.max(1L, context.getResources().getInteger(R.integer.drive_cache_max_size_mb)) * 1048576L;
        maxFileBytes = Math.max(1L, context.getResources().getInteger(R.integer.drive_max_file_size_mb)) * 1048576L;
        mimeTypes = new HashSet<>(Arrays.asList(context.getString(R.string.drive_supported_mime_types).split("\\s*,\\s*")));
        mimeTypes.retainAll(Arrays.asList("image/jpeg", "image/png", "image/webp"));
    }

    static DriveConfig load(Context context) {
        File dir = Config.dir(context);
        return parse(context, Config.readOverlay(context, dir == null ? null : new File(dir, Config.FILE_NAME),
                Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())));
    }

    static DriveConfig parse(Context context, JSONObject theme) {
        String themeId = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString("selected_theme", "");
        String folder = "", error = "";
        int interval = 360, retentionDays = 30;
        boolean enabled = false, launch = true;
        try {
            Object value = theme == null ? null : theme.opt("googleDrive");
            if (value == null) return new DriveConfig(context, false, "", themeId, interval, launch, retentionDays, "");
            if (!(value instanceof JSONObject)) throw new IllegalArgumentException("googleDrive");
            JSONObject drive = (JSONObject) value;
            Object flag = drive.opt("enabled");
            if (flag != null && !(flag instanceof Boolean)) throw new IllegalArgumentException("googleDrive.enabled");
            if (Boolean.FALSE.equals(flag)) return new DriveConfig(context, false, "", themeId, interval, launch, retentionDays, "");
            Object id = drive.opt("folderId");
            if (!(id instanceof String) || !((String) id).matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException("googleDrive.folderId");
            folder = (String) id;
            Object minutes = drive.opt("syncInterval");
            if (minutes != null) {
                if (!(minutes instanceof Integer || minutes instanceof Long) || ((Number) minutes).longValue() < 0
                        || ((Number) minutes).longValue() > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("googleDrive.syncInterval");
                interval = ((Number) minutes).intValue();
            }
            Object retention = drive.opt("cacheRetentionDays");
            if (retention != null) {
                if (!(retention instanceof Integer || retention instanceof Long) || ((Number) retention).longValue() < 1
                        || ((Number) retention).longValue() > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("googleDrive.cacheRetentionDays");
                retentionDays = ((Number) retention).intValue();
            }
            Object start = drive.opt("syncOnLaunch");
            if (start != null && !(start instanceof Boolean)) throw new IllegalArgumentException("googleDrive.syncOnLaunch");
            launch = start == null || (Boolean) start;
            enabled = true;
        } catch (IllegalArgumentException exception) {
            error = exception.getMessage();
            Log.w("TvHome.Drive", "Theme " + themeId + ": invalid " + error);
        }
        return new DriveConfig(context, enabled, folder, themeId, interval, launch, retentionDays, error);
    }

    boolean displaysCache() { return enabled || (!error.isEmpty() && !folderId.isEmpty()); }

    String identity() { return "theme-layout-v2:" + themeId + ":" + folderId + ":" + enabled + ":" + intervalMinutes + ":" + syncOnLaunch
            + ":" + cacheRetentionDays + ":" + cacheBytes + ":" + maxFileBytes + ":" + new java.util.TreeSet<>(mimeTypes); }
}
