package de.delorean23.tube;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Download-Auswahl: Audio (MP3) oder Video (MP4) mit Nachfrage nach der Auflösung. Created by DeLorean23 */
public final class DownloadDialog {

    public static final int PERMISSION_REQUEST = 7;

    private DownloadDialog() {
    }

    public static void show(Activity a, StreamInfo info, String url) {
        if (info == null) {
            Toast.makeText(a, "Video wird noch geladen …", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Streams.isLive(info)) {
            Toast.makeText(a, "Live-Streams können nicht heruntergeladen werden", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(a,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST);
            Toast.makeText(a, "Bitte Speichern erlauben und dann nochmal auf Download tippen",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Download")
                .setItems(new String[]{"Audio (MP3)", "Video (MP4)"}, (d, which) -> {
                    if (which == 0) start(a, info, url, true, 0);
                    else askResolution(a, info, url);
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private static void askResolution(Activity a, StreamInfo info, String url) {
        LinkedHashMap<Integer, VideoStream> choices = Streams.videoChoices(info, true);
        if (choices.isEmpty()) {
            Toast.makeText(a, "Für dieses Video gibt es keine MP4-Datei", Toast.LENGTH_LONG).show();
            return;
        }
        final List<Integer> heights = new ArrayList<>(choices.keySet());
        String[] labels = new String[heights.size()];
        long dur = info.getDuration();
        for (int i = 0; i < heights.size(); i++) {
            int h = heights.get(i);
            VideoStream v = choices.get(h);
            String size = "";
            if (v != null && v.getBitrate() > 0 && dur > 0) {
                long bytes = (long) v.getBitrate() / 8 * dur + 128_000L / 8 * dur;
                size = "  (ca. " + (bytes / (1024 * 1024)) + " MB)";
            }
            labels[i] = Streams.label(v) + size;
        }
        new AlertDialog.Builder(a)
                .setTitle("Welche Auflösung?")
                .setItems(labels, (d, which) -> start(a, info, url, false, heights.get(which)))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private static void start(Activity a, StreamInfo info, String url, boolean mp3, int height) {
        Intent i = new Intent(a, DownloadService.class)
                .putExtra("url", url)
                .putExtra("title", info.getName())
                .putExtra("artist", info.getUploaderName())
                .putExtra("mp3", mp3)
                .putExtra("height", height);
        ContextCompat.startForegroundService(a, i);
        Toast.makeText(a, mp3 ? "MP3-Download gestartet" : "Video-Download gestartet",
                Toast.LENGTH_SHORT).show();
    }
}
