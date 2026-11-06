package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** Foreground-only, source-scoped Toast feedback. Images count; captions never inflate the denominator. */
final class DriveProgress {
    enum Phase { QUEUED, PREPARING, IMAGES, COMPLETE, PARTIAL, ERROR }
    static final class State {
        final String source;
        final Phase phase;
        final int done, total;
        State(String source, Phase phase, int done, int total) {
            this.source = source; this.phase = phase; this.total = total;
            this.done = Math.max(0, total >= 0 ? Math.min(done, total) : done);
        }
        int percent() { return total > 0 ? (int) (100L * done / total) : 0; }
        boolean terminal() { return phase == Phase.COMPLETE || phase == Phase.PARTIAL || phase == Phase.ERROR; }
    }
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static String requestedSource;
    /** Arm one foreground session only from the Settings theme picker, and only for a cold cache. */
    static synchronized void themeSelected(Context context, DriveConfig config) {
        requestedSource = config.enabled && DriveImageCache.images(context, config).isEmpty() ? config.identity() : null;
    }
    private static synchronized boolean consumeRequest(Context context, DriveConfig config) {
        if (!config.identity().equals(requestedSource)) return false;
        requestedSource = null;
        return config.enabled && DriveImageCache.images(context, config).isEmpty();
    }
    private static final Set<DriveProgress> VISIBLE = new CopyOnWriteArraySet<>();
    static State state(DriveConfig config) { return STATES.get(config.identity()); }
    static void update(DriveConfig config, Phase phase, int done, int total) {
        State state = new State(config.identity(), phase, done, total);
        STATES.put(state.source, state);
        new Handler(Looper.getMainLooper()).post(() -> { for (DriveProgress view : VISIBLE) view.receive(state); });
    }
    static void queued(DriveConfig config) {
        State old = state(config);
        if (old == null || old.terminal()) update(config, Phase.QUEUED, 0, -1);
    }
    static void finish(DriveConfig config, Phase phase) {
        State old = state(config);
        update(config, phase, old == null ? 0 : old.done, old == null ? -1 : old.total);
    }

    private final Context context;
    private final DriveConfig config;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable render = this::render;
    private State current;
    private Toast toast;
    private boolean active;
    private long lastShown = -1;

    DriveProgress(Context context, DriveConfig config) { this.context = context; this.config = config; }
    void start() {
        if (active || !consumeRequest(context, config)) return;
        lastShown = -1;
        active = true;
        VISIBLE.add(this);
        State pending = state(config);
        if (pending != null && !pending.terminal()) receive(pending);
    }
    void stop() {
        active = false;
        VISIBLE.remove(this);
        handler.removeCallbacks(render);
        if (toast != null) { toast.cancel(); toast = null; }
        lastShown = -1;
    }
    private void receive(State state) {
        if (!active || !config.identity().equals(state.source)) return;
        current = state;
        long elapsed = lastShown < 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - lastShown;
        if (state.terminal() || elapsed >= 2000) render();
        else {
            handler.removeCallbacks(render);
            handler.postDelayed(render, 2000 - elapsed);
        }
    }
    @SuppressWarnings("deprecation") // Custom foreground Toast: text Toast ignores gravity on Android 11+.
    private void render() {
        handler.removeCallbacks(render);
        if (!active || current == null) return;
        if (!config.identity().equals(DriveConfig.load(context).identity())) { stop(); return; }
        if (toast != null) toast.cancel();
        TextView text = new TextView(context);
        float density = context.getResources().getDisplayMetrics().density;
        text.setText(message(context, current));
        text.setTextColor(Color.WHITE);
        text.setTextSize(17);
        text.setGravity(Gravity.CENTER);
        text.setMaxWidth(Math.round(700 * density));
        text.setPadding(Math.round(24 * density), Math.round(12 * density), Math.round(24 * density), Math.round(12 * density));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xEE20252B);
        background.setCornerRadius(12 * density);
        text.setBackground(background);
        toast = new Toast(context);
        toast.setView(text);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, Math.round(48 * density));
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.show();
        lastShown = SystemClock.elapsedRealtime();
        if (current.terminal()) {
            active = false;
            VISIBLE.remove(this); // Completion ends this request; later Home/periodic sync stays silent.
        } else if (current.phase != Phase.QUEUED) handler.postDelayed(render, 3000);
    }
    static String message(Context context, State state) {
        switch (state.phase) {
            case QUEUED: return context.getString(R.string.drive_progress_queued);
            case PREPARING: return context.getString(R.string.drive_progress_preparing);
            case COMPLETE:
                return state.total == 0 ? context.getString(R.string.drive_progress_empty)
                        : context.getString(R.string.drive_progress_complete, state.percent(), state.done, state.total);
            case PARTIAL: return context.getString(R.string.drive_progress_partial, state.percent(), state.done, Math.max(0, state.total));
            case ERROR: return context.getString(R.string.drive_progress_error);
            default: return context.getString(R.string.drive_progress_images, state.percent(), state.done, Math.max(0, state.total));
        }
    }
}
