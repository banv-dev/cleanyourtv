# Google Drive theme library (private fork)

Google Drive is a theme source in Launcher Settings. Themes installed with `tools/theme.sh` remain
ordinary local themes. The old `googleDrive` object in local/remote theme JSON is ignored; no folder
ID, scheduler or retention configuration is read from a theme's JSON.

## Configure the app

Use the existing `app/src/main/res/values/drive.xml` resources:

```xml
<string name="drive_theme_dir" translatable="false">YOUR_THEME_LIBRARY_FOLDER_ID</string>
<integer name="drive_sync_interval">5</integer>
<bool name="drive_sync_on_launch">true</bool>
<integer name="drive_cache_retention_days">5</integer>
```

- `drive_theme_dir`: the parent library folder, whose immediate subfolders are complete themes.
- `drive_sync_interval`: minutes; 0 disables periodic sync, positive values below 15 are clamped to
  Android's 15-minute periodic minimum. Android may delay jobs for network/power constraints.
- `drive_sync_on_launch`: whether opening Home requests a sync of the selected Drive theme. Choosing
  an uncached theme explicitly in Settings always queues its initial download, even if this is false.
- `drive_cache_retention_days`: positive integer days since last theme use before inactive cache can
  be deleted. This is now a global XML setting, not a theme JSON key.

Technical limits also live in drive.xml: `drive_cache_max_size_mb` (300 total across themes),
`drive_max_file_size_mb` (20 per ordinary file) and `drive_supported_mime_types` (JPEG/PNG/WebP for
wallpaper display). `config.json` is bounded to 64 KB; `wallpapers/captions.txt` to 256 KB.
Do not define the same resource names again in another values XML file.

Share the library folder with the service account as **Viewer**, keep General access **Restricted**,
and enable Google Drive API. Keep the key only at `app/src/main/google_service_account.json`, ignored
by Git. Gradle copies it to APK assets. The private key is extractable from the APK: keep it private
and revoke the key if it leaves your control. No TV Google login or backend is used.

## Drive layout and selection

```text
Theme library/                 # drive_theme_dir
  Nature/
    config.json                # required JSON object, same keys as an ordinary local theme
    wallpaper.jpg              # optional
    wallpapers/
      photo-1.jpg
      photo-2.png
      captions.txt             # optional UTF-8: filename<TAB>caption
    assets/                    # other files/subfolders are mirrored too
      nested/
        example.bin
  Cinema/
    config.json
    wallpapers/
      photo-1.webp
```

Launcher Settings → Theme lists local themes and **gDrive-{folder name}** entries together; choose a
Drive theme directly from this list. Cached Drive entries appear immediately. Every time the picker
opens, the library refreshes off the UI thread; additions, removals and renames update the same list
only if its visible contents changed. Current selection/focus are preserved by source ID. A failed
refresh keeps cached entries usable. The currently selected Drive theme remains listed even if it is
missing from the latest library response. Selection/cache use stable folder IDs, so renamed folders
keep their cache and duplicate labels cannot overwrite data. Only children of the configured library
are listed. After changing the library XML, reselect a theme from that library before new sync can run.

The selected folder is recursively synchronized, including config.json, images, captions, other
ordinary files and empty subdirectories. Paths are validated against traversal/absolute paths and
control characters. Duplicate sibling paths, shortcuts/Google document exports, cycles, paths over
1024 characters, more than 16 directory levels or 10,000 entries are rejected before publishing a
new snapshot. These limits prevent unbounded traversal on low-memory TVs.

Build/install the APK using the existing procedure (JDK 17; `./gradlew --no-daemon testReleaseUnitTest
lintRelease assembleRelease`). No `tools/theme.sh` command or per-theme folder-ID setup is needed for
Drive themes. Local themes still use that script exactly as before.

## Cache, updates and presentation

```text
<private filesDir>/drive-images/<theme-folder-id>/
  manifest.json
  snapshots/<revision>/
    config.json
    wallpaper.jpg
    wallpapers/
      photo-1.jpg
      captions.txt
    assets/...
```

The active snapshot directory is passed to the normal theme/config loader. JSON keys for colours,
dock, columns, rotation, captions and other ordinary theme behavior therefore work as with a pushed
local theme. Pending/failed first downloads show built-in defaults, never another local theme's
config. The snapshot must contain a valid config.json before it can be published. Changes to config
or other style assets reload Home automatically; wallpaper/caption-only changes update the running
wallpaper player without a full Home reload.

A sync verifies and fully paginates the entire folder tree, then compares file IDs and MIME type plus
MD5 when present, otherwise modified time and size. Unchanged files reuse immutable bytes with hard
links (bounded copies if unsupported). Only new/changed content is downloaded. JSON/captions are
validated as UTF-8; images are decoded for validation; sizes/checksums are checked before publication.
Deleted/renamed files and folders are reflected in the next valid snapshot. An unchanged source keeps
the same snapshot and downloads no content bytes.

The manifest switches atomically after staging. Listing/permission/config errors or cancellation keep
the published theme. Individual optional file failures can retain an older file and mark sync partial.
A partial first download cannot publish without a valid config.json. Old snapshots are removed only
after the new snapshot is published. API retries are bounded; logs never contain key/token bodies,
file names, folder IDs or caption content.

Switching A → B retains A's entire cache. Returning to A loads it immediately offline; the next sync
checks metadata before downloading content. Last use is recorded when selecting/showing/leaving Home
and periodically while visible. Background sync does not extend retention. The current source is
protected; expired inactive caches are cleaned during successful listing, not by an additional timer.
Unknown cache history receives a grace period using the global retention setting. Cache is never
evicted before expiry just to make room: if the total budget is full, another sync can be partial.

Progress Toasts remain restricted to Settings selection of a theme without cached images. Pressing
Home, warm-cache selection, manual or periodic sync stays silent. The one-use Toast session ends on
completion/failure or when leaving Home. Percent/count reflect ready wallpaper images; config,
caption and other assets do not inflate the image denominator.

**Sync now** sits directly below the Theme row and is visible only for a selected Drive theme; local
and built-in themes hide it. Use it for an explicit refresh. Set `wallpaper_captions: true` in the remote config.json to
show caption text (up to 80 characters per entry). Playback follows the remote theme's normal
`wallpaper_rotation_minutes`, `wallpaper_order`, dimming and wallpaper choice rules.

## Verification and debugging

`adb logcat -s TvHome.Drive:I` reports stages, counts, HTTP/OAuth codes and safe exception classes/code
locations. Check automatic TV date/time if `reason=clock_skew` appears. APK/code/key changes require
reinstallation; Drive content changes only require sync.

Ordinary tests do not access the real account. For an opt-in read-only library/full-theme smoke test:
`DRIVE_LIVE_LIBRARY=1 ./gradlew testReleaseUnitTest --tests com.gathin.tvhome.DriveLiveTest --rerun-tasks`.
Optionally set `DRIVE_LIVE_FOLDER` to a child folder ID to choose the test source. Live downloads are
bounded to 20 MB and 100 entries; names/content are not logged.

Real-TV verification remains required for D-pad selection, actual network loss/reboot, visual styling,
caption updates and progress Toast placement. Full lint in this fork has existing MainActivity errors
(resource types, receiver flags and an English format string); the broader Python suite also has
existing theme/provider expectations. No CI/safety/Home guard policy has been changed.

Library verification: 340 unit tests passed (one opt-in real-account test skipped in the normal run),
33 scoped local-theme script tests passed, and the release APK was assembled. The read-only live test
listed four library folders, downloaded/applied one complete theme (16 entries including one directory,
11 wallpaper images and captions), and confirmed a second sync kept the same snapshot. Full lint still
reports the five pre-existing MainActivity errors; no new Drive lint error was reported. Real-TV visual
and remote-navigation validation has not been performed.

Picker refresh verification: 343 unit tests passed (one opt-in live test skipped) and release APK
assembled. UI tests confirm Sync Now is directly below Theme, visibility follows source type, cached
local/Drive entries appear before refresh completes, fresh results rename/add/remove rows in the same
dialog, unchanged results do not rebuild the adapter, and cached entries remain selectable on failure.
Real-TV D-pad/visual checks remain pending.

For a Drive theme containing only a `wallpapers/` collection, `wallpaper_rotation_minutes: 0` displays
the first naturally sorted image statically if `wallpaper.jpg` is absent. A separate wallpaper.jpg is
still preferred for static playback. Positive rotation values rotate the collection; `wallpaper: false`
and the Wallpaper picker’s None option still suppress images. This fallback does not change local
pushed-theme defaults. Wallpaper selection/decode logs report configuration readiness, enablement,
rotation, selected image count and safe decode rejection reasons without file names or IDs.

Natural-theme regression verified: the source loaded wallpaper=true, rotation=0, one collection image
and no wallpaper.jpg. Before the fix, no playable files were selected; the static fallback now selects
and renders the image successfully with a 3840x2160 test display. 345 unit tests passed (one opt-in live
test skipped in the ordinary run); release APK assembled. The separate read-only live render test passed.
Use DRIVE_LIVE_NAME to target a folder by name and DRIVE_LIVE_RENDER=1 to exercise bitmap rendering.
This is native Android graphics verification in Robolectric, not validation on the physical TV.
