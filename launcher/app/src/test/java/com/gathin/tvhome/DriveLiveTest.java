package com.gathin.tvhome;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.json.JSONObject;
import java.io.File;
import java.util.List;
import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.*;

/** Opt-in, read-only smoke test; never runs against a real account in ordinary test runs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DriveLiveTest {
    @Test public void sharedFolderAndFirstImageWhenPresentAreReadable() throws Exception {
        String folder = System.getenv("DRIVE_LIVE_FOLDER");
        assumeTrue("Set DRIVE_LIVE_FOLDER to run the real-account smoke test", folder != null && !folder.isEmpty());
        DriveAuthManager auth;
        try {
            auth = new DriveAuthManager(RuntimeEnvironment.getApplication());
        } catch (Exception redacted) {
            fail("Local service-account configuration could not be loaded (details redacted).");
            return;
        }
        DriveConfig config = DriveConfig.parse(RuntimeEnvironment.getApplication(), new JSONObject().put("googleDrive",
                new JSONObject().put("folderId", folder)));
        assertTrue(config.enabled);
        DriveImageRepository repository = new DriveImageRepository(auth, () -> {});
        try {
            List<JSONObject> items = repository.list(config);
            int singles = 0, captionFiles = 0;
            for (JSONObject item : items) {
                if ("wallpaper.jpg".equals(item.optString("themePath"))) singles++;
                if (DriveImageCache.caption(item)) captionFiles++;
            }
            System.out.println("Live Drive check: single=" + singles + " collectionImages=" + (items.size() - singles - captionFiles)
                    + " captions=" + captionFiles + "; no names or identifiers logged.");
            if (items.isEmpty()) {
                JSONObject raw = repository.json("https://www.googleapis.com/drive/v3/files?q="
                        + java.net.URLEncoder.encode("'" + folder + "' in parents and trashed = false", "UTF-8")
                        + "&fields=files(mimeType),nextPageToken&pageSize=100");
                org.json.JSONArray files = raw.getJSONArray("files");
                java.util.Map<String, Integer> types = new java.util.TreeMap<>();
                for (int i = 0; i < files.length(); i++) {
                    String type = files.getJSONObject(i).optString("mimeType");
                    types.put(type, types.getOrDefault(type, 0) + 1);
                }
                System.out.println("Live Drive check: folder readable; first-page MIME counts = " + types
                        + "; image download not exercised because no supported images are present.");
                return;
            }
            JSONObject firstImage = null;
            for (JSONObject item : items) if (!DriveImageCache.caption(item)) { firstImage = item; break; }
            if (firstImage == null) return;
            File image = File.createTempFile("drive-live-check", ".img");
            try {
                assertTrue(repository.download(firstImage, image, config, config.maxFileBytes) > 0);
                DriveImageCache.validate(image, firstImage);
                System.out.println("Live Drive check: one image downloaded and validated successfully.");
            } finally { image.delete(); }
            long total = 0;
            for (JSONObject item : items) total += Math.max(0, item.optLong("size", config.maxFileBytes));
            if (items.size() <= 20 && total <= config.maxFileBytes) {
                assertTrue(DriveImageCache.sync(RuntimeEnvironment.getApplication(), config, repository, () -> {}));
                List<File> cached = DriveImageCache.images(RuntimeEnvironment.getApplication(), config);
                assertEquals(items.size() - captionFiles, cached.size());
                if (captionFiles > 0) {
                    assertNotNull(DriveImageCache.captionsFile(RuntimeEnvironment.getApplication(), config));
                    java.util.Map<String, String> captions = DriveImageCache.captions(RuntimeEnvironment.getApplication(), config);
                    int matches = 0;
                    for (File file : cached) if (captions.containsKey(file.getName())) matches++;
                    System.out.println("Live Drive check: caption entries=" + captions.size() + " matching images=" + matches + "; caption text hidden.");
                }
                for (File file : cached) assertTrue(file.getName().equals("wallpaper.jpg") || file.getParentFile().getName().equals("wallpapers"));
                System.out.println("Live Drive check: bounded full sync published local theme snapshot; images=" + cached.size());
            }
        } catch (Exception failure) {
            // No chained OAuth exception in test reports, which may otherwise expose credential data.
            String category = failure instanceof DriveImageRepository.ApiException
                    ? "Drive HTTP " + ((DriveImageRepository.ApiException) failure).status
                    : failure.getClass().getSimpleName();
            fail("Live Drive read failed: " + category + " (details redacted). Check API enablement, Viewer sharing and connectivity.");
        }
    }
}
