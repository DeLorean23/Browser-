package de.delorean23.adblockbrowser;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.mediarouter.app.MediaRouteButton;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    private static final int M_HOME = 1, M_RELOAD = 2, M_CAST = 3, M_TV_PAUSE = 4, M_TV_STOP = 5,
            M_STRICT = 6, M_UPDATE = 7, M_MIRROR = 8, M_INFO = 9, M_FORWARD = 10;

    private WebView web;
    private EditText urlBar;
    private ProgressBar progress;
    private View startPage, mainArea;
    private TextView blockInfo;
    private FrameLayout fullscreen;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private AdBlocker adBlocker;
    private CastContext castContext;
    private SharedPreferences prefs;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicInteger blockedCount = new AtomicInteger();

    private volatile String currentHost = "";
    private String mediaUrl;      // zuletzt erkanntes Video auf der aktuellen Seite
    private String mediaTitle = "";
    private String askedForUrl;   // damit die Cast-Frage pro Video nur einmal kommt

    // ------------------------------------------------------------------ Start

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        web = findViewById(R.id.web);
        urlBar = findViewById(R.id.urlBar);
        progress = findViewById(R.id.progress);
        startPage = findViewById(R.id.startPage);
        mainArea = findViewById(R.id.mainArea);
        blockInfo = findViewById(R.id.blockInfo);
        fullscreen = findViewById(R.id.fullscreen);

        // Werbeblocker laden, beim ersten Start große Liste holen
        adBlocker = new AdBlocker(this);
        bg.execute(() -> {
            adBlocker.load();
            ui.post(this::updateBlockInfo);
            if (!adBlocker.hasUpdateFile()) {
                try {
                    adBlocker.update();
                    ui.post(this::updateBlockInfo);
                } catch (Exception ignored) {
                }
            }
        });

        setupCast();
        setupWebView();
        setupUrlBar();
        findViewById(R.id.menuButton).setOnClickListener(this::showMenu);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            open(intent.getData().toString());
        }
    }

    private void setupCast() {
        MediaRouteButton castButton = findViewById(R.id.castButton);
        try {
            castContext = CastContext.getSharedInstance(this);
            CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), castButton);
        } catch (Exception e) {
            // Keine Google-Play-Dienste → App läuft ohne Chromecast weiter
            castContext = null;
            castButton.setVisibility(View.GONE);
        }
    }

    // --------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Popups: neue Fenster werden abgefangen und verworfen
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.addJavascriptInterface(new Bridge(), "DLBridge");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, length) ->
                askDownload(url, userAgent, contentDisposition, mimeType));
    }

    private class Client extends WebViewClient {

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (adBlocker.isBlocked(uri.getHost())) {
                blockedCount.incrementAndGet();
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            String url = uri.toString();
            if (looksLikeMedia(url)) {
                ui.post(() -> onMediaFound(url, null));
            }
            return null;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

            // intent:, market:, App-Links usw. → nicht aus dem Browser springen
            if (!scheme.equals("http") && !scheme.equals("https")) {
                blockedCount.incrementAndGet();
                return true;
            }
            if (adBlocker.isBlocked(uri.getHost())) {
                blockedCount.incrementAndGet();
                toast("Werbe-Weiterleitung blockiert");
                return true;
            }
            // Strenger Modus: Seitenwechsel auf fremde Domain ohne Antippen = typische Werbe-Umleitung
            if (isStrict() && request.isForMainFrame() && !request.hasGesture()) {
                boolean redirect = Build.VERSION.SDK_INT >= 24 && request.isRedirect();
                String host = uri.getHost() == null ? "" : uri.getHost();
                if (!redirect && !currentHost.isEmpty() && !sameSite(host, currentHost)) {
                    blockedCount.incrementAndGet();
                    toast("Automatische Umleitung blockiert");
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            Uri u = Uri.parse(url);
            String host = u.getHost() == null ? "" : u.getHost();
            if (!sameSite(host, currentHost)) {
                mediaUrl = null;
                askedForUrl = null;
            }
            currentHost = host;
            if (!urlBar.hasFocus()) urlBar.setText(url);
            progress.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            view.evaluateJavascript(HOOK_JS, null);
        }
    }

    private class Chrome extends WebChromeClient {

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            blockedCount.incrementAndGet();
            toast("Popup blockiert");
            return false;
        }

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customCallback = callback;
            fullscreen.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            fullscreen.setVisibility(View.VISIBLE);
            mainArea.setVisibility(View.GONE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            hideSystemUi(true);
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreen.removeView(customView);
        fullscreen.setVisibility(View.GONE);
        mainArea.setVisibility(View.VISIBLE);
        customView = null;
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        hideSystemUi(false);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi(boolean hide) {
        View d = getWindow().getDecorView();
        if (hide) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    /** Wird auf jeder Seite eingefügt: meldet abgespielte Videos und schaltet window.open ab. */
    private static final String HOOK_JS =
            "(function(){if(window.__dlhook)return;window.__dlhook=1;" +
            "function rep(v){try{var s=v.currentSrc||v.src;" +
            "if(!s){var so=v.querySelector('source');if(so)s=so.src;}" +
            "if(s&&s.indexOf('blob:')!==0)DLBridge.media(s,document.title||'');}catch(e){}}" +
            "document.addEventListener('play',function(e){var t=e.target;" +
            "if(t&&t.tagName==='VIDEO')rep(t);},true);" +
            "try{window.open=function(){return null;};}catch(e){}" +
            "})();";

    private class Bridge {
        @JavascriptInterface
        public void media(String url, String title) {
            ui.post(() -> onMediaFound(url, title));
        }
    }

    // ------------------------------------------------------------- Chromecast

    private static boolean looksLikeMedia(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        String path = q >= 0 ? u.substring(0, q) : u;
        return path.endsWith(".mp4") || path.endsWith(".m3u8") || path.endsWith(".webm")
                || path.endsWith(".mpd") || path.endsWith(".mkv");
    }

    private void onMediaFound(String url, String title) {
        if (url == null || url.startsWith("blob:")) return;
        // HLS: die erste m3u8 einer Seite ist meist die Hauptliste → nicht überschreiben
        if (mediaUrl != null && mediaUrl.contains(".m3u8") && url.contains(".m3u8")) return;
        mediaUrl = url;
        if (title != null && !title.isEmpty()) mediaTitle = title;
        else if (web.getTitle() != null) mediaTitle = web.getTitle();

        // Ist ein Fernseher verbunden, einmal pro Video fragen
        if (isCastConnected() && !url.equals(askedForUrl)) {
            askedForUrl = url;
            new AlertDialog.Builder(this)
                    .setTitle("Video gefunden")
                    .setMessage("Wo soll das Video laufen?")
                    .setPositiveButton("Auf Fernseher", (d, w) -> castMedia())
                    .setNegativeButton("Hier abspielen", null)
                    .show();
        }
    }

    private CastSession castSession() {
        if (castContext == null) return null;
        return castContext.getSessionManager().getCurrentCastSession();
    }

    private boolean isCastConnected() {
        CastSession s = castSession();
        return s != null && s.isConnected();
    }

    private void castMedia() {
        if (castContext == null) {
            toast("Chromecast nicht verfügbar (Google-Play-Dienste fehlen)");
            return;
        }
        CastSession s = castSession();
        if (s == null || !s.isConnected()) {
            toast("Erst oben auf das Cast-Symbol tippen und den Fernseher wählen");
            return;
        }
        if (mediaUrl == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Kein Video erkannt")
                    .setMessage("Starte das Video kurz hier in der App und versuche es dann erneut.\n\n" +
                            "Klappt es trotzdem nicht, nutze „Bildschirm spiegeln“ im Menü.")
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Bildschirm spiegeln", (d, w) -> openMirroring())
                    .show();
            return;
        }
        RemoteMediaClient rmc = s.getRemoteMediaClient();
        if (rmc == null) {
            toast("Verbindung zum Fernseher noch nicht bereit");
            return;
        }
        MediaMetadata meta = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        meta.putString(MediaMetadata.KEY_TITLE, mediaTitle == null || mediaTitle.isEmpty() ? "Video" : mediaTitle);
        MediaInfo info = new MediaInfo.Builder(mediaUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(contentType(mediaUrl))
                .setMetadata(meta)
                .build();
        rmc.load(new MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build());
        // Wiedergabe auf dem Handy pausieren
        web.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause();});", null);
        toast("Wird auf dem Fernseher abgespielt");
    }

    private static String contentType(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains(".m3u8")) return "application/x-mpegURL";
        if (u.contains(".mpd")) return "application/dash+xml";
        if (u.contains(".webm")) return "video/webm";
        if (u.contains(".mkv")) return "video/x-matroska";
        return "video/mp4";
    }

    private void tvTogglePause() {
        CastSession s = castSession();
        RemoteMediaClient rmc = s == null ? null : s.getRemoteMediaClient();
        if (rmc == null || !rmc.hasMediaSession()) {
            toast("Auf dem Fernseher läuft nichts");
            return;
        }
        rmc.togglePlayback();
    }

    private void tvStop() {
        CastSession s = castSession();
        RemoteMediaClient rmc = s == null ? null : s.getRemoteMediaClient();
        if (rmc != null && rmc.hasMediaSession()) rmc.stop();
    }

    private void openMirroring() {
        try {
            startActivity(new Intent(Settings.ACTION_CAST_SETTINGS));
        } catch (ActivityNotFoundException e) {
            toast("Bitte in der Google-Home-App „Bildschirm streamen“ wählen");
        }
    }

    // --------------------------------------------------------------- Download

    private void askDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
        new AlertDialog.Builder(this)
                .setTitle("Datei herunterladen?")
                .setMessage(name)
                .setPositiveButton("Herunterladen", (d, w) -> download(url, userAgent, mimeType, name))
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void download(String url, String userAgent, String mimeType, String name) {
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(mimeType);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) r.addRequestHeader("Cookie", cookies);
            r.addRequestHeader("User-Agent", userAgent);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            if (Build.VERSION.SDK_INT >= 29) {
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            } else {
                r.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            }
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(r);
            toast("Download gestartet");
        } catch (Exception e) {
            toast("Download fehlgeschlagen: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ Adressleiste

    private void setupUrlBar() {
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                open(urlBar.getText().toString());
                return true;
            }
            return false;
        });
    }

    private void open(String input) {
        String text = input.trim();
        if (text.isEmpty()) return;
        String url;
        if (text.startsWith("http://") || text.startsWith("https://")) {
            url = text;
        } else if (!text.contains(" ") && text.contains(".")) {
            url = "https://" + text;
        } else {
            try {
                url = "https://duckduckgo.com/?q=" + URLEncoder.encode(text, "UTF-8");
            } catch (Exception e) {
                url = "https://duckduckgo.com/?q=" + text;
            }
        }
        startPage.setVisibility(View.GONE);
        urlBar.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        web.loadUrl(url);
    }

    private void goHome() {
        web.stopLoading();
        web.loadUrl("about:blank");
        web.clearHistory();
        currentHost = "";
        mediaUrl = null;
        urlBar.setText("");
        startPage.setVisibility(View.VISIBLE);
        updateBlockInfo();
    }

    // ------------------------------------------------------------------- Menü

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, M_HOME, 0, "Startseite");
        m.add(0, M_RELOAD, 1, "Neu laden");
        m.add(0, M_FORWARD, 2, "Vorwärts");
        m.add(0, M_CAST, 3, "Video auf Fernseher abspielen");
        m.add(0, M_TV_PAUSE, 4, "Fernseher: Pause / Weiter");
        m.add(0, M_TV_STOP, 5, "Fernseher: Stopp");
        m.add(0, M_MIRROR, 6, "Bildschirm spiegeln");
        m.add(0, M_STRICT, 7, "Strenger Umleitungs-Schutz").setCheckable(true).setChecked(isStrict());
        m.add(0, M_UPDATE, 8, "Filterliste aktualisieren");
        m.add(0, M_INFO, 9, "Info");
        pm.setOnMenuItemClickListener(this::onMenu);
        pm.show();
    }

    private boolean onMenu(MenuItem item) {
        switch (item.getItemId()) {
            case M_HOME: goHome(); return true;
            case M_RELOAD: web.reload(); return true;
            case M_FORWARD: if (web.canGoForward()) web.goForward(); return true;
            case M_CAST: castMedia(); return true;
            case M_TV_PAUSE: tvTogglePause(); return true;
            case M_TV_STOP: tvStop(); return true;
            case M_MIRROR: openMirroring(); return true;
            case M_STRICT:
                boolean v = !isStrict();
                prefs.edit().putBoolean("strict", v).apply();
                toast(v ? "Strenger Schutz an" : "Strenger Schutz aus");
                return true;
            case M_UPDATE:
                toast("Filterliste wird geladen …");
                bg.execute(() -> {
                    try {
                        int n = adBlocker.update();
                        ui.post(() -> { toast(n + " Domains in der Filterliste"); updateBlockInfo(); });
                    } catch (Exception e) {
                        ui.post(() -> toast("Aktualisieren fehlgeschlagen: " + e.getMessage()));
                    }
                });
                return true;
            case M_INFO:
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.app_name))
                        .setMessage("Version 1.0\n\n"
                                + "Filterliste: " + adBlocker.size() + " Domains\n"
                                + "Blockiert seit Start: " + blockedCount.get() + "\n\n"
                                + getString(R.string.credit))
                        .setPositiveButton("OK", null)
                        .show();
                return true;
        }
        return false;
    }

    private boolean isStrict() {
        return prefs.getBoolean("strict", true);
    }

    // ---------------------------------------------------------------- Hilfen

    private static boolean sameSite(String a, String b) {
        return root(a).equals(root(b));
    }

    /** Grobe Hauptdomain: die letzten zwei Teile (video.beispiel.de → beispiel.de). */
    private static String root(String host) {
        if (host == null) return "";
        String h = host.toLowerCase(Locale.ROOT);
        int last = h.lastIndexOf('.');
        if (last <= 0) return h;
        int prev = h.lastIndexOf('.', last - 1);
        return prev < 0 ? h : h.substring(prev + 1);
    }

    private void updateBlockInfo() {
        int n = adBlocker.size();
        blockInfo.setText(n > 0 ? "Werbefilter aktiv · " + n + " Domains" : "Werbefilter wird geladen …");
    }

    private void toast(String msg) {
        ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
        } else if (startPage.getVisibility() != View.VISIBLE && web.canGoBack()) {
            web.goBack();
        } else if (startPage.getVisibility() != View.VISIBLE) {
            goHome();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        web.destroy();
        super.onDestroy();
    }
}
