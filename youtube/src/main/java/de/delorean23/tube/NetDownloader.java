package de.delorean23.tube;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

/**
 * Netzwerk für NewPipeExtractor und für das Laden der Video-/Audiodaten.
 * Created by DeLorean23
 */
public final class NetDownloader extends Downloader {

    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0";
    private static final byte[] POST_BODY = new byte[]{0x78, 0};
    private static final String YT = "https://www.youtube.com";

    private static NetDownloader instance;
    private final OkHttpClient client;
    private final AtomicInteger requestNumber = new AtomicInteger(0);

    private NetDownloader() {
        client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public static synchronized NetDownloader get() {
        if (instance == null) instance = new NetDownloader();
        return instance;
    }

    public OkHttpClient client() {
        return client;
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        RequestBody body = null;
        byte[] data = request.dataToSend();
        if (data != null) body = RequestBody.create(data);

        okhttp3.Request.Builder b = new okhttp3.Request.Builder()
                .method(request.httpMethod(), body)
                .url(request.url())
                .addHeader("User-Agent", USER_AGENT);

        Map<String, List<String>> headers = request.headers();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            b.removeHeader(e.getKey());
            for (String v : e.getValue()) b.addHeader(e.getKey(), v);
        }

        try (okhttp3.Response r = client.newCall(b.build()).execute()) {
            if (r.code() == 429) {
                throw new ReCaptchaException("reCaptcha Challenge requested", request.url());
            }
            String text = null;
            ResponseBody rb = r.body();
            if (rb != null) text = rb.string();
            return new Response(r.code(), r.message(), r.headers().toMultimap(), text,
                    r.request().url().toString());
        }
    }

    /**
     * Baut eine Anfrage für eine YouTube-Mediendatei (wie die NewPipe-App:
     * POST, Parameter rn, Range-Header, passende Header für WEB-Links).
     * end = -1 bedeutet bis zum Ende.
     */
    public okhttp3.Request mediaRequest(String url, long start, long end) {
        String u = url;
        boolean playback = u.contains("/videoplayback");
        if (playback && !u.contains("&rn=")) {
            u = u + "&rn=" + requestNumber.getAndIncrement();
        }
        okhttp3.Request.Builder b = new okhttp3.Request.Builder().url(u);
        if (start > 0 || end >= 0) {
            b.header("Range", "bytes=" + start + "-" + (end >= 0 ? String.valueOf(end) : ""));
        }
        boolean visionOs = false;
        try {
            if (YoutubeParsingHelper.isWebStreamingUrl(u)) {
                b.header("Origin", YT);
                b.header("Referer", YT);
                b.header("Sec-Fetch-Dest", "empty");
                b.header("Sec-Fetch-Mode", "cors");
                b.header("Sec-Fetch-Site", "cross-site");
            }
            visionOs = YoutubeParsingHelper.isVisionOsStreamingUrl(u);
        } catch (Throwable ignored) {
        }
        b.header("User-Agent", visionOs
                ? YoutubeParsingHelper.getVisionOsUserAgent(null) : USER_AGENT);
        b.header("Accept-Encoding", "identity");
        if (playback) {
            b.post(RequestBody.create(POST_BODY));
        }
        return b.build();
    }
}
