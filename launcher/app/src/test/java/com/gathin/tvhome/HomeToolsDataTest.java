package com.gathin.tvhome;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class HomeToolsDataTest {
    private List<WeatherData.Day> days() {
        List<WeatherData.Day> days = new ArrayList<>();
        for (int day = 25; day <= 31; day++) days.add(new WeatherData.Day("2026-10-" + day, 2, 72, 54, 10));
        return days;
    }

    @Test public void availableMemoryIsNotConfusedWithFreeMemory() {
        DeviceUsage data = new DeviceUsage(2_000_000_000L, 500_000_000L);
        assertEquals(1_500_000_000L, data.used);
        assertEquals(75, data.percent);
        assertEquals(0, new DeviceUsage(100, 100).percent);
        assertEquals(100, new DeviceUsage(Long.MAX_VALUE, 0).percent);
        for (long[] totals : new long[][] {{0, 0}, {100, -1}, {100, 101}}) {
            assertThrows(IllegalArgumentException.class, () -> new DeviceUsage(totals[0], totals[1]));
        }
    }

    @Test public void acceptsACompleteForecastAndPreservesMissingValues() {
        WeatherData data = new WeatherData("2026-10-25T12:00", 68, 2, days());
        assertEquals(7, data.days.size());
        assertEquals(68.0, data.temperature, 0.0);
        WeatherData.Day missing = new WeatherData.Day("2026-10-25", null, null, null, null);
        assertNull(missing.high);
        assertNull(missing.rain);
        assertEquals("Unavailable", WeatherData.condition(missing.code));
        assertThrows(UnsupportedOperationException.class, () -> data.days.clear());
    }

    @Test public void rejectsBadDatesAndPartialOrOutOfOrderForecasts() {
        for (String date : new String[] {"2026-02-30", "2026-13-01", "2026-10-25x", "bad"}) {
            assertThrows(IllegalArgumentException.class, () -> new WeatherData.Day(date, 0, 70, 50, 0));
        }
        List<WeatherData.Day> shortDays = days();
        shortDays.remove(6);
        assertThrows(IllegalArgumentException.class, () -> new WeatherData("2026-10-25T12:00", 68, 0, shortDays));
        List<WeatherData.Day> repeated = days();
        repeated.set(1, repeated.get(0));
        assertThrows(IllegalArgumentException.class, () -> new WeatherData("2026-10-25T12:00", 68, 0, repeated));
        assertThrows(IllegalArgumentException.class, () -> new WeatherData("2026-10-26T12:00", 68, 0, days()));
        assertThrows(IllegalArgumentException.class, () -> new WeatherData("2026-10-25T25:00", 68, 0, days()));
    }

    @Test public void rejectsNonfiniteValuesInvertedRangesAndInvalidProbabilities() {
        for (Number bad : new Number[] {Double.NaN, Double.POSITIVE_INFINITY, 201, -201}) {
            assertThrows(IllegalArgumentException.class, () -> new WeatherData("2026-10-25T12:00", bad, 2, days()));
        }
        assertThrows(IllegalArgumentException.class, () -> new WeatherData.Day("2026-10-25", 2, 40, 70, 10));
        assertThrows(IllegalArgumentException.class, () -> new WeatherData.Day("2026-10-25", 2.5, 70, 50, 10));
        assertThrows(IllegalArgumentException.class, () -> new WeatherData.Day("2026-10-25", 100, 70, 50, 10));
        assertThrows(IllegalArgumentException.class, () -> new WeatherData.Day("2026-10-25", 2, 70, 50, 101));
    }

    @Test public void rejectsOversizedOrDeepJsonBeforePlatformParsing() {
        WeatherData.requireShallowJson("{\"message\":\"[[[\\\"{{{\",\"daily\":[[0]]}");
        assertThrows(IllegalArgumentException.class, () -> WeatherData.requireShallowJson("x".repeat(65_537)));
        assertThrows(IllegalArgumentException.class, () -> WeatherData.requireShallowJson("[".repeat(25) + "]".repeat(25)));
    }
}
