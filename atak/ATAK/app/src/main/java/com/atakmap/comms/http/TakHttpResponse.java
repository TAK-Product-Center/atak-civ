
package com.atakmap.comms.http;

import androidx.annotation.NonNull;

import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.util.Collections2;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Set;

import gov.tak.api.engine.net.IResponse;

/**
 * Encapsulates an HTTP response
 */
public class TakHttpResponse implements IResponse {
    private static final String TAG = "TakHttpResponse";

    private final HttpRequestBase _request;
    private final HttpResponse _response;
    private final StatusLine _statusLine;
    private final int _statusCode;
    private HttpEntity _entity;
    private final Set<InputStream> _contentStreams = Collections2
            .newIdentityHashSet();

    public TakHttpResponse(HttpRequestBase request, HttpResponse response) {
        this._request = request;
        this._response = response;

        _statusLine = _response.getStatusLine();
        _statusCode = _statusLine.getStatusCode();

        if (isOk() || isCreated()) {
            Log.d(TAG, "Op: " + _request.getRequestLine() + ", response: "
                    + _statusCode);
        } else {
            // The reason phrase is peer controlled text; log its length only.
            Log.w(TAG, "Op: " + _request.getRequestLine() + ", response: "
                    + _statusCode + ", reason length "
                    + safeLength(_statusLine.getReasonPhrase()));
        }
    }

    public HttpResponse getResponse() {
        return _response;
    }

    public String getRequestUrl() {
        URI uri = _request.getURI();
        if (uri != null)
            return uri.toString();

        return null;
    }

    public StatusLine getStatusLine() {
        return _statusLine;
    }

    public int getStatusCode() {
        return _statusCode;
    }

    public void verifyOk() throws TakHttpException {
        verify(HttpStatus.SC_OK);
    }

    public boolean isOk() {
        return _statusCode == HttpStatus.SC_OK;
    }

    public boolean isCreated() {
        return _statusCode == HttpStatus.SC_CREATED;
    }

    public void verify(int status) throws TakHttpException {
        if (!isStatus(status)) {
            // Status code only: the full response carries peer headers and
            // reason text that must never reach logcat.
            Log.w(TAG, "HTTP operation: " + _request.getRequestLine()
                    + " expected: " + status + ", got: " + _statusCode);
            String errorMessage = getReasonPhrase();
            if (FileSystemUtils.isEmpty(errorMessage))
                errorMessage = "HTTP operation failed: " + _statusCode;
            throw new TakHttpException(errorMessage, _statusCode);
        }
    }

    public boolean isStatus(int status) {
        return _statusCode == status;
    }

    public String getStringEntity() throws IOException {
        return getStringEntity(null);
    }

    public String getStringEntity(String verify) throws IOException {
        String ret = EntityUtils.toString(getEntity(),
                FileSystemUtils.UTF8_CHARSET.name());
        if (!FileSystemUtils.isEmpty(verify)) {
            if (FileSystemUtils.isEmpty(ret)) {
                Log.w(TAG, "Failed to parse response body");
                throw new TakHttpException("Failed to parse response body",
                        _statusCode);
            }

            //validate expected contents
            if (!ret.contains(verify)) {
                // Never copy the peer body into a log line or an exception
                // message (callers log messages): report its length.
                Log.w(TAG, "Failed to parse expected response body: " + verify
                        + ", body length " + ret.length());
                throw new TakHttpException(
                        "Failed to parse expected response body, length "
                                + ret.length(),
                        _statusCode);
            }
        }

        return ret;
    }

    public HttpEntity getEntity() {
        checkGZip();
        if (_entity == null)
            _entity = _response.getEntity();
        return _entity;
    }

    private void checkGZip() {
        //TODO refactor to use addRequestInterceptor & addResponseInterceptor?
        Header contentEncoding = getHeader("Content-Encoding");
        if (contentEncoding != null
                && HttpUtil.GZIP.equalsIgnoreCase(contentEncoding.getValue())) {
            Log.d(TAG, "Response deflating Gzip");
            _response.setEntity(new GzipInflatingEntity(_response.getEntity()));
        } else {
            //Log.d(TAG, "Response not compressed");
        }
    }

    public String getReasonPhrase() {
        return _statusLine.getReasonPhrase();
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    public Header getHeader(String name) {
        return _response.getFirstHeader(name);
    }

    public long getHeaderLong(String name) {
        long value = -1;
        Header h = getHeader(name);
        if (h == null || FileSystemUtils.isEmpty(h.getValue())) {
            //TODO throw instead?
            return value;
        }

        try {
            value = Long.parseLong(h.getValue());
        } catch (Exception e) {
            Log.w(TAG, "Unable to determine header: " + name, e);
            value = -1;
        }

        return value;
    }

    public String getHeaderString(String name) {
        Header h = getHeader(name);
        if (h == null || FileSystemUtils.isEmpty(h.getValue())) {
            //TODO throw instead?
            return null;
        }

        return h.getValue();
    }

    public long getContentLength() {
        return getHeaderLong("Content-Length");
    }

    public String getContentType() {
        return getHeaderString("Content-Type");
    }

    @NonNull
    @Override
    public String toString() {
        return "" + _statusCode + ": " + _statusLine.getReasonPhrase();
    }

    @Override
    public InputStream getBody() {
        try {
            synchronized (_contentStreams) {
                final InputStream body = getEntity().getContent();
                _contentStreams.add(body);
                return body;
            }
        } catch (final IOException e) {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    throw e;
                }
            };
        }
    }

    @Override
    public final int getCode() {
        return getStatusCode();
    }

    @Override
    public final long getLength() {
        return getContentLength();
    }

    @Override
    public final void close() {
        InputStream[] contentStreams = new InputStream[0];
        synchronized (_contentStreams) {
            if (!_contentStreams.isEmpty()) {
                contentStreams = _contentStreams.toArray(contentStreams);
                _contentStreams.clear();
            }
        }
        for (InputStream is : contentStreams) {
            try {
                is.close();
            } catch (IOException ignored) {
            }
        }
        try {
            if (_entity != null)
                _entity.consumeContent();
        } catch (IOException ignored) {
        }
    }
}
