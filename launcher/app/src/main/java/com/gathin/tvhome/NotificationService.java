package com.gathin.tvhome;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Listens for system notifications and forwards them to MainActivity so the launcher can
 * show the latest notification, a notification-bell icon in the top bar, and the notification list popup.
 *
 * <p>Enable it once via adb (or the user grants it in Settings → Apps → Special app access
 * → Notification access) and the service stays connected across reboots.
 */
public class NotificationService extends NotificationListenerService {

    /** Broadcast action sent to MainActivity when the notification list changes. */
    static final String ACTION_NOTIFICATIONS_CHANGED = "com.gathin.tvhome.action.NOTIFICATIONS_CHANGED";

    /** Key for the extra that carries the latest notification text (title + body). */
    static final String EXTRA_LATEST_TEXT = "latest_text";
    /** Key for the extra that carries the latest notification package name. */
    static final String EXTRA_LATEST_PKG = "latest_pkg";
    /** Key for the extra that carries the unread notification count. */
    static final String EXTRA_COUNT = "count";

    /** Snapshot of a visible notification for the launcher's notification list popup. */
    static final class Item {
        final String key;
        final String pkg;
        final String title;
        final String text;
        final long postTime;
        final PendingIntent contentIntent;
        final boolean clearable;

        Item(String key, String pkg, String title, String text,
             long postTime, PendingIntent contentIntent, boolean clearable) {
            this.key = key;
            this.pkg = pkg;
            this.title = title;
            this.text = text;
            this.postTime = postTime;
            this.contentIntent = contentIntent;
            this.clearable = clearable;
        }
    }

    /**
     * Packages whose notifications are suppressed on the launcher.
     * CleanYourTV itself is excluded so its own alerts don't echo back.
     */
    private static final String[] IGNORED_PACKAGES = {
            "com.gathin.tvhome",
    };

    private static volatile NotificationService instance;
    private static volatile List<Item> cachedItems = Collections.emptyList();

    private final Handler handler = new Handler(Looper.getMainLooper());

    // ---- NotificationListenerService callbacks ------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        handler.post(this::broadcast);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        handler.post(this::broadcast);
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        handler.post(this::broadcast);
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Collects the current visible notifications, updates the cache, and returns the list. */
    private List<Item> collectVisible() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all == null) all = new StatusBarNotification[0];

            List<StatusBarNotification> visible = new ArrayList<>();
            for (StatusBarNotification sbn : all) {
                if (isIgnored(sbn)) continue;
                Notification n = sbn.getNotification();
                if (n == null) continue;
                if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                visible.add(sbn);
            }

            // Sort newest first (postTime descending)
            visible.sort((a, b) -> Long.compare(b.getPostTime(), a.getPostTime()));

            List<Item> items = new ArrayList<>(visible.size());
            for (StatusBarNotification sbn : visible) {
                Notification n = sbn.getNotification();
                CharSequence rawTitle = n.extras != null
                        ? n.extras.getCharSequence(Notification.EXTRA_TITLE) : null;
                if (TextUtils.isEmpty(rawTitle) && n.extras != null) {
                    rawTitle = n.extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
                }
                CharSequence rawText = n.extras != null
                        ? n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) : null;
                if (TextUtils.isEmpty(rawText) && n.extras != null) {
                    rawText = n.extras.getCharSequence(Notification.EXTRA_TEXT);
                }
                if (TextUtils.isEmpty(rawText) && n.extras != null) {
                    rawText = n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
                }
                if (TextUtils.isEmpty(rawText) && n.tickerText != null) {
                    rawText = n.tickerText;
                }
                String title = rawTitle != null ? rawTitle.toString().trim() : "";
                String text = rawText != null ? rawText.toString().trim() : "";
                // Skip silent background service notifications with no text or title.
                if (title.isEmpty() && text.isEmpty()) continue;

                PendingIntent pi = n.contentIntent;
                if (pi == null && n.actions != null && n.actions.length > 0) {
                    pi = n.actions[0].actionIntent;
                }
                items.add(new Item(
                        sbn.getKey(),
                        sbn.getPackageName(),
                        title,
                        text,
                        sbn.getPostTime(),
                        pi,
                        sbn.isClearable()));
            }
            cachedItems = Collections.unmodifiableList(items);
            return cachedItems;
        } catch (Exception ignored) {
            return cachedItems;
        }
    }

    /** Collects the current notification list and sends the summary broadcast. */
    private void broadcast() {
        try {
            List<Item> items = collectVisible();
            String latestText = "";
            String latestPkg = "";
            if (!items.isEmpty()) {
                Item top = items.get(0);
                latestPkg = top.pkg;
                if (!top.title.isEmpty() && !top.text.isEmpty()) {
                    latestText = top.title + ": " + top.text;
                } else if (!top.title.isEmpty()) {
                    latestText = top.title;
                } else {
                    latestText = top.text;
                }
            }

            Intent intent = new Intent(ACTION_NOTIFICATIONS_CHANGED);
            intent.setPackage(getPackageName());
            intent.putExtra(EXTRA_COUNT, items.size());
            intent.putExtra(EXTRA_LATEST_TEXT, latestText);
            intent.putExtra(EXTRA_LATEST_PKG, latestPkg);
            sendBroadcast(intent);
        } catch (Exception ignored) {
            // Service not connected yet; MainActivity handles missing data gracefully.
        }
    }

    private boolean isIgnored(StatusBarNotification sbn) {
        String pkg = sbn.getPackageName();
        for (String ignored : IGNORED_PACKAGES) {
            if (ignored.equals(pkg)) return true;
        }
        return false;
    }

    // ---- static helpers ------------------------------------------------------------------------

    /** Returns the current active notifications (refreshing live if the listener is connected). */
    static List<Item> getNotifications(Context ctx) {
        NotificationService svc = instance;
        if (svc != null) {
            return svc.collectVisible();
        }
        if (isEnabled(ctx)) {
            try {
                requestRebind(new ComponentName(ctx, NotificationService.class));
            } catch (Exception ignored) {
            }
        }
        return cachedItems;
    }

    /** Triggers a broadcast refresh (or requests a rebind if enabled but not yet connected). */
    static void refresh(Context ctx) {
        NotificationService svc = instance;
        if (svc != null) {
            svc.handler.post(svc::broadcast);
        } else if (isEnabled(ctx)) {
            try {
                requestRebind(new ComponentName(ctx, NotificationService.class));
            } catch (Exception ignored) {
            }
        }
    }

    /** Dismisses a single notification by its StatusBarNotification key. */
    static void dismiss(String key) {
        NotificationService svc = instance;
        if (svc == null || key == null) return;
        try {
            svc.cancelNotification(key);
            svc.handler.post(svc::broadcast);
        } catch (Exception ignored) {
        }
    }

    /** Dismisses all clearable notifications. */
    static void dismissAll() {
        NotificationService svc = instance;
        if (svc == null) return;
        try {
            svc.cancelAllNotifications();
            svc.handler.post(svc::broadcast);
        } catch (Exception ignored) {
        }
    }

    /** Returns true when the notification listener permission has been granted. */
    static boolean isEnabled(Context ctx) {
        try {
            String flat = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_notification_listeners");
            if (flat == null) return false;
            String component = new ComponentName(ctx, NotificationService.class).flattenToString();
            String shortComponent = new ComponentName(ctx, NotificationService.class).flattenToShortString();
            return flat.contains(component) || flat.contains(shortComponent);
        } catch (Exception e) {
            return false;
        }
    }
}
