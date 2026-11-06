package com.gathin.tvhome;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.json.JSONObject;
import android.content.Context;
import java.io.File;
import java.util.List;
import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.*;

/** Opt-in read-only Drive smoke test; all local writes use the temporary Robolectric app directory. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DriveLiveTest {
    @Test public void libraryAndFullThemeCanBeCachedAndApplied() throws Exception {
        String requested = System.getenv("DRIVE_LIVE_FOLDER");
        String requestedName = System.getenv("DRIVE_LIVE_NAME");
        assumeTrue("Set DRIVE_LIVE_LIBRARY=1 or DRIVE_LIVE_FOLDER", System.getenv("DRIVE_LIVE_LIBRARY") != null || requested != null);
        Context context = RuntimeEnvironment.getApplication();
        try {
            DriveImageRepository repository = new DriveImageRepository(new DriveAuthManager(context), () -> {});
            List<JSONObject> folders = repository.folders(context.getString(R.string.drive_theme_dir).trim());
            System.out.println("Live Drive library: theme folders=" + folders.size() + "; names/IDs hidden.");
            assertFalse("No theme folders returned", folders.isEmpty());
            DriveThemes.saveCatalog(context, folders);
            JSONObject selected = folders.get(0);
            if (requested != null) {
                selected = null;
                for (JSONObject folder : folders) if (requested.equals(folder.optString("id"))) selected = folder;
                assertNotNull("Requested folder is not in the configured library", selected);
            }
            if (requestedName != null) {
                selected = null;
                for (JSONObject folder : folders) if (requestedName.equalsIgnoreCase(folder.optString("name"))) selected = folder;
                assertNotNull("Named folder is not in the configured library", selected);
            }
            String id = selected.getString("id");
            DriveThemes.remember(context, id, selected.getString("name"));
            context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                    .putString("selected_theme", DriveThemes.selection(id)).apply();
            DriveConfig config = DriveConfig.load(context);
            assertTrue(config.enabled);
            List<JSONObject> assets = repository.list(config);
            long bytes = 0; int directories = 0;
            for (JSONObject asset : assets) {
                if (asset.optBoolean("directory")) directories++;
                else bytes += Math.max(0, asset.optLong("size", config.maxFileBytes));
            }
            System.out.println("Live Drive theme: entries=" + assets.size() + " directories=" + directories + "; content hidden.");
            assumeTrue("Theme exceeds bounded live-test download budget", assets.size() <= 100 && bytes <= 20 * 1048576L);
            assertTrue(DriveImageCache.sync(context, config, repository, () -> {}));
            File published = DriveImageCache.publishedDirectory(context, config);
            assertNotNull(published);
            assertEquals(published, com.gathin.tvhome.Config.dir(context));
            assertNotNull(DriveImageCache.readThemeConfig(new File(published, "config.json")));
            com.gathin.tvhome.Config loaded = com.gathin.tvhome.Config.load(context);
            assertNotNull(loaded);
            System.out.println("Live wallpaper state: enabled=" + loaded.wallpaperEnabled + " rotationMinutes="
                    + loaded.wallpaperRotationMinutes + " playable=" + loaded.wallpapers.size()
                    + " single=" + (DriveImageCache.wallpaper(context, config) != null)
                    + " collection=" + DriveImageCache.collection(context, config).size());
            if (System.getenv("DRIVE_LIVE_RENDER") != null) {
                assertTrue("Wallpaper disabled by theme configuration", loaded.wallpaperEnabled);
                assertFalse("No playable wallpaper files selected", loaded.wallpapers.isEmpty());
                android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
                metrics.widthPixels = 3840; metrics.heightPixels = 2160;
                int maxWidth = 0, maxHeight = 0;
                for (File file : loaded.wallpapers) {
                    android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true; android.graphics.BitmapFactory.decodeFile(file.getPath(), bounds);
                    maxWidth = Math.max(maxWidth, bounds.outWidth); maxHeight = Math.max(maxHeight, bounds.outHeight);
                }
                System.out.println("Live wallpaper dimensions: maxWidth=" + maxWidth + " maxHeight=" + maxHeight + "; render target=3840x2160.");
                android.widget.FrameLayout root = new android.widget.FrameLayout(context);
                WallpaperPlayer player = new WallpaperPlayer(context, root, loaded);
                try {
                    boolean rendered = false;
                    for (int i = 0; i < root.getChildCount(); i++) if (root.getChildAt(i) instanceof android.widget.ImageView)
                        rendered |= ((android.widget.ImageView) root.getChildAt(i)).getDrawable() != null;
                    assertTrue("Wallpaper decoding did not produce a visible bitmap", rendered);
                    System.out.println("Live wallpaper render: bitmap produced successfully.");
                } finally { player.close(); }
            }
            System.out.println("Live Drive full theme: snapshot published and config applied; images="
                    + DriveImageCache.images(context, config).size() + " captions=" + (DriveImageCache.captionsFile(context, config) != null));
            int[] checks = {0};
            assertTrue(DriveImageCache.sync(context, config, repository, () -> checks[0]++));
            assertEquals(published, DriveImageCache.publishedDirectory(context, config));
            System.out.println("Live Drive unchanged theme reused the same local snapshot.");
        } catch (Exception failure) {
            String category = failure instanceof DriveImageRepository.ApiException
                    ? "Drive HTTP " + ((DriveImageRepository.ApiException) failure).status : failure.getClass().getSimpleName();
            fail("Live library check failed: " + category + " (details redacted).");
        }
    }
}
