package de.delorean23.tube;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.media3.common.C;
import androidx.media3.common.Player;

/** Gemeinsame Player-Einstellungen. Created by DeLorean23 */
public final class Playback {

    private Playback() {
    }

    public static SharedPreferences prefs() {
        return App.get().getSharedPreferences("player", Context.MODE_PRIVATE);
    }

    public static boolean audioOnly() {
        return prefs().getBoolean("audio_only", false);
    }

    /**
     * Bild an/aus. visible=false wenn kein Bildschirm der App mehr sichtbar ist:
     * dann wird nur noch der Ton geladen (spart Daten und Akku, Ton läuft weiter).
     */
    public static void setVideo(Player p, boolean visible) {
        if (p == null) return;
        try {
            p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !visible || audioOnly())
                    .build());
        } catch (Throwable ignored) {
        }
    }
}
