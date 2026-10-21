package com.gathin.tvhome;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Listens for system notifications and forwards them to MainActivity so the launcher can
 * show the latest unread notification, an unread count badge on the notification-bell icon
 * in the top bar, and the notification list / detail views.
 *
 * <p>Enable it once via adb (or the user grants it in Settings → Apps → Special app access
 * → Notification access) and the service stays connected across reboots.
 */
public class NotificationService extends NotificationListenerService {

    /** Broadcast action sent to MainActivity when the notification list changes. */
    static final String ACTION_NOTIFICATIONS_CHANGED = "com.gathin.tvhome.action.NOTIFICATIONS_CHANGED";

    /** Key for the extra that carries the latest unread notification text (title + body). */
    static final String EXTRA_LATEST_TEXT = "latest_text";
    /** Key for the extra that carries the latest unread notification package name. */
    static final String EXTRA_LATEST_PKG = "latest_pkg";
    /** Key for the extra that carries the unread notification count. */
    static final String EXTRA_COUNT = "count";

    private static final String PREFS_READ = "notification_reads";
    private static final String KEY_READ_IDS = "read_ids";

    /** Snapshot of a visible notification for the launcher's notification list and detail dialog. */
    static final class Item {
        final String key;
        final String readId;
        final String pkg;
        final String title;
        final String text;
        final long postTime;
        final PendingIntent contentIntent;
        final boolean clearable;
        final boolean unread;

        Item(String key, String readId, String pkg, String title, String text,
             long postTime, PendingIntent contentIntent, boolean clearable, boolean unread) {
            this.key = key;
            this.readId = readId;
            this.pkg = pkg;
            this.title = title;
            this.text = text;
            this.postTime = postTime;
            this.contentIntent = contentIntent;
            this.clearable = clearable;
            this.unread = unread;
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

    private static SharedPreferences readPrefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_READ, Context.MODE_PRIVATE);
    }

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

            SharedPreferences prefs = readPrefs(this);
            Set<String> storedRead = prefs.getStringSet(KEY_READ_IDS, Collections.emptySet());
            Set<String> activeRead = new HashSet<>();

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
                String readId = sbn.getKey() + "@" + sbn.getPostTime();
                boolean isRead = storedRead.contains(readId);
                if (isRead) activeRead.add(readId);

                items.add(new Item(
                        sbn.getKey(),
                        readId,
                        sbn.getPackageName(),
                        title,
                        text,
                        sbn.getPostTime(),
                        pi,
                        sbn.isClearable(),
                        !isRead));
            }
            if ( activeRead.size() != storedRead.size()) {
                prefs.edit().putStringSet(KEY_READ_IDS, activeRead).apply();
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
            int unreadCount = 0;
            Item latestUnread = null;
            for (Item item : items) {
                if (item.unread) {
                    unreadCount++;
                    if (latestUnread == null) latestUnread = item;
                }
            }

            String latestText = "";
            String latestPkg = "";
            if (latestUnread != null) {
                latestPkg = latestUnread.pkg;
                if (!latestUnread.title.isEmpty() && !latestUnread.text.isEmpty()) {
                    latestText = latestUnread.title + ": " + latestUnread.text;
                } else if (!latestUnread.title.isEmpty()) {
                    latestText = latestUnread.title;
                } else {
                    latestText = latestUnread.text;
                }
            }

            Intent intent = new Intent(ACTION_NOTIFICATIONS_CHANGED);
            intent.setPackage(getPackageName());
            intent.putExtra(EXTRA_COUNT, unreadCount);
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

    /** Marks a single notification as read and refreshes the unread count. */
    static void markRead(Context ctx, Item item) {
        if (item == null || !item.unread) return;
        SharedPreferences prefs = readPrefs(ctx);
        Set<String> next = new HashSet<>(prefs.getStringSet(KEY_READ_IDS, Collections.emptySet()));
        if (next.add(item.readId)) {
            prefs.edit().putStringSet(KEY_READ_IDS, next).apply();
        }
        refresh(ctx);
    }

    /** Marks all current notifications as read and refreshes the unread count. */
    static void markAllRead(Context ctx) {
        List<Item> current = getNotifications(ctx);
        if (current.isEmpty()) return;
        SharedPreferences prefs = readPrefs(ctx);
        Set<String> next = new HashSet<>(prefs.getStringSet(KEY_READ_IDS, Collections.emptySet()));
        boolean changed = false;
        for (Item item : current) {
            if (next.add(item.readId)) changed = true;
        }
        if (changed) {
            prefs.edit().putStringSet(KEY_READ_IDS, next).apply();
        }
        refresh(ctx);
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
