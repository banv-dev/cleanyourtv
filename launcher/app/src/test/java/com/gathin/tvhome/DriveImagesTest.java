package com.gathin.tvhome;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DriveImagesTest {
    private Context context;
    private MockWebServer server;
    private DriveConfig config;
    private byte[] png, json;
    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        org.robolectric.shadows.ShadowEnvironment.setExternalStorageState(android.os.Environment.MEDIA_MOUNTED);
        server = new MockWebServer(); server.start();
        config = DriveConfig.forFolder(context, "theme-a");
        png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAEklEQVR4nGP4z8DwHx9mGBkKAMLXf4EvceABAAAAAElFTkSuQmCC");
        json = bytes("{\"columns\":4,\"wallpaper\":true,\"wallpaper_captions\":true,\"wallpaper_rotation_minutes\":1}");
        remove(new File(context.getFilesDir(), "drive-images"));
    }
    @After public void tearDown() throws Exception { server.shutdown(); }
    private void remove(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); file.delete(); }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private DriveImageRepository repo() { return new DriveImageRepository(refresh -> "test-token", () -> {}, server.url("/drive/v3").toString()); }
    private void payload(byte[] data, String mime) { server.enqueue(new MockResponse().setBody(new Buffer().write(data)).setHeader("Content-Type", mime)); }
    private void response(JSONObject value) { server.enqueue(new MockResponse().setBody(value.toString()).setHeader("Content-Type", "application/json")); }
    private void folder() throws Exception { response(new JSONObject().put("mimeType", "application/vnd.google-apps.folder")); }
    private JSONObject file(String id, String name, String mime, byte[] data, String revision) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("mimeType", mime).put("size", data.length).put("modifiedTime", revision);
    }
    private JSONObject image(String id, String name, String revision) throws Exception { return file(id, name, "image/png", png, revision); }
    private JSONObject child(String id, String name) throws Exception { return new JSONObject().put("id", id).put("name", name).put("mimeType", "application/vnd.google-apps.folder"); }
    private void listing(JSONObject... files) throws Exception { JSONArray values = new JSONArray(); for (JSONObject f : files) values.put(f); response(new JSONObject().put("files", values)); }
    private void root(JSONObject... files) throws Exception {
        folder(); JSONArray values = new JSONArray().put(file("cfg", "config.json", "application/json", json, "v1"));
        for (JSONObject f : files) values.put(f);
        response(new JSONObject().put("files", values));
    }
    private void initial() throws Exception {
        root(image("one", "wallpaper.jpg", "v1")); payload(json, "application/json"); payload(png, "image/png");
        assertTrue(DriveImageCache.sync(context, config, repo(), () -> {}));
    }
    private void activate(String id, String name) {
        DriveThemes.remember(context, id, name);
        context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putString("selected_theme", DriveThemes.selection(id)).apply();
    }
    @Test public void settingsComeFromXmlAndOldLocalJsonDoesNotEnableDrive() throws Exception {
        File local = context.getExternalFilesDir(null); assertNotNull(local); local.mkdirs();
        Files.write(new File(local, "config.json").toPath(), bytes("{\"columns\":4,\"googleDrive\":{\"folderId\":\"old-source\",\"syncInterval\":999}}"));
        assertFalse(DriveConfig.load(context).enabled);
        assertEquals(4, com.gathin.tvhome.Config.load(context).columns);
        activate("theme-a", "Nature");
        DriveConfig current = DriveConfig.load(context);
        assertTrue(current.enabled);
        assertEquals(context.getResources().getInteger(R.integer.drive_sync_interval), current.intervalMinutes);
        assertEquals(context.getResources().getInteger(R.integer.drive_cache_retention_days), current.cacheRetentionDays);
        assertEquals(context.getResources().getBoolean(R.bool.drive_sync_on_launch), current.syncOnLaunch);
        assertEquals("gDrive-Nature", DriveThemes.label(context, current.themeId));
        assertFalse(DriveConfig.create(context, "theme-a", -1, true, 5).enabled);
        assertFalse(DriveConfig.create(context, "theme-a", 5, true, 0).enabled);
    }
    @Test public void fullThemeAndNestedFilesArePublishedAndLoadedByNormalConfig() throws Exception {
        byte[] readme = bytes("theme documentation"), caption = bytes("photo.png\tMountain\n");
        root(file("readme", "README.md", "text/markdown", readme, "v1"), child("photos", "wallpapers"), child("assets", "assets"));
        // Recursion follows the root API order, independently of download sorting.
        folder(); listing(image("photo", "photo.png", "v1"), file("caption", "captions.txt", "text/plain", caption, "v1"));
        folder(); listing(child("empty", "empty"), file("extra", "other.bin", "application/octet-stream", readme, "v1"));
        folder(); listing();
        // Natural path order: assets/other.bin, config.json, README.md, wallpapers/captions.txt, photo.png.
        payload(readme, "application/octet-stream"); payload(json, "application/json"); payload(readme, "text/markdown"); payload(caption, "text/plain"); payload(png, "image/png");
        assertTrue(DriveImageCache.sync(context, config, repo(), () -> {}));
        activate("theme-a", "Thiên nhiên");
        File dir = com.gathin.tvhome.Config.dir(context);
        assertTrue(new File(dir, "assets/empty").isDirectory());
        assertArrayEquals(readme, Files.readAllBytes(new File(dir, "assets/other.bin").toPath()));
        assertEquals(4, com.gathin.tvhome.Config.load(context).columns);
        assertEquals(1, com.gathin.tvhome.Config.load(context).wallpapers.size());
        assertEquals("Mountain", DriveImageCache.captions(context, config).get("photo.png"));
        assertEquals(1, DriveProgress.state(config).total);
    }
    @Test public void cachedSwitchBackNeedsMetadataOnlyAndOfflineConfigWorks() throws Exception {
        initial(); DriveImageCache.markUsed(context, config);
        File before = DriveImageCache.publishedDirectory(context, config);
        DriveConfig b = DriveConfig.forFolder(context, "theme-b"); DriveImageCache.markUsed(context, b);
        root(image("b", "wallpaper.jpg", "v1")); payload(json, "application/json"); payload(png, "image/png");
        assertTrue(DriveImageCache.sync(context, b, repo(), () -> {}));
        assertTrue(before.isDirectory());
        activate("theme-a", "A");
        assertEquals(before, com.gathin.tvhome.Config.dir(context)); assertEquals(4, com.gathin.tvhome.Config.load(context).columns);
        root(image("one", "wallpaper.jpg", "v1")); int count = server.getRequestCount();
        assertTrue(DriveImageCache.sync(context, config, repo(), () -> {}));
        assertEquals(2, server.getRequestCount() - count); assertEquals(before, DriveImageCache.publishedDirectory(context, config));
    }
    @Test public void configChangesReloadSettingsAndMissingConfigCannotReplaceOldTheme() throws Exception {
        initial(); activate("theme-a", "A"); DriveThemes.published(context, config);
        long before = context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE).getLong(com.gathin.tvhome.Config.SETTINGS_REVISION_KEY, 0);
        byte[] changed = bytes("{\"columns\":7}");
        folder(); listing(file("cfg", "config.json", "application/json", changed, "v2")); payload(changed, "application/json");
        assertTrue(DriveImageCache.sync(context, config, repo(), () -> {}));
        assertEquals(7, com.gathin.tvhome.Config.load(context).columns);
        assertTrue(context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE).getLong(com.gathin.tvhome.Config.SETTINGS_REVISION_KEY, 0) > before);
        File active = DriveImageCache.publishedDirectory(context, config);
        folder(); listing(image("one", "wallpaper.jpg", "v1"));
        try { DriveImageCache.sync(context, config, repo(), () -> {}); fail(); } catch (java.io.IOException expected) { }
        assertEquals(active, DriveImageCache.publishedDirectory(context, config));
    }
    @Test public void malformedConfigAndFailedNestedListingKeepPreviousSnapshot() throws Exception {
        initial(); File before = DriveImageCache.publishedDirectory(context, config);
        byte[] bad = bytes("[1,2]");
        folder(); listing(file("cfg", "config.json", "application/json", bad, "v2")); payload(bad, "application/json");
        try { DriveImageCache.sync(context, config, repo(), () -> {}); fail(); } catch (Exception expected) { }
        assertEquals(before, DriveImageCache.publishedDirectory(context, config));
        root(child("child", "assets")); folder(); server.enqueue(new MockResponse().setResponseCode(403));
        try { DriveImageCache.sync(context, config, repo(), () -> {}); fail(); } catch (DriveImageRepository.ApiException expected) { }
        assertEquals(before, DriveImageCache.publishedDirectory(context, config));
    }
    @Test public void pendingDriveSelectionDoesNotUsePreviousLocalThemeOrOverlayCopy() throws Exception {
        File root = context.getExternalFilesDir(null); assertNotNull(root); root.mkdirs();
        Files.write(new File(root, "config.json").toPath(), bytes("{\"columns\":7}"));
        assertEquals(7, com.gathin.tvhome.Config.load(context).columns);
        activate("not-cached", "Pending");
        assertEquals(context.getResources().getInteger(R.integer.columns), com.gathin.tvhome.Config.load(context).columns);
        assertFalse(new File(com.gathin.tvhome.Config.dir(context), "config.json").exists());
    }
    @Test public void traversalDuplicatePathsAndShortcutsAreRejectedBeforeDownloads() throws Exception {
        for (JSONObject bad : new JSONObject[]{file("bad", "../escape", "text/plain", json, "v1"),
                file("cfg2", "config.json", "application/json", json, "v1"),
                file("shortcut", "linked", "application/vnd.google-apps.shortcut", json, "v1")}) {
            root(bad);
            try { repo().list(config); fail(); } catch (java.io.IOException expected) { }
        }
        assertFalse(DriveImageCache.validThemePath("/absolute")); assertFalse(DriveImageCache.validThemePath("x/../escape"));
        assertTrue(DriveImageCache.validThemePath("assets/nested/ảnh 1.png"));
    }
    @Test public void paginationAndCatalogPersistOfflineFolderNames() throws Exception {
        folder(); response(new JSONObject().put("files", new JSONArray().put(child("ten", "Theme 10"))).put("nextPageToken", "p2"));
        listing(child("two", "Theme 2"), file("not-folder", "readme.txt", "text/plain", json, "v1"));
        List<JSONObject> folders = repo().folders("library");
        assertEquals(2, folders.size()); assertEquals("two", folders.get(0).getString("id"));
        DriveThemes.saveCatalog(context, folders);
        assertEquals(2, DriveThemes.catalog(context).size());
        assertEquals("gDrive-Theme 2", DriveThemes.label(context, DriveThemes.selection("two")));
    }
    @Test public void imageChangeAndBadCaptionKeepPreviousValidBytes() throws Exception {
        byte[] cap = bytes("photo.png\tFirst\n");
        root(child("photos", "wallpapers")); folder(); listing(image("photo", "photo.png", "v1"), file("cap", "captions.txt", "text/plain", cap, "v1"));
        payload(json, "application/json"); payload(cap, "text/plain"); payload(png, "image/png");
        assertTrue(DriveImageCache.sync(context, config, repo(), () -> {}));
        byte[] invalid = new byte[]{(byte)0xc3, 0x28};
        root(child("photos", "wallpapers")); folder(); listing(image("photo", "photo.png", "v2"), file("cap", "captions.txt", "text/plain", invalid, "v2"));
        payload(invalid, "text/plain"); payload(png, "image/png");
        assertFalse(DriveImageCache.sync(context, config, repo(), () -> {}));
        assertEquals("First", DriveImageCache.captions(context, config).get("photo.png"));
        assertEquals(1, DriveProgress.state(config).done);
    }
    @Test public void expiryUsesGlobalXmlPolicyAndBackgroundSyncDoesNotRefreshLastUse() throws Exception {
        initial(); long now = System.currentTimeMillis(), day = 86400000L;
        DriveImageCache.markUsed(context, config, now - (config.cacheRetentionDays - 1L) * day);
        DriveConfig b = DriveConfig.forFolder(context, "b");
        DriveImageCache.evictExpired(context, b, () -> {}, now);
        assertNotNull(DriveImageCache.publishedDirectory(context, config));
        root(image("one", "wallpaper.jpg", "v1")); assertTrue(DriveImageCache.sync(context, config, repo(), () -> {}));
        DriveImageCache.evictExpired(context, b, () -> {}, now + day);
        assertFalse(DriveImageCache.directory(context, config).exists());
    }
    @Test public void cancellationDoesNotPublishHalfATheme() throws Exception {
        initial(); File before = DriveImageCache.publishedDirectory(context, config);
        root(image("one", "wallpaper.jpg", "v2")); payload(png, "image/png");
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        try { DriveImageCache.sync(context, config, repo(), () -> { if (checks.incrementAndGet() > 3) throw new java.io.IOException("Cancelled"); }); fail(); }
        catch (java.io.IOException expected) { }
        assertEquals(before, DriveImageCache.publishedDirectory(context, config));
    }
    @Test public void imageOnlyChangeKeepsStyleRevisionAndExpiredRedownloadAppliesAgain() throws Exception {
        initial(); activate("theme-a", "A");
        android.content.SharedPreferences prefs = context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        long before = prefs.getLong(com.gathin.tvhome.Config.SETTINGS_REVISION_KEY, 0);
        root(image("one", "wallpaper.jpg", "v2")); payload(png, "image/png");
        assertTrue(DriveImageCache.sync(context, config, repo(), () -> {}));
        assertEquals(before, prefs.getLong(com.gathin.tvhome.Config.SETTINGS_REVISION_KEY, 0));
        DriveImageCache.markUsed(context, config, System.currentTimeMillis() - (config.cacheRetentionDays + 1L) * 86400000L);
        activate("theme-b", "B"); DriveImageCache.evictExpired(context, DriveConfig.load(context), () -> {}, System.currentTimeMillis());
        activate("theme-a", "A"); assertNull(DriveImageCache.publishedDirectory(context, config));
        root(image("one", "wallpaper.jpg", "v2")); payload(json, "application/json"); payload(png, "image/png");
        assertTrue(DriveImageCache.sync(context, config, repo(), () -> {}));
        assertTrue(prefs.getLong(com.gathin.tvhome.Config.SETTINGS_REVISION_KEY, 0) > before);
        assertEquals(4, com.gathin.tvhome.Config.load(context).columns);
    }
    @Test public void fullThemeCaptionUpdateReachesRunningPlayerWithoutStyleReload() throws Exception {
        byte[] first = bytes("photo.png\tFirst\n"), second = bytes("photo.png\tSecond\n");
        root(child("photos", "wallpapers")); folder(); listing(image("photo", "photo.png", "v1"), file("cap", "captions.txt", "text/plain", first, "v1"));
        payload(json, "application/json"); payload(first, "text/plain"); payload(png, "image/png");
        assertTrue(DriveImageCache.sync(context, config, repo(), () -> {})); activate("theme-a", "A");
        android.widget.FrameLayout rootView = new android.widget.FrameLayout(context);
        WallpaperPlayer player = new WallpaperPlayer(context, rootView, com.gathin.tvhome.Config.load(context));
        try {
            player.start(); android.widget.TextView label = null;
            for (int i = 0; i < rootView.getChildCount(); i++) if (rootView.getChildAt(i) instanceof android.widget.TextView) label = (android.widget.TextView) rootView.getChildAt(i);
            assertNotNull(label); assertEquals("First", label.getText().toString());
            long revision = context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE).getLong(com.gathin.tvhome.Config.SETTINGS_REVISION_KEY, 0);
            root(child("photos", "wallpapers")); folder(); listing(image("photo", "photo.png", "v1"), file("cap", "captions.txt", "text/plain", second, "v2")); payload(second, "text/plain");
            assertTrue(DriveImageCache.sync(context, config, repo(), () -> {}));
            assertEquals(revision, context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE).getLong(com.gathin.tvhome.Config.SETTINGS_REVISION_KEY, 0));
            DriveSync.notifyImages(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("Second", label.getText().toString());
        } finally { player.close(); }
    }
    @Test public void collectionOnlyThemeWithZeroRotationShowsFirstImageStatically() throws Exception {
        json = bytes("{\"wallpaper\":true,\"wallpaper_rotation_minutes\":0}");
        root(child("photos", "wallpapers")); folder(); listing(image("ten", "photo-10.png", "v1"), image("two", "photo-2.png", "v1"));
        payload(json, "application/json"); payload(png, "image/png"); payload(png, "image/png");
        assertTrue(DriveImageCache.sync(context, config, repo(), () -> {})); activate("theme-a", "A");
        com.gathin.tvhome.Config loaded = com.gathin.tvhome.Config.load(context);
        assertTrue(loaded.wallpaperEnabled); assertEquals(0, loaded.wallpaperRotationMinutes);
        assertEquals(1, loaded.wallpapers.size()); assertEquals("photo-2.png", loaded.wallpapers.get(0).getName());
        android.widget.FrameLayout rootView = new android.widget.FrameLayout(context);
        WallpaperPlayer player = new WallpaperPlayer(context, rootView, loaded);
        try {
            boolean rendered = false;
            for (int i = 0; i < rootView.getChildCount(); i++) if (rootView.getChildAt(i) instanceof android.widget.ImageView)
                rendered |= ((android.widget.ImageView) rootView.getChildAt(i)).getDrawable() != null;
            assertTrue("Static collection fallback should decode and populate the wallpaper view", rendered);
        } finally { player.close(); }
        assertTrue(DriveImageCache.playable(context, config, 0, "none").isEmpty());
    }
    @Test public void staticFallbackStillPrefersSingleWallpaperAndRespectsWallpaperDisabled() throws Exception {
        json = bytes("{\"wallpaper\":false,\"wallpaper_rotation_minutes\":0}");
        root(image("single", "wallpaper.jpg", "v1"), child("photos", "wallpapers")); folder(); listing(image("photo", "photo.png", "v1"));
        payload(json, "application/json"); payload(png, "image/png"); payload(png, "image/png");
        assertTrue(DriveImageCache.sync(context, config, repo(), () -> {})); activate("theme-a", "A");
        assertEquals("wallpaper.jpg", DriveImageCache.playable(context, config, 0, null).get(0).getName());
        com.gathin.tvhome.Config loaded = com.gathin.tvhome.Config.load(context);
        assertFalse(loaded.wallpaperEnabled); assertTrue(loaded.wallpapers.isEmpty());
    }
    @Test public void noCacheProgressRequestIsNotArmedForACachedDriveTheme() throws Exception {
        initial(); activate("theme-a", "A"); DriveProgress.themeSelected(context, DriveConfig.load(context));
        org.robolectric.shadows.ShadowToast.reset(); DriveProgress progress = new DriveProgress(context, DriveConfig.load(context));
        try {
            progress.start(); DriveProgress.update(DriveConfig.load(context), DriveProgress.Phase.PREPARING, 0, -1);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(0, org.robolectric.shadows.ShadowToast.shownToastCount());
        } finally { progress.stop(); }
    }
}
