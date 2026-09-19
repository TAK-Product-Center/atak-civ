package com.atakmap.android.oidc.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/** One-shot HTTP receiver bound only to IPv4 loopback. */
public final class LoopbackRedirectReceiver implements RedirectReceiver {

    public static final String PATH = "/oidc/callback";

    private static final String SUCCESS_BODY =
            "<!doctype html><html><body>You can return to ATAK</body></html>";
    private static final String NOT_FOUND_BODY = "Not found";

    private final Object lock = new Object();
    private ServerSocket serverSocket;
    private boolean cancelled;

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
                InetAddress loopback = InetAddress.getLoopbackAddress();
                if (loopback.getAddress().length != 4) {
                    loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
                }
                ServerSocket socket = new ServerSocket();
                socket.bind(new InetSocketAddress(loopback, 0));
                serverSocket = socket;
                cancelled = false;
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
         * T12: a stray or hostile local connection that is not the callback
         * (wrong path, wrong method) gets a 404 and the listener keeps waiting.
         * Closing the port on a bad request would free it for another process
         * to bind before the browser delivers the real redirect.
         */
        long deadline = System.nanoTime() + Math.max(0, timeoutMillis) * 1000000L;
        try {
            while (true) {
                long remaining = (deadline - System.nanoTime()) / 1000000L;
                if (remaining <= 0) {
                    throw cancelled();
                }
                socket.setSoTimeout(timeout(remaining));
                Socket client = null;
                try {
                    client = socket.accept();
                    if (!client.getInetAddress().isLoopbackAddress()) {
                        continue;
                    }
                    client.setSoTimeout(timeout(Math.min(remaining, 5000L)));
                    BufferedReader reader = new BufferedReader(new InputStreamReader(
                            client.getInputStream(), StandardCharsets.US_ASCII));
                    String redirect = requestLineToUri(reader.readLine(),
                            socket.getLocalPort());
                    writeResponse(client.getOutputStream(), redirect != null);
                    if (redirect != null) {
                        return redirect;
                    }
                } catch (SocketTimeoutException e) {
                    if (client == null) {
                        throw cancelled();
                    }
                    // A slow or silent client: drop it and keep listening.
                } finally {
                    if (client != null) {
                        try {
                            client.close();
                        } catch (IOException ignored) {
                            // The response is already complete.
                        }
                    }
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
        synchronized (lock) {
            cancelled = true;
            closeSocket(serverSocket);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closeSocket(serverSocket);
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
