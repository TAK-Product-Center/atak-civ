
package com.atakmap.android.track.ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class TrackDateTimeFormatter {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    public static TimeZone resolveTimeZone(String tzPref) {
        if ("Local".equals(tzPref)) {
            return TimeZone.getDefault();
        }
        return UTC;
    }

    public static String formatDate(long millis, TimeZone tz, Locale locale) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", locale);
        sdf.setTimeZone(tz);
        return sdf.format(new Date(millis));
    }

    public static String formatTime(long millis, TimeZone tz, Locale locale) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", locale);
        sdf.setTimeZone(tz);
        String time = sdf.format(new Date(millis));
        if ("UTC".equals(tz.getID())) {
            return time + "Z";
        }
        return time + tz.getDisplayName(
                tz.inDaylightTime(new Date(millis)),
                TimeZone.SHORT, locale);
    }
}
