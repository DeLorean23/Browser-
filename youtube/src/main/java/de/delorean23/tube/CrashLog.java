package de.delorean23.tube;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Speichert Abstürze und zeigt sie beim nächsten Start an (zum Kopieren/Teilen).
 * Created by DeLorean23
 */
public final class CrashLog {

    private CrashLog() {
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "crash.txt");
    }

    public static void install(Context app) {
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                pw.println("DeLorean Tube " + version(app) + " – Absturz "
                        + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(new Date()));
                pw.println("Gerät: " + Build.MANUFACTURER + " " + Build.MODEL
                        + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                        + ", ABI " + Build.SUPPORTED_ABIS[0]);
                pw.println("Thread: " + thread.getName());
                ex.printStackTrace(pw);
                pw.flush();
                String text = sw.toString();
                if (text.length() > 30000) text = text.substring(0, 30000);
                try (FileOutputStream fo = new FileOutputStream(file(app))) {
                    fo.write(text.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) {
            }
            if (old != null) old.uncaughtException(thread, ex);
        });
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "?";
        }
    }

    /** Beim Start aufrufen: zeigt den letzten Absturz, falls es einen gab. */
    public static void showIfAny(Activity a) {
        File f = file(a);
        if (!f.exists()) return;
        String text;
        try {
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            }
            text = new String(b, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            text = "Absturzbericht nicht lesbar: " + e;
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        final String report = text;

        TextView tv = new TextView(a);
        tv.setText(report);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11);
        tv.setTextColor(a.getResources().getColor(R.color.text));
        int p = (int) (16 * a.getResources().getDisplayMetrics().density);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(a);
        sv.addView(tv);

        new AlertDialog.Builder(a)
                .setTitle("Die App ist abgestürzt")
                .setView(sv)
                .setPositiveButton("Kopieren", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("Absturzbericht", report));
                    Toast.makeText(a, "Kopiert – bitte an Claude schicken", Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("Teilen", (d, w) -> a.startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, report), "Absturzbericht teilen")))
                .setNegativeButton("Schließen", null)
                .show();
    }
}
