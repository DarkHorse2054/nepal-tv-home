package com.gathin.tvhome;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** One city-center forecast, fetched only on demand; normal HTTPS validation stays enabled. */
final class NativeWeather {
    static final String LOCATION = "San Mateo";
    static final String TIMEZONE = "America/Los_Angeles";
    static final String UNIT = "°F";
    // Public city-center coordinates, not device location or a household address.
    private static final String URL = "https://api.open-meteo.com/v1/forecast?latitude=37.563&longitude=-122.325"
            + "&current=temperature_2m,weather_code,is_day"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
            + "&temperature_unit=fahrenheit&timezone=America%2FLos_Angeles&forecast_days=7";
    private static final int MAX_BYTES = 65_536;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private static WeatherData cached;
    private static long fetchedAt, attemptedAt = Long.MIN_VALUE;
    private static boolean failed;
    private static Call active;

    static final class State {
        final WeatherData data;
        final long ageSeconds;
        final boolean stale, failed, loading;
        State(WeatherData data, long ageSeconds, boolean stale, boolean failed, boolean loading) {
            this.data = data;
            this.ageSeconds = ageSeconds;
            this.stale = stale;
            this.failed = failed;
            this.loading = loading;
        }
    }

    static synchronized State state() {
        long age = cached == null ? 0 : Math.max(0, (SystemClock.elapsedRealtime() - fetchedAt) / 1000);
        return new State(cached, age, failed || age >= 900, failed, active != null);
    }

    static synchronized Call fetch(Runnable changed) {
        long now = SystemClock.elapsedRealtime();
        if (active != null || (cached != null && !failed && now - fetchedAt < 900_000)
                || (attemptedAt != Long.MIN_VALUE && now - attemptedAt < 60_000)) return null;
        attemptedAt = now;
        Call call = CLIENT.newCall(new Request.Builder().url(URL).header("Accept", "application/json")
                .header("User-Agent", "NepalHome/4.5").build());
        active = call;
        call.enqueue(new Callback() {
            @Override public void onFailure(Call request, IOException error) { complete(request, null, changed); }
            @Override public void onResponse(Call request, Response response) {
                WeatherData data = null;
                try (Response closing = response) {
                    ResponseBody body = closing.body();
                    if (closing.code() != 200 || body == null || body.contentType() == null
                            || !"application".equals(body.contentType().type())
                            || !"json".equals(body.contentType().subtype()) || body.contentLength() > MAX_BYTES) {
                        throw new IOException("Invalid weather response");
                    }
                    data = parse(readBounded(body.byteStream()));
                } catch (IOException | JSONException | IllegalArgumentException invalid) {
                    // Retain the last successful forecast and expose its stale state.
                }
                complete(request, data, changed);
            }
        });
        return call;
    }

    private static synchronized void complete(Call call, WeatherData data, Runnable changed) {
        if (active != call || call.isCanceled()) return;
        active = null;
        failed = data == null;
        if (data != null) {
            cached = data;
            fetchedAt = SystemClock.elapsedRealtime();
        }
        changed.run();
    }

    static synchronized void cancel(Call call) {
        if (call == null) return;
        call.cancel();
        if (active == call) {
            active = null;
            attemptedAt = Long.MIN_VALUE;
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int length; (length = input.read(buffer, 0, Math.min(buffer.length, MAX_BYTES + 1 - output.size()))) != -1;) {
            output.write(buffer, 0, length);
            if (output.size() > MAX_BYTES) throw new IOException("Weather response too large");
        }
        return output.toString("UTF-8");
    }

    private static Number nullable(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (value == JSONObject.NULL) return null;
        if (!(value instanceof Number)) throw new JSONException("Invalid number");
        return (Number) value;
    }

    private static Number nullable(JSONArray array, int index) throws JSONException {
        Object value = array.get(index);
        if (value == JSONObject.NULL) return null;
        if (!(value instanceof Number)) throw new JSONException("Invalid number");
        return (Number) value;
    }

    private static WeatherData parse(String raw) throws JSONException {
        WeatherData.requireShallowJson(raw);
        JSONObject root = new JSONObject(raw);
        if (root.optBoolean("error", false)) throw new JSONException("Provider error");
        JSONObject current = root.getJSONObject("current"), daily = root.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time"), codes = daily.getJSONArray("weather_code"),
                high = daily.getJSONArray("temperature_2m_max"), low = daily.getJSONArray("temperature_2m_min"),
                rain = daily.getJSONArray("precipitation_probability_max");
        for (JSONArray values : new JSONArray[] {dates, codes, high, low, rain}) {
            if (values.length() != 7) throw new JSONException("Incomplete forecast");
        }
        List<WeatherData.Day> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) days.add(new WeatherData.Day(dates.getString(i), nullable(codes, i),
                nullable(high, i), nullable(low, i), nullable(rain, i)));
        return new WeatherData(current.getString("time"), nullable(current, "temperature_2m"),
                nullable(current, "weather_code"), days);
    }
}
