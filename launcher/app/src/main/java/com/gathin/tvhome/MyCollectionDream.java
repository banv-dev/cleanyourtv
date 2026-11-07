package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.widget.FrameLayout;

/** The selected theme's wallpaper playback, with a small stationary clock in the bottom-left corner. */
public class MyCollectionDream extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WallpaperPlayer wallpaper;
    private ClockRefresh clock;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            wallpaper.update();
            handler.postDelayed(this, 60_000L);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_collection);
        FrameLayout root = findViewById(R.id.collectionRoot);
        Config config = Config.load(this);
        root.setBackgroundColor(config.backgroundColor);
        wallpaper = new WallpaperPlayer(this, root, config);
        ClockFormat.apply(root, config.clockFormat);
        clock = new ClockRefresh(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        wallpaper.start();
        clock.start();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 60_000L);
    }

    @Override
    public void onDreamingStopped() {
        stopPlayback();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopPlayback();
        if (wallpaper != null) wallpaper.close();
        super.onDetachedFromWindow();
    }

    private void stopPlayback() {
        handler.removeCallbacks(tick);
        if (clock != null) clock.stop();
        if (wallpaper != null) wallpaper.stop();
    }
}
