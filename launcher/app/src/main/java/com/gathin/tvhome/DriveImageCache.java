package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** An immutable local theme snapshot, published by one atomic manifest replacement. */
final class DriveImageCache {
    static final String CAPTIONS_PATH = "wallpapers/captions.txt";
    static final long MAX_CAPTION_BYTES = 256 * 1024L;
    static boolean caption(JSONObject item) { return CAPTIONS_PATH.equals(item.optString("themePath")); }
    static boolean themeConfig(JSONObject item) { return "config.json".equals(item.optString("themePath")); }
    static boolean textAsset(JSONObject item) { return caption(item) || themeConfig(item); }
    static boolean captionMime(String mime) { return textMime(mime); }
    static boolean textMime(String mime) { return "text/plain".equals(mime) || "application/json".equals(mime) || "application/octet-stream".equals(mime); }
    static boolean image(JSONObject item) {
        if (item.optBoolean("directory")) return false;
        String path = item.optString("themePath");
        return "wallpaper.jpg".equals(path) || path.startsWith("wallpapers/") && path.indexOf('/', 11) < 0 && imageName(path.substring(11));
    }
    private static boolean imageName(String name) { return name.toLowerCase(java.util.Locale.ROOT).matches(".+\\.(jpg|jpeg|png|webp)"); }
    static long maxBytes(JSONObject item, DriveConfig config) {
        return Math.min(config.maxFileBytes, caption(item) ? MAX_CAPTION_BYTES : themeConfig(item) ? 65536L : config.maxFileBytes);
    }
    private static boolean accepted(JSONObject item, DriveConfig config) {
        if (textAsset(item)) return textMime(item.optString("mimeType"));
        return !image(item) || config.mimeTypes.contains(item.optString("decodedMime", item.optString("mimeType")));
    }
    private static File root(Context context) { return new File(context.getFilesDir(), "drive-images"); }
    static File directory(Context context, DriveConfig config) { return new File(root(context), config.folderId); }

    private static final String USAGE_PREFS = "drive_cache_usage";
    private static final long DAY_MS = 86400000L;

    /** User-visible use only; background sync must never extend a theme's retention lease. */
    static void markUsed(Context context, DriveConfig config) { markUsed(context, config, System.currentTimeMillis()); }
    static synchronized void markUsed(Context context, DriveConfig config, long now) {
        if (!config.displaysCache() || config.folderId.isEmpty()) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences(USAGE_PREFS, Context.MODE_PRIVATE);
        String key = config.folderId + ":theme:" + config.themeId;
        JSONObject previous = usage(prefs.getAll().get(key));
        long lastUsed = previous == null ? now : Math.max(now, previous.optLong("lastUsedMs", now));
        try {
            JSONObject record = new JSONObject().put("folderId", config.folderId).put("lastUsedMs", lastUsed)
                    .put("retentionDays", config.cacheRetentionDays);
            prefs.edit().putString(key, record.toString()).remove(config.folderId + ":legacy").apply();
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JSONObject usage(Object value) {
        if (!(value instanceof String)) return null;
        try {
            JSONObject record = new JSONObject((String) value);
            return record.optLong("lastUsedMs", -1) >= 0 && record.optInt("retentionDays", 0) > 0 ? record : null;
        } catch (org.json.JSONException ignored) { return null; }
    }

    /** Keep shared source bytes while ANY theme has a live lease; never evict early to make space. */
    static synchronized void evictExpired(Context context, DriveConfig active, DriveImageRepository.Check check, long now) throws Exception {
        File[] partitions = root(context).listFiles();
        if (partitions == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences(USAGE_PREFS, Context.MODE_PRIVATE);
        Map<String, ?> records = prefs.getAll();
        for (File partition : partitions) {
            if (!partition.isDirectory() || !partition.getName().matches("[A-Za-z0-9_-]+")
                    || partition.equals(directory(context, active))) continue;
            check.check();
            boolean known = false, protectedByTheme = false;
            for (Map.Entry<String, ?> entry : records.entrySet()) {
                JSONObject record = usage(entry.getValue());
                if (record == null || !partition.getName().equals(record.optString("folderId"))) continue;
                known = true;
                long lastUsed = record.getLong("lastUsedMs");
                long retention = active.cacheRetentionDays * DAY_MS;
                if (now < lastUsed || now - lastUsed < retention) protectedByTheme = true;
            }
            if (!known) {
                // Upgrade: no reliable usage timestamp exists. Start a conservative 30-day grace period.
                JSONObject grace = new JSONObject().put("folderId", partition.getName()).put("lastUsedMs", now).put("retentionDays", active.cacheRetentionDays);
                prefs.edit().putString(partition.getName() + ":legacy", grace.toString()).apply();
                continue;
            }
            if (!protectedByTheme) {
                check.check();
                remove(partition);
                if (!partition.exists()) {
                    android.content.SharedPreferences.Editor edit = prefs.edit();
                    for (Map.Entry<String, ?> entry : records.entrySet()) {
                        JSONObject record = usage(entry.getValue());
                        if (record != null && partition.getName().equals(record.optString("folderId"))) edit.remove(entry.getKey());
                    }
                    edit.apply();
                    DriveLog.event("stage=cache expired inactive source removed; retention elapsed");
                }
            }
        }
    }

    static boolean validThemePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 1024 || path.indexOf('\\') >= 0) return false;
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
            for (int i = 0; i < part.length(); i++) if (Character.isISOControl(part.charAt(i))) return false;
        }
        return true;
    }
    private static boolean validSnapshot(String path) { return path.matches("snapshots/[a-f0-9-]{36}"); }
    private static synchronized JSONObject manifest(Context context, DriveConfig config) {
        if (config.folderId.isEmpty()) return new JSONObject();
        try {
            AtomicFile file = new AtomicFile(new File(directory(context, config), "manifest.json"));
            JSONObject data = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            if (data.optInt("schemaVersion") == 3 && config.folderId.equals(data.optString("folderId"))
                    && validSnapshot(data.optString("snapshot"))) return data;
        } catch (Exception ignored) { /* A missing/corrupt cache never blocks Home. */ }
        return new JSONObject();
    }
    static JSONObject readThemeConfig(File file) {
        try {
            if (!file.isFile() || file.length() > 65536L) return null;
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file.toPath()))).toString();
            return new JSONObject(text.startsWith("\uFEFF") ? text.substring(1) : text);
        } catch (Exception ignored) { return null; }
    }
    static String styleRevision(Context context, DriveConfig config) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            JSONArray items = manifest(context, config).getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (image(item) || caption(item) || item.optBoolean("directory")) continue;
                String content = item.optString("md5Checksum");
                if (content.isEmpty()) content = item.optString("modifiedTime") + ":" + item.optLong("size", -1);
                digest.update((item.getString("themePath") + "\0" + item.optString("mimeType") + "\0" + content + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return java.util.Base64.getEncoder().encodeToString(digest.digest());
        } catch (Exception ignored) { return ""; }
    }
    static File publishedDirectory(Context context, DriveConfig config) {
        JSONObject data = manifest(context, config);
        String snapshot = data.optString("snapshot");
        if (!validSnapshot(snapshot)) return null;
        File dir = new File(directory(context, config), snapshot);
        return readThemeConfig(new File(dir, "config.json")) != null ? dir : null;
    }
    private static File imageFile(File dir, JSONObject item) {
        String path = item.optString("cacheFile");
        String relative = item.optString("themePath");
        if (!validThemePath(relative) || !path.endsWith("/" + relative)) return null;
        String snapshot = path.substring(0, path.length() - relative.length() - 1);
        return validSnapshot(snapshot) ? new File(dir, path) : null;
    }
    private static List<File> files(Context context, DriveConfig config, boolean collection, boolean single) {
        List<File> result = new ArrayList<>();
        JSONArray items = manifest(context, config).optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !image(item) || !accepted(item, config)) continue;
            String path = item.optString("themePath");
            if (!(single && "wallpaper.jpg".equals(path) || collection && path.startsWith("wallpapers/"))) continue;
            File file = imageFile(directory(context, config), item);
            if (file != null && file.isFile() && file.length() > 0 && file.length() <= config.maxFileBytes) result.add(file);
        }
        return result;
    }
    static List<File> images(Context context, DriveConfig config) { return files(context, config, true, true); }
    static List<File> collection(Context context, DriveConfig config) { return files(context, config, true, false); }
    static File wallpaper(Context context, DriveConfig config) {
        List<File> single = files(context, config, false, true);
        return single.isEmpty() ? null : single.get(0);
    }
    static File captionsFile(Context context, DriveConfig config) {
        JSONArray items = manifest(context, config).optJSONArray("items");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !caption(item) || !accepted(item, config)) continue;
            File file = imageFile(directory(context, config), item);
            if (file != null && file.isFile() && file.length() <= maxBytes(item, config)) return file;
        }
        return null;
    }
    static Map<String, String> captions(Context context, DriveConfig config) {
        File file = captionsFile(context, config);
        return file == null ? Collections.emptyMap() : WallpaperCaptions.read(file.getParentFile());
    }
    static List<File> playable(Context context, DriveConfig config, int rotationMinutes, String choice) {
        List<File> collection = collection(context, config);
        File single = wallpaper(context, config);
        List<File> chosen = WallpaperRotation.chosen(collection, single, choice);
        if (chosen != null) return chosen;
        if (rotationMinutes > 0 && !collection.isEmpty()) return collection;
        if (single != null) return Collections.singletonList(single);
        // Rotation 0 disables changing pictures, not the wallpaper itself. A collection-only Drive
        // theme can still display its first image when it has no separate wallpaper.jpg.
        return collection.isEmpty() ? Collections.emptyList() : Collections.singletonList(collection.get(0));
    }
    private static long size(File file) {
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += size(child);
        return total;
    }
    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) remove(child);
        file.delete();
    }
    private static void prune(File dir, String snapshot) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.getName().startsWith("manifest.json")) continue;
            if ("snapshots".equals(child.getName())) {
                File[] revisions = child.listFiles();
                if (revisions != null) for (File revision : revisions)
                    if (!("snapshots/" + revision.getName()).equals(snapshot)) remove(revision);
            } else remove(child); // obsolete flat-cache format and abandoned files
        }
    }
    private static boolean same(JSONObject remote, JSONObject local) {
        if (remote.optBoolean("directory") || local.optBoolean("directory")) return remote.optBoolean("directory") && local.optBoolean("directory");
        if (!remote.optString("mimeType").equals(local.optString("mimeType"))) return false;
        String checksum = remote.optString("md5Checksum");
        if (!checksum.isEmpty()) return checksum.equals(local.optString("md5Checksum"));
        return !remote.optString("modifiedTime").isEmpty()
                && remote.optString("modifiedTime").equals(local.optString("modifiedTime"))
                && remote.optLong("size", -1) == local.optLong("size", -2);
    }
    static void validate(File file, JSONObject item) throws Exception {
        String checksum = item.optString("md5Checksum");
        if (!checksum.isEmpty()) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (java.io.InputStream input = Files.newInputStream(file.toPath())) {
                byte[] buffer = new byte[16384]; int n;
                while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (!checksum.equalsIgnoreCase(hex.toString())) throw new java.io.IOException("Checksum mismatch");
        }
        if (textAsset(item)) {
            if (file.length() > (caption(item) ? MAX_CAPTION_BYTES : 65536L)) throw new IOException("Theme text exceeds limit");
            String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file.toPath()))).toString();
            if (text.indexOf('\0') >= 0) throw new IOException("Invalid theme text");
            if (themeConfig(item)) {
                if (text.startsWith("\uFEFF")) text = text.substring(1);
                new JSONObject(text); // Require an object, never accept a scalar/array as a theme.
            }
            return;
        }
        String mime = item.optString("mimeType");
        if (!(mime.equals("image/jpeg") || mime.equals("image/png") || mime.equals("image/webp") || image(item))) return;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || !("image/jpeg".equals(options.outMimeType)
                || "image/png".equals(options.outMimeType) || "image/webp".equals(options.outMimeType))
                || !(mime.equals(options.outMimeType) || "application/octet-stream".equals(mime)))
            throw new java.io.IOException("Invalid image");
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
        if (bitmap == null) throw new java.io.IOException("Image cannot decode");
        bitmap.recycle();
        item.put("decodedMime", options.outMimeType);
    }

    /** Returns false for a partial snapshot. Failure/cancellation before publish retains the previous one. */
    static boolean sync(Context context, DriveConfig config, DriveImageRepository repository,
                        DriveImageRepository.Check check) throws Exception {
        List<JSONObject> remote = repository.list(config); // Complete BOTH root and collection pagination first.
        check.check();
        int totalImages = 0;
        for (JSONObject item : remote) if (image(item)) totalImages++;
        int readyImages = 0;
        DriveProgress.update(config, DriveProgress.Phase.IMAGES, 0, totalImages);
        File dir = directory(context, config);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cache unavailable");
        boolean hadPublishedTheme = publishedDirectory(context, config) != null;
        JSONObject previousManifest = manifest(context, config);
        JSONArray before = previousManifest.optJSONArray("items");
        Map<String, JSONObject> old = new HashMap<>();
        if (before != null) for (int i = 0; i < before.length(); i++) {
            JSONObject item = before.getJSONObject(i);
            File file = imageFile(dir, item);
            if (file != null && (item.optBoolean("directory") ? file.isDirectory() : file.isFile())) old.put(item.getString("id"), item);
        }
        prune(dir, previousManifest.optString("snapshot"));
        evictExpired(context, config, check, System.currentTimeMillis());
        boolean unchanged = before != null && old.size() == remote.size() && before.length() == remote.size();
        for (JSONObject item : remote) {
            JSONObject previous = old.get(item.getString("id"));
            if (previous == null || !same(item, previous)
                    || !item.getString("themePath").equals(previous.optString("themePath"))
                    || imageFile(dir, previous).length() > maxBytes(item, config)) unchanged = false;
        }
        if (unchanged) {
            check.check();
            previousManifest.put("partial", false).put("lastSuccessfulSyncUtc", Instant.now().toString());
            publish(dir, previousManifest, check);
            DriveThemes.published(context, config);
            DriveProgress.update(config, DriveProgress.Phase.IMAGES, totalImages, totalImages);
            DriveLog.event("stage=cache unchanged theme snapshot; reused=" + remote.size());
            return true;
        }
        String snapshot = "snapshots/" + UUID.randomUUID();
        File stage = new File(dir, snapshot);
        if (!new File(stage, "wallpapers").mkdirs()) throw new IOException("Cache unavailable");
        long used = size(root(context));
        DriveLog.event("stage=cache preparing theme snapshot previousImages=" + old.size()
                + " usedBytes=" + used + " limitBytes=" + config.cacheBytes + " usableDiskBytes=" + dir.getUsableSpace());
        int reused = 0, downloaded = 0, failed = 0;
        JSONArray next = new JSONArray();
        boolean committed = false;
        try {
            for (JSONObject item : remote) {
                check.check();
                String relative = item.getString("themePath");
                if (!validThemePath(relative)) throw new IOException("Invalid theme image path");
                File target = new File(stage, relative);
                if (item.optBoolean("directory")) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cache unavailable");
                    next.put(new JSONObject(item.toString()).put("cacheFile", snapshot + "/" + relative).put("size", 0));
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cache unavailable");
                File temporary = new File(stage, UUID.randomUUID() + ".tmp");
                JSONObject previous = old.get(item.getString("id"));
                JSONObject saved;
                boolean synced = true;
                boolean reuse = previous != null && caption(item) == caption(previous) && same(item, previous)
                        && imageFile(dir, previous).length() <= maxBytes(item, config);
                try {
                    if (reuse) {
                        used += linkOrCopy(imageFile(dir, previous), target, config.cacheBytes - used);
                        saved = new JSONObject(item.toString());
                        if (previous.has("decodedMime")) saved.put("decodedMime", previous.getString("decodedMime"));
                        reused++;
                    } else {
                        try {
                            if (used >= config.cacheBytes) throw new IOException("Cache full");
                            long bytes = repository.download(item, temporary, config, config.cacheBytes - used);
                            validate(temporary, item);
                            check.check();
                            Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                            used += bytes;
                            downloaded++;
                            saved = new JSONObject(item.toString());
                        } catch (DriveImageRepository.ApiException failure) { throw failure; }
                        catch (IOException failure) {
                            check.check();
                            temporary.delete();
                            DriveLog.failure(caption(item) ? "cache.captions" : "cache.image", failure);
                            failed++;
                            synced = false;
                            if (previous == null || caption(item) != caption(previous) || imageFile(dir, previous).length() > maxBytes(item, config)
                                    || !accepted(previous, config)) continue;
                            used += linkOrCopy(imageFile(dir, previous), target, config.cacheBytes - used);
                            saved = new JSONObject(previous.toString()).put("name", item.optString("name"));
                        }
                    }
                    saved.put("themePath", relative).put("cacheFile", snapshot + "/" + relative).put("size", target.length());
                    next.put(saved);
                    if (image(item) && synced) {
                        readyImages++;
                        DriveProgress.update(config, DriveProgress.Phase.IMAGES, readyImages, totalImages);
                    }
                } finally { temporary.delete(); }
            }
            check.check();
            if (readThemeConfig(new File(stage, "config.json")) == null) throw new IOException("Theme config unavailable");
            JSONObject data = new JSONObject().put("schemaVersion", 3).put("folderId", config.folderId)
                    .put("themeId", config.themeId).put("snapshot", snapshot).put("items", next)
                    .put("partial", failed > 0).put("lastAttemptUtc", Instant.now().toString())
                    .put("lastSuccessfulSyncUtc", failed == 0 ? Instant.now().toString() : previousManifest.optString("lastSuccessfulSyncUtc"));
            publish(dir, data, check);
            committed = true;
            DriveThemes.published(context, config, !hadPublishedTheme);
            prune(dir, snapshot);
            DriveLog.event("stage=cache theme snapshot committed assets=" + next.length() + " reused=" + reused
                    + " downloaded=" + downloaded + " failed=" + failed);
            return failed == 0;
        } finally { if (!committed) remove(stage); }
    }

    /** Hard links reuse immutable bytes without doubling disk use; copies are bounded if unsupported. */
    private static long linkOrCopy(File from, File to, long budget) throws IOException {
        try { Files.createLink(to.toPath(), from.toPath()); return 0; }
        catch (IOException | UnsupportedOperationException unsupported) {
            if (from.length() > budget) throw new IOException("Cache full");
            Files.copy(from.toPath(), to.toPath());
            return from.length();
        }
    }
    private static synchronized void publish(File dir, JSONObject data, DriveImageRepository.Check check) throws Exception {
        AtomicFile file = new AtomicFile(new File(dir, "manifest.json"));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(data.toString().getBytes(StandardCharsets.UTF_8));
            check.check();
            file.finishWrite(output);
        } catch (Exception failure) { if (output != null) file.failWrite(output); throw failure; }
    }
}
