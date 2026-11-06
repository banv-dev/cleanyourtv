package com.gathin.tvhome;

import android.content.Context;
import android.os.Looper;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DriveProgressTest {
    private Context context;
    private DriveConfig config;
    private DriveProgress presenter;
    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        org.robolectric.shadows.ShadowEnvironment.setExternalStorageState(android.os.Environment.MEDIA_MOUNTED);
        selectSource("progress-test");
        config = DriveConfig.load(context);
        DriveProgress.update(config, DriveProgress.Phase.COMPLETE, 0, 0);
        idle(); ShadowToast.reset();
        DriveProgress.themeSelected(context, config);
        presenter = new DriveProgress(context, config);
    }
    @After public void tearDown() { presenter.stop(); }
    private void selectSource(String folder) throws Exception {
        File dir = com.gathin.tvhome.Config.dir(context); assertNotNull(dir); dir.mkdirs();
        Files.write(new File(dir, com.gathin.tvhome.Config.FILE_NAME).toPath(), new JSONObject()
                .put("googleDrive", new JSONObject().put("folderId", folder)).toString().getBytes(StandardCharsets.UTF_8));
    }
    private void idle() { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); }
    @SuppressWarnings("deprecation")
    private String text() { return ((TextView) ShadowToast.getLatestToast().getView()).getText().toString(); }
    @Test public void toastShowsBottomProgressAndCompletionWithThrottling() {
        presenter.start();
        DriveProgress.update(config, DriveProgress.Phase.PREPARING, 0, -1); idle();
        assertEquals(1, ShadowToast.shownToastCount());
        Toast toast = ShadowToast.getLatestToast();
        assertEquals(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, toast.getGravity());
        assertTrue(toast.getYOffset() > 0);
        DriveProgress.update(config, DriveProgress.Phase.IMAGES, 1, 10);
        DriveProgress.update(config, DriveProgress.Phase.IMAGES, 3, 10); idle();
        assertEquals(1, ShadowToast.shownToastCount());
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        assertEquals(2, ShadowToast.shownToastCount());
        assertTrue(text(), text().contains("30%")); assertTrue(text(), text().contains("3/10"));
        DriveProgress.update(config, DriveProgress.Phase.IMAGES, 10, 10);
        DriveProgress.finish(config, DriveProgress.Phase.COMPLETE); idle();
        assertTrue(text(), text().contains("100%")); assertTrue(text(), text().contains("10/10"));
        int terminalCount = ShadowToast.shownToastCount();
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        assertEquals(terminalCount, ShadowToast.shownToastCount());
    }
    @Test public void stoppingHomeSuppressesToastsAndPendingRefreshes() {
        presenter.start(); DriveProgress.update(config, DriveProgress.Phase.IMAGES, 1, 4); idle();
        int before = ShadowToast.shownToastCount(); presenter.stop();
        DriveProgress.update(config, DriveProgress.Phase.IMAGES, 2, 4);
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));
        assertEquals(before, ShadowToast.shownToastCount());
    }
    @Test public void sourceChangesSuppressOldThemeProgress() throws Exception {
        presenter.start();
        DriveConfig other = DriveConfig.parse(context, new JSONObject().put("googleDrive", new JSONObject().put("folderId", "other")));
        DriveProgress.update(other, DriveProgress.Phase.IMAGES, 1, 2); idle();
        assertEquals(0, ShadowToast.shownToastCount());
        selectSource("other");
        DriveProgress.update(config, DriveProgress.Phase.IMAGES, 1, 2); idle();
        assertEquals(0, ShadowToast.shownToastCount());
    }
    @Test public void homeReturnDoesNotReplayProgressWithoutAnotherSettingsSelection() {
        presenter.start();
        DriveProgress.update(config, DriveProgress.Phase.IMAGES, 2, 5); idle();
        assertTrue(text().contains("40%"));
        presenter.stop(); ShadowToast.reset();
        presenter.start();
        DriveProgress.update(config, DriveProgress.Phase.IMAGES, 3, 5); idle();
        assertEquals(0, ShadowToast.shownToastCount());
    }
    @Test public void laterSyncAfterCompletionIsSilentUntilThemeIsSelectedAgain() {
        presenter.start();
        DriveProgress.update(config, DriveProgress.Phase.IMAGES, 5, 5);
        DriveProgress.finish(config, DriveProgress.Phase.COMPLETE); idle();
        ShadowToast.reset();
        presenter.start();
        DriveProgress.update(config, DriveProgress.Phase.PREPARING, 0, -1); idle();
        assertEquals(0, ShadowToast.shownToastCount());
        DriveProgress.themeSelected(context, config); presenter.start(); idle();
        assertEquals(1, ShadowToast.shownToastCount());
    }
    @Test public void ordinaryHomeLaunchDoesNotShowProgressEvenWithEmptyCache() {
        DriveConfig disabled = DriveConfig.parse(context, new JSONObject());
        DriveProgress.themeSelected(context, disabled); // clear any pending Settings request
        presenter.start();
        DriveProgress.update(config, DriveProgress.Phase.IMAGES, 1, 3); idle();
        assertEquals(0, ShadowToast.shownToastCount());
    }
    @Test public void partialAndErrorDoNotPretendAllImagesSynced() {
        DriveProgress.update(config, DriveProgress.Phase.IMAGES, 2, 5);
        DriveProgress.finish(config, DriveProgress.Phase.PARTIAL);
        DriveProgress.State state = DriveProgress.state(config);
        assertEquals(40, state.percent()); assertEquals(2, state.done); assertEquals(5, state.total);
        assertTrue(DriveProgress.message(context, state).contains("2/5"));
        DriveProgress.finish(config, DriveProgress.Phase.ERROR);
        assertEquals(40, DriveProgress.state(config).percent());
        assertFalse(DriveProgress.message(context, DriveProgress.state(config)).contains("100%"));
    }
}
