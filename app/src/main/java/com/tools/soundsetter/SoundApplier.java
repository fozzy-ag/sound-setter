package com.tools.soundsetter;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.media.MediaScannerConnection;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The apply pipeline. For every system sound event it:
 *
 *  1. determines the real audio format of the user-picked file (provider MIME,
 *     extension table, byte-sniffing fallback) because RingtoneManager
 *     SILENTLY IGNORES URIs whose MIME it cannot resolve,
 *  2. publishes the bytes into the correct MediaStore collection
 *     (Ringtones/ / Notifications/ / Alarms/, IS_PENDING while copying, then
 *     IS_RINGTONE/IS_NOTIFICATION/IS_ALARM flags) - on Android 9 and below it
 *     writes the public folder and runs a media scan instead,
 *  3. writes the setting through RingtoneManager (and, on Xiaomi, through the
 *     extra per-SIM keys that otherwise override the global ringtone),
 *  4. VERIFIES the write by reading the setting back, retries with a direct
 *     Settings.System write, and - if still refused - retries once more
 *     through Shizuku (shell uid 2000) when the user enabled it.
 *
 * Every failure carries the step that failed, the underlying exception and a
 * concrete hint, so the UI can tell the user exactly what went wrong.
 */
public final class SoundApplier {

    private static final String TAG = "SoundSetter";

    public interface Listener {
        /** Called from the worker thread for each pipeline step (UI may hop to main). */
        void onStep(String stepLabel);
        /** Called on the main thread exactly once. */
        void onResult(ApplyResult result);
    }

    public static final class ApplyResult {
        public boolean success;
        public String stepId = "";
        public String headline = "";
        public String message = "";
        public String hint = "";
        public Throwable error;
        /** MediaStore URI of the published copy (also on partial failure, for fallbacks). */
        public Uri mediaUri;
        /** URI read back from the system after the write. */
        public Uri verifiedUri;
        /** Human-readable warnings (e.g. MIUI per-SIM key kept its own value). */
        public final List<String> warnings = new ArrayList<>();
        public final StringBuilder log = new StringBuilder();

        public String fullReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("== Sound Setter report ==\n");
            sb.append("Result: ").append(success ? "SUCCESS" : "FAILED").append('\n');
            sb.append("Step: ").append(stepId.isEmpty() ? "-" : stepId).append('\n');
            sb.append("Headline: ").append(headline).append('\n');
            sb.append("Message: ").append(message).append('\n');
            if (!hint.isEmpty()) sb.append("Hint: ").append(hint).append('\n');
            if (mediaUri != null) sb.append("Published file: ").append(mediaUri).append('\n');
            if (verifiedUri != null) sb.append("Read back: ").append(verifiedUri).append('\n');
            for (String w : warnings) sb.append("Warning: ").append(w).append('\n');
            sb.append('\n').append("---- pipeline log ----\n").append(log);
            if (error != null) {
                sb.append("\n---- exception ----\n");
                sb.append(Log.getStackTraceString(error));
            }
            return sb.toString();
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private SoundApplier() {}

    public static void applyAsync(final Context ctx, final SoundEvent event, final Uri source,
                                  final String sourceName, final Listener listener) {
        final Handler main = new Handler(Looper.getMainLooper());
        final Context app = ctx.getApplicationContext();
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                final ApplyResult result;
                try {
                    result = apply(app, event, source, sourceName, new Listener() {
                        @Override public void onStep(String stepLabel) { listener.onStep(stepLabel); }
                        @Override public void onResult(ApplyResult r) { /* not used internally */ }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "apply crashed", t);
                    ApplyResult r = new ApplyResult();
                    r.stepId = "unexpected";
                    r.headline = "Unexpected error";
                    r.message = "The pipeline crashed before the step could finish.";
                    r.hint = "Please report the technical details below.";
                    r.error = t;
                    main.post(new Runnable() { @Override public void run() { listener.onResult(r); } });
                    return;
                }
                main.post(new Runnable() { @Override public void run() { listener.onResult(result); } });
            }
        });
    }

    // ------------------------------------------------------------------ pipeline

    private static ApplyResult apply(Context ctx, SoundEvent event, Uri source, String sourceName,
                                     Listener steps) {
        ApplyResult r = new ApplyResult();
        ContentResolver cr = ctx.getContentResolver();

        step(steps, r, "Reading the selected file");
        log(r, "Event: " + event.title + " (settings key: " + event.settingsKey
                + (event.miuiSlotKey != null ? ", MIUI key: " + event.miuiSlotKey : "") + ")");
        log(r, "Source: " + source + (sourceName != null ? " (" + sourceName + ")" : ""));
        if (source == null) {
            return fail(r, "source", "No file selected", "The file picker did not return a file.",
                    "Pick the sound again.", null);
        }
        if (!isXiaomi(ctx) && Settings.System.getString(cr, SoundEvent.MIUI_SLOT_1) == null
                && Settings.System.getString(cr, SoundEvent.MIUI_SLOT_2) == null) {
            log(r, "Device is not a MIUI/HyperOS per-SIM device - global keys only.");
        }

        // 1. Format detection ------------------------------------------------
        step(steps, r, "Detecting the audio format");
        String mime = resolveMime(ctx, source, sourceName);
        if (mime == null) {
            return fail(r, "format", "Unsupported or unknown audio format",
                    "Could not determine the format of \"" + safeName(sourceName, source)
                            + "\". The system only accepts audio files it can identify.",
                    "Supported: MP3, WAV, OGG/OGG Opus, FLAC, M4A/AAC, AMR, 3GP, MIDI, AIFF "
                            + "(anything Android reports as audio/*). Re-encode the file and try again.",
                    null);
        }
        log(r, "Format: " + mime);

        // 2. Publish into the system media store -----------------------------
        step(steps, r, "Publishing the file into system sound storage");
        Uri item = publish(ctx, event, source, sourceName, mime, r);
        if (item == null) {
            // publish() already filled the failure details
            if (r.stepId.isEmpty()) {
                fail(r, "publish", "Could not publish the file",
                        "The file could not be stored in the " + event.mediaDir + " collection.",
                        "Check that shared storage is mounted and free space is available.", null);
            }
            return r;
        }
        r.mediaUri = item;
        log(r, "Published as: " + item);

        // MediaProvider must be able to resolve the MIME, otherwise
        // RingtoneManager.setActualDefaultRingtoneUri ignores the write silently.
        String publishedMime = null;
        for (int attempt = 0; attempt < 3 && publishedMime == null; attempt++) {
            try {
                publishedMime = cr.getType(item);
            } catch (Throwable ignored) {
            }
            if (publishedMime == null) sleep(300);
        }
        if (publishedMime == null) {
            return fail(r, "media-visibility", "System cannot see the published file",
                    "MediaProvider did not index the new file yet, so the system would silently "
                            + "ignore it (Android's known silent no-op in RingtoneManager).",
                    "Wait a few seconds, make sure storage is mounted, then apply again. "
                            + "On MIUI also make sure the file is not in a hidden folder.", null);
        }
        log(r, "MediaProvider MIME: " + publishedMime);

        // 3. Permission ------------------------------------------------------
        step(steps, r, "Checking system write permission");
        if (!Settings.System.canWrite(ctx)) {
            return fail(r, "permission", "Missing \"Modify system settings\" permission",
                    "Android blocks every write to the default sound settings without it "
                            + "(on Xiaomi it is called \"Modify system settings\" as well).",
                    "Tap Grant and enable the switch, then apply again.", null);
        }
        log(r, "Settings.System.canWrite = true");

        // 4. Write -----------------------------------------------------------
        step(steps, r, "Writing the system setting");
        boolean wroteAnything = writeSetting(ctx, event, item, r);
        if (!wroteAnything) {
            return fail(r, "system-write", "The system rejected the write",
                    "Writing the default sound setting threw an error (see details).",
                    "Re-grant \"Modify system settings\", then apply again.", r.error);
        }

        // 5. Verify ----------------------------------------------------------
        step(steps, r, "Verifying the setting was accepted");
        Uri actual = readCurrent(ctx, event);
        boolean verified = urisMatch(actual, item);
        log(r, "Read back after write: " + actual);

        if (!verified) {
            log(r, "First attempt not visible - retrying with direct Settings write");
            sleep(400);
            try {
                Settings.System.putString(cr, event.settingsKey, item.toString());
                log(r, "Direct Settings.System.putString(" + event.settingsKey + ") done");
            } catch (Throwable t) {
                log(r, "Direct Settings write failed: " + t);
            }
            if (event.ringtoneType >= 0) {
                try {
                    RingtoneManager.setActualDefaultRingtoneUri(ctx, event.ringtoneType, item);
                    log(r, "Second RingtoneManager attempt done");
                } catch (Throwable t) {
                    log(r, "Second RingtoneManager attempt failed: " + t);
                }
            }
            sleep(400);
            actual = readCurrent(ctx, event);
            verified = urisMatch(actual, item);
            log(r, "Read back after retry: " + actual);
        }

        if (!verified && ShizukuBridge.isAuthorized()) {
            step(steps, r, "Normal write refused - retrying with elevated (ADB) access");
            verified = shizukuForceSet(ctx, event, item, r);
        }

        if (!verified) {
            StringBuilder msg = new StringBuilder();
            msg.append("Android accepted the file into MediaStore, but the system setting still reads \"")
                    .append(actual == null ? "none" : actual.toString())
                    .append("\" instead of \"").append(item).append("\".");
            String hint = buildFailureHint(ctx, actual, item);
            return fail(r, "verify", "The system refused to change the default sound",
                    msg.toString(), hint, null);
        }
        r.verifiedUri = actual;
        log(r, "VERIFIED: setting points at the published file");

        // 6. MIUI per-SIM keys ----------------------------------------------
        step(steps, r, "Updating device-specific keys (if any)");
        verifyAndFixSlots(ctx, event, item, r);

        // 7. Readability ------------------------------------------------------
        step(steps, r, "Checking the file is readable by the system");
        String readable = checkReadable(ctx, item);
        if (readable != null) r.warnings.add(readable);

        // Success -------------------------------------------------------------
        r.success = true;
        r.stepId = "done";
        r.headline = event.title + " updated";
        r.message = "\"" + describeUri(ctx, item) + "\" is now the system default "
                + event.title.toLowerCase(Locale.US) + " and Android confirmed the change.";
        // Clean the previous copy first (it still needs the saved URI), then remember the new one.
        cleanupPreviousOwned(ctx, event, item);
        rememberOwnedUri(ctx, event, item);
        return r;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean writeSetting(Context ctx, SoundEvent event, Uri item, ApplyResult r) {
        ContentResolver cr = ctx.getContentResolver();
        boolean any = false;

        if (event.ringtoneType >= 0) {
            try {
                RingtoneManager.setActualDefaultRingtoneUri(ctx, event.ringtoneType, item);
                log(r, "RingtoneManager.setActualDefaultRingtoneUri(" + event.settingsKey + ") returned normally");
                any = true;
            } catch (SecurityException se) {
                r.error = se;
                log(r, "RingtoneManager threw SecurityException: " + se);
            } catch (Throwable t) {
                r.error = t;
                log(r, "RingtoneManager threw: " + t);
            }
        }
        // SIM rows (ringtoneType < 0) and belt-and-braces on failures: direct write.
        if (event.ringtoneType < 0 || !any) {
            try {
                Settings.System.putString(cr, event.settingsKey, item.toString());
                log(r, "Settings.System.putString(" + event.settingsKey + ") done");
                any = true;
            } catch (Throwable t) {
                r.error = t;
                log(r, "Settings.System.putString(" + event.settingsKey + ") failed: " + t);
            }
        }

        // Xiaomi per-SIM keys: they override the global ringtone when present.
        for (String key : slotKeysFor(ctx, event)) {
            String old = null;
            try {
                old = Settings.System.getString(cr, key);
            } catch (Throwable ignored) {
            }
            if (old == null && !isXiaomi(ctx)) {
                log(r, "Key " + key + " does not exist on this device - skipped");
                continue;
            }
            try {
                Settings.System.putString(cr, key, item.toString());
                log(r, "Wrote MIUI/HyperOS key " + key + (old != null ? " (was: " + old + ")" : ""));
                any = true;
            } catch (Throwable t) {
                log(r, "Could not write key " + key + ": " + t);
            }
        }
        return any;
    }

    private static List<String> slotKeysFor(Context ctx, SoundEvent event) {
        List<String> keys = new ArrayList<>();
        boolean ringtoneLike = event.ringtoneType == RingtoneManager.TYPE_RINGTONE
                || event.miuiSlotKey != null;
        if (ringtoneLike) {
            keys.add(SoundEvent.MIUI_SLOT_1);
            keys.add(SoundEvent.MIUI_SLOT_2);
        }
        if (event.miuiSlotKey != null && !keys.contains(event.miuiSlotKey)) {
            keys.add(event.miuiSlotKey);
        }
        return keys;
    }

    /** On Xiaomi the global ringtone can be verified while per-SIM keys keep the old tone. */
    private static void verifyAndFixSlots(Context ctx, SoundEvent event, Uri item, ApplyResult r) {
        if (!isXiaomi(ctx) && event.miuiSlotKey == null) {
            log(r, "No per-SIM keys to verify");
            return;
        }
        ContentResolver cr = ctx.getContentResolver();
        List<String> keys = slotKeysFor(ctx, event);
        List<String> stale = new ArrayList<>();
        for (String key : keys) {
            String value = null;
            try {
                value = Settings.System.getString(cr, key);
            } catch (Throwable t) {
                log(r, "Cannot read " + key + ": " + t);
                continue;
            }
            if (value == null) {
                log(r, key + ": not set (device derives it from the global ringtone)");
                continue;
            }
            if (valueEqualsUri(value, item)) {
                log(r, key + ": verified");
            } else {
                log(r, key + ": still \"" + value + "\"");
                stale.add(key);
            }
        }
        if (stale.isEmpty()) return;

        // Try to fix stale slot keys through the normal write path first, then shell.
        for (int i = stale.size() - 1; i >= 0; i--) {
            String key = stale.get(i);
            try {
                Settings.System.putString(cr, key, item.toString());
                String now = Settings.System.getString(cr, key);
                log(r, "Rewrote " + key + " -> " + (valueEqualsUri(now, item) ? "verified" : "still " + now));
                if (valueEqualsUri(now, item)) stale.remove(i);
            } catch (Throwable t) {
                log(r, "Rewrite of " + key + " failed: " + t);
            }
        }
        if (!stale.isEmpty() && ShizukuBridge.isAuthorized()) {
            List<String[]> cmds = new ArrayList<>();
            for (String key : stale) cmds.add(new String[] {"settings", "put", "system", key, item.toString()});
            runShell(ctx, cmds, r);
            for (int i = stale.size() - 1; i >= 0; i--) {
                String key = stale.get(i);
                String now = null;
                try {
                    now = Settings.System.getString(cr, key);
                } catch (Throwable ignored) {
                }
                if (valueEqualsUri(now, item)) stale.remove(i);
            }
        }
        for (String key : stale) {
            r.warnings.add("Xiaomi kept its own value for " + key + " (a theme/MIUI sound setting). "
                    + "Calls on that SIM may still use the old tone. Fix: apply again with Elevated "
                    + "(ADB) mode, or pick the file once in Settings > Sound & vibration.");
        }
    }

    /** Optional Shizuku tier: settings put system <key> <uri> as shell (uid 2000). */
    private static boolean shizukuForceSet(Context ctx, SoundEvent event, Uri item, ApplyResult r) {
        if (!ShizukuBridge.isAuthorized()) {
            log(r, "Elevated (ADB) mode is not active - skipping shell write");
            return false;
        }
        log(r, "Using elevated (ADB/Shizuku) write as shell uid 2000");
        List<String[]> cmds = new ArrayList<>();
        if (event.settingsKey != null) {
            cmds.add(new String[] {"settings", "put", "system", event.settingsKey, item.toString()});
        }
        for (String key : slotKeysFor(ctx, event)) {
            if (isXiaomi(ctx) || Settings.System.getString(ctx.getContentResolver(), key) != null) {
                cmds.add(new String[] {"settings", "put", "system", key, item.toString()});
            }
        }
        boolean ran = runShell(ctx, cmds, r);
        if (!ran) return false;
        sleep(400);
        Uri actual = readCurrent(ctx, event);
        log(r, "Read back after elevated write: " + actual);
        return urisMatch(actual, item);
    }

    private static boolean runShell(Context ctx, List<String[]> cmds, ApplyResult r) {
        boolean ranAny = false;
        for (String[] cmd : cmds) {
            StringBuilder shown = new StringBuilder("$");
            for (String c : cmd) shown.append(' ').append(c);
            try {
                ShizukuBridge.ExecResult res = ShizukuBridge.exec(cmd);
                log(r, shown.append(" -> ").append(res.describe()).toString());
                if (res.code == 0) ranAny = true;
            } catch (Throwable t) {
                log(r, shown.append(" -> FAILED: ").append(t).toString());
            }
        }
        return ranAny;
    }

    // ------------------------------------------------------------------ publish

    private static Uri publish(Context ctx, SoundEvent event, Uri source, String sourceName,
                               String mime, ApplyResult r) {
        String baseName = safeName(sourceName, source);
        if (baseName == null || baseName.trim().isEmpty()) baseName = "sound";
        if (!baseName.contains(".")) {
            String ext = extensionForMime(mime);
            if (ext != null) baseName = baseName + "." + ext;
        }

        if (Build.VERSION.SDK_INT >= 29) {
            return publishQ(ctx, event, source, baseName, mime, r);
        }
        return publishLegacy(ctx, event, source, baseName, mime, r);
    }

    private static Uri publishQ(Context ctx, SoundEvent event, Uri source, String baseName,
                                String mime, ApplyResult r) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Audio.Media.DISPLAY_NAME, baseName);
            v.put(MediaStore.Audio.Media.MIME_TYPE, mime);
            v.put(MediaStore.Audio.Media.RELATIVE_PATH, event.mediaDir + "/");
            v.put(MediaStore.Audio.Media.IS_PENDING, 1);
            putTypeFlags(v, event);
            Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri item = cr.insert(collection, v);
            if (item == null) {
                fail(r, "media-store-insert", "MediaStore rejected the new file",
                        "insert() into " + collection + " returned null.",
                        "Storage may be read-only or full; also check that no device manager "
                                + "policy blocks writing to shared storage.", null);
                return null;
            }
            OutputStream os = null;
            try {
                os = cr.openOutputStream(item, "w");
                if (os == null) throw new IOException("openOutputStream returned null");
                copy(ctx, source, os);
            } catch (Throwable t) {
                try {
                    cr.delete(item, null, null);
                } catch (Throwable ignored) {
                }
                fail(r, "copy-file", "Copying the file failed",
                        "Could not write the bytes into " + item + ": " + t,
                        "The source may have been revoked or the storage is full.", t);
                return null;
            } finally {
                if (os != null) try { os.close(); } catch (IOException ignored) {}
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Audio.Media.IS_PENDING, 0);
            cr.update(item, done, null, null);
            return item;
        } catch (Throwable t) {
            fail(r, "media-store-insert", "MediaStore publish failed", "" + t,
                    "Check storage space and permissions.", t);
            return null;
        }
    }

    private static Uri publishLegacy(Context ctx, SoundEvent event, Uri source, String baseName,
                                     String mime, ApplyResult r) {
        ContentResolver cr = ctx.getContentResolver();
        try {
            if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                fail(r, "storage", "Shared storage is not mounted",
                        "getExternalStorageState() = " + Environment.getExternalStorageState(),
                        "Remount/insert storage or free space, then try again.", null);
                return null;
            }
            File dir = new File(Environment.getExternalStoragePublicDirectory(event.mediaDir), "");
            if (!dir.exists() && !dir.mkdirs()) {
                fail(r, "storage", "Could not create the " + event.mediaDir + " folder",
                        dir.getAbsolutePath() + " does not exist and could not be created.",
                        "Grant the storage permission (Settings > Apps > Sound Setter > Permissions) "
                                + "and check the folder manually.", null);
                return null;
            }
            File out = uniqueFile(dir, baseName);
            InputStream in = null;
            FileOutputStream fos = null;
            try {
                in = openSource(ctx, source);
                fos = new FileOutputStream(out);
                copy(in, fos);
            } catch (Throwable t) {
                fail(r, "copy-file", "Copying the file failed",
                        "Could not write " + out.getAbsolutePath() + ": " + t,
                        "Grant the storage permission and check free space.", t);
                return null;
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
                if (fos != null) try { fos.close(); } catch (IOException ignored) {}
            }

            final CountDownLatch latch = new CountDownLatch(1);
            MediaScannerConnection.scanFile(ctx,
                    new String[] {out.getAbsolutePath()}, new String[] {mime},
                    new MediaScannerConnection.OnScanCompletedListener() {
                        @Override public void onScanCompleted(String path, Uri uri) { latch.countDown(); }
                    });
            try {
                latch.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            Uri item = findByDataPath(ctx, out.getAbsolutePath());
            if (item == null) {
                // Classic fallback: let MediaProvider index the existing file.
                try {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Audio.Media.DATA, out.getAbsolutePath());
                    v.put(MediaStore.Audio.Media.DISPLAY_NAME, out.getName());
                    v.put(MediaStore.Audio.Media.MIME_TYPE, mime);
                    v.put(MediaStore.Audio.Media.TITLE, stripExtension(out.getName()));
                    v.put(MediaStore.Audio.Media.SIZE, out.length());
                    v.put(MediaStore.Audio.Media.DATE_ADDED, out.lastModified() / 1000);
                    putTypeFlags(v, event);
                    item = cr.insert(MediaStore.Audio.Media.getContentUriForPath(out.getAbsolutePath()), v);
                } catch (Throwable t) {
                    log(r, "Classic MediaStore insert failed: " + t);
                }
            }
            if (item == null) {
                fail(r, "media-store-index", "The media scanner did not index the file",
                        "Neither scanFile() nor a manual MediaStore insert produced an entry for "
                                + out.getAbsolutePath() + ".",
                        "Check that the file exists and the storage permission is granted, then retry.",
                        null);
                return null;
            }
            return item;
        } catch (Throwable t) {
            fail(r, "publish", "Publishing the file failed", "" + t, "Check storage and permissions.", t);
            return null;
        }
    }

    private static void putTypeFlags(ContentValues v, SoundEvent event) {
        int type = event.ringtoneType;
        v.put(MediaStore.Audio.Media.IS_RINGTONE, type == RingtoneManager.TYPE_RINGTONE || type < 0 ? 1 : 0);
        v.put(MediaStore.Audio.Media.IS_NOTIFICATION, type == RingtoneManager.TYPE_NOTIFICATION ? 1 : 0);
        v.put(MediaStore.Audio.Media.IS_ALARM, type == RingtoneManager.TYPE_ALARM ? 1 : 0);
        v.put(MediaStore.Audio.Media.IS_MUSIC, 0);
    }

    private static Uri findByDataPath(Context ctx, String path) {
        try {
            Cursor c = ctx.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[] {MediaStore.Audio.Media._ID},
                    MediaStore.Audio.Media.DATA + "=?",
                    new String[] {path}, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                c.getLong(0));
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------ mime

    private static final Map<String, String> MIME_BY_EXT = new HashMap<>();
    static {
        MIME_BY_EXT.put("mp3", "audio/mpeg");
        MIME_BY_EXT.put("wav", "audio/wav");
        MIME_BY_EXT.put("wave", "audio/wav");
        MIME_BY_EXT.put("ogg", "audio/ogg");
        MIME_BY_EXT.put("oga", "audio/ogg");
        MIME_BY_EXT.put("opus", "audio/ogg");
        MIME_BY_EXT.put("flac", "audio/flac");
        MIME_BY_EXT.put("m4a", "audio/mp4");
        MIME_BY_EXT.put("m4b", "audio/mp4");
        MIME_BY_EXT.put("mp4", "audio/mp4");
        MIME_BY_EXT.put("aac", "audio/aac");
        MIME_BY_EXT.put("amr", "audio/amr");
        MIME_BY_EXT.put("awb", "audio/amr-wb");
        MIME_BY_EXT.put("3gp", "audio/3gpp");
        MIME_BY_EXT.put("3gpp", "audio/3gpp");
        MIME_BY_EXT.put("3ga", "audio/3gpp");
        MIME_BY_EXT.put("mid", "audio/midi");
        MIME_BY_EXT.put("midi", "audio/midi");
        MIME_BY_EXT.put("aif", "audio/aiff");
        MIME_BY_EXT.put("aiff", "audio/aiff");
        MIME_BY_EXT.put("mka", "audio/x-mka");
        MIME_BY_EXT.put("ape", "audio/x-ape");
        MIME_BY_EXT.put("ac3", "audio/ac3");
        MIME_BY_EXT.put("dts", "audio/dts");
        MIME_BY_EXT.put("m4r", "audio/mp4");
    }

    private static boolean isWhitelistedMime(String m) {
        if (m == null) return false;
        m = m.toLowerCase(Locale.US);
        return m.startsWith("audio/") || m.startsWith("video/")
                || m.equals("application/ogg") || m.equals("application/x-flac")
                || m.equals("application/mp4");
    }

    static String resolveMime(Context ctx, Uri source, String sourceName) {
        String fromProvider = null;
        try {
            fromProvider = ctx.getContentResolver().getType(source);
            if (fromProvider != null) fromProvider = fromProvider.trim().toLowerCase(Locale.US);
            if (fromProvider != null && (fromProvider.isEmpty()
                    || fromProvider.equals("application/octet-stream"))) {
                fromProvider = null;
            }
        } catch (Throwable ignored) {
        }
        if (isWhitelistedMime(fromProvider)) return fromProvider;

        String name = sourceName;
        if (name == null) name = source.getLastPathSegment();
        String ext = extensionOf(name);
        if (ext != null) {
            String byExt = MIME_BY_EXT.get(ext);
            if (byExt != null) return byExt;
        }
        String sniffed = sniffMime(ctx, source);
        if (sniffed != null) return sniffed;
        if (isWhitelistedMime(fromProvider)) return fromProvider;
        return null;
    }

    /** Byte-level detection for files whose provider reports no usable MIME. */
    private static String sniffMime(Context ctx, Uri source) {
        InputStream in = null;
        try {
            in = openSource(ctx, source);
            byte[] head = new byte[16];
            int got = 0;
            while (got < head.length) {
                int n = in.read(head, got, head.length - got);
                if (n < 0) break;
                got += n;
            }
            if (got < 4) return null;
            String ascii = new String(head, 0, Math.min(got, 12), "ISO-8859-1");
            if (ascii.startsWith("ID3") || (head[0] == (byte) 0xFF && (head[1] & 0xE0) == 0xE0)) {
                return "audio/mpeg";
            }
            if (ascii.startsWith("RIFF") && ascii.contains("WAVE")) return "audio/wav";
            if (ascii.startsWith("OggS")) return "audio/ogg";
            if (ascii.startsWith("fLaC")) return "audio/flac";
            if (ascii.startsWith("#!AMR")) return "audio/amr";
            if (got >= 11 && ascii.startsWith("FORM") && ascii.substring(8).startsWith("AIF")) {
                return "audio/aiff";
            }
            if (got >= 8 && "ftyp".equals(ascii.substring(4, 8))) return "audio/mp4";
            if (head[0] == 0x1A && head[1] == 0x45 && head[2] == (byte) 0xDF && head[3] == (byte) 0xA3) {
                return "audio/ogg";
            }
            if (got >= 4 && head[0] == 'M' && head[1] == 'T' && head[2] == 'h' && head[3] == 'd') {
                return "audio/midi";
            }
        } catch (Throwable ignored) {
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
        return null;
    }

    private static String extensionForMime(String mime) {
        if (mime == null) return null;
        switch (mime) {
            case "audio/mpeg": return "mp3";
            case "audio/wav": return "wav";
            case "audio/ogg": return "ogg";
            case "audio/flac": return "flac";
            case "audio/mp4": return "m4a";
            case "audio/aac": return "aac";
            case "audio/amr": return "amr";
            case "audio/3gpp": return "3gp";
            case "audio/midi": return "mid";
            default: return null;
        }
    }

    private static String extensionOf(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ------------------------------------------------------------------ io

    private static InputStream openSource(Context ctx, Uri source) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(source);
        if (in == null) throw new IOException("openInputStream returned null for " + source);
        return in;
    }

    private static void copy(Context ctx, Uri source, OutputStream out) throws IOException {
        InputStream in = openSource(ctx, source);
        try {
            copy(in, out);
        } finally {
            in.close();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        out.flush();
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = stripExtension(name);
        String ext = extensionOf(name);
        for (int i = 1; i < 1000; i++) {
            File cand = new File(dir, ext == null ? base + "-" + i : base + "-" + i + "." + ext);
            if (!cand.exists()) return cand;
        }
        return new File(dir, "sound-" + System.currentTimeMillis() + (ext == null ? "" : "." + ext));
    }

    // ------------------------------------------------------------------ verify / read

    static Uri readCurrent(Context ctx, SoundEvent event) {
        try {
            if (event.ringtoneType >= 0) {
                return RingtoneManager.getActualDefaultRingtoneUri(ctx, event.ringtoneType);
            }
            String s = Settings.System.getString(ctx.getContentResolver(), event.settingsKey);
            return s == null || s.isEmpty() ? null : Uri.parse(s);
        } catch (Throwable t) {
            Log.w(TAG, "readCurrent failed", t);
            return null;
        }
    }

    static boolean urisMatch(Uri a, Uri b) {
        if (a == null || b == null) return a == b;
        return normalizeUriString(a.toString()).equals(normalizeUriString(b.toString()));
    }

    private static boolean valueEqualsUri(String value, Uri item) {
        if (value == null || item == null) return false;
        return normalizeUriString(value).equals(normalizeUriString(item.toString()));
    }

    /** Strips per-user prefixes (content://0@...) and external volume aliases. */
    static String normalizeUriString(String s) {
        if (s == null) return "";
        s = s.replaceFirst("^content://\\d+@", "content://");
        s = s.replace("/external_primary/", "/external/");
        if (s.startsWith("file://")) {
            try {
                s = Uri.parse(s).getPath();
            } catch (Throwable ignored) {
            }
        }
        return s;
    }

    private static String checkReadable(Context ctx, Uri item) {
        try {
            android.content.res.AssetFileDescriptor afd =
                    ctx.getContentResolver().openAssetFileDescriptor(item, "r");
            if (afd == null) {
                return "The published file could not be opened for reading by this app; "
                        + "the system may not be able to play it either.";
            }
            afd.close();
            return null;
        } catch (Throwable t) {
            return "The published file could not be opened for reading (" + t.getMessage()
                    + "); playback may fail.";
        }
    }

    static String describeUri(Context ctx, Uri uri) {
        if (uri == null) return "None / built-in default";
        String s = uri.toString();
        if (s.startsWith("file://")) {
            String p = Uri.parse(s).getPath();
            return p != null ? "File: " + p : s;
        }
        if (s.startsWith("content://settings/")) return "System built-in default";
        try {
            Cursor c = ctx.getContentResolver().query(uri,
                    new String[] {MediaStore.Audio.AudioColumns.DISPLAY_NAME,
                            MediaStore.Audio.AudioColumns.TITLE}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        String disp = c.getString(0);
                        String title = c.getString(1);
                        if (disp != null) {
                            return (title != null && !title.equals(disp))
                                    ? disp + "  (" + title + ")" : disp;
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (SecurityException se) {
            return "Sound file (name hidden - grant \"Read media audio\" permission)";
        } catch (Throwable ignored) {
        }
        String last = uri.getLastPathSegment();
        if (last != null && !last.isEmpty()) {
            try {
                last = Uri.decode(last);
            } catch (Throwable ignored) {
            }
            return last.matches("\\d+") ? "Sound file #" + last : last;
        }
        return s;
    }

    // ------------------------------------------------------------------ ownership

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("sound_setter", Context.MODE_PRIVATE);
    }

    private static void rememberOwnedUri(Context ctx, SoundEvent event, Uri uri) {
        prefs(ctx).edit().putString("owned_" + event.id, uri.toString()).apply();
    }

    /** Removes the previous copy this app made for the same event (never others' files). */
    private static void cleanupPreviousOwned(Context ctx, SoundEvent event, Uri keep) {
        String old = prefs(ctx).getString("owned_" + event.id, null);
        if (old == null) return;
        Uri oldUri = Uri.parse(old);
        if (urisMatch(oldUri, keep)) return;
        try {
            int n = ctx.getContentResolver().delete(oldUri, null, null);
            Log.i(TAG, "Cleaned previous owned file " + oldUri + " (rows=" + n + ")");
        } catch (Throwable t) {
            Log.i(TAG, "Could not clean previous file " + oldUri + ": " + t);
        }
    }

    // ------------------------------------------------------------------ misc

    static boolean isXiaomi(Context ctx) {
        String m = safeLower(Build.MANUFACTURER);
        String b = safeLower(Build.BRAND);
        String model = safeLower(Build.MODEL);
        String fingerprint = safeLower(Build.FINGERPRINT) + " " + safeLower(Build.DISPLAY)
                + " " + safeLower(Build.PRODUCT);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")
                || b.contains("xiaomi") || b.contains("redmi") || b.contains("poco")
                || model.contains("miui")
                || fingerprint.contains("miui") || fingerprint.contains("hyperos");
    }

    private static String safeLower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
    }

    static String safeName(String sourceName, Uri source) {
        if (sourceName != null && !sourceName.trim().isEmpty()) return sourceName.trim();
        if (source != null) {
            String last = source.getLastPathSegment();
            if (last != null) {
                try {
                    last = Uri.decode(last);
                } catch (Throwable ignored) {
                }
                if (!last.isEmpty() && !last.matches("\\d+")) return last;
            }
        }
        return "sound";
    }

    private static String buildFailureHint(Context ctx, Uri actual, Uri item) {
        StringBuilder h = new StringBuilder();
        if (!Settings.System.canWrite(ctx)) {
            h.append("1) Re-grant \"Modify system settings\" and try again. ");
        }
        if (isXiaomi(ctx)) {
            h.append("Xiaomi/MIUI specifics: enable \"Modify system settings\" for this app, "
                    + "make sure Battery saver is not restricting the app, then apply again. "
                    + "If the Sound & vibration page still shows the old tone, use the system "
                    + "sound picker fallback (your file is already listed there), or enable "
                    + "Elevated (ADB) mode which writes the setting directly as shell. ");
        } else {
            h.append("Re-grant \"Modify system settings\", then apply again. "
                    + "If it still fails, enable Elevated (ADB) mode (Shizuku) which writes "
                    + "the setting directly as shell. ");
        }
        if (actual == null) {
            h.append("Read-back was empty, meaning the write never landed (silent no-op).");
        }
        return h.toString();
    }

    private static void log(ApplyResult r, String line) {
        Log.i(TAG, line);
        r.log.append(line).append('\n');
    }

    private static void step(Listener steps, ApplyResult r, String label) {
        log(r, "-- step: " + label);
        if (steps != null) steps.onStep(label);
    }

    private static ApplyResult fail(ApplyResult r, String stepId, String headline, String message,
                                    String hint, Throwable t) {
        r.success = false;
        r.stepId = stepId;
        r.headline = headline;
        r.message = message;
        r.hint = hint == null ? "" : hint;
        if (t != null && r.error == null) r.error = t;
        log(r, "FAILED at '" + stepId + "': " + headline + " - " + message);
        return r;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
