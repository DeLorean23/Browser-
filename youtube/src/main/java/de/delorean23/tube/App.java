package de.delorean23.tube;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Created by DeLorean23 */
public class App extends Application {

    public static final String CH_DOWNLOAD = "downloads";

    private static App instance;
    /** Hintergrund-Threads mit großem Stack (die JavaScript-Auswertung von YouTube braucht viel). */
    public static final ExecutorService IO = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(null, r, "tube-io", 16L * 1024 * 1024);
        t.setDaemon(true);
        return t;
    });
    public static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final LruCache<String, StreamInfo> INFO_CACHE = new LruCache<>(30);

    private static int started;

    /** Wie viele Bildschirme der App gerade sichtbar sind (0 = App im Hintergrund / Display aus). */
    public static int visibleScreens() {
        return started;
    }

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CrashLog.install(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) { started++; }
            @Override public void onActivityStopped(Activity a) { started = Math.max(0, started - 1); }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
        NewPipe.init(NetDownloader.get(), new Localization("de", "DE"), new ContentCountry("DE"));

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel ch = new NotificationChannel(CH_DOWNLOAD,
                    getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
    }

    /** StreamInfo laden (mit kleinem Zwischenspeicher, läuft NICHT im UI-Thread aufrufen). */
    public static StreamInfo streamInfo(String url) throws Exception {
        String key = Util.videoId(url);
        if (key == null) key = url;
        synchronized (INFO_CACHE) {
            StreamInfo cached = INFO_CACHE.get(key);
            if (cached != null) return cached;
        }
        StreamInfo info = StreamInfo.getInfo(url);
        synchronized (INFO_CACHE) {
            INFO_CACHE.put(key, info);
        }
        return info;
    }

    /** Wird bei abgelaufenen Stream-Links (403) aufgerufen. */
    public static void forgetStreamInfo(String url) {
        String key = Util.videoId(url);
        synchronized (INFO_CACHE) {
            INFO_CACHE.remove(key != null ? key : url);
        }
    }
}
