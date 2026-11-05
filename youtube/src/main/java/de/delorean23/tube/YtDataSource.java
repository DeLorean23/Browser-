package de.delorean23.tube;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Lädt YouTube-Mediendaten so wie die NewPipe-App (POST + rn + Range),
 * sonst blockt YouTube die Anfragen mit 403.
 * Created by DeLorean23
 */
public final class YtDataSource extends BaseDataSource {

    public static final class Factory implements DataSource.Factory {
        @NonNull
        @Override
        public DataSource createDataSource() {
            return new YtDataSource();
        }
    }

    @Nullable private DataSpec spec;
    @Nullable private Response response;
    @Nullable private InputStream stream;
    private long bytesRemaining;
    private boolean opened;

    public YtDataSource() {
        super(true);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        spec = dataSpec;
        transferInitializing(dataSpec);
        long start = dataSpec.position;
        long end = dataSpec.length == C.LENGTH_UNSET ? -1 : start + dataSpec.length - 1;
        okhttp3.Request req = NetDownloader.get().mediaRequest(dataSpec.uri.toString(), start, end);
        Response r;
        try {
            r = NetDownloader.get().client().newCall(req).execute();
        } catch (IOException e) {
            throw new HttpDataSource.HttpDataSourceException(e, dataSpec,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    HttpDataSource.HttpDataSourceException.TYPE_OPEN);
        }
        int code = r.code();
        if (code < 200 || code > 299) {
            Map<String, List<String>> headers = r.headers().toMultimap();
            r.close();
            throw new HttpDataSource.InvalidResponseCodeException(code, r.message(), null,
                    headers, dataSpec, new byte[0]);
        }
        response = r;
        ResponseBody body = r.body();
        if (body == null) throw new IOException("Leere Antwort");
        stream = body.byteStream();

        long skip = 0;
        if (code == 200 && start > 0) {
            // Server hat Range ignoriert -> selbst vorspulen
            skip = start;
        }
        long contentLength = body.contentLength();
        if (dataSpec.length != C.LENGTH_UNSET) {
            bytesRemaining = dataSpec.length;
        } else if (contentLength != -1) {
            bytesRemaining = contentLength - skip;
        } else {
            bytesRemaining = C.LENGTH_UNSET;
        }
        while (skip > 0) {
            long n = stream.skip(skip);
            if (n <= 0) {
                if (stream.read() < 0) throw new IOException("Ende beim Vorspulen");
                n = 1;
            }
            skip -= n;
        }
        opened = true;
        transferStarted(dataSpec);
        return bytesRemaining;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
        int toRead = bytesRemaining == C.LENGTH_UNSET ? length
                : (int) Math.min(length, bytesRemaining);
        int n;
        try {
            n = stream.read(buffer, offset, toRead);
        } catch (IOException e) {
            throw new HttpDataSource.HttpDataSourceException(e, spec,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    HttpDataSource.HttpDataSourceException.TYPE_READ);
        }
        if (n == -1) return C.RESULT_END_OF_INPUT;
        if (bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= n;
        bytesTransferred(n);
        return n;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return spec != null ? spec.uri : null;
    }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return response != null ? response.headers().toMultimap() : Collections.emptyMap();
    }

    @Override
    public void close() {
        if (response != null) {
            response.close();
            response = null;
        }
        stream = null;
        if (opened) {
            opened = false;
            transferEnded();
        }
    }
}
