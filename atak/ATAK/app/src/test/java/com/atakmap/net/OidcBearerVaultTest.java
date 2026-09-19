package com.atakmap.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** v02 (Astra rank 10): active expiry and bounded capacity of the bearer vault. */
public final class OidcBearerVaultTest {

    @Before
    public void emptyVault() {
        OidcBearerVault.clear();
    }

    @After
    public void leaveEmpty() {
        OidcBearerVault.clear();
    }

    @Test
    public void takeIsOneShotAndHandlesDiffer() {
        String first = OidcBearerVault.put("assertion-one");
        String second = OidcBearerVault.put("assertion-two");
        assertNotEquals(first, second);
        assertEquals("assertion-one", OidcBearerVault.take(first));
        assertNull(OidcBearerVault.take(first));
        assertEquals("assertion-two", OidcBearerVault.take(second));
        assertEquals(0, OidcBearerVault.size());
    }

    /** Astra probe: an expired entry leaves memory with no further put or take. */
    @Test
    public void expiredEntryIsRemovedWithoutAnyFurtherVaultCall() throws Exception {
        OidcBearerVault.put("short-lived", 150L);
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            if (OidcBearerVault.rawSize() == 0) {
                return;
            }
            Thread.sleep(25);
        }
        fail("expired bearer still held after 5 s with no vault calls");
    }

    @Test
    public void expiredHandleIsNeverReturnedByTake() throws Exception {
        String handle = OidcBearerVault.put("stale", 1L);
        Thread.sleep(20);
        assertNull(OidcBearerVault.take(handle));
    }

    @Test
    public void capacityIsBoundedAndRefusesRatherThanGrowing() {
        for (int i = 0; i < OidcBearerVault.MAX_ENTRIES; i++) {
            OidcBearerVault.put("bearer-" + i);
        }
        try {
            OidcBearerVault.put("one-too-many");
            fail("vault accepted more than MAX_ENTRIES live bearers");
        } catch (IllegalStateException expected) {
            assertEquals(OidcBearerVault.MAX_ENTRIES, OidcBearerVault.size());
        }
    }

    @Test
    public void expiredEntriesDoNotCountAgainstCapacity() throws Exception {
        for (int i = 0; i < OidcBearerVault.MAX_ENTRIES; i++) {
            OidcBearerVault.put("bearer-" + i, 1L);
        }
        Thread.sleep(20);
        String handle = OidcBearerVault.put("fresh");
        assertEquals("fresh", OidcBearerVault.take(handle));
    }
}
