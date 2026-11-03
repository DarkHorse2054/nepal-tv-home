package com.gathin.tvhome;

/**
 * Shared, allocation-free geometry for the rectangular dial and its moving hands.
 *
 * Twelve is zero degrees; angles increase clockwise. Every minute is exactly six degrees from
 * the same pivot. Marks intersect the rectangular track along those rays: equal distances along
 * the perimeter would be incorrect for a clock, especially near the corners.
 */
final class MechanicalDialGeometry {
    static final float WIDTH = 1672f;
    static final float HEIGHT = 941f;
    static final float PIVOT_X = 835f;
    static final float PIVOT_Y = 438f;
    static final float TRACK_LEFT = 35f;
    static final float TRACK_TOP = 26f;
    static final float TRACK_RIGHT = 1637f;
    static final float TRACK_BOTTOM = 895f;
    static final int MINUTE_COUNT = 60;
    static final float MINUTE_DEGREES = 6f;
    static final float HOUR_DEGREES = 30f;
    static final float MINOR_TICK_LENGTH = 19f;
    static final float MAJOR_TICK_LENGTH = 7.5f;
    static final float HOUR_INDEX_INSET = 14f;
    static final float HOUR_HAND_LENGTH = 225f;
    static final float MINUTE_HAND_LENGTH = 350f;

    private MechanicalDialGeometry() { }

    static float minuteDegrees(int minute) { return Math.floorMod(minute, MINUTE_COUNT) * MINUTE_DEGREES; }
    static float hourDegrees(int hour) { return Math.floorMod(hour, 12) * HOUR_DEGREES; }
    static float directionX(float degrees) { return (float) Math.sin(Math.toRadians(degrees)); }
    static float directionY(float degrees) { return (float) -Math.cos(Math.toRadians(degrees)); }

    /** Distance from the common pivot to the rectangular minute track, along a clock ray. */
    static float trackDistance(float degrees) {
        double radians = Math.toRadians(degrees);
        double x = Math.sin(radians);
        double y = -Math.cos(radians);
        double horizontal = Math.abs(x) < .000001 ? Double.POSITIVE_INFINITY
                : (x < 0 ? PIVOT_X - TRACK_LEFT : TRACK_RIGHT - PIVOT_X) / Math.abs(x);
        double vertical = Math.abs(y) < .000001 ? Double.POSITIVE_INFINITY
                : (y < 0 ? PIVOT_Y - TRACK_TOP : TRACK_BOTTOM - PIVOT_Y) / Math.abs(y);
        return (float) Math.min(horizontal, vertical);
    }

    static float trackX(float degrees) { return PIVOT_X + directionX(degrees) * trackDistance(degrees); }
    static float trackY(float degrees) { return PIVOT_Y + directionY(degrees) * trackDistance(degrees); }
    static float tickLength(int minute) { return minute % 5 == 0 ? MAJOR_TICK_LENGTH : MINOR_TICK_LENGTH; }
    static float secondHandLength(float degrees) { return trackDistance(degrees) - 6f; }

    /** The raised hour bars start inward of the separate fine minute/second marks. */
    static float hourIndexDistance(int hour) { return trackDistance(hourDegrees(hour)) - HOUR_INDEX_INSET; }
    static float hourIndexX(int hour) {
        return PIVOT_X + directionX(hourDegrees(hour)) * hourIndexDistance(hour);
    }
    static float hourIndexY(int hour) {
        return PIVOT_Y + directionY(hourDegrees(hour)) * hourIndexDistance(hour);
    }
}
