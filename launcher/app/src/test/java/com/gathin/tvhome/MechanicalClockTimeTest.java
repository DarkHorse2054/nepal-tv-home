package com.gathin.tvhome;

import org.junit.Test;
import java.time.Instant;
import static org.junit.Assert.*;

public class MechanicalClockTimeTest {
    private static long at(String iso) { return Instant.parse(iso).toEpochMilli(); }

    @Test public void allDisplaysUseSanMateoInstantAndEnglishDigits() {
        MechanicalClockTime time = new MechanicalClockTime();
        time.update(at("2026-10-29T17:08:30.500Z"));
        assertEquals("10:08", time.digital);
        assertEquals("AM", time.period);
        assertEquals("29", time.day);
        assertEquals(304.25417f, time.hourDegrees(true), .0001f);
        assertEquals(51.05f, time.minuteDegrees(true), .0001f);
        assertEquals(183f, time.secondDegrees(true), .0001f);
        assertEquals("10:08 AM, San Mateo, October 29, 2026", time.description);
    }

    @Test public void springForwardSkipsTheMissingLocalHour() {
        MechanicalClockTime time = new MechanicalClockTime();
        time.update(at("2026-03-08T09:59:59.900Z"));
        assertEquals("1:59", time.digital);
        assertTrue(time.update(at("2026-03-08T10:00:00Z")));
        assertEquals("3:00", time.digital);
        assertEquals(90, time.hourDegrees(true), 0);
        assertEquals("8", time.day);
    }

    @Test public void fallBackRepeatsHourWithoutStaleHands() {
        MechanicalClockTime time = new MechanicalClockTime();
        time.update(at("2026-11-01T08:59:59Z"));
        assertEquals("1:59", time.digital);
        time.update(at("2026-11-01T09:00:00Z"));
        assertEquals("1:00", time.digital);
        assertEquals(30, time.hourDegrees(true), 0);
        assertEquals(0, time.minuteDegrees(true), 0);
    }

    @Test public void midnightUpdatesDateWhileNoonUsesPm() {
        MechanicalClockTime time = new MechanicalClockTime();
        time.update(at("2027-01-01T07:59:59Z"));
        assertEquals("31", time.day);
        time.update(at("2027-01-01T08:00:00Z"));
        assertEquals("12:00", time.digital);
        assertEquals("AM", time.period);
        assertEquals("1", time.day);
        time.update(at("2027-01-01T20:00:00Z"));
        assertEquals("12:00", time.digital);
        assertEquals("PM", time.period);
    }

    @Test public void framesReuseTextAndStillAdvanceHands() {
        MechanicalClockTime time = new MechanicalClockTime();
        long now = at("2026-10-29T17:08:30Z");
        assertTrue(time.update(now));
        String text = time.digital;
        assertFalse(time.update(now + 500));
        assertSame(text, time.digital);
        assertEquals(183, time.secondDegrees(true), 0);
        assertEquals(180, time.secondDegrees(false), 0);
        assertTrue(time.update(now + 30_000));
        assertEquals("10:09", time.digital);
    }

    @Test public void backwardClockCorrectionRefreshesDateAndHands() {
        MechanicalClockTime time = new MechanicalClockTime();
        time.update(at("2026-10-30T08:00:00Z"));
        assertEquals("30", time.day);
        time.update(at("2026-10-29T17:08:00Z"));
        assertEquals("29", time.day);
        assertEquals("10:08", time.digital);
        assertEquals(304, time.hourDegrees(true), 0);
    }

}
