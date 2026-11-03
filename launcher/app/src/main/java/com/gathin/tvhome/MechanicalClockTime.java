package com.gathin.tvhome;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** A single instant drives every clock display, including across San Mateo's DST changes. */
final class MechanicalClockTime {
    private final Calendar local = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"), Locale.US);
    private long minuteBucket = Long.MIN_VALUE;
    private int hour;
    private int minute;
    private double second;
    String digital = "";
    String period = "";
    String day = "";
    String description = "";

    /** Returns true only when the text/date needs refreshing. Calendar and strings are reused per minute. */
    boolean update(long epochMillis) {
        second = Math.floorMod(epochMillis, 60_000L) / 1000.0;
        long bucket = Math.floorDiv(epochMillis, 60_000L);
        if (bucket == minuteBucket) return false;
        minuteBucket = bucket;
        local.setTimeInMillis(epochMillis);
        hour = local.get(Calendar.HOUR);
        minute = local.get(Calendar.MINUTE);
        digital = String.format(Locale.US, "%d:%02d", hour == 0 ? 12 : hour, minute);
        period = local.get(Calendar.AM_PM) == Calendar.AM ? "AM" : "PM";
        day = Integer.toString(local.get(Calendar.DAY_OF_MONTH));
        description = String.format(Locale.US, "%s %s, San Mateo, %s %d, %d",
                digital, period, local.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.US),
                local.get(Calendar.DAY_OF_MONTH), local.get(Calendar.YEAR));
        return true;
    }

    float secondDegrees(boolean smooth) { return (float) ((smooth ? second : Math.floor(second)) * 6.0); }
    float minuteDegrees(boolean smooth) { return (float) (minute * 6.0 + (smooth ? second : Math.floor(second)) * .1); }
    float hourDegrees(boolean smooth) { return (float) (hour * 30.0 + minute * .5 + (smooth ? second : Math.floor(second)) / 120.0); }

}
