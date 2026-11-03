package com.tools.soundsetter;

import android.media.RingtoneManager;

/**
 * Describes one system sound event that can be set (default ringtone, default
 * notification sound, default alarm sound) plus the Xiaomi/MIUI per-SIM ringtone
 * slots which are stored in their own Settings.System keys on MIUI/HyperOS.
 */
public final class SoundEvent {

    public static final String KEY_RINGTONE = "ringtone";
    public static final String KEY_NOTIFICATION = "notification_sound";
    public static final String KEY_ALARM = "alarm_alert";

    /** MIUI/HyperOS per-SIM keys (observed in Settings.System on dual-SIM Xiaomi devices). */
    public static final String MIUI_SLOT_1 = "ringtone_sound_slot_1";
    public static final String MIUI_SLOT_2 = "ringtone_sound_slot_2";

    /** Stable id, used for preferences (owning previously copied files). */
    public final String id;
    /** Short human title shown in the list. */
    public final String title;
    /** One-line explanation shown under the title. */
    public final String description;
    /** RingtoneManager.TYPE_* constant, or -1 when the event only has a MIUI slot key. */
    public final int ringtoneType;
    /** Global Settings.System key ("ringtone" / "notification_sound" / "alarm_alert"). */
    public final String settingsKey;
    /** MIUI per-SIM key, or null. */
    public final String miuiSlotKey;
    /** Public media directory the file is published to: Ringtones / Notifications / Alarms. */
    public final String mediaDir;

    private SoundEvent(String id, String title, String description, int ringtoneType,
                       String settingsKey, String miuiSlotKey, String mediaDir) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.ringtoneType = ringtoneType;
        this.settingsKey = settingsKey;
        this.miuiSlotKey = miuiSlotKey;
        this.mediaDir = mediaDir;
    }

    /** The three standard AOSP system sound events. */
    public static SoundEvent[] standardEvents() {
        return new SoundEvent[] {
            new SoundEvent("ringtone", "Call ringtone",
                    "Rings for incoming calls (system-wide default)",
                    RingtoneManager.TYPE_RINGTONE, KEY_RINGTONE, null, "Ringtones"),
            new SoundEvent("notification", "Notification sound",
                    "Played for received SMS/text messages and app notifications",
                    RingtoneManager.TYPE_NOTIFICATION, KEY_NOTIFICATION, null, "Notifications"),
            new SoundEvent("alarm", "Alarm sound",
                    "Played by the Clock app's alarms (system-wide default)",
                    RingtoneManager.TYPE_ALARM, KEY_ALARM, null, "Alarms"),
        };
    }

    /**
     * Xiaomi/Redmi/Poco (MIUI/HyperOS) keep a per-SIM ringtone next to the global
     * one. When those keys exist they override the global ringtone, so they must be
     * written as well or the change appears to be ignored.
     */
    public static SoundEvent[] miuiSimEvents() {
        return new SoundEvent[] {
            new SoundEvent("ringtone_sim1", "SIM 1 ringtone (Xiaomi)",
                    "MIUI per-SIM key; also updates the global ringtone",
                    -1, KEY_RINGTONE, MIUI_SLOT_1, "Ringtones"),
            new SoundEvent("ringtone_sim2", "SIM 2 ringtone (Xiaomi)",
                    "MIUI per-SIM key; also updates the global ringtone",
                    -1, KEY_RINGTONE, MIUI_SLOT_2, "Ringtones"),
        };
    }
}
