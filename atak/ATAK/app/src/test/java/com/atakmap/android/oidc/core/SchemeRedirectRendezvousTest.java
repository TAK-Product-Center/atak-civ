package com.atakmap.android.oidc.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.fail;
import org.junit.Test;

public final class SchemeRedirectRendezvousTest {

    @Test
    public void deliverBeforeStartReturnsFalse() {
        assertFalse(SchemeRedirectRendezvous.deliver(
                "com.atakmap.app.civ:/oidc/callback?code=x&state=y"));
    }

    @Test
    public void startThenDeliverFromAnotherThreadReturnsRedirect() throws Exception {
        final SchemeRedirectRendezvous receiver = new SchemeRedirectRendezvous();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            receiver.bindState("y");
            receiver.start();
            final String uri = "com.atakmap.app.civ:/oidc/callback?code=x&state=y";
            Future<String> awaited = executor.submit(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return receiver.awaitRedirect(2000);
                }
            });
            Future<Boolean> delivered = executor.submit(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return SchemeRedirectRendezvous.deliver(uri);
                }
            });
            assertTrue(delivered.get(2, TimeUnit.SECONDS));
            assertEquals(uri, awaited.get(2, TimeUnit.SECONDS));
        } finally {
            receiver.close();
            executor.shutdownNow();
        }
    }

    @Test
    public void cancelMakesAwaitRedirectBrowserCancelled() throws Exception {
        SchemeRedirectRendezvous receiver = new SchemeRedirectRendezvous();
        receiver.start();
        receiver.cancel();
        try {
            receiver.awaitRedirect(1000);
        } catch (OidcException expected) {
            assertEquals(OidcError.BROWSER_CANCELLED, expected.getError());
            return;
        } finally {
            receiver.close();
        }
        throw new AssertionError("cancelled receiver returned normally");
    }

    @Test
    public void redirectReceiverFactoryMatchesConfiguredMode() {
        assertTrue(RedirectReceivers.forMode(RedirectMode.LOOPBACK)
                instanceof LoopbackRedirectReceiver);
        assertTrue(RedirectReceivers.forMode(RedirectMode.SCHEME)
                instanceof SchemeRedirectRendezvous);
        assertTrue(RedirectReceivers.forMode(null)
                instanceof SchemeRedirectRendezvous);
    }

    /** v02 (Silas L4, Astra rank 8): a started but unbound receiver accepts nothing. */
    @Test
    public void unboundStartedReceiverRejectsEveryDeliveryAndStillWaits() throws Exception {
        SchemeRedirectRendezvous receiver = new SchemeRedirectRendezvous();
        receiver.start();
        try {
            assertTrue(SchemeRedirectRendezvous.isWaiting());
            assertFalse(SchemeRedirectRendezvous.deliver(
                    SchemeRedirectRendezvous.REDIRECT_URI + "?code=evil&state=x"));
            assertFalse(SchemeRedirectRendezvous.deliver(
                    SchemeRedirectRendezvous.REDIRECT_URI + "?code=evil"));
            // The attempt is intact: binding now lets the real redirect through.
            receiver.bindState("x");
            assertTrue(SchemeRedirectRendezvous.deliver(
                    SchemeRedirectRendezvous.REDIRECT_URI + "?code=good&state=x"));
            assertEquals(SchemeRedirectRendezvous.REDIRECT_URI + "?code=good&state=x",
                    receiver.awaitRedirect(1000));
        } finally {
            receiver.close();
        }
    }

    /** v02: the state bound before start survives start, so publication is never unbound. */
    @Test
    public void stateBoundBeforeStartIsHonouredImmediatelyAfterStart() throws Exception {
        SchemeRedirectRendezvous receiver = new SchemeRedirectRendezvous();
        receiver.bindState("pre");
        receiver.start();
        try {
            assertFalse(SchemeRedirectRendezvous.deliver(
                    SchemeRedirectRendezvous.REDIRECT_URI + "?code=evil&state=other"));
            assertTrue(SchemeRedirectRendezvous.deliver(
                    SchemeRedirectRendezvous.REDIRECT_URI + "?code=good&state=pre"));
            assertFalse(SchemeRedirectRendezvous.isWaiting());
        } finally {
            receiver.close();
        }
    }

    /** F-24: isWaiting is false when nothing was started, after cancel and after close. */
    @Test
    public void isWaitingIsFalseWithoutAnAttemptAndAfterCancel() throws Exception {
        assertFalse(SchemeRedirectRendezvous.isWaiting());
        SchemeRedirectRendezvous receiver = new SchemeRedirectRendezvous();
        receiver.bindState("s");
        receiver.start();
        assertTrue(SchemeRedirectRendezvous.isWaiting());
        receiver.cancel();
        assertFalse(SchemeRedirectRendezvous.isWaiting());
        receiver.close();
        assertFalse(SchemeRedirectRendezvous.isWaiting());
    }

    /** T12 (F01): a callback with the wrong state is ignored and the attempt keeps waiting. */
    @Test
    public void boundStateIgnoresMismatchedDeliveryAndAcceptsTheRealOne() throws Exception {
        SchemeRedirectRendezvous receiver = new SchemeRedirectRendezvous();
        receiver.start();
        receiver.bindState("expected");
        assertFalse(SchemeRedirectRendezvous.deliver(
                SchemeRedirectRendezvous.REDIRECT_URI + "?code=evil&state=wrong"));
        assertFalse(SchemeRedirectRendezvous.deliver("tak://com.atakmap.app/enroll?state=expected"));
        assertTrue(SchemeRedirectRendezvous.deliver(
                SchemeRedirectRendezvous.REDIRECT_URI + "?code=good&state=expected"));
        assertEquals(SchemeRedirectRendezvous.REDIRECT_URI + "?code=good&state=expected",
                receiver.awaitRedirect(1000));
        try {
            receiver.awaitRedirect(50);
            fail("a consumed redirect must not be returned twice");
        } catch (OidcException e) {
            assertEquals(OidcError.BROWSER_CANCELLED, e.getError());
        }
        receiver.close();
    }
}
