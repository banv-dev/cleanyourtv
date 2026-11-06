package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Catalog/selection metadata only; theme configuration and assets live in published private snapshots. */
final class DriveThemes {
    static final String PREFIX = "drive:";
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("drive_themes", Context.MODE_PRIVATE); }
    static String selection(String folder) { return folder.isEmpty() ? "" : PREFIX + folder; }
    static String folderId(String selection) {
        if (selection == null || !selection.startsWith(PREFIX)) return "";
        String id = selection.substring(PREFIX.length());
        return DriveConfig.validId(id) ? id : "";
    }
    static String label(Context context, String selection) {
        String id = folderId(selection);
        return id.isEmpty() ? selection : "gDrive-" + prefs(context).getString("name:" + id, context.getString(R.string.drive_theme_unnamed));
    }
    static void remember(Context context, String id, String name) {
        prefs(context).edit().putString("name:" + id, name)
                .putString("parent:" + id, context.getString(R.string.drive_theme_dir).trim()).apply();
    }
    static boolean fromCurrentLibrary(Context context, String id) {
        return context.getString(R.string.drive_theme_dir).trim().equals(prefs(context).getString("parent:" + id, ""));
    }
    static List<JSONObject> catalog(Context context) {
        try {
            JSONArray values = new JSONArray(prefs(context).getString("catalog:" + context.getString(R.string.drive_theme_dir).trim(), "[]"));
            List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) result.add(values.getJSONObject(i));
            return result;
        } catch (Exception ignored) { return new ArrayList<>(); }
    }
    static void saveCatalog(Context context, List<JSONObject> folders) {
        JSONArray values = new JSONArray();
        for (JSONObject folder : folders) {
            values.put(folder);
            remember(context, folder.optString("id"), folder.optString("name"));
        }
        prefs(context).edit().putString("catalog:" + context.getString(R.string.drive_theme_dir).trim(), values.toString()).apply();
    }
    static File directory(Context context, String selection) {
        String id = folderId(selection);
        if (id.isEmpty()) return null;
        File published = DriveImageCache.publishedDirectory(context, DriveConfig.forFolder(context, id));
        return published != null ? published : new File(new File(context.getFilesDir(), "drive-pending"), id);
    }
    static void published(Context context, DriveConfig config) { published(context, config, false); }
    static void published(Context context, DriveConfig config, boolean firstDownload) {
        File directory = DriveImageCache.publishedDirectory(context, config);
        if (directory == null) return;
        String revision = DriveImageCache.styleRevision(context, config);
        if (!firstDownload && revision.equals(prefs(context).getString("applied:" + config.folderId, ""))) return;
        prefs(context).edit().putString("applied:" + config.folderId, revision).apply();
        SharedPreferences settings = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        if (!config.themeId.equals(settings.getString("selected_theme", ""))) return;
        settings.edit().putLong(Config.SETTINGS_REVISION_KEY, settings.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }
}
