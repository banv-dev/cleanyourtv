# Google Drive images (private fork)

This fork supports a Restricted Google Drive folder as a theme's image source. It adds Internet access
and scheduled jobs, outside the upstream launcher's offline/576 KB contract.

## Source layout

Set `googleDrive.folderId` to the **theme root folder**, containing either or both:

```text
Drive theme root/
  wallpaper.jpg          # optional single wallpaper
  wallpapers/            # optional image collection
    photo-1.jpg
    photo-2.png
    photo-10.webp
    captions.txt         # optional UTF-8: filename<TAB>caption
```

These image locations and the optional `wallpapers/captions.txt` are synchronized. `config.json` on Drive is not loaded; the installed local
theme remains the source of settings. Other root images/files and deeper subfolders are ignored.
Collection filenames follow normal local-theme names (letters, digits, `_`, `-`, `.`, JPG/JPEG/PNG/WebP;
no leading dot, `..`, or path separators). MIME type, size, checksum when available and image decode
are checked; an extension alone does not establish that a file is an image. Duplicate `wallpapers`
folders or duplicate image paths are rejected rather than selected arbitrarily.

## Setup and install

1. Enable Google Drive API in your Cloud project. Share the **theme root folder** with the service
   account as **Viewer**, keeping General access **Restricted**. No project IAM role is needed.
2. Keep the JSON key at `launcher/app/src/main/google_service_account.json` (ignored by Git). Gradle
   copies only this file into the APK as `assets/service-account.json`. The key is extractable from
   the APK: keep the APK private and revoke the key if it leaves your control.
3. Merge this object into the existing local theme `config.json`:

   ```json
   "googleDrive": {
     "enabled": true,
     "folderId": "THEME_ROOT_FOLDER_ID",
     "syncInterval": 360,
     "syncOnLaunch": true,
     "cacheRetentionDays": 30
   }
   ```

   `syncInterval` is minutes; 0 disables periodic sync, positive values below 15 use Android's 15-minute
   minimum. `syncOnLaunch: false` disables Home-triggered sync. Without `googleDrive`, or with
   `enabled: false`, no Drive API is called. Invalid settings are logged by theme/key.
4. Build using JDK 17: in `launcher/`, run
   `./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease`.
   Install using the existing build/install procedure. Push the local theme using
   `TV_HOST=<tv ip> tools/theme.sh themes/driver-natural`, then **select driver-natural in Launcher Settings**.
   A theme push installs configuration, not a new APK; credential/code changes require reinstalling
   the APK. `launcher/drive-theme-example.json` is a template with a deliberately empty folder ID.
5. Use **Google Drive: Sync now** in Launcher Settings to queue a manual sync.

Playback matches a normal local theme: with `wallpaper_rotation_minutes > 0`, a nonempty `wallpapers/`
collection rotates; otherwise `wallpaper.jpg` is used. If the collection is absent/empty, the single
wallpaper is the fallback. With rotation set to 0 and no single wallpaper, no wallpaper is shown.
Drive images use natural filename order. The Wallpaper picker offers the single image and collection
images, **None** hides them, and **Theme default** restores the theme's rotation/fallback behavior.
The existing `wallpaper_dim` setting still applies. Set `wallpaper_captions: true` to show captions.
`wallpapers/captions.txt` uses the normal local-theme format: one `filename<TAB>caption` per UTF-8 line;
blank/comment/invalid lines are ignored and displayed captions are limited to 80 characters. The file
is bounded to 256 KB, shares the image snapshot/cache budget, and is never treated as a wallpaper.
A valid remote deletion removes old captions; failed downloads preserve previous valid text. Caption
additions, changes and deletions update the running wallpaper player after sync without restarting Home.

## Local cache and failure handling

Technical limits are resource-only settings in `app/src/main/res/values/config.xml`:
`drive_cache_max_size_mb` (300), `drive_max_file_size_mb` (20), and `drive_supported_mime_types`
(JPEG, PNG, WebP). Themes cannot override these limits.

```text
<private filesDir>/drive-images/<root-folder-id>/
  manifest.json                    # points at the currently published snapshot
  snapshots/<revision>/
    wallpaper.jpg
    wallpapers/
      photo-1.jpg
      photo-2.png
      captions.txt
```

Each snapshot preserves normal theme image paths and names. The manifest also records Drive file IDs,
metadata and snapshot-relative cache paths; it contains no token/key. The app uses manifest-selected
files, so a different source or unfinished snapshot cannot leak into the active image list.

The worker verifies and completely paginates the root and the optional collection before publishing
changes. New images and captions stream into temporary files and are validated before entering a snapshot. Unchanged
images reuse immutable bytes with hard links, with bounded copies when hard links are unsupported.
A failed image retains its previous bytes when available and marks the result partial. Listing/API
errors, obsolete jobs, or failed manifest writes preserve the previous published snapshot. Only after
an atomic manifest switch are old snapshots removed. A fully unchanged sync keeps the same paths;
changed image bytes are reloaded even when their original filename is unchanged.

Missing `wallpaper.jpg`/`wallpapers/` is allowed. A verified empty source removes previously synced images;
a denied or incomplete collection listing does not. Inactive-source caches are evicted only after
their retention has elapsed; abandoned snapshots within the current source are removed separately. Published active images and unexpired inactive-source caches are preserved when
space is insufficient, so new downloads can be partial and replacements may need temporary free space.
Changing the source cancels obsolete jobs. Periodic jobs survive reboot;
Android can delay them for network/power constraints. Old flat `.img` cache manifests are replaced on
the first successful sync with the new theme layout; the folder ID should now refer to the theme root.

## Keeping caches across theme switches

`googleDrive.cacheRetentionDays` is a positive integer in **days**, default **30**. Set it in each
local theme's `config.json`. Switching A → B retains A's published images and captions. Returning to A
uses its cache immediately, including offline; if metadata is unchanged, its next sync downloads no
image/caption bytes.

Usage is recorded when the theme is selected, when Home is shown/left, and periodically while Home
stays visible using its existing update loop. Background sync does **not** reset last-use time.
The current source is always protected. Inactive-source cache is removed only when all themes using
that Drive folder have passed their individual retention deadlines. Themes sharing a folder share
cache bytes but have separate usage records. Expiration is checked during sync after the full remote
listing succeeds, so deletion can occur later than the exact deadline; no new background cleanup job
is added. Existing caches without usage history receive a conservative 30-day grace period on upgrade.

The resource `drive_cache_max_size_mb` remains the total cache budget across all sources. Unexpired
caches are never removed early just to make room: if they fill the budget, another theme's sync can
be partial until space is available or the limit is increased in config.xml. Normal remote-image
replacement/deletion and removal of obsolete snapshots continue within the active source.

## Debugging and verification

Filter logcat with `adb logcat -s TvHome.Drive:I`. Logs show job/theme, device UTC, configured/effective
interval, credential load/token refresh, HTTP status/retry, listing pages/counts, download bytes, cache
limits and snapshot counts. Errors show the stage, cause classes and code locations, without arbitrary
exception bodies, keys, tokens, account addresses, image names, IDs or URLs. `reason=clock_skew` means
check TV automatic date/time; `invalid_grant` can also mean a revoked key or disabled service account.

The opt-in read-only integration test uses `DRIVE_LIVE_FOLDER=<theme root folder id>` and
`./gradlew testReleaseUnitTest --tests com.gathin.tvhome.DriveLiveTest --rerun-tasks`.
It uses the app's production auth and bundled asset, lists the theme structure, and validates downloaded
image data. Ordinary tests skip real-account access.

Real-TV verification remains required for Home/D-pad behavior, actual network loss, reboot, source
switching and visual screenshots. Full lint in this fork has five pre-existing MainActivity errors
(resource types, receiver flags and an English string format). The broader Python suite also has
existing theme/provider expectations and sandbox socket limitations; scoped theme-tool tests pass.

Theme-layout verification: 334 unit tests passed (one opt-in live test skipped in the normal run).
The separate live test read the configured theme root, found one collection image, downloaded and
validated it, and published a complete local snapshot with the `wallpapers/` directory. Both local
`google1` and `driver-natural` configurations now point to the root folder rather than its child.
Single-wallpaper, combined layout, fallback, rename, deletion, duplicate-name rejection, unsafe paths,
failed child listings and cancellation are covered by synthetic tests. Real-TV checks remain pending.

Caption update verification: 338 unit tests passed (one real-account test skipped by default), including
caption add/update/delete, bad UTF-8, oversized/duplicate caption files and a running wallpaper player
receiving new captions without recreation. Release APK assembled. The separate real-account sync passed;
the current source returned one image and no supported captions.txt, so live caption content remains
unverified. No real-TV check has been performed.

Retention verification: 344 unit tests passed (one opt-in live test skipped), 35 scoped theme-tool tests
passed, and release APK assembled. Tests cover A → B → A without image redownload, configurable expiry,
use resetting the deadline, shared-folder ownership, backward clocks, background sync not extending
retention and migration grace for existing caches. No real-TV retention run has been performed.

## Sync progress Toast

Only after selecting a different theme in Launcher Settings, and only if that source has no usable
cached images, the next Home session shows sync Toasts centered at the bottom: waiting for Android/network, checking
the list, then percentage and synchronized image count (for example `40% (4/10 images)`). The total
is known only after root/collection pagination finishes. Percentage is based on successfully downloaded
or reused images, not bytes; captions do not inflate the image count. Failed image updates retaining
old bytes are not counted as synchronized. Full completion is announced only after the snapshot is
published; partial/error and verified empty-folder results have separate messages.

Only the active source can display progress. The Settings selection arms a one-use presentation request;
completion, failure, or leaving Home ends it. Pressing Home, returning from another app, manual sync,
and periodic/background sync do not arm a request. Selecting a source with cached images stays silent,
even while its metadata/content refresh runs. Leaving Home cancels the Toast and timers; returning
without another Settings theme selection does not replay progress. Updates are coalesced to at most one Toast per two
seconds, with a three-second refresh while work is in progress; terminal results display immediately.
Queued jobs show one waiting message until Android starts them. The foreground custom Toast keeps
bottom positioning on newer Android versions and requires no overlay permission.

Progress verification: release APK assembled; 349 unit tests passed, with one opt-in live test skipped.
Tests cover percentage/counts, image/caption separation, bottom gravity, throttling, partial/error
results, stopping Home and suppressing stale-source progress. Real-TV visual checks remain
pending.

Toast eligibility update verified: 352 unit tests passed (one opt-in live test skipped) and release APK
assembled. Tests confirm ordinary Home launches and returning Home stay silent, cached-theme selection
stays silent, terminal sync ends the one-use session, and a new cold-cache Settings selection can show
progress again. Real-TV checks remain pending.
