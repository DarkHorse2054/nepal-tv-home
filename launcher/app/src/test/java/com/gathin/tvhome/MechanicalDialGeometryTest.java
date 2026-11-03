package com.gathin.tvhome;

import org.junit.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

public class MechanicalDialGeometryTest {
    private static final float EPSILON = .001f;

    @Test public void sixtyDistinctTicksReadAsOneMinuteStepsAroundThePivot() {
        Set<String> positions = new HashSet<>();
        double previous = -6;
        for (int minute = 0; minute < 60; minute++) {
            float angle = MechanicalDialGeometry.minuteDegrees(minute);
            float x = MechanicalDialGeometry.trackX(angle);
            float y = MechanicalDialGeometry.trackY(angle);
            positions.add(x + ":" + y);
            double measured = angleFromPivot(x, y);
            assertEquals("Tick " + minute + " is on its true clock ray", minute * 6, measured, EPSILON);
            assertEquals("Clockwise angular spacing", 6, measured - previous, EPSILON);
            previous = measured;
        }
        assertEquals(60, positions.size());
        assertEquals("The closing interval also covers exactly one minute", 6, 360 - previous, EPSILON);
    }

    @Test public void cardinalMarksUseTheSamePivotAsTheHands() {
        assertPoint(0, 835, 26);
        assertPoint(90, 1637, 438);
        assertPoint(180, 835, 895);
        assertPoint(270, 35, 438);
        assertEquals(412, MechanicalDialGeometry.trackDistance(0), EPSILON);
        assertEquals(802, MechanicalDialGeometry.trackDistance(90), EPSILON);
        assertEquals(457, MechanicalDialGeometry.trackDistance(180), EPSILON);
        assertEquals(800, MechanicalDialGeometry.trackDistance(270), EPSILON);
    }

    @Test public void allSixtyTickSegmentsAreRadialAndInsideTheRectangularTrack() {
        for (int minute = 0; minute < 60; minute++) {
            float angle = MechanicalDialGeometry.minuteDegrees(minute);
            float x = MechanicalDialGeometry.trackX(angle);
            float y = MechanicalDialGeometry.trackY(angle);
            assertInside(x, y);
            assertTrue("Outer endpoint must touch a track edge", onTrackEdge(x, y));
            float innerX = x - MechanicalDialGeometry.directionX(angle) * MechanicalDialGeometry.tickLength(minute);
            float innerY = y - MechanicalDialGeometry.directionY(angle) * MechanicalDialGeometry.tickLength(minute);
            assertInside(innerX, innerY);
            assertEquals(angle, angleFromPivot(innerX, innerY), EPSILON);
        }
    }

    @Test public void everyHourIndexAndSeparateMajorTickShareOneThirtyDegreeRay() {
        for (int hour = 0; hour < 12; hour++) {
            float angle = MechanicalDialGeometry.hourDegrees(hour);
            float x = MechanicalDialGeometry.hourIndexX(hour);
            float y = MechanicalDialGeometry.hourIndexY(hour);
            assertEquals(hour * 30, angleFromPivot(x, y), EPSILON);
            assertEquals(MechanicalDialGeometry.minuteDegrees(hour * 5), angle, 0);
            assertInside(x, y);
            double distanceToTrack = Math.hypot(MechanicalDialGeometry.trackX(angle) - x,
                    MechanicalDialGeometry.trackY(angle) - y);
            assertEquals(14, distanceToTrack, EPSILON);
            assertTrue("Fine major tick must end before the raised hour bar starts",
                    MechanicalDialGeometry.tickLength(hour * 5) < distanceToTrack);
        }
    }

    @Test public void movingMinuteAndSecondHandsAlignWithEveryRenderedTick() {
        MechanicalClockTime time = new MechanicalClockTime();
        long noon = Instant.parse("2026-10-30T19:00:00Z").toEpochMilli();
        for (int minute = 0; minute < 60; minute++) {
            time.update(noon + minute * 60_000L);
            float angle = time.minuteDegrees(true);
            assertEquals(angle, angleFromPivot(MechanicalDialGeometry.trackX(angle),
                    MechanicalDialGeometry.trackY(angle)), EPSILON);
            assertEquals(MechanicalDialGeometry.minuteDegrees(minute), angle, EPSILON);
            time.update(noon + minute * 1_000L);
            assertEquals(MechanicalDialGeometry.minuteDegrees(minute), time.secondDegrees(true), EPSILON);
        }
    }

    @Test public void continuousHandRaysIntersectTheTrackWithoutCornerOvershoot() {
        for (int step = 0; step < 36_000; step++) {
            float angle = step / 100f;
            float distance = MechanicalDialGeometry.trackDistance(angle);
            assertTrue(Float.isFinite(distance));
            assertTrue(distance > 0);
            float x = MechanicalDialGeometry.trackX(angle);
            float y = MechanicalDialGeometry.trackY(angle);
            assertInside(x, y);
            assertTrue(onTrackEdge(x, y));
        }
    }

    @Test public void hourHandIsAlwaysShortestAndAllThreeHandTipsStayInsideTheTrack() {
        for (int step = 0; step < 36_000; step++) {
            float angle = step / 100f;
            float hour = MechanicalDialGeometry.HOUR_HAND_LENGTH;
            float minute = MechanicalDialGeometry.MINUTE_HAND_LENGTH;
            float second = MechanicalDialGeometry.secondHandLength(angle);
            float distance = MechanicalDialGeometry.trackDistance(angle);
            assertTrue(hour > 0);
            assertTrue(hour < minute);
            assertTrue(minute < second);
            assertTrue(second < distance);
            assertHandTipInside(angle, hour);
            assertHandTipInside(angle, minute);
            assertHandTipInside(angle, second);
        }
    }

    private static void assertPoint(float angle, float x, float y) {
        assertEquals(x, MechanicalDialGeometry.trackX(angle), EPSILON);
        assertEquals(y, MechanicalDialGeometry.trackY(angle), EPSILON);
    }

    private static void assertHandTipInside(float angle, float length) {
        assertInside(MechanicalDialGeometry.PIVOT_X + MechanicalDialGeometry.directionX(angle) * length,
                MechanicalDialGeometry.PIVOT_Y + MechanicalDialGeometry.directionY(angle) * length);
    }

    private static void assertInside(float x, float y) {
        assertTrue(x >= MechanicalDialGeometry.TRACK_LEFT - EPSILON);
        assertTrue(x <= MechanicalDialGeometry.TRACK_RIGHT + EPSILON);
        assertTrue(y >= MechanicalDialGeometry.TRACK_TOP - EPSILON);
        assertTrue(y <= MechanicalDialGeometry.TRACK_BOTTOM + EPSILON);
    }

    private static boolean onTrackEdge(float x, float y) {
        return Math.abs(x - MechanicalDialGeometry.TRACK_LEFT) < EPSILON
                || Math.abs(x - MechanicalDialGeometry.TRACK_RIGHT) < EPSILON
                || Math.abs(y - MechanicalDialGeometry.TRACK_TOP) < EPSILON
                || Math.abs(y - MechanicalDialGeometry.TRACK_BOTTOM) < EPSILON;
    }

    private static double angleFromPivot(float x, float y) {
        double angle = Math.toDegrees(Math.atan2(x - MechanicalDialGeometry.PIVOT_X,
                MechanicalDialGeometry.PIVOT_Y - y));
        return angle < 0 ? angle + 360 : angle;
    }
}
