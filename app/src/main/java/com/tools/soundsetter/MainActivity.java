package com.tools.soundsetter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * Single-screen UI: shows the current system sound settings, lets the user pick
 * any audio file, runs the apply pipeline with progress feedback, and reports
 * exactly which step failed and how to fix it.
 */
public class MainActivity extends Activity {

    private static final int REQ_PICK_FILE = 101;
    private static final int REQ_SYSTEM_PICKER = 102;
    private static final int REQ_STORAGE = 103;
    private static final int REQ_READ_MEDIA = 104;

    private static final int STAGE_IDLE = 0;
    private static final int STAGE_WAIT_WRITE_SETTINGS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<SoundEvent> events = new ArrayList<>();
    private final Map<String, Row> rows = new LinkedHashMap<>();

    private LinearLayout eventList;
    private TextView writeStatus;
    private Button writeButton;
    private TextView shizukuStatus;
    private AlertDialog progressDialog;
    private TextView progressLabel;

    private int stage = STAGE_IDLE;
    private SoundEvent pendingEvent;
    private MediaPlayer currentPlayer;
    private final Runnable stopPreview = new Runnable() {
        @Override public void run() { stopPreviewPlayback(); }
    };

    private static final class Row {
        final SoundEvent event;
        final TextView current;
        Row(SoundEvent event, TextView current) {
            this.event = event;
            this.current = current;
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        registerShizuku();
        maybeRequestReadPermission();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshWriteStatus();
        refreshShizukuStatus();
        refreshAllRows();

        if (stage == STAGE_WAIT_WRITE_SETTINGS) {
            if (Settings.System.canWrite(this)) {
                stage = STAGE_IDLE;
                advanceToPick();
            } else {
                stage = STAGE_IDLE;
                new AlertDialog.Builder(this)
                        .setTitle("Permission still not granted")
                        .setMessage("\"Modify system settings\" is still switched off, so Android "
                                + "will silently ignore any attempt to change the default sounds.\n\n"
                                + "Enable the switch for Sound Setter, then tap Choose file again.")
                        .setPositiveButton("Open settings again", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                stage = STAGE_WAIT_WRITE_SETTINGS;
                                openWriteSettings();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        } catch (Throwable ignored) {
        }
        dismissProgress();
        stopPreviewPlayback();
        if (handler != null) handler.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------ UI construction

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout card() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF4F6F8);
        bg.setCornerRadius(dp(12));
        ll.setBackground(bg);
        ll.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        ll.setLayoutParams(lp);
        return ll;
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));

        TextView intro = text("Set any audio file as a system sound. Changes are written to "
                + "Android's own sound settings and verified by reading them back.", 14, 0xFF555555);
        intro.setPadding(0, 0, 0, dp(12));
        root.addView(intro);

        // --- system write permission card ---
        LinearLayout permCard = card();
        permCard.addView(text("System write access", 16, 0xFF222222));
        writeStatus = text("", 13, 0xFF666666);
        writeStatus.setPadding(0, dp(6), 0, dp(6));
        permCard.addView(writeStatus);
        writeButton = new Button(this);
        writeButton.setText("Grant permission");
        writeButton.setAllCaps(false);
        writeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stage = STAGE_WAIT_WRITE_SETTINGS;
                openWriteSettings();
            }
        });
        permCard.addView(writeButton);
        root.addView(permCard);

        // --- events ---
        TextView header = text("System sound events", 16, 0xFF222222);
        header.setPadding(0, dp(4), 0, dp(6));
        root.addView(header);

        eventList = new LinearLayout(this);
        eventList.setOrientation(LinearLayout.VERTICAL);
        root.addView(eventList);

        for (SoundEvent e : collectEvents()) {
            addRow(e);
        }

        // --- elevated mode card ---
        LinearLayout shizukuCard = card();
        shizukuCard.addView(text("Elevated (ADB) mode - optional", 16, 0xFF222222));
        shizukuStatus = text("", 13, 0xFF666666);
        shizukuStatus.setPadding(0, dp(6), 0, dp(6));
        shizukuCard.addView(shizukuStatus);
        TextView shizukuHint = text("Only used when the device blocks normal writes (some "
                + "Xiaomi/MIUI and other OEM builds). Uses the free Shizuku app over wireless "
                + "debugging to write the setting directly as shell - no root. Never required "
                + "for normal use.", 12, 0xFF777777);
        shizukuCard.addView(shizukuHint);
        Button shizukuButton = new Button(this);
        shizukuButton.setText("Enable / check elevated mode");
        shizukuButton.setAllCaps(false);
        shizukuButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onElevatedModeClicked(); }
        });
        LinearLayout shizukuBtnWrap = new LinearLayout(this);
        shizukuBtnWrap.addView(shizukuButton);
        shizukuCard.addView(shizukuBtnWrap);
        root.addView(shizukuCard);

        // --- troubleshooting ---
        Button help = new Button(this);
        help.setText("Troubleshooting guide");
        help.setAllCaps(false);
        help.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showTroubleshooting(); }
        });
        root.addView(help);

        TextView footer = text("Sound Setter v1.0 - everything runs on-device, no internet "
                + "access, no external apps needed.", 12, 0xFF999999);
        footer.setPadding(0, dp(10), 0, 0);
        root.addView(footer);

        scroll.addView(root);
        setContentView(scroll);
    }

    private List<SoundEvent> collectEvents() {
        events.clear();
        for (SoundEvent e : SoundEvent.standardEvents()) events.add(e);
        ContentResolver cr = getContentResolver();
        boolean xiaomi = SoundApplier.isXiaomi(this);
        boolean slotsSeen = false;
        try {
            slotsSeen = Settings.System.getString(cr, SoundEvent.MIUI_SLOT_1) != null
                    || Settings.System.getString(cr, SoundEvent.MIUI_SLOT_2) != null;
        } catch (Throwable ignored) {
        }
        if (xiaomi || slotsSeen) {
            for (SoundEvent e : SoundEvent.miuiSimEvents()) events.add(e);
        }
        return events;
    }

    private void addRow(final SoundEvent e) {
        LinearLayout card = card();

        TextView title = text(e.title, 16, 0xFF222222);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);

        TextView desc = text(e.description, 12, 0xFF777777);
        desc.setPadding(0, dp(2), 0, dp(8));
        card.addView(desc);

        TextView current = text("Current: …", 13, 0xFF1565C0);
        current.setPadding(0, 0, 0, dp(8));
        card.addView(current);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button choose = new Button(this);
        choose.setText("Choose file");
        choose.setAllCaps(false);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = dp(6);
        choose.setLayoutParams(lp1);
        choose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onChooseClicked(e); }
        });
        buttons.addView(choose);

        Button preview = new Button(this);
        preview.setText("\u25B6 Preview");
        preview.setAllCaps(false);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = dp(6);
        preview.setLayoutParams(lp2);
        preview.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onPreviewClicked(e); }
        });
        buttons.addView(preview);

        card.addView(buttons);
        eventList.addView(card);
        rows.put(e.id, new Row(e, current));
    }

    // ------------------------------------------------------------ status refresh

    private void refreshWriteStatus() {
        boolean can = Settings.System.canWrite(this);
        if (can) {
            writeStatus.setText("Granted - this app can change system sound settings \u2713");
            writeStatus.setTextColor(0xFF2E7D32);
            writeButton.setText("Re-check");
        } else {
            writeStatus.setText("Not granted - Android will block every change. Required.");
            writeStatus.setTextColor(0xFFC62828);
            writeButton.setText("Grant permission");
        }
    }

    private void refreshAllRows() {
        for (Row row : rows.values()) {
            row.current.setText("Current: " + currentForDisplay(row.event));
        }
    }

    /** Reads the current system settings for the event (incl. MIUI per-SIM keys). */
    private String currentForDisplay(SoundEvent e) {
        try {
            if (e.miuiSlotKey != null) {
                String v = Settings.System.getString(getContentResolver(), e.miuiSlotKey);
                if (v == null || v.isEmpty()) {
                    return "Not set separately - follows the global ringtone";
                }
                return SoundApplier.describeUri(this, Uri.parse(v)) + "  (MIUI per-SIM key)";
            }
            Uri uri = SoundApplier.readCurrent(this, e);
            return SoundApplier.describeUri(this, uri);
        } catch (Throwable t) {
            return "Unavailable: " + t.getMessage();
        }
    }

    private Uri currentUri(SoundEvent e) {
        try {
            if (e.miuiSlotKey != null) {
                String v = Settings.System.getString(getContentResolver(), e.miuiSlotKey);
                if (v != null && !v.isEmpty()) return Uri.parse(v);
            }
            return SoundApplier.readCurrent(this, e);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ picking + pipeline

    private void onChooseClicked(SoundEvent e) {
        pendingEvent = e;
        if (!Settings.System.canWrite(this)) {
            stage = STAGE_WAIT_WRITE_SETTINGS;
            toast("Enable \"Modify system settings\" for Sound Setter, then pick your file.");
            openWriteSettings();
            return;
        }
        advanceToPick();
    }

    private void openWriteSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Throwable t2) {
                toast("Open Settings > Apps > Sound Setter > Modify system settings manually.");
            }
        }
    }

    private void advanceToPick() {
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            stage = STAGE_IDLE;
            new AlertDialog.Builder(this)
                    .setTitle("Storage permission needed")
                    .setMessage("On Android 9 and below the file must be copied into the public "
                            + "Ringtones/Notifications folder, which needs the Storage permission.")
                    .setPositiveButton("Grant", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            requestPermissions(
                                    new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE},
                                    REQ_STORAGE);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        stage = STAGE_IDLE;
        openPicker();
    }

    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        // "*/*": every file is selectable so no supported format can be hidden by a
        // misbehaving document provider; the pipeline validates the real format.
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK_FILE);
            return;
        } catch (Throwable ignored) {
        }
        try {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("*/*");
            startActivityForResult(g, REQ_PICK_FILE);
        } catch (Throwable t) {
            new AlertDialog.Builder(this)
                    .setTitle("No file picker available")
                    .setMessage("This device has no document provider to choose files with ("
                            + t.getMessage() + ").")
                    .setPositiveButton("Close", null)
                    .show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FILE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
            }
            if (pendingEvent == null) {
                toast("Internal error: no event selected.");
                return;
            }
            startPipeline(pendingEvent, uri, queryDisplayName(uri));
            return;
        }
        if (requestCode == REQ_SYSTEM_PICKER) {
            if (resultCode == RESULT_OK && data != null) {
                Uri picked = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                refreshAllRows();
                toast("System sound picker applied: "
                        + SoundApplier.describeUri(this, picked));
            } else {
                refreshAllRows();
            }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openPicker();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Storage permission denied")
                        .setMessage("Without it the file cannot be placed in the system "
                                + "sound folder on this Android version.")
                        .setPositiveButton("Open app settings", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + getPackageName())));
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }
        // REQ_READ_MEDIA denial only hides sound file names - setting still works.
    }

    private void maybeRequestReadPermission() {
        try {
            String perm = Build.VERSION.SDK_INT >= 33
                    ? Manifest.permission.READ_MEDIA_AUDIO
                    : Manifest.permission.READ_EXTERNAL_STORAGE;
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] {perm}, REQ_READ_MEDIA);
            }
        } catch (Throwable ignored) {
        }
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) return n;
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    // ------------------------------------------------------------ pipeline UI

    private void startPipeline(final SoundEvent e, Uri uri, String name) {
        showProgress("Starting\u2026");
        SoundApplier.applyAsync(this, e, uri, name, new SoundApplier.Listener() {
            @Override public void onStep(final String stepLabel) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (progressLabel != null) progressLabel.setText(stepLabel);
                    }
                });
            }

            @Override public void onResult(SoundApplier.ApplyResult r) {
                dismissProgress();
                handleResult(e, r);
            }
        });
    }

    private void showProgress(String label) {
        dismissProgress();
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(24), dp(16), dp(24), dp(8));
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this);
        bar.setIndeterminate(true);
        LinearLayout barWrap = new LinearLayout(this);
        barWrap.setGravity(Gravity.CENTER_HORIZONTAL);
        barWrap.addView(bar);
        ll.addView(barWrap);
        progressLabel = new TextView(this);
        progressLabel.setText(label);
        progressLabel.setPadding(0, dp(12), 0, 0);
        progressLabel.setGravity(Gravity.CENTER);
        ll.addView(progressLabel);
        progressDialog = new AlertDialog.Builder(this)
                .setTitle("Applying sound\u2026")
                .setView(ll)
                .setCancelable(false)
                .show();
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Throwable ignored) {
            }
            progressDialog = null;
            progressLabel = null;
        }
    }

    private void handleResult(final SoundEvent e, SoundApplier.ApplyResult r) {
        refreshAllRows();
        if (r.success) {
            StringBuilder msg = new StringBuilder(r.message);
            if (!r.warnings.isEmpty()) {
                msg.append("\n\nNotes:");
                for (String w : r.warnings) msg.append("\n\u2022 ").append(w);
            }
            if (SoundApplier.isXiaomi(this)) {
                msg.append("\n\nXiaomi/MIUI: if the Sound & vibration page still shows the old "
                        + "tone, open it once and re-select the new entry - MIUI caches its "
                        + "sound list.");
            }
            msg.append("\n\n").append("Stored in: ").append(e.mediaDir).append(" folder of shared storage.");
            new AlertDialog.Builder(this)
                    .setTitle("\u2713 " + r.headline)
                    .setMessage(msg.toString())
                    .setPositiveButton("Play it", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            Uri uri = currentUri(e);
                            if (uri != null) playUri(uri);
                        }
                    })
                    .setNeutralButton("Details", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            showReport(SoundApplierLastReport.get());
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
            if (r.mediaUri != null) SoundApplierLastReport.set(r);
            toast("Applied and verified \u2713");
            return;
        }

        // Failure: exactly what failed, why, and what to do.
        SoundApplierLastReport.set(r);
        StringBuilder msg = new StringBuilder();
        msg.append(r.message);
        if (!r.hint.isEmpty()) {
            msg.append("\n\nWhat to do:\n").append(r.hint);
        }
        if (!r.warnings.isEmpty()) {
            msg.append("\n\nNotes:");
            for (String w : r.warnings) msg.append("\n\u2022 ").append(w);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("\u2717 " + r.headline)
                .setMessage(msg.toString())
                .setNeutralButton("Details", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        showReport(SoundApplierLastReport.get());
                    }
                })
                .setNegativeButton("Close", null);
        if (r.mediaUri != null && e.ringtoneType >= 0) {
            b.setPositiveButton("Open system sound picker", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    openSystemPicker(e, SoundApplierLastReport.get().mediaUri);
                }
            });
        } else {
            b.setPositiveButton("Try elevated mode", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    onElevatedModeClicked();
                }
            });
        }
        b.show();
    }

    /** Last pipeline report, kept for the Details button. */
    private static final class SoundApplierLastReport {
        private static volatile SoundApplier.ApplyResult last;
        static void set(SoundApplier.ApplyResult r) { last = r; }
        static SoundApplier.ApplyResult get() { return last; }
    }

    private void showReport(SoundApplier.ApplyResult r) {
        if (r == null) {
            toast("No report available.");
            return;
        }
        TextView tv = new TextView(this);
        tv.setText(r.fullReport());
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Technical details")
                .setView(scroll)
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        try {
                            ClipboardManager cm = (ClipboardManager)
                                    getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("Sound Setter report",
                                    SoundApplierLastReport.get().fullReport()));
                            toast("Report copied.");
                        } catch (Throwable t) {
                            toast("Copy failed: " + t.getMessage());
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void openSystemPicker(SoundEvent e, Uri existing) {
        try {
            Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                    e.ringtoneType >= 0 ? e.ringtoneType : RingtoneManager.TYPE_RINGTONE);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, e.title);
            Uri def = RingtoneManager.getDefaultUri(
                    e.ringtoneType >= 0 ? e.ringtoneType : RingtoneManager.TYPE_RINGTONE);
            if (def != null) i.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, def);
            if (existing != null) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing);
            startActivityForResult(i, REQ_SYSTEM_PICKER);
        } catch (Throwable t) {
            toast("No system sound picker available: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------ preview

    private void onPreviewClicked(SoundEvent e) {
        Uri uri = currentUri(e);
        if (uri == null && e.ringtoneType >= 0) {
            uri = RingtoneManager.getDefaultUri(e.ringtoneType);
        }
        if (uri == null) {
            toast("No sound is set for " + e.title + ".");
            return;
        }
        playUri(uri);
    }

    private void playUri(Uri uri) {
        stopPreviewPlayback();
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(this, uri);
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) {
                    if (currentPlayer == m) currentPlayer = null;
                    try { m.release(); } catch (Throwable ignored) {}
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    if (currentPlayer == m) currentPlayer = null;
                    try { m.release(); } catch (Throwable ignored) {}
                    toast("Preview failed (code " + what + "). The system may not be able to "
                            + "read this sound.");
                    return true;
                }
            });
            mp.prepare();
            mp.start();
            currentPlayer = mp;
            handler.postDelayed(stopPreview, 8000);
            toast("Playing\u2026");
        } catch (Throwable t) {
            toast("Couldn't play: " + t.getMessage());
        }
    }

    private void stopPreviewPlayback() {
        handler.removeCallbacks(stopPreview);
        if (currentPlayer != null) {
            try {
                currentPlayer.stop();
            } catch (Throwable ignored) {
            }
            try {
                currentPlayer.release();
            } catch (Throwable ignored) {
            }
            currentPlayer = null;
        }
    }

    // ------------------------------------------------------------ elevated (Shizuku) mode

    private Shizuku.OnBinderReceivedListener binderReceivedListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { refreshShizukuStatus(); }
                    });
                }
            };

    private Shizuku.OnBinderDeadListener binderDeadListener = new Shizuku.OnBinderDeadListener() {
        @Override public void onBinderDead() {
            runOnUiThread(new Runnable() {
                @Override public void run() { refreshShizukuStatus(); }
            });
        }
    };

    private Shizuku.OnRequestPermissionResultListener permissionResultListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            refreshShizukuStatus();
                            if (ShizukuBridge.isAuthorized()) {
                                toast("Elevated (ADB) mode active \u2713");
                            }
                        }
                    });
                }
            };

    private void registerShizuku() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
        } catch (Throwable t) {
            // Shizuku unavailable - the app works fully without it.
        }
    }

    private void refreshShizukuStatus() {
        if (shizukuStatus == null) return;
        if (ShizukuBridge.isAuthorized()) {
            shizukuStatus.setText("Active \u2713 - blocked writes are retried as shell automatically.");
            shizukuStatus.setTextColor(0xFF2E7D32);
        } else if (!ShizukuBridge.isManagerInstalled(this)) {
            shizukuStatus.setText("Not active - Shizuku app not installed (optional).");
            shizukuStatus.setTextColor(0xFF888888);
        } else if (!ShizukuBridge.isBinderAlive()) {
            shizukuStatus.setText("Shizuku installed but not running - start it (wireless debugging).");
            shizukuStatus.setTextColor(0xFFE65100);
        } else if (ShizukuBridge.isPreV11()) {
            shizukuStatus.setText("Shizuku version too old - please update Shizuku.");
            shizukuStatus.setTextColor(0xFFE65100);
        } else {
            shizukuStatus.setText("Shizuku running - tap the button to authorize this app.");
            shizukuStatus.setTextColor(0xFFE65100);
        }
    }

    private void onElevatedModeClicked() {
        if (ShizukuBridge.isAuthorized()) {
            toast("Elevated (ADB) mode is already active \u2713");
            return;
        }
        if (!ShizukuBridge.isManagerInstalled(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Elevated mode needs Shizuku")
                    .setMessage("Shizuku is a free, open-source tool that lets apps use Android's "
                            + "built-in wireless debugging - no root, no PC needed on Android 11+.\n\n"
                            + "Install it, start it, then come back here and tap this button again.\n\n"
                            + "Skip it entirely if the normal path already works for you.")
                    .setPositiveButton("Get Shizuku", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://shizuku.rikka.app/download/")));
                            } catch (Throwable ignored) {
                            }
                        }
                    })
                    .setNegativeButton("Not now", null)
                    .show();
            return;
        }
        if (!ShizukuBridge.isBinderAlive()) {
            new AlertDialog.Builder(this)
                    .setTitle("Start Shizuku")
                    .setMessage("Shizuku is installed but not running:\n\n"
                            + "1. Android 11+: Settings > Developer options > Wireless debugging > "
                            + "pair with Shizuku (the app walks you through it).\n"
                            + "2. Older Android: enable USB debugging and run Shizuku's one-line "
                            + "command from a PC, once per reboot.\n\n"
                            + "Then tap Enable again.")
                    .setPositiveButton("Open Shizuku", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            ShizukuBridge.openManager(MainActivity.this);
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }
        if (ShizukuBridge.isPreV11()) {
            toast("Please update the Shizuku app to a recent version.");
            return;
        }
        try {
            ShizukuBridge.requestPermission();
        } catch (Throwable t) {
            toast("Could not request Shizuku permission: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------ help / utils

    private void showTroubleshooting() {
        StringBuilder sb = new StringBuilder();
        sb.append("How this app works:\n")
          .append("1. Copies your file into Android's Ringtones/Notifications/Alarms media store.\n")
          .append("2. Writes the system default sound setting (RingtoneManager).\n")
          .append("3. Reads the setting back to prove Android accepted it.\n\n")
          .append("Common problems:\n")
          .append("\u2022 \"Modify system settings\" not granted - required on every Android 6+ "
                  + "device. Tap Grant and enable the switch.\n")
          .append("\u2022 Xiaomi/Redmi/Poco: MIUI keeps per-SIM ringtone keys "
                  + "(ringtone_sound_slot_1/2) that override the global one. This app writes "
                  + "those too. If the Sound & vibration page still shows the old tone, "
                  + "open it once and re-select the new entry, or use Elevated (ADB) mode.\n")
          .append("\u2022 Device in Silent mode - the setting changes but you hear nothing "
                  + "until you raise the volume.\n")
          .append("\u2022 Reinstalled the app - previously set tones owned by the old install "
                  + "become unreadable; apply the file again.\n")
          .append("\u2022 \"Unsupported format\" - re-encode to MP3/WAV/OGG/FLAC/M4A.\n")
          .append("\u2022 Nothing works - enable Elevated (ADB) mode; it writes the setting "
                  + "directly as shell and bypasses OEM restrictions.");
        new AlertDialog.Builder(this)
                .setTitle("Troubleshooting")
                .setMessage(sb.toString())
                .setPositiveButton("Close", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
