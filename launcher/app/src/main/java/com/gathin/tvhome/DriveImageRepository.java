package com.gathin.tvhome;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Bounded HTTPS streaming with complete pagination and bounded retries. */
final class DriveImageRepository {
    static final class ApiException extends IOException {
        final int status;
        final boolean retryable;
        ApiException(int status, boolean retryable) {
            super("Drive HTTP " + status + (retryable ? " (temporary)" : " (permission/configuration)"));
            this.status = status;
            this.retryable = retryable;
        }
    }
    interface Check { void check() throws IOException; }
    interface TokenProvider { String token(boolean refresh) throws IOException; }
    private final TokenProvider auth;
    private final String base;
    private final Check check;
    DriveImageRepository(DriveAuthManager auth, Check check) {
        this(auth::token, check, "https://www.googleapis.com/drive/v3");
    }
    DriveImageRepository(TokenProvider auth, Check check, String base) {
        this.auth = auth; this.check = check; this.base = base;
    }

    private HttpURLConnection open(String url) throws IOException {
        boolean refreshed = false;
        for (int attempt = 0; ; ) {
            check.check();
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            try {
                connection.setRequestProperty("Authorization", "Bearer " + auth.token(false));
                int code = connection.getResponseCode();
                DriveLog.event("stage=http operation=" + (url.contains("alt=media") ? "download" : "metadata")
                        + " status=" + code + " attempt=" + (attempt + 1));
                if (code == 200) return connection;
                String body = "";
                InputStream error = connection.getErrorStream();
                if (error != null) try (InputStream input = error) { body = read(input, 65536); }
                if (code == 401 && !refreshed) {
                    connection.disconnect();
                    DriveLog.event("stage=http unauthorized; refreshing token once");
                    auth.token(true);
                    refreshed = true;
                    continue;
                }
                boolean temporary = code == 429 || code >= 500 && code <= 599
                        || code == 403 && (body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded"));
                if (!temporary || attempt >= 3) throw new ApiException(code, temporary);
                connection.disconnect();
                DriveLog.event("stage=http transient error; retry with backoff");
                try { Thread.sleep((1000L << attempt++) + ThreadLocalRandom.current().nextLong(500)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Cancelled"); }
            } catch (IOException | RuntimeException failure) {
                connection.disconnect();
                throw failure;
            }
        }
    }

    /** Catalog contains immediate child folders of the configured Drive theme library. */
    List<JSONObject> folders(String parent) throws Exception {
        if (!DriveConfig.validId(parent)) throw new IOException("Invalid Drive library");
        verifyFolder(parent);
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject item : listFolder(parent))
            if ("application/vnd.google-apps.folder".equals(item.optString("mimeType"))) result.add(item);
        result.sort((a, b) -> naturalCompare(a.optString("name"), b.optString("name")));
        return result;
    }
    /** Full recursive theme snapshot; reject ambiguous paths/shortcuts before touching published data. */
    List<JSONObject> list(DriveConfig config) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        walk(config.folderId, "", result, new HashSet<>(), new HashSet<>(), 0);
        boolean hasConfig = false;
        for (JSONObject item : result) if ("config.json".equals(item.optString("themePath")) && !item.optBoolean("directory")) hasConfig = true;
        if (!hasConfig) throw new IOException("Theme config unavailable");
        result.sort((a, b) -> naturalCompare(a.optString("themePath"), b.optString("themePath")));
        DriveLog.event("stage=list full theme complete assets=" + result.size());
        return result;
    }
    private void walk(String folder, String prefix, List<JSONObject> result, Set<String> paths, Set<String> folders, int depth) throws Exception {
        if (depth > 16 || !folders.add(folder)) throw new IOException("Invalid theme folder tree");
        verifyFolder(folder);
        for (JSONObject item : listFolder(folder)) {
            check.check();
            if (result.size() >= 10000) throw new IOException("Theme has too many files");
            String path = prefix + item.optString("name");
            if (!DriveImageCache.validThemePath(path) || !paths.add(path)) throw new IOException("Duplicate or invalid theme path");
            String mime = item.optString("mimeType");
            if ("application/vnd.google-apps.folder".equals(mime)) {
                result.add(new JSONObject(item.toString()).put("themePath", path).put("directory", true));
                walk(item.getString("id"), path + "/", result, paths, folders, depth + 1);
            } else {
                if (mime.startsWith("application/vnd.google-apps.")) throw new IOException("Unsupported Drive document or shortcut");
                if (result.size() >= 10000) throw new IOException("Theme has too many files");
                result.add(new JSONObject(item.toString()).put("themePath", path));
            }
        }
    }

    private void verifyFolder(String id) throws Exception {
        DriveLog.event("stage=list verifying folder");
        JSONObject folder = json(base + "/files/" + id + "?fields=id,mimeType,trashed");
        if (!"application/vnd.google-apps.folder".equals(folder.optString("mimeType")) || folder.optBoolean("trashed"))
            throw new IOException("Source is not an available folder");
    }

    private List<JSONObject> listFolder(String id) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        Set<String> pages = new HashSet<>(), ids = new HashSet<>();
        String page = "";
        int pageNumber = 0;
        do {
            check.check();
            Uri.Builder query = Uri.parse(base + "/files").buildUpon()
                    .appendQueryParameter("q", "'" + id + "' in parents and trashed = false")
                    .appendQueryParameter("fields", "nextPageToken,incompleteSearch,files(id,name,mimeType,size,modifiedTime,md5Checksum)")
                    .appendQueryParameter("pageSize", "100");
            if (!page.isEmpty()) query.appendQueryParameter("pageToken", page);
            JSONObject snapshot = json(query.build().toString());
            if (snapshot.optBoolean("incompleteSearch")) throw new IOException("Incomplete Drive listing");
            JSONArray files = snapshot.getJSONArray("files");
            DriveLog.event("stage=list page=" + (++pageNumber) + " entries=" + files.length());
            for (int i = 0; i < files.length(); i++) {
                JSONObject item = files.getJSONObject(i);
                String fileId = item.getString("id");
                if (!fileId.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid file ID");
                if (ids.add(fileId)) result.add(item);
            }
            page = snapshot.optString("nextPageToken");
            if (!page.isEmpty() && !pages.add(page)) throw new IOException("Repeated Drive page");
        } while (!page.isEmpty());
        return result;
    }

    JSONObject json(String url) throws Exception {
        HttpURLConnection connection = open(url);
        try (InputStream input = connection.getInputStream()) { return new JSONObject(read(input, 4 * 1024 * 1024)); }
        finally { connection.disconnect(); }
    }

    long download(JSONObject item, File target, DriveConfig config, long budget) throws Exception {
        DriveLog.event("stage=download kind=" + (DriveImageCache.caption(item) ? "captions" : DriveImageCache.themeConfig(item) ? "config" : DriveImageCache.image(item) ? "image" : "asset")
                + " start declaredBytes=" + item.optLong("size", -1));
        long limit = Math.min(DriveImageCache.maxBytes(item, config), budget);
        long declared = item.optLong("size", -1);
        if (declared > limit) throw new IOException("Image exceeds cache/file limit");
        HttpURLConnection connection = open(base + "/files/" + item.getString("id") + "?alt=media");
        try {
            String type = connection.getContentType();
            String actualType = type == null ? "" : type.split(";")[0].trim();
            if (DriveImageCache.textAsset(item) ? !DriveImageCache.textMime(actualType)
                    : !item.getString("mimeType").equals(actualType) && !"application/octet-stream".equals(actualType))
                throw new IOException("Unexpected asset Content-Type");
            if (connection.getContentLengthLong() > limit) throw new IOException("Image exceeds limit");
            long size = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[16384];
                int n;
                long checkedAt = 0;
                while ((n = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                    if (size - checkedAt >= 1048576L) { check.check(); checkedAt = size; }
                    size += n;
                    if (size > limit) throw new IOException("Image exceeds limit");
                    output.write(buffer, 0, n);
                }
                output.getFD().sync();
            }
            if (declared >= 0 && size != declared) throw new IOException("Incomplete image");
            DriveLog.event("stage=download complete bytes=" + size);
            return size;
        } finally { connection.disconnect(); }
    }

    private static String read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > limit) throw new IOException("Response too large");
            output.write(buffer, 0, n);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    static int naturalCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char x = Character.toLowerCase(a.charAt(i)), y = Character.toLowerCase(b.charAt(j));
            if (x >= '0' && x <= '9' && y >= '0' && y <= '9') {
                int ai = i, bj = j;
                while (i < a.length() && a.charAt(i) >= '0' && a.charAt(i) <= '9') i++;
                while (j < b.length() && b.charAt(j) >= '0' && b.charAt(j) <= '9') j++;
                String an = a.substring(ai, i).replaceFirst("^0+(?!$)", "");
                String bn = b.substring(bj, j).replaceFirst("^0+(?!$)", "");
                int compared = Integer.compare(an.length(), bn.length());
                if (compared == 0) compared = an.compareTo(bn);
                if (compared != 0) return compared;
            } else {
                if (x != y) return Character.compare(x, y);
                i++; j++;
            }
        }
        int compared = Integer.compare(a.length() - i, b.length() - j);
        return compared != 0 ? compared : a.compareTo(b);
    }
}
