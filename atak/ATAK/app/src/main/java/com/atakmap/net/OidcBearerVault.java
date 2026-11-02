package com.atakmap.net;

import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Holds OIDC bearers behind opaque, one-shot handles so a bearer is never
 * written into a Parcel or Intent. DeviceProfileRequest travels through
 * DataDroid as a Parcelable.
 */
public final class OidcBearerVault {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final ConcurrentHashMap<String, Entry> BEARERS =
            new ConcurrentHashMap<>();

    /**
     * F04: a bearer that nobody took (dispatch failure, missing callback) must
     * not live forever. Every put and take evicts expired entries first, and
     * (v02, Astra rank 10) every entry also has its own scheduled removal, so
     * an abandoned bearer leaves memory at its expiry even when the vault is
     * never touched again.
     */
    static final long TTL_MILLIS = 2L * 60L * 1000L;

    /**
     * Bounded capacity (v02, Astra rank 10). Enrollment holds at most one
     * bearer per attempt and attempts are serialised by the UI; a full vault
     * means handles are leaking and the put is refused rather than growing.
     */
    static final int MAX_ENTRIES = 4;

    private static final ScheduledExecutorService SWEEPER =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "oidc-bearer-vault");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static final class Entry {
        final String assertion;
        final long expiresAt;

        Entry(String assertion, long expiresAt) {
            this.assertion = assertion;
            this.expiresAt = expiresAt;
        }
    }

    private static void evictExpired() {
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<String, Entry> e : BEARERS.entrySet()) {
            if (e.getValue().expiresAt <= now) {
                BEARERS.remove(e.getKey(), e.getValue());
            }
        }
    }

    private OidcBearerVault() {
    }

    public static String put(String assertion) {
        return put(assertion, TTL_MILLIS);
    }

    /**
     * @throws IllegalStateException when the vault already holds
     *         {@link #MAX_ENTRIES} live bearers.
     */
    static String put(String assertion, long ttlMillis) {
        if (assertion == null) {
            throw new IllegalArgumentException("assertion is required");
        }

        evictExpired();
        if (BEARERS.size() >= MAX_ENTRIES) {
            throw new IllegalStateException("bearer vault is full");
        }
        long expiresAt = System.currentTimeMillis() + ttlMillis;
        while (true) {
            byte[] bytes = new byte[16];
            RANDOM.nextBytes(bytes);
            char[] chars = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                chars[i * 2] = HEX[value >>> 4];
                chars[i * 2 + 1] = HEX[value & 0x0f];
            }

            final String handle = new String(chars);
            final Entry entry = new Entry(assertion, expiresAt);
            if (BEARERS.putIfAbsent(handle, entry) == null) {
                SWEEPER.schedule(new Runnable() {
                    @Override
                    public void run() {
                        // Removes this entry only; a handle reused after a
                        // take is a different Entry and is left alone.
                        BEARERS.remove(handle, entry);
                    }
                }, ttlMillis, TimeUnit.MILLISECONDS);
                return handle;
            }
        }
    }

    public static String take(String handle) {
        evictExpired();
        if (handle == null) {
            return null;
        }
        Entry entry = BEARERS.remove(handle);
        if (entry == null || entry.expiresAt <= System.currentTimeMillis()) {
            return null;
        }
        return entry.assertion;
    }

    /** Live entries, for tests and diagnostics. Never returns a bearer. */
    static int size() {
        evictExpired();
        return BEARERS.size();
    }

    /** Entries physically held, without eviction: what a sweep leaves behind. */
    static int rawSize() {
        return BEARERS.size();
    }

    public static void clear() {
        BEARERS.clear();
    }
}
