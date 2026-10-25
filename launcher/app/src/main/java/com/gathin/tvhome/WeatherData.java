package com.gathin.tvhome;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Validated display data, independent of Android and the network transport. */
final class WeatherData {
    static final class Day {
        final String date;
        final Integer code;
        final Double high, low, rain;

        Day(String date, Number code, Number high, Number low, Number rain) {
            dateMillis(date);
            this.date = date;
            this.code = code(code);
            this.high = number(high, -200, 200);
            this.low = number(low, -200, 200);
            this.rain = number(rain, 0, 100);
            if (this.high != null && this.low != null && this.low > this.high) {
                throw new IllegalArgumentException("Inverted temperature range");
            }
        }
    }

    final String time;
    final Double temperature;
    final Integer code;
    final List<Day> days;

    WeatherData(String time, Number temperature, Number code, List<Day> days) {
        if (time == null || !time.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}")) {
            throw new IllegalArgumentException("Invalid current time");
        }
        parse(time, "yyyy-MM-dd'T'HH:mm");
        if (days == null || days.size() != 7) throw new IllegalArgumentException("Seven days required");
        long previous = 0;
        for (int i = 0; i < days.size(); i++) {
            Day day = days.get(i);
            if (day == null) throw new IllegalArgumentException("Missing day");
            long stamp = dateMillis(day.date);
            if (i > 0 && stamp != previous + 86_400_000L) throw new IllegalArgumentException("Nonconsecutive days");
            previous = stamp;
        }
        if (!time.substring(0, 10).equals(days.get(0).date)) throw new IllegalArgumentException("Mismatched current day");
        this.time = time;
        this.temperature = number(temperature, -200, 200);
        this.code = code(code);
        this.days = Collections.unmodifiableList(new ArrayList<>(days));
    }

    static Double number(Number value, double min, double max) {
        if (value == null) return null;
        double number = value.doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number < min || number > max) {
            throw new IllegalArgumentException("Invalid weather value");
        }
        return number;
    }

    private static Integer code(Number value) {
        Double number = number(value, 0, 99);
        if (number == null) return null;
        if (number != Math.rint(number)) throw new IllegalArgumentException("Invalid weather code");
        return number.intValue();
    }

    static long dateMillis(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Invalid date");
        return parse(value, "yyyy-MM-dd");
    }

    private static long parse(String value, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date result = format.parse(value, position);
        if (result == null || position.getIndex() != value.length()) throw new IllegalArgumentException("Invalid date");
        return result.getTime();
    }

    static String condition(Integer code) {
        if (code == null) return "Unavailable";
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "Unavailable";
    }

    static void requireShallowJson(String raw) {
        if (raw == null || raw.length() > 65_536) throw new IllegalArgumentException("Weather response too large");
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') quoted = true;
            else if (character == '{' || character == '[') {
                if (++depth > 24) throw new IllegalArgumentException("Weather response nested too deeply");
            } else if (character == '}' || character == ']') depth--;
        }
    }
}
