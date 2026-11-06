package com.gathin.tvhome;

import android.app.AlertDialog;
import android.content.Context;
import android.database.DataSetObserver;
import android.view.View;
import android.view.ViewGroup;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DriveThemePickerTest {
    private Context context;
    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        org.robolectric.shadows.ShadowEnvironment.setExternalStorageState(android.os.Environment.MEDIA_MOUNTED);
    }
    private JSONObject folder(String id, String name) throws Exception { return new JSONObject().put("id", id).put("name", name); }
    private void choose(String key) { context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putString("selected_theme", key).apply(); }
    private void finishFetch(LauncherSettingsActivity activity) throws Exception {
        Future<?> request = org.robolectric.util.ReflectionHelpers.getField(activity, "driveThemeRequest");
        request.get(3, TimeUnit.SECONDS);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    private int find(AlertDialog dialog, String label) {
        android.widget.ListAdapter adapter = dialog.getListView().getAdapter();
        for (int i = 0; i < adapter.getCount(); i++) if (label.equals(adapter.getItem(i))) return i;
        return -1;
    }
    @Test public void cachedThemesShowImmediatelyThenFreshListRenamesAddsAndRemovesInPlace() throws Exception {
        DriveThemes.saveCatalog(context, Arrays.asList(folder("one", "Nature"), folder("old", "Old")));
        choose(DriveThemes.selection("one"));
        File local = new File(context.getExternalFilesDir(null), "themes/Local"); local.mkdirs();
        Files.write(new File(local, "config.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        try (org.robolectric.android.controller.ActivityController<LauncherSettingsActivity> controller = Robolectric.buildActivity(LauncherSettingsActivity.class).setup()) {
            LauncherSettingsActivity activity = controller.get();
            CountDownLatch release = new CountDownLatch(1);
            activity.driveFolderLoader = parent -> { release.await(); return Arrays.asList(folder("one", "Renamed"), folder("new", "Cinema")); };
            activity.findViewById(R.id.themeChoice).performClick();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(find(dialog, "Local") >= 0); assertTrue(find(dialog, "gDrive-Nature") >= 0); assertTrue(find(dialog, "gDrive-Old") >= 0);
            assertEquals(find(dialog, "gDrive-Nature"), dialog.getListView().getCheckedItemPosition());
            release.countDown(); finishFetch(activity);
            assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog());
            assertEquals(-1, find(dialog, "gDrive-Old")); assertEquals(-1, find(dialog, "gDrive-Nature"));
            assertTrue(find(dialog, "gDrive-Renamed") >= 0);
            assertEquals(find(dialog, "gDrive-Renamed"), dialog.getListView().getCheckedItemPosition());
            assertTrue(((android.widget.TextView) activity.findViewById(R.id.themeChoice)).getText().toString().contains("gDrive-Renamed"));
            int cinema = find(dialog, "gDrive-Cinema"); assertTrue(cinema >= 0);
            dialog.getListView().performItemClick(null, cinema, dialog.getListView().getAdapter().getItemId(cinema));
            assertEquals(DriveThemes.selection("new"), context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString("selected_theme", ""));
            assertTrue(activity.isFinishing());
        }
    }
    @Test public void syncNowIsDirectlyBelowThemeAndOnlyVisibleForDriveSelection() {
        choose("");
        try (org.robolectric.android.controller.ActivityController<LauncherSettingsActivity> controller = Robolectric.buildActivity(LauncherSettingsActivity.class).setup()) {
            LauncherSettingsActivity activity = controller.get();
            View theme = activity.findViewById(R.id.themeChoice), sync = activity.findViewById(R.id.driveSyncChoice);
            ViewGroup parent = (ViewGroup) theme.getParent();
            assertSame(parent, sync.getParent()); assertEquals(parent.indexOfChild(theme) + 1, parent.indexOfChild(sync));
            assertEquals(View.GONE, sync.getVisibility());
            DriveThemes.remember(context, "one", "Nature"); choose(DriveThemes.selection("one"));
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(activity, "refresh");
            assertEquals(View.VISIBLE, sync.getVisibility());
            choose("Local"); org.robolectric.util.ReflectionHelpers.callInstanceMethod(activity, "refresh");
            assertEquals(View.GONE, sync.getVisibility());
        }
    }
    @Test public void firstFetchDoesNotBlockLocalPickerAndUnchangedRefreshDoesNotRebuildAdapter() throws Exception {
        List<JSONObject> folders = Arrays.asList(folder("one", "Nature"));
        try (org.robolectric.android.controller.ActivityController<LauncherSettingsActivity> controller = Robolectric.buildActivity(LauncherSettingsActivity.class).setup()) {
            LauncherSettingsActivity activity = controller.get(); CountDownLatch release = new CountDownLatch(1);
            activity.driveFolderLoader = parent -> { release.await(); return folders; };
            activity.findViewById(R.id.themeChoice).performClick(); AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog.getListView()); assertEquals(-1, find(dialog, "gDrive-Nature"));
            assertTrue(dialog.getListView().getAdapter().getCount() >= 1);
            release.countDown(); finishFetch(activity); assertTrue(find(dialog, "gDrive-Nature") >= 0); dialog.dismiss();
            activity.driveFolderLoader = parent -> folders;
            activity.findViewById(R.id.themeChoice).performClick(); dialog = ShadowAlertDialog.getLatestAlertDialog();
            AtomicInteger changes = new AtomicInteger();
            android.widget.ListAdapter adapter = dialog.getListView().getAdapter();
            adapter.registerDataSetObserver(new DataSetObserver() { @Override public void onChanged() { changes.incrementAndGet(); } });
            finishFetch(activity); assertSame(adapter, dialog.getListView().getAdapter()); assertEquals(0, changes.get()); dialog.dismiss();
        }
    }
    @Test public void fetchFailureKeepsCachedThemesSelectable() throws Exception {
        DriveThemes.saveCatalog(context, Arrays.asList(folder("one", "Nature")));
        try (org.robolectric.android.controller.ActivityController<LauncherSettingsActivity> controller = Robolectric.buildActivity(LauncherSettingsActivity.class).setup()) {
            LauncherSettingsActivity activity = controller.get(); activity.driveFolderLoader = parent -> { throw new java.io.IOException("offline"); };
            activity.findViewById(R.id.themeChoice).performClick(); AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            finishFetch(activity); int position = find(dialog, "gDrive-Nature"); assertTrue(position >= 0);
            dialog.getListView().performItemClick(null, position, dialog.getListView().getAdapter().getItemId(position));
            assertEquals(DriveThemes.selection("one"), context.getSharedPreferences(com.gathin.tvhome.Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString("selected_theme", ""));
        }
    }
}
