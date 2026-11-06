package com.gathin.tvhome;

import android.content.Context;
import android.app.job.JobScheduler;
import android.app.job.JobInfo;
import org.robolectric.RuntimeEnvironment;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class DriveImagesTest {
    private Context context;
    private MockWebServer server;
    private DriveConfig config;
    private byte[] png;
    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        org.robolectric.shadows.ShadowEnvironment.setExternalStorageState(android.os.Environment.MEDIA_MOUNTED);
        server = new MockWebServer(); server.start();
        config = parse("{\"folderId\":\"test-folder\"}");
        png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAEklEQVR4nGP4z8DwHx9mGBkKAMLXf4EvceABAAAAAElFTkSuQmCC");
        delete(new File(context.getFilesDir(), "drive-images"));
    }
    @After public void tearDown() throws Exception { server.shutdown(); }
    private void delete(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); file.delete(); }
    private DriveConfig parse(String drive) throws Exception { return DriveConfig.parse(context, new JSONObject("{\"googleDrive\":" + drive + "}")); }
    private DriveImageRepository repository() { return new DriveImageRepository(refresh -> "test-token", () -> {}, server.url("/drive/v3").toString()); }
    private void response(String body) { server.enqueue(new MockResponse().setBody(body).setHeader("Content-Type", "application/json")); }
    private void folder() { response("{\"mimeType\":\"application/vnd.google-apps.folder\"}"); }
    private JSONObject item(String id, String name, String revision) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("mimeType", "image/png").put("size", png.length).put("modifiedTime", revision);
    }
    private void listing(JSONObject... items) throws Exception {
        JSONArray array = new JSONArray(); for (JSONObject item : items) array.put(item);
        response(new JSONObject().put("files", array).toString());
    }
    private JSONObject collectionFolder() throws Exception {
        return new JSONObject().put("id", "collection").put("name", "wallpapers").put("mimeType", "application/vnd.google-apps.folder");
    }
    private void image() { server.enqueue(new MockResponse().setBody(new Buffer().write(png)).setHeader("Content-Type", "image/png")); }
    private void initialSync() throws Exception {
        folder(); listing(item("one", "wallpaper.jpg", "v1")); image();
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
    }
    @Test public void strictConfigAndDefaults() throws Exception {
        assertFalse(DriveConfig.parse(context, new JSONObject()).enabled);
        assertTrue(config.enabled); assertTrue(config.syncOnLaunch); assertEquals(360, config.intervalMinutes); assertEquals(30, config.cacheRetentionDays);
        assertFalse(parse("{\"enabled\":false}").enabled);
        for (String invalid : new String[]{"null", "[]", "{\"folderId\":\"\"}", "{\"folderId\":\"https://drive.google.com/x\"}",
                "{\"folderId\":\"a\",\"syncInterval\":-1}", "{\"folderId\":\"a\",\"syncInterval\":1.5}",
                "{\"folderId\":\"a\",\"syncOnLaunch\":\"true\"}"}) {
            DriveConfig bad = parse(invalid); assertFalse(invalid, bad.enabled); assertFalse(bad.error.isEmpty());
        }
        DriveConfig zero = parse("{\"folderId\":\"a\",\"syncInterval\":0,\"syncOnLaunch\":false,\"cacheMaxSizeMb\":1}");
        assertTrue(zero.enabled); assertEquals(0, zero.intervalMinutes); assertFalse(zero.syncOnLaunch); assertEquals(300 * 1048576L, zero.cacheBytes);
    }
    @Test public void completePaginationAndNaturalOrder() throws Exception {
        folder(); listing(collectionFolder()); folder();
        response(new JSONObject().put("files", new JSONArray().put(item("ten", "photo-10.png", "v1"))).put("nextPageToken", "page2").toString());
        listing(item("two", "photo-2.png", "v1"));
        List<JSONObject> result = repository().list(config);
        assertEquals(2, result.size()); assertEquals("two", result.get(0).getString("id"));
        for (int i = 0; i < 4; i++) server.takeRequest();
        assertTrue(server.takeRequest().getPath().contains("pageToken=page2"));
    }
    @Test public void unauthorizedRefreshesOnlyOnce() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401)); server.enqueue(new MockResponse().setResponseCode(401));
        AtomicInteger refreshed = new AtomicInteger();
        DriveImageRepository repo = new DriveImageRepository(refresh -> { if (refresh) refreshed.incrementAndGet(); return "test"; }, () -> {}, server.url("/drive/v3").toString());
        try { repo.list(config); fail(); } catch (DriveImageRepository.ApiException expected) { assertEquals(401, expected.status); }
        assertEquals(1, refreshed.get()); assertEquals(2, server.getRequestCount());
    }
    @Test public void cacheSurvivesFailedPageAndRevokedFolder() throws Exception {
        initialSync(); List<File> previous = DriveImageCache.images(context, config);
        folder(); response("{\"files\":[],\"nextPageToken\":\"p2\"}"); server.enqueue(new MockResponse().setResponseCode(403));
        try { DriveImageCache.sync(context, config, repository(), () -> {}); fail(); } catch (DriveImageRepository.ApiException expected) { }
        assertEquals(previous, DriveImageCache.images(context, config)); assertTrue(previous.get(0).isFile());
        server.enqueue(new MockResponse().setResponseCode(404));
        try { DriveImageCache.sync(context, config, repository(), () -> {}); fail(); } catch (DriveImageRepository.ApiException expected) { }
        assertEquals(previous, DriveImageCache.images(context, config));
    }
    @Test public void incrementalReplacementAndVerifiedEmptyFolder() throws Exception {
        initialSync(); File before = DriveImageCache.images(context, config).get(0);
        folder(); listing(item("one", "wallpaper.jpg", "v1"));
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertEquals(before, DriveImageCache.images(context, config).get(0));
        folder(); listing(item("one", "wallpaper.jpg", "v2")); image();
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertNotEquals(before, DriveImageCache.images(context, config).get(0)); assertFalse(before.exists());
        assertTrue(DriveImageCache.images(context, parse("{\"folderId\":\"other\"}")).isEmpty());
        folder(); listing(); assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertTrue(DriveImageCache.images(context, config).isEmpty());
    }
    @Test public void badImagePreservesOldVersionAndOtherDownloads() throws Exception {
        initialSync(); File before = DriveImageCache.images(context, config).get(0);
        folder(); listing(item("one", "wallpaper.jpg", "v2"), collectionFolder());
        folder(); listing(item("two", "photo-2.png", "v1"));
        server.enqueue(new MockResponse().setBody("bad image").setHeader("Content-Type", "image/png")); image();
        assertFalse(DriveImageCache.sync(context, config, repository(), () -> {}));
        List<File> images = DriveImageCache.images(context, config); assertEquals(2, images.size()); assertEquals("wallpaper.jpg", images.get(0).getName());
        assertArrayEquals(png, Files.readAllBytes(images.get(0).toPath()));
        assertEquals(2, DriveProgress.state(config).total);
        assertEquals(1, DriveProgress.state(config).done); // failed update retains old bytes but is not synced

    }
    @Test public void cancelledSyncNeverPublishes() throws Exception {
        initialSync(); List<File> previous = DriveImageCache.images(context, config);
        folder(); listing(item("one", "wallpaper.jpg", "v2")); image();
        AtomicInteger checks = new AtomicInteger();
        try { DriveImageCache.sync(context, config, repository(), () -> { if (checks.incrementAndGet() > 3) throw new java.io.IOException("changed theme"); }); fail(); }
        catch (java.io.IOException expected) { }
        assertEquals(previous, DriveImageCache.images(context, config)); assertTrue(previous.get(0).isFile());
    }
    @Test public void nonImagesAreFilteredAndIncompleteSearchIsRejected() throws Exception {
        folder();
        response("{\"files\":[{\"id\":\"pdf\",\"mimeType\":\"application/pdf\"},{\"id\":\"child\",\"mimeType\":\"application/vnd.google-apps.folder\"}]}");
        assertTrue(repository().list(config).isEmpty());
        folder(); response("{\"files\":[],\"incompleteSearch\":true}");
        try { repository().list(config); fail(); } catch (java.io.IOException expected) { }
    }
    @Test public void oversizedMetadataDoesNotDownloadOrDiscardOldImage() throws Exception {
        initialSync(); List<File> previous = DriveImageCache.images(context, config);
        folder(); listing(item("one", "wallpaper.jpg", "v2").put("size", config.maxFileBytes + 1));
        int requestsBefore = server.getRequestCount();
        assertFalse(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertEquals(2, server.getRequestCount() - requestsBefore);
        assertEquals(previous.get(0).getName(), DriveImageCache.images(context, config).get(0).getName());
        assertArrayEquals(png, Files.readAllBytes(DriveImageCache.images(context, config).get(0).toPath()));
    }
    @Test public void wrongContentTypeKeepsPublishedVersion() throws Exception {
        initialSync(); List<File> previous = DriveImageCache.images(context, config);
        folder(); listing(item("one", "wallpaper.jpg", "v2"));
        server.enqueue(new MockResponse().setBody("not an image").setHeader("Content-Type", "text/html"));
        assertFalse(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertEquals(previous.get(0).getName(), DriveImageCache.images(context, config).get(0).getName());
        assertArrayEquals(png, Files.readAllBytes(DriveImageCache.images(context, config).get(0).toPath()));
    }
    @Test public void themeStructureAndWallpaperSelectionMatchLocalThemes() throws Exception {
        folder(); listing(item("single", "wallpaper.jpg", "v1"), collectionFolder(), item("ignored", "other.png", "v1"));
        folder(); listing(item("ten", "photo-10.png", "v1"), item("two", "photo-2.png", "v1"));
        image(); image(); image();
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        File single = DriveImageCache.wallpaper(context, config);
        assertNotNull(single); assertEquals("wallpaper.jpg", single.getName());
        List<File> collection = DriveImageCache.collection(context, config);
        assertEquals(2, collection.size()); assertEquals("photo-2.png", collection.get(0).getName());
        assertEquals(new File(single.getParentFile(), "wallpapers"), collection.get(0).getParentFile());
        assertEquals(java.util.Collections.singletonList(single), DriveImageCache.playable(context, config, 0, null));
        assertEquals(collection, DriveImageCache.playable(context, config, 1, null));
        assertEquals(java.util.Collections.singletonList(collection.get(0)), DriveImageCache.playable(context, config, 1, "photo-2.png"));
        assertTrue(DriveImageCache.playable(context, config, 1, "none").isEmpty());
    }
    @Test public void collectionOnlyAndFailedChildListingPreserveSnapshot() throws Exception {
        folder(); listing(collectionFolder()); folder(); listing(item("two", "photo-2.png", "v1")); image();
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        List<File> previous = DriveImageCache.images(context, config);
        assertNull(DriveImageCache.wallpaper(context, config));
        assertEquals(previous, DriveImageCache.playable(context, config, 1, null));
        assertTrue(DriveImageCache.playable(context, config, 0, null).isEmpty());
        folder(); listing(collectionFolder()); folder();
        server.enqueue(new MockResponse().setResponseCode(403));
        try { DriveImageCache.sync(context, config, repository(), () -> {}); fail(); }
        catch (DriveImageRepository.ApiException expected) { }
        assertEquals(previous, DriveImageCache.images(context, config)); assertTrue(previous.get(0).isFile());
    }
    @Test public void unsafeNamesAndNestedFoldersAreNotDownloaded() throws Exception {
        folder(); listing(collectionFolder()); folder();
        listing(item("escape", "../escape.png", "v1"), item("hidden", ".hidden.png", "v1"),
                item("two", "photo-2.png", "v1"), new JSONObject().put("id", "nested").put("name", "nested").put("mimeType", "application/vnd.google-apps.folder"));
        List<JSONObject> files = repository().list(config);
        assertEquals(1, files.size()); assertEquals("wallpapers/photo-2.png", files.get(0).getString("themePath"));
        assertFalse(DriveImageCache.validThemePath("wallpapers/../../escape.png"));
        assertFalse(DriveImageCache.validThemePath("wallpapers/nested/photo.png"));
    }
    @Test public void duplicateFoldersAndImageNamesAreRejectedBeforePublish() throws Exception {
        initialSync(); List<File> previous = DriveImageCache.images(context, config);
        folder(); listing(collectionFolder(), new JSONObject(collectionFolder().toString()).put("id", "duplicate"));
        try { DriveImageCache.sync(context, config, repository(), () -> {}); fail(); }
        catch (java.io.IOException expected) { }
        assertEquals(previous, DriveImageCache.images(context, config));
        folder(); listing(collectionFolder()); folder(); listing(item("a", "same.png", "v1"), item("b", "same.png", "v1"));
        try { DriveImageCache.sync(context, config, repository(), () -> {}); fail(); }
        catch (java.io.IOException expected) { }
        assertEquals(previous, DriveImageCache.images(context, config));
    }
    @Test public void renameReusesBytesAndChildFailureDoesNotPublishRootDeletion() throws Exception {
        folder(); listing(collectionFolder()); folder(); listing(item("one", "before.png", "v1")); image();
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        folder(); listing(collectionFolder()); folder(); listing(item("one", "after.png", "v1"));
        int requests = server.getRequestCount();
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertEquals(4, server.getRequestCount() - requests);
        assertEquals("after.png", DriveImageCache.collection(context, config).get(0).getName());
    }
    private JSONObject captionItem(String revision, byte[] bytes) throws Exception {
        return new JSONObject().put("id", "captions").put("name", "captions.txt").put("mimeType", "text/plain")
                .put("size", bytes.length).put("modifiedTime", revision);
    }
    private void text(byte[] bytes) {
        server.enqueue(new MockResponse().setBody(new Buffer().write(bytes)).setHeader("Content-Type", "text/plain; charset=UTF-8"));
    }
    @Test public void captionsAreAddedUpdatedAndDeletedWithTheSnapshot() throws Exception {
        byte[] v1 = "photo-2.png\tFirst caption\n".getBytes(StandardCharsets.UTF_8);
        folder(); listing(collectionFolder()); folder(); listing(captionItem("v1", v1), item("two", "photo-2.png", "v1"));
        text(v1); image();
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertEquals(1, DriveImageCache.images(context, config).size());
        assertEquals("First caption", DriveImageCache.captions(context, config).get("photo-2.png"));
        assertEquals(1, DriveProgress.state(config).total); // captions are not images
        assertEquals(1, DriveProgress.state(config).done);
        assertEquals(DriveImageCache.collection(context, config).get(0).getParentFile(), DriveImageCache.captionsFile(context, config).getParentFile());
        byte[] v2 = "photo-2.png\tUpdated caption\n".getBytes(StandardCharsets.UTF_8);
        folder(); listing(collectionFolder()); folder(); listing(captionItem("v2", v2), item("two", "photo-2.png", "v1")); text(v2);
        int requests = server.getRequestCount();
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertEquals(5, server.getRequestCount() - requests); // metadata + captions only; image bytes reused
        assertEquals("Updated caption", DriveImageCache.captions(context, config).get("photo-2.png"));
        folder(); listing(collectionFolder()); folder(); listing(item("two", "photo-2.png", "v1"));
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertNull(DriveImageCache.captionsFile(context, config));
        assertTrue(DriveImageCache.captions(context, config).isEmpty());
        assertEquals(1, DriveImageCache.images(context, config).size());
    }
    @Test public void malformedCaptionsKeepPreviousTextAndImage() throws Exception {
        byte[] good = "photo-2.png\tKeep this caption\n".getBytes(StandardCharsets.UTF_8);
        folder(); listing(collectionFolder()); folder(); listing(captionItem("v1", good), item("two", "photo-2.png", "v1")); text(good); image();
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        byte[] bad = new byte[]{(byte) 0xc3, 0x28};
        folder(); listing(collectionFolder()); folder(); listing(captionItem("v2", bad), item("two", "photo-2.png", "v1")); text(bad);
        assertFalse(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertEquals("Keep this caption", DriveImageCache.captions(context, config).get("photo-2.png"));
        assertEquals(1, DriveImageCache.images(context, config).size());
    }
    @Test public void oversizedCaptionsAreNotDownloadedAndDuplicateCaptionPathsAreRejected() throws Exception {
        folder(); listing(collectionFolder()); folder(); listing(captionItem("v1", new byte[0]).put("size", DriveImageCache.MAX_CAPTION_BYTES + 1));
        assertFalse(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertEquals(4, server.getRequestCount());
        assertNull(DriveImageCache.captionsFile(context, config));
        folder(); listing(collectionFolder()); folder(); listing(captionItem("v1", new byte[0]), captionItem("v2", new byte[0]).put("id", "duplicate"));
        try { DriveImageCache.sync(context, config, repository(), () -> {}); fail(); }
        catch (java.io.IOException expected) { }
    }
    @Test public void runningWallpaperPlayerLoadsNewCaptionsWithoutRecreation() throws Exception {
        folder(); listing(collectionFolder()); folder(); listing(item("two", "photo-2.png", "v1")); image();
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        File dir = com.gathin.tvhome.Config.dir(context); assertNotNull(dir); dir.mkdirs();
        Files.write(new File(dir, com.gathin.tvhome.Config.FILE_NAME).toPath(),
                "{\"googleDrive\":{\"folderId\":\"test-folder\"},\"wallpaper\":true,\"wallpaper_captions\":true,\"wallpaper_rotation_minutes\":1}".getBytes(StandardCharsets.UTF_8));
        android.widget.FrameLayout root = new android.widget.FrameLayout(context);
        WallpaperPlayer player = new WallpaperPlayer(context, root, com.gathin.tvhome.Config.load(context));
        try {
            player.start();
            android.widget.TextView label = null;
            for (int i = 0; i < root.getChildCount(); i++) if (root.getChildAt(i) instanceof android.widget.TextView)
                label = (android.widget.TextView) root.getChildAt(i);
            assertNotNull("Drive caption view must exist before the first captions file arrives", label);
            byte[] bytes = "photo-2.png\tCaption added during sync\n".getBytes(StandardCharsets.UTF_8);
            folder(); listing(collectionFolder()); folder(); listing(captionItem("v1", bytes), item("two", "photo-2.png", "v1")); text(bytes);
            assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
            DriveSync.notifyImages();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("Caption added during sync", label.getText().toString());
            assertEquals(1f, label.getAlpha(), 0.001f);
            folder(); listing(collectionFolder()); folder(); listing(item("two", "photo-2.png", "v1"));
            assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
            DriveSync.notifyImages();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("", label.getText().toString());
            assertEquals(0f, label.getAlpha(), 0.001f);
        } finally { player.close(); }
    }
    @Test public void themeSwitchRetainsCacheAndReturningUnchangedSourceDoesNotDownload() throws Exception {
        initialSync();
        DriveImageCache.markUsed(context, config);
        File a = DriveImageCache.images(context, config).get(0);
        DriveConfig b = parse("{\"folderId\":\"folder-b\",\"cacheRetentionDays\":30}");
        DriveImageCache.markUsed(context, b);
        folder(); listing(item("b", "wallpaper.jpg", "v1")); image();
        assertTrue(DriveImageCache.sync(context, b, repository(), () -> {}));
        assertTrue(a.isFile());
        assertEquals(a, DriveImageCache.images(context, config).get(0)); // available immediately, without network
        folder(); listing(item("one", "wallpaper.jpg", "v1"));
        int requests = server.getRequestCount();
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        assertEquals(2, server.getRequestCount() - requests); // only metadata
        assertEquals(a, DriveImageCache.images(context, config).get(0));
        assertEquals(1, DriveImageCache.images(context, b).size());
    }
    @Test public void retentionExpiresAfterConfiguredDaysAndUseResetsTheTimer() throws Exception {
        initialSync();
        long now = System.currentTimeMillis(), day = 86400000L;
        DriveConfig a = parse("{\"folderId\":\"test-folder\",\"cacheRetentionDays\":7}");
        DriveConfig b = parse("{\"folderId\":\"folder-b\"}");
        DriveImageCache.markUsed(context, a, now);
        File file = DriveImageCache.images(context, a).get(0);
        DriveImageCache.evictExpired(context, b, () -> {}, now + 6 * day);
        assertTrue(file.isFile());
        DriveImageCache.markUsed(context, a, now + 6 * day);
        DriveImageCache.evictExpired(context, b, () -> {}, now + 7 * day);
        assertTrue(file.isFile());
        DriveImageCache.evictExpired(context, b, () -> {}, now + 13 * day);
        assertFalse(DriveImageCache.directory(context, a).exists());
    }
    @Test public void sharedFolderRemainsUntilEveryThemesRetentionHasElapsed() throws Exception {
        initialSync();
        long now = System.currentTimeMillis(), day = 86400000L;
        context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putString("selected_theme", "short").apply();
        DriveConfig shortLease = parse("{\"folderId\":\"test-folder\",\"cacheRetentionDays\":7}");
        context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putString("selected_theme", "long").apply();
        DriveConfig longLease = parse("{\"folderId\":\"test-folder\",\"cacheRetentionDays\":60}");
        DriveImageCache.markUsed(context, shortLease, now);
        DriveImageCache.markUsed(context, longLease, now);
        DriveConfig b = parse("{\"folderId\":\"folder-b\"}");
        DriveImageCache.evictExpired(context, b, () -> {}, now + 30 * day);
        assertEquals(1, DriveImageCache.images(context, config).size());
        DriveImageCache.evictExpired(context, b, () -> {}, now + 60 * day);
        assertFalse(DriveImageCache.directory(context, config).exists());
    }
    @Test public void backgroundSyncDoesNotRefreshUsageAndClockRollbackDoesNotExpireCache() throws Exception {
        initialSync();
        long now = System.currentTimeMillis(), day = 86400000L;
        DriveImageCache.markUsed(context, config, now - 31 * day);
        DriveConfig b = parse("{\"folderId\":\"folder-b\"}");
        DriveImageCache.evictExpired(context, b, () -> {}, now - 32 * day);
        assertEquals(1, DriveImageCache.images(context, config).size());
        folder(); listing(item("one", "wallpaper.jpg", "v1"));
        assertTrue(DriveImageCache.sync(context, config, repository(), () -> {}));
        DriveImageCache.evictExpired(context, b, () -> {}, now);
        assertFalse(DriveImageCache.directory(context, config).exists());
    }
    @Test public void legacyCacheWithoutUsageHistoryGetsThirtyDayGrace() throws Exception {
        initialSync();
        long now = System.currentTimeMillis(), day = 86400000L;
        DriveConfig b = parse("{\"folderId\":\"folder-b\"}");
        DriveImageCache.evictExpired(context, b, () -> {}, now);
        DriveImageCache.evictExpired(context, b, () -> {}, now + 29 * day);
        assertEquals(1, DriveImageCache.images(context, config).size());
        DriveImageCache.evictExpired(context, b, () -> {}, now + 30 * day);
        assertFalse(DriveImageCache.directory(context, config).exists());
    }
    @Test public void retentionConfigurationIsStrict() throws Exception {
        for (String value : new String[]{"0", "-1", "true", "1.5", "\"30\"", "2147483648"}) {
            DriveConfig invalid = parse("{\"folderId\":\"test-folder\",\"cacheRetentionDays\":" + value + "}");
            assertFalse(invalid.enabled); assertEquals("googleDrive.cacheRetentionDays", invalid.error);
        }
        assertEquals(60, parse("{\"folderId\":\"test-folder\",\"cacheRetentionDays\":60}").cacheRetentionDays);
    }
    @Test public void selectingThemeWithCachedImagesDoesNotShowSyncProgressToast() throws Exception {
        initialSync();
        File dir = com.gathin.tvhome.Config.dir(context); assertNotNull(dir); dir.mkdirs();
        Files.write(new File(dir, com.gathin.tvhome.Config.FILE_NAME).toPath(),
                "{\"googleDrive\":{\"folderId\":\"test-folder\"}}".getBytes(StandardCharsets.UTF_8));
        DriveProgress.themeSelected(context, config);
        org.robolectric.shadows.ShadowToast.reset();
        DriveProgress progress = new DriveProgress(context, config);
        try {
            progress.start();
            DriveProgress.update(config, DriveProgress.Phase.PREPARING, 0, -1);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(0, org.robolectric.shadows.ShadowToast.shownToastCount());
        } finally { progress.stop(); }
    }
    @Test public void schedulerHonorsZeroAndCancelsChangedSource() throws Exception {
        File dir = com.gathin.tvhome.Config.dir(context); assertNotNull(dir); dir.mkdirs();
        File file = new File(dir, com.gathin.tvhome.Config.FILE_NAME);
        Files.write(file.toPath(), "{\"googleDrive\":{\"folderId\":\"test-folder\",\"syncInterval\":360}}".getBytes(StandardCharsets.UTF_8));
        DriveSync.schedule(context, true);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo periodic = scheduler.getPendingJob(DriveSync.PERIODIC);
        assertNotNull(periodic); assertEquals(360 * 60000L, periodic.getIntervalMillis());
        assertNotNull(scheduler.getPendingJob(DriveSync.NOW));
        Files.write(file.toPath(), "{\"googleDrive\":{\"folderId\":\"other\",\"syncInterval\":0,\"syncOnLaunch\":false}}".getBytes(StandardCharsets.UTF_8));
        DriveSync.schedule(context, true);
        assertNull(scheduler.getPendingJob(DriveSync.PERIODIC)); assertNull(scheduler.getPendingJob(DriveSync.NOW));
    }
}
