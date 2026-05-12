
package com.atakmap.android.track.ui;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class TrackDateTimeFormatterTest {

    // 2021-01-01 00:00:00 UTC
    private static final long MIDNIGHT_2021_UTC = 1609459200000L;

    private TimeZone originalDefault;

    @Before
    public void setUp() {
        originalDefault = TimeZone.getDefault();
    }

    @After
    public void tearDown() {
        TimeZone.setDefault(originalDefault);
    }

    // --- AC 1 & 3: Default/explicit UTC returns UTC-formatted date and "Z" suffix ---

    @Test
    public void test_resolveTimeZone_utc_returns_utc() {
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("UTC");
        assertEquals("UTC", tz.getID());
    }

    @Test
    public void test_formatDate_utc_returns_utc_date() {
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("UTC");
        String date = TrackDateTimeFormatter.formatDate(
                MIDNIGHT_2021_UTC, tz, Locale.US);
        assertEquals("2021-01-01", date);
    }

    @Test
    public void test_formatTime_utc_returns_z_suffix() {
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("UTC");
        String time = TrackDateTimeFormatter.formatTime(
                MIDNIGHT_2021_UTC, tz, Locale.US);
        assertEquals("00:00:00Z", time);
    }

    // --- AC 2 & 5: "Local" with America/New_York returns local date and tz abbreviation ---

    @Test
    public void test_resolveTimeZone_local_returns_device_default() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("Local");
        assertEquals("America/New_York", tz.getID());
    }

    @Test
    public void test_formatDate_local_new_york_crosses_day_boundary() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("Local");
        String date = TrackDateTimeFormatter.formatDate(
                MIDNIGHT_2021_UTC, tz, Locale.US);
        assertEquals("2020-12-31", date);
    }

    @Test
    public void test_formatTime_local_new_york_shows_est() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("Local");
        String time = TrackDateTimeFormatter.formatTime(
                MIDNIGHT_2021_UTC, tz, Locale.US);
        assertEquals("19:00:00EST", time);
    }

    // --- AC 3: Unrecognized preference value falls back to UTC ---

    @Test
    public void test_resolveTimeZone_unrecognized_falls_back_to_utc() {
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("garbage");
        assertEquals("UTC", tz.getID());
    }

    @Test
    public void test_resolveTimeZone_null_falls_back_to_utc() {
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone(null);
        assertEquals("UTC", tz.getID());
    }

    @Test
    public void test_resolveTimeZone_empty_falls_back_to_utc() {
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("");
        assertEquals("UTC", tz.getID());
    }

    // --- AC 4 (edge case): Exact midnight UTC ---

    @Test
    public void test_formatDate_exact_midnight_utc() {
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("UTC");
        String date = TrackDateTimeFormatter.formatDate(
                MIDNIGHT_2021_UTC, tz, Locale.US);
        assertEquals("2021-01-01", date);
    }

    @Test
    public void test_formatTime_exact_midnight_utc() {
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("UTC");
        String time = TrackDateTimeFormatter.formatTime(
                MIDNIGHT_2021_UTC, tz, Locale.US);
        assertEquals("00:00:00Z", time);
    }

    // --- AC 5 (edge case): Positive offset timezone (Asia/Kolkata, UTC+5:30) ---

    @Test
    public void test_formatDate_kolkata_positive_offset() {
        TimeZone tz = TimeZone.getTimeZone("Asia/Kolkata");
        // 2021-01-01 00:00:00 UTC = 2021-01-01 05:30:00 IST
        String date = TrackDateTimeFormatter.formatDate(
                MIDNIGHT_2021_UTC, tz, Locale.US);
        assertEquals("2021-01-01", date);
    }

    @Test
    public void test_formatTime_kolkata_positive_offset() {
        TimeZone tz = TimeZone.getTimeZone("Asia/Kolkata");
        String time = TrackDateTimeFormatter.formatTime(
                MIDNIGHT_2021_UTC, tz, Locale.US);
        // IST = India Standard Time abbreviation; non-UTC so no "Z"
        assertEquals("05:30:00IST", time);
    }

    @Test
    public void test_formatDate_kolkata_positive_offset_crosses_day_forward() {
        // 2020-12-31 20:00:00 UTC = 2021-01-01 01:30:00 IST
        long dec31_2000_utc = MIDNIGHT_2021_UTC - (4 * 3600 * 1000L);
        TimeZone tz = TimeZone.getTimeZone("Asia/Kolkata");
        String date = TrackDateTimeFormatter.formatDate(
                dec31_2000_utc, tz, Locale.US);
        assertEquals("2021-01-01", date);
    }

    @Test
    public void test_formatTime_kolkata_positive_offset_crosses_day_forward() {
        long dec31_2000_utc = MIDNIGHT_2021_UTC - (4 * 3600 * 1000L);
        TimeZone tz = TimeZone.getTimeZone("Asia/Kolkata");
        String time = TrackDateTimeFormatter.formatTime(
                dec31_2000_utc, tz, Locale.US);
        assertEquals("01:30:00IST", time);
    }

    // --- Additional: UTC time suffix is literal "Z", not a timezone abbreviation ---

    @Test
    public void test_formatTime_utc_suffix_is_literal_z_not_abbreviation() {
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("UTC");
        // Use a non-midnight time to verify suffix is always "Z" for UTC
        long nonMidnight = MIDNIGHT_2021_UTC + (13 * 3600 * 1000L)
                + (45 * 60 * 1000L) + (30 * 1000L);
        String time = TrackDateTimeFormatter.formatTime(
                nonMidnight, tz, Locale.US);
        assertEquals("13:45:30Z", time);
    }

    @Test
    public void test_formatDate_utc_non_midnight_same_day() {
        TimeZone tz = TrackDateTimeFormatter.resolveTimeZone("UTC");
        long afternoon = MIDNIGHT_2021_UTC + (15 * 3600 * 1000L);
        String date = TrackDateTimeFormatter.formatDate(
                afternoon, tz, Locale.US);
        assertEquals("2021-01-01", date);
    }
}
