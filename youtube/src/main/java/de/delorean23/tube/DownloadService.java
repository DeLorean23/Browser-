package de.delorean23.tube;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import de.delorean23.tube.lame.Mp3Encoder;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads im Hintergrund: MP3 (Ton wird umgewandelt) oder MP4 in gewählter Auflösung
 * (Bild- und Tonspur werden zusammengefügt). Gespeichert in Musik/DeLorean Tube bzw. Filme/DeLorean Tube.
 * Created by DeLorean23
 */
public class DownloadService extends Service {

    private static final String FOLDER = "DeLorean Tube";
    private static final long CHUNK = 4L * 1024 * 1024;
    private static final int FG_ID = 4200;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger running = new AtomicInteger(0);
    private final AtomicInteger notifyIds = new AtomicInteger(4300);
    private NotificationManager nm;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification fg = base("Download wird vorbereitet …").setProgress(0, 0, true).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(FG_ID, fg, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_ID, fg);
        }
        if (intent == null) {
            if (running.get() == 0) stopSelf();
            return START_NOT_STICKY;
        }
        final String url = intent.getStringExtra("url");
        final String title = intent.getStringExtra("title");
        final String artist = intent.getStringExtra("artist");
        final boolean mp3 = intent.getBooleanExtra("mp3", true);
        final int height = intent.getIntExtra("height", 720);
        running.incrementAndGet();
        worker.execute(() -> {
            int id = notifyIds.getAndIncrement();
            try {
                Job job = new Job(id, url, title, artist, mp3, height);
                job.run();
            } catch (Throwable e) {
                nm.notify(id, base("Download fehlgeschlagen")
                        .setContentText(title + ": " + e.getMessage())
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText(title + "\n" + e.getMessage()))
                        .setSmallIcon(android.R.drawable.stat_notify_error)
                        .setOngoing(false).setAutoCancel(true).build());
            } finally {
                if (running.decrementAndGet() == 0) {
                    stopForeground(true);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private NotificationCompat.Builder base(String title) {
        return new NotificationCompat.Builder(this, App.CH_DOWNLOAD)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
    }

    // ------------------------------------------------------------------ Auftrag

    private final class Job {
        final int id;
        final String url, title, artist;
        final boolean mp3;
        final int height;
        long lastNotify;

        Job(int id, String url, String title, String artist, boolean mp3, int height) {
            this.id = id;
            this.url = url;
            this.title = title != null ? title : "Video";
            this.artist = artist;
            this.mp3 = mp3;
            this.height = height;
        }

        void progress(String step, long done, long total) {
            long now = System.currentTimeMillis();
            if (now - lastNotify < 700 && done < total) return;
            lastNotify = now;
            int pct = total > 0 ? (int) (done * 100 / total) : 0;
            Notification n = base(title)
                    .setContentText(step + (total > 0 ? "  " + pct + " %" : ""))
                    .setProgress(100, pct, total <= 0)
                    .build();
            nm.notify(FG_ID, n);
        }

        void run() throws Exception {
            progress("Lade Infos …", 0, 0);
            App.forgetStreamInfo(url); // frische Links holen
            StreamInfo info = App.streamInfo(url);
            File dir = new File(getCacheDir(), "dl");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String base = Util.safeFileName(title);

            if (mp3) {
                AudioStream a = Streams.bestAudio(info, true);
                if (a == null) throw new IOException("Keine Tonspur gefunden");
                File src = new File(dir, id + "_audio.m4a");
                File out = new File(dir, id + ".mp3");
                try {
                    download(a.getContent(), src, "Ton wird geladen", 0, 1);
                    toMp3(src, out);
                    Uri uri = publish(out, base + ".mp3", "audio/mpeg", true);
                    finished("MP3 gespeichert", base + ".mp3", uri, "audio/mpeg");
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    src.delete();
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                }
            } else {
                VideoStream v = Streams.videoChoices(info, true).get(height);
                if (v == null) throw new IOException(height + "p ist nicht mehr verfügbar");
                File out = new File(dir, id + ".mp4");
                String name = base + " (" + height + "p).mp4";
                if (!v.isVideoOnly()) {
                    try {
                        download(v.getContent(), out, "Video wird geladen", 0, 1);
                        Uri uri = publish(out, name, "video/mp4", false);
                        finished("Video gespeichert", name, uri, "video/mp4");
                    } finally {
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                    }
                    return;
                }
                AudioStream a = Streams.bestAudio(info, true);
                if (a == null) throw new IOException("Keine Tonspur gefunden");
                File vf = new File(dir, id + "_video.mp4");
                File af = new File(dir, id + "_audio.m4a");
                try {
                    download(v.getContent(), vf, "Bild wird geladen", 0, 2);
                    download(a.getContent(), af, "Ton wird geladen", 1, 2);
                    progress("Wird zusammengefügt …", 0, 0);
                    mux(vf, af, out);
                    Uri uri = publish(out, name, "video/mp4", false);
                    finished("Video gespeichert", name, uri, "video/mp4");
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    vf.delete();
                    //noinspection ResultOfMethodCallIgnored
                    af.delete();
                    //noinspection ResultOfMethodCallIgnored
                    out.delete();
                }
            }
        }

        /** Lädt in 4-MB-Stücken (so drosselt YouTube nicht). */
        void download(String mediaUrl, File target, String step, int part, int parts) throws IOException {
            long total = -1;
            long pos = 0;
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buf = new byte[64 * 1024];
                while (total < 0 || pos < total) {
                    long end = pos + CHUNK - 1;
                    if (total > 0) end = Math.min(end, total - 1);
                    final long chunkStart = pos;
                    int attempt = 0;
                    while (true) {
                        try (Response r = NetDownloader.get().client()
                                .newCall(NetDownloader.get().mediaRequest(mediaUrl, pos, end)).execute()) {
                            if (r.code() == 416 && total < 0) {
                                total = pos; // genau am Ende
                                break;
                            }
                            if (!r.isSuccessful()) throw new IOException("HTTP " + r.code());
                            String cr = r.header("Content-Range");
                            if (cr != null && cr.contains("/")) {
                                try {
                                    total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                                } catch (NumberFormatException ignored) {
                                }
                            }
                            ResponseBody body = r.body();
                            if (body == null) throw new IOException("Leere Antwort");
                            boolean whole = r.code() == 200;
                            if (whole) {
                                if (pos > 0) throw new IOException("Server unterstützt kein Fortsetzen");
                                total = body.contentLength();
                            }
                            InputStream in = body.byteStream();
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                os.write(buf, 0, n);
                                pos += n;
                                long t = total > 0 ? total : 0;
                                progress(step, part * t + pos, (long) parts * t);
                            }
                            if (whole && total < 0) total = pos;
                            break;
                        } catch (IOException e) {
                            if (++attempt >= 4) throw e;
                            try {
                                Thread.sleep(1500L * attempt);
                            } catch (InterruptedException ie) {
                                throw new IOException("Abgebrochen");
                            }
                        }
                    }
                    if (total < 0) {
                        // Größe unbekannt: kürzeres Stück als angefragt = Dateiende
                        if (pos - chunkStart < CHUNK) break;
                    }
                }
            }
            if (pos == 0) throw new IOException("Datei ist leer");
        }

        /** M4A (AAC) -> PCM -> MP3 (192 kbit/s). */
        void toMp3(File src, File out) throws IOException {
            MediaExtractor ex = new MediaExtractor();
            MediaCodec codec = null;
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                ex.setDataSource(src.getAbsolutePath());
                int track = -1;
                MediaFormat fmt = null;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        track = i;
                        fmt = f;
                        break;
                    }
                }
                if (track < 0) throw new IOException("Keine Tonspur in der Datei");
                ex.selectTrack(track);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                long durationUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                        ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
                int sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

                codec = MediaCodec.createDecoderByType(mime);
                codec.configure(fmt, null, null, 0);
                codec.start();

                Mp3Encoder enc = null;
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                boolean inputDone = false, outputDone = false;
                byte[] pcm = new byte[0];
                int outChannels = channels;
                int outRate = sampleRate;

                while (!outputDone) {
                    if (!inputDone) {
                        int inIdx = codec.dequeueInputBuffer(10_000);
                        if (inIdx >= 0) {
                            ByteBuffer ib = inputBuffer(codec, inIdx);
                            int size = ex.readSampleData(ib, 0);
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, ex.getSampleTime(), 0);
                                ex.advance();
                            }
                        }
                    }
                    int outIdx = codec.dequeueOutputBuffer(bi, 10_000);
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = codec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            outChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                            outRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    } else if (outIdx >= 0) {
                        if (bi.size > 0) {
                            if (enc == null) {
                                enc = new Mp3Encoder(outRate, Math.min(outChannels, 2),
                                        outRate < 32000 ? 128 : 192, title, artist, os);
                            }
                            ByteBuffer ob = outputBuffer(codec, outIdx);
                            ob.position(bi.offset);
                            ob.limit(bi.offset + bi.size);
                            if (pcm.length < bi.size) pcm = new byte[bi.size];
                            ob.get(pcm, 0, bi.size);
                            int len = bi.size;
                            if (outChannels > 2) len = downmix(pcm, bi.size, outChannels);
                            if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) swap(pcm, len);
                            enc.encode(pcm, 0, len);
                            progress("Wird in MP3 umgewandelt", bi.presentationTimeUs, durationUs);
                        }
                        codec.releaseOutputBuffer(outIdx, false);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    }
                }
                if (enc == null) throw new IOException("Kein Ton dekodiert");
                enc.finish();
            } finally {
                if (codec != null) {
                    try {
                        codec.stop();
                    } catch (Exception ignored) {
                    }
                    codec.release();
                }
                ex.release();
            }
        }

        /** Bild (H.264) und Ton (AAC) zu einer MP4-Datei zusammenfügen. */
        void mux(File video, File audio, File out) throws IOException {
            MediaExtractor ve = new MediaExtractor();
            MediaExtractor ae = new MediaExtractor();
            MediaMuxer muxer = null;
            boolean started = false;
            try {
                ve.setDataSource(video.getAbsolutePath());
                ae.setDataSource(audio.getAbsolutePath());
                int vt = findTrack(ve, "video/");
                int at = findTrack(ae, "audio/");
                if (vt < 0) throw new IOException("Keine Bildspur");
                if (at < 0) throw new IOException("Keine Tonspur");
                ve.selectTrack(vt);
                ae.selectTrack(at);
                MediaFormat vf = ve.getTrackFormat(vt);
                MediaFormat af = ae.getTrackFormat(at);
                long durUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0;

                muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int mv = muxer.addTrack(vf);
                int ma = muxer.addTrack(af);
                muxer.start();
                started = true;

                int cap = 2 * 1024 * 1024;
                if (vf.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    cap = Math.max(cap, vf.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                ByteBuffer buf = ByteBuffer.allocateDirect(cap);
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                boolean vDone = false, aDone = false;
                while (!vDone || !aDone) {
                    boolean takeVideo;
                    if (vDone) takeVideo = false;
                    else if (aDone) takeVideo = true;
                    else takeVideo = ve.getSampleTime() <= ae.getSampleTime();
                    MediaExtractor ex = takeVideo ? ve : ae;
                    buf.clear();
                    int size = ex.readSampleData(buf, 0);
                    if (size < 0) {
                        if (takeVideo) vDone = true;
                        else aDone = true;
                        continue;
                    }
                    bi.offset = 0;
                    bi.size = size;
                    bi.presentationTimeUs = ex.getSampleTime();
                    bi.flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                    muxer.writeSampleData(takeVideo ? mv : ma, buf, bi);
                    ex.advance();
                    if (takeVideo) progress("Wird zusammengefügt", bi.presentationTimeUs, durUs);
                }
            } finally {
                if (muxer != null) {
                    try {
                        if (started) muxer.stop();
                    } catch (Exception ignored) {
                    }
                    muxer.release();
                }
                ve.release();
                ae.release();
            }
        }

        /** In den öffentlichen Ordner kopieren (Musik bzw. Filme). */
        Uri publish(File file, String displayName, String mime, boolean music) throws IOException {
            progress("Wird gespeichert …", 0, 0);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = getContentResolver();
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        (music ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES) + "/" + FOLDER);
                cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
                if (music) {
                    cv.put(MediaStore.Audio.Media.TITLE, title);
                    if (artist != null) cv.put(MediaStore.Audio.Media.ARTIST, artist);
                }
                Uri collection = music
                        ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                Uri uri = cr.insert(collection, cv);
                if (uri == null) throw new IOException("Speichern nicht möglich");
                try (InputStream in = new FileInputStream(file);
                     OutputStream os = cr.openOutputStream(uri)) {
                    if (os == null) throw new IOException("Speichern nicht möglich");
                    copy(in, os);
                }
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                return uri;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        music ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_MOVIES), FOLDER);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File target = new File(dir, displayName);
                int n = 1;
                while (target.exists()) {
                    int dot = displayName.lastIndexOf('.');
                    target = new File(dir, displayName.substring(0, dot) + " (" + (n++) + ")"
                            + displayName.substring(dot));
                }
                try (InputStream in = new FileInputStream(file);
                     OutputStream os = new FileOutputStream(target)) {
                    copy(in, os);
                }
                MediaScannerConnection.scanFile(DownloadService.this,
                        new String[]{target.getAbsolutePath()}, new String[]{mime}, null);
                return Uri.fromFile(target);
            }
        }

        void finished(String what, String name, Uri uri, String mime) {
            NotificationCompat.Builder b = new NotificationCompat.Builder(DownloadService.this, App.CH_DOWNLOAD)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(what)
                    .setContentText(name)
                    .setAutoCancel(true);
            if (Build.VERSION.SDK_INT >= 29) {
                Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                b.setContentIntent(PendingIntent.getActivity(DownloadService.this, id, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            nm.notify(id, b.build());
        }
    }

    // ------------------------------------------------------------------ Hilfen

    @SuppressWarnings("deprecation")
    private static ByteBuffer inputBuffer(MediaCodec c, int i) {
        if (Build.VERSION.SDK_INT >= 21) return c.getInputBuffer(i);
        return c.getInputBuffers()[i];
    }

    @SuppressWarnings("deprecation")
    private static ByteBuffer outputBuffer(MediaCodec c, int i) {
        if (Build.VERSION.SDK_INT >= 21) return c.getOutputBuffer(i);
        return c.getOutputBuffers()[i];
    }

    private static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    /** Mehrkanal (z. B. 5.1) auf Stereo: nimmt die ersten beiden Kanäle. */
    private static int downmix(byte[] pcm, int size, int channels) {
        int frames = size / (2 * channels);
        for (int f = 0; f < frames; f++) {
            int src = f * channels * 2, dst = f * 4;
            pcm[dst] = pcm[src];
            pcm[dst + 1] = pcm[src + 1];
            pcm[dst + 2] = pcm[src + 2];
            pcm[dst + 3] = pcm[src + 3];
        }
        return frames * 4;
    }

    private static void swap(byte[] pcm, int len) {
        for (int i = 0; i + 1 < len; i += 2) {
            byte t = pcm[i];
            pcm[i] = pcm[i + 1];
            pcm[i + 1] = t;
        }
    }

    private static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) os.write(b, 0, n);
        os.flush();
    }
}
