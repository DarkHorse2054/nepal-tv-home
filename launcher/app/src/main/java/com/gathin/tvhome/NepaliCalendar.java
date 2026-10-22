package com.gathin.tvhome;

import android.content.Context;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Offline civil dates. The bundled Apache-2.0 sambat table ends at verified BS 2083. */
final class NepaliCalendar {
    private static final String[] MONTHS = {"वैशाख", "जेठ", "असार", "साउन", "भदौ", "असोज", "कात्तिक", "मङ्सिर", "पुस", "माघ", "फागुन", "चैत"};
    private static final String[] ENGLISH_MONTHS = {"Baisakh", "Jestha", "Ashadh", "Shrawan", "Bhadra", "Ashwin", "Kartik", "Mangsir", "Poush", "Magh", "Falgun", "Chaitra"};
    private static final String[] DAYS = {"आइतबार", "सोमबार", "मङ्गलबार", "बुधबार", "बिहीबार", "शुक्रबार", "शनिबार"};
    private static final String[] AD_MONTHS = {"जनवरी", "फेब्रुअरी", "मार्च", "अप्रिल", "मे", "जुन", "जुलाई", "अगस्ट", "सेप्टेम्बर", "अक्टोबर", "नोभेम्बर", "डिसेम्बर"};
    private static NepaliCalendar instance;
    private final List<Year> years = new ArrayList<>();
    private static final class Year {
        int number;
        long start;
        final int[] days = new int[12];
    }
    static synchronized NepaliCalendar get(Context context) {
        if (instance == null) instance = new NepaliCalendar(context);
        return instance;
    }
    private NepaliCalendar(Context context) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(context.getAssets().open("nepali_calendar.csv"), "UTF-8"))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split(",", -1);
                if (fields.length < 14) continue;
                Year year = new Year();
                year.number = Integer.parseInt(fields[0]);
                String[] ad = fields[1].split("-");
                year.start = utcDay(Integer.parseInt(ad[0]), Integer.parseInt(ad[1]) - 1, Integer.parseInt(ad[2]));
                for (int m = 0; m < 12; m++) year.days[m] = Integer.parseInt(fields[m + 2]);
                years.add(year);
            }
        } catch (Exception ignored) {
            years.clear(); // Gregorian date remains available if calendar assets cannot be read.
        }
    }
    private static long utcDay(int year, int month, int day) {
        Calendar utc = new GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        utc.clear();
        utc.set(year, month, day);
        return utc.getTimeInMillis() / 86_400_000L;
    }
    String formatBs(Calendar local) {
        return formatBs(local, false);
    }
    String formatBsEnglish(Calendar local) {
        return formatBs(local, true);
    }
    private String formatBs(Calendar local, boolean english) {
        long day = utcDay(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
        for (int i = years.size() - 1; i >= 0; i--) {
            Year year = years.get(i);
            if (day < year.start) continue;
            long offset = day - year.start;
            for (int m = 0; m < 12; m++) {
                if (offset < year.days[m]) {
                    if (english) return (offset + 1) + " " + ENGLISH_MONTHS[m] + " " + year.number + " BS";
                    return digits(Long.toString(offset + 1)) + " " + MONTHS[m] + " " + digits(Integer.toString(year.number));
                }
                offset -= year.days[m];
            }
            return null;
        }
        return null;
    }
    String datePanel(Calendar sanMateo) {
        Calendar nepal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kathmandu"));
        nepal.setTimeInMillis(sanMateo.getTimeInMillis());
        java.text.SimpleDateFormat ad = new java.text.SimpleDateFormat("EEE, MMM d", Locale.US);
        ad.setTimeZone(sanMateo.getTimeZone());
        return "San Mateo · " + ad.format(sanMateo.getTime())
            + "\nNepal · " + dateEnglish(nepal);
    }

    String dateEnglish(Calendar local) {
        String bs = formatBsEnglish(local);
        if (bs != null) return bs;
        java.text.SimpleDateFormat ad = new java.text.SimpleDateFormat("MMM d, yyyy", Locale.US);
        ad.setTimeZone(local.getTimeZone());
        return ad.format(local.getTime());
    }

    static String digits(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            result.append(c >= '0' && c <= '9' ? (char) ('०' + c - '0') : c);
        }
        return result.toString();
    }
    static String clockEnglish(Calendar time, boolean fullDay) {
        java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat(fullDay ? "HH:mm" : "h:mm", Locale.US);
        clock.setTimeZone(time.getTimeZone());
        return clock.format(time.getTime());
    }
    static String clock(Calendar time, boolean fullDay) {
        int hour = time.get(fullDay ? Calendar.HOUR_OF_DAY : Calendar.HOUR);
        if (!fullDay && hour == 0) hour = 12;
        return digits(hour + ":" + String.format(Locale.ROOT, "%02d", time.get(Calendar.MINUTE)));
    }
    static String phase(Calendar time) {
        int hour = time.get(Calendar.HOUR_OF_DAY);
        return hour < 5 ? "राति" : hour < 12 ? "बिहान" : hour < 17 ? "दिउँसो" : hour < 20 ? "बेलुका" : "राति";
    }
    static String weekday(Calendar time) { return DAYS[time.get(Calendar.DAY_OF_WEEK) - 1]; }
    static String gregorian(Calendar time) {
        return digits(Integer.toString(time.get(Calendar.DAY_OF_MONTH))) + " " + AD_MONTHS[time.get(Calendar.MONTH)] + " " + digits(Integer.toString(time.get(Calendar.YEAR)));
    }
}
