package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.fail;
import org.junit.Test;

public final class LoopbackRedirectReceiverTest {

    @Test
    public void redirectUriFor49152UsesExactIpv4LoopbackCallback() {
        assertEquals("http://127.0.0.1:49152/oidc/callback",
                LoopbackRedirectReceiver.redirectUriFor(49152));
    }

    @Test
    public void requestLineToUriAcceptsGoodGetLine() {
        assertEquals("http://127.0.0.1:49152/oidc/callback?code=x&state=y",
                LoopbackRedirectReceiver.requestLineToUri(
                        "GET /oidc/callback?code=x&state=y HTTP/1.1", 49152));
    }

    @Test
    public void requestLineToUriRejectsWrongPath() {
        assertNull(LoopbackRedirectReceiver.requestLineToUri(
                "GET /wrong?code=x&state=y HTTP/1.1", 49152));
    }

    @Test
    public void requestLineToUriRejectsPost() {
        assertNull(LoopbackRedirectReceiver.requestLineToUri(
                "POST /oidc/callback?code=x&state=y HTTP/1.1", 49152));
    }

    @Test
    public void liveReceiverReturnsFullUriAndReplies200() throws Exception {
        final LoopbackRedirectReceiver receiver = new LoopbackRedirectReceiver();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Socket client = null;
        try {
            receiver.start();
            assertNotNull(receiver.getRedirectUri());
            assertTrue(receiver.getBoundAddress().isLoopbackAddress());
            int port = port(receiver.getRedirectUri());
            Future<String> redirect = executor.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return receiver.awaitRedirect(2000);
                }
            });

            client = new Socket("127.0.0.1", port);
            OutputStream output = client.getOutputStream();
            output.write(("GET /oidc/callback?code=abc&state=xyz HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            BufferedReader response = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.US_ASCII));
            assertEquals("HTTP/1.1 200 OK", response.readLine());
            assertEquals("http://127.0.0.1:" + port
                    + "/oidc/callback?code=abc&state=xyz",
                    redirect.get(2, TimeUnit.SECONDS));
        } finally {
            if (client != null) {
                client.close();
            }
            receiver.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void secondStartAfterCloseBindsANewPort() throws Exception {
        LoopbackRedirectReceiver receiver = new LoopbackRedirectReceiver();
        try {
            receiver.start();
            int first = port(receiver.getRedirectUri());
            receiver.close();
            receiver.start();
            int second = port(receiver.getRedirectUri());
            assertTrue(first > 0);
            assertTrue(second > 0);
            assertNotEquals(first, second);
        } finally {
            receiver.close();
        }
    }

    @Test
    public void awaitRedirectAfter200MillisecondTimeoutIsBrowserCancelled()
            throws Exception {
        LoopbackRedirectReceiver receiver = new LoopbackRedirectReceiver();
        try {
            receiver.start();
            try {
                receiver.awaitRedirect(200);
            } catch (OidcException expected) {
                assertEquals(OidcError.BROWSER_CANCELLED, expected.getError());
                return;
            }
            throw new AssertionError("timeout did not cancel receiver");
        } finally {
            receiver.close();
        }
    }

    @Test
    public void socketIsBoundToLoopbackAddress() throws Exception {
        LoopbackRedirectReceiver receiver = new LoopbackRedirectReceiver();
        try {
            receiver.start();
            assertTrue(receiver.getBoundAddress().isLoopbackAddress());
            assertEquals("127.0.0.1", receiver.getBoundAddress().getHostAddress());
        } finally {
            receiver.close();
        }
    }

    private static int port(String redirectUri) {
        String prefix = "http://127.0.0.1:";
        int end = redirectUri.indexOf('/', prefix.length());
        return Integer.parseInt(redirectUri.substring(prefix.length(), end));
    }

    /** T12: a stray local request must not free the port before the real redirect arrives. */
    @Test
    public void strayRequestGets404AndReceiverKeepsListeningForTheRealRedirect()
            throws Exception {
        final LoopbackRedirectReceiver receiver = new LoopbackRedirectReceiver();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            receiver.start();
            final int port = port(receiver.getRedirectUri());
            Future<String> redirect = executor.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return receiver.awaitRedirect(4000);
                }
            });
            Socket stray = new Socket("127.0.0.1", port);
            stray.getOutputStream().write(("GET /other HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            stray.getOutputStream().flush();
            BufferedReader strayResponse = new BufferedReader(new InputStreamReader(
                    stray.getInputStream(), StandardCharsets.US_ASCII));
            assertEquals("HTTP/1.1 404 Not Found", strayResponse.readLine());
            stray.close();

            Socket client = new Socket("127.0.0.1", port);
            client.getOutputStream().write(("GET /oidc/callback?code=abc&state=xyz HTTP/1.1\r\n"
                    + "Host: 127.0.0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            BufferedReader response = new BufferedReader(new InputStreamReader(
                    client.getInputStream(), StandardCharsets.US_ASCII));
            assertEquals("HTTP/1.1 200 OK", response.readLine());
            assertEquals("http://127.0.0.1:" + port + "/oidc/callback?code=abc&state=xyz",
                    redirect.get(3, TimeUnit.SECONDS));
            client.close();
        } finally {
            receiver.close();
            executor.shutdownNow();
        }
    }

    /** T12 (F01, F02): wrong state and an oversized line are ignored; the real redirect still lands. */
    @Test
    public void boundStateIgnoresWrongStateAndOversizedLines() throws Exception {
        final LoopbackRedirectReceiver receiver = new LoopbackRedirectReceiver();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            receiver.start();
            receiver.bindState("expected");
            final int port = port(receiver.getRedirectUri());
            Future<String> redirect = executor.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return receiver.awaitRedirect(6000);
                }
            });
            Socket wrong = new Socket("127.0.0.1", port);
            wrong.getOutputStream().write(("GET /oidc/callback?code=evil&state=wrong HTTP/1.1\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            wrong.getOutputStream().flush();
            assertEquals("HTTP/1.1 404 Not Found", new BufferedReader(new InputStreamReader(
                    wrong.getInputStream(), StandardCharsets.US_ASCII)).readLine());
            wrong.close();

            Socket huge = new Socket("127.0.0.1", port);
            byte[] filler = new byte[LoopbackRedirectReceiver.MAX_REQUEST_LINE + 10];
            java.util.Arrays.fill(filler, (byte) 'a');
            huge.getOutputStream().write("GET /oidc/callback?state=".getBytes(StandardCharsets.US_ASCII));
            huge.getOutputStream().write(filler);
            huge.getOutputStream().flush();
            assertEquals("HTTP/1.1 404 Not Found", new BufferedReader(new InputStreamReader(
                    huge.getInputStream(), StandardCharsets.US_ASCII)).readLine());
            huge.close();

            Socket good = new Socket("127.0.0.1", port);
            good.getOutputStream().write(("GET /oidc/callback?code=abc&state=expected HTTP/1.1\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            good.getOutputStream().flush();
            assertEquals("HTTP/1.1 200 OK", new BufferedReader(new InputStreamReader(
                    good.getInputStream(), StandardCharsets.US_ASCII)).readLine());
            assertEquals("http://127.0.0.1:" + port + "/oidc/callback?code=abc&state=expected",
                    redirect.get(3, TimeUnit.SECONDS));
            good.close();
        } finally {
            receiver.close();
            executor.shutdownNow();
        }
    }

    /** F02: cancel() interrupts a client that connected and then went silent. */
    @Test
    public void cancelClosesASilentAcceptedClient() throws Exception {
        final LoopbackRedirectReceiver receiver = new LoopbackRedirectReceiver();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            receiver.start();
            final int port = port(receiver.getRedirectUri());
            Future<String> redirect = executor.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return receiver.awaitRedirect(60000);
                }
            });
            Socket silent = new Socket("127.0.0.1", port);
            silent.getOutputStream().write("GET /oidc".getBytes(StandardCharsets.US_ASCII));
            silent.getOutputStream().flush();
            Thread.sleep(200);
            receiver.cancel();
            try {
                redirect.get(3, TimeUnit.SECONDS);
                fail("cancel must end the wait");
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(e.getCause() instanceof OidcException);
                assertEquals(OidcError.BROWSER_CANCELLED, ((OidcException) e.getCause()).getError());
            }
            silent.close();
        } finally {
            receiver.close();
            executor.shutdownNow();
        }
    }
}
