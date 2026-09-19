package com.atakmap.android.oidc.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * RFC 8252 section 7.3 loopback receiver: one HTTP request line on an
 * ephemeral 127.0.0.1 port, alive for one attempt only.
 */
public final class LoopbackRedirectReceiver implements RedirectReceiver {

    public static final String PATH = "/oidc/callback";

    /** Longest request line accepted; anything longer is dropped (T12). */
    static final int MAX_REQUEST_LINE = 8192;
    /** Longest a single client may take to send its request line. */
    private static final long CLIENT_READ_MILLIS = 5000L;

    private static final String SUCCESS_BODY =
            "<!doctype html><html><body>You can return to ATAK</body></html>";
    private static final String NOT_FOUND_BODY = "Not found";

    private final Object lock = new Object();
    private ServerSocket serverSocket;
    private Socket client;
    private boolean cancelled;
    private String expectedState;

    public static String redirectUriFor(int port) {
        return "http://127.0.0.1:" + port + PATH;
    }

    public static String requestLineToUri(String requestLine, int port) {
        if (requestLine == null) {
            return null;
        }
        String[] parts = requestLine.split(" ", -1);
        if (parts.length != 3 || !"GET".equals(parts[0])
                || !("HTTP/1.1".equals(parts[2]) || "HTTP/1.0".equals(parts[2]))) {
            return null;
        }
        String target = parts[1];
        if (!(PATH.equals(target) || target.startsWith(PATH + "?"))) {
            return null;
        }
        return redirectUriFor(port) + target.substring(PATH.length());
    }

    @Override
    public void start() throws OidcException {
        synchronized (lock) {
            if (serverSocket != null && !serverSocket.isClosed()) {
                throw new OidcException(OidcError.BROWSER_CANCELLED,
                        "redirect receiver already started");
            }
            try {
                InetAddress loopback = InetAddress.getByAddress(
                        new byte[] {127, 0, 0, 1});
                ServerSocket socket = new ServerSocket();
                socket.bind(new InetSocketAddress(loopback, 0));
                serverSocket = socket;
                cancelled = false;
                // expectedState is kept: it is bound before start (v02).
            } catch (IOException e) {
                throw new OidcException(OidcError.BROWSER_CANCELLED,
                        "unable to start redirect receiver", e);
            }
        }
    }

    @Override
    public String getRedirectUri() {
        synchronized (lock) {
            return serverSocket == null ? null
                    : redirectUriFor(serverSocket.getLocalPort());
        }
    }

    @Override
    public void bindState(String state) {
        synchronized (lock) {
            expectedState = state;
        }
    }

    InetAddress getBoundAddress() {
        synchronized (lock) {
            return serverSocket == null ? null : serverSocket.getInetAddress();
        }
    }

    @Override
    public String awaitRedirect(long timeoutMillis) throws OidcException {
        ServerSocket socket;
        synchronized (lock) {
            socket = serverSocket;
        }
        if (socket == null || socket.isClosed()) {
            throw cancelled();
        }

        /*
         * T12: a stray or hostile local connection (wrong path, wrong method,
         * wrong state, oversized line, silent client) gets a reply or is dropped
         * and the listener keeps waiting. Closing the port on a bad request
         * would free it for another process to bind before the browser delivers
         * the real redirect. One absolute deadline covers every connection and
         * every single read inside a connection (v02, Astra rank 8: a client
         * dripping one byte per 100 ms must not hold the attempt open past it).
         */
        long deadline = System.nanoTime() + Math.max(0, timeoutMillis) * 1000000L;
        try {
            while (true) {
                long remaining = (deadline - System.nanoTime()) / 1000000L;
                if (remaining <= 0 || isCancelled()) {
                    throw cancelled();
                }
                socket.setSoTimeout(timeout(remaining));
                Socket accepted = null;
                try {
                    accepted = socket.accept();
                    synchronized (lock) {
                        client = accepted;
                    }
                    if (!accepted.getInetAddress().isLoopbackAddress()) {
                        continue;
                    }
                    String line = readRequestLine(accepted, deadline);
                    String redirect = requestLineToUri(line, socket.getLocalPort());
                    boolean accept = redirect != null
                            && stateMatches(redirect);
                    writeResponse(accepted.getOutputStream(), accept);
                    if (accept) {
                        return redirect;
                    }
                } catch (SocketTimeoutException e) {
                    if (accepted == null) {
                        throw cancelled();
                    }
                    // A slow or silent client: drop it and keep listening.
                } catch (IOException e) {
                    if (accepted == null) {
                        throw e;
                    }
                    // A broken client connection: drop it and keep listening.
                } finally {
                    synchronized (lock) {
                        client = null;
                    }
                    closeQuietly(accepted);
                }
            }
        } catch (SocketException e) {
            throw cancelled();
        } catch (IOException e) {
            throw new OidcException(OidcError.BROWSER_CANCELLED,
                    "redirect receiver failed", e);
        } finally {
            closeSocket(socket);
        }
    }

    private boolean stateMatches(String redirect) {
        String expected;
        synchronized (lock) {
            expected = expectedState;
        }
        return RedirectMatch.stateMatches(redirect, expected);
    }

    private boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    /**
     * Reads one CRLF or LF terminated line of at most MAX_REQUEST_LINE bytes.
     * Every read is bounded by the per-client inactivity timeout and by the
     * absolute deadline, whichever comes first; passing the deadline ends the
     * attempt.
     */
    private String readRequestLine(Socket accepted, long deadline)
            throws IOException, OidcException {
        InputStream in = accepted.getInputStream();
        byte[] buffer = new byte[MAX_REQUEST_LINE];
        int n = 0;
        while (true) {
            long remaining = (deadline - System.nanoTime()) / 1000000L;
            if (remaining <= 0 || isCancelled()) {
                throw cancelled();
            }
            accepted.setSoTimeout(timeout(Math.min(remaining, CLIENT_READ_MILLIS)));
            int b = in.read();
            if (b < 0) {
                return null;
            }
            if (b == '\n') {
                break;
            }
            if (n == buffer.length) {
                return null;
            }
            buffer[n++] = (byte) b;
        }
        if (n > 0 && buffer[n - 1] == '\r') {
            n--;
        }
        return new String(buffer, 0, n, StandardCharsets.US_ASCII);
    }

    private static int timeout(long timeoutMillis) {
        if (timeoutMillis <= 0) {
            return 1;
        }
        return (int) Math.min(timeoutMillis, Integer.MAX_VALUE);
    }

    private static void writeResponse(OutputStream output, boolean success) throws IOException {
        byte[] body = (success ? SUCCESS_BODY : NOT_FOUND_BODY)
                .getBytes(StandardCharsets.UTF_8);
        String status = success ? "200 OK" : "404 Not Found";
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static OidcException cancelled() {
        return new OidcException(OidcError.BROWSER_CANCELLED,
                "redirect cancelled or timed out");
    }

    @Override
    public void cancel() {
        Socket current;
        synchronized (lock) {
            cancelled = true;
            current = client;
            client = null;
            closeSocket(serverSocket);
        }
        // Also interrupt a read in progress on an accepted connection.
        closeQuietly(current);
    }

    @Override
    public void close() {
        cancel();
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best effort.
            }
        }
    }

    private void closeSocket(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Closing is best effort and idempotent.
            }
            synchronized (lock) {
                if (serverSocket == socket) {
                    serverSocket = null;
                }
            }
        }
    }
}
