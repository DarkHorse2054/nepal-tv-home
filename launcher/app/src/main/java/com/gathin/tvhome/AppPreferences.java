package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** App edits are local, survive launcher updates, and never change installed packages. */
final class AppPreferences {
    private final SharedPreferences preferences;

    AppPreferences(Context context) {
        preferences = context.getSharedPreferences("home_app_preferences", Context.MODE_PRIVATE);
    }

    List<String> order() {
        List<String> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(preferences.getString("order", "[]"));
            for (int i = 0; i < values.length(); i++) {
                String key = values.optString(i, "");
                if (!key.isEmpty() && !result.contains(key)) result.add(key);
            }
        } catch (JSONException ignored) { }
        return result;
    }

    void setOrder(List<String> order) {
        preferences.edit().putString("order", new JSONArray(order).toString()).apply();
    }

    Set<String> hidden() {
        return new HashSet<>(preferences.getStringSet("hidden", new HashSet<>()));
    }

    void setHidden(String key, boolean hidden) {
        Set<String> values = hidden();
        if (hidden) values.add(key); else values.remove(key);
        preferences.edit().putStringSet("hidden", values).apply();
    }

    String name(String key, String original) {
        String name = preferences.getString("name:" + key, "").trim();
        return name.isEmpty() ? original : name;
    }

    void setName(String key, String name) {
        SharedPreferences.Editor editor = preferences.edit();
        if (name == null || name.trim().isEmpty()) editor.remove("name:" + key);
        else editor.putString("name:" + key, name.trim());
        editor.apply();
    }
}
