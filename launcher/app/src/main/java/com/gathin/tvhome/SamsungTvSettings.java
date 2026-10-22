package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/** Local companion-free Samsung pairing. Neither token nor certificate is shared with the Mac. */
public final class SamsungTvSettings {
    private static final String NAME = "samsung_tv_private";
    public final String address;
    public final String model;
    public final String expectedModel;
    final String token;
    final String certificatePin;

    private SamsungTvSettings(SharedPreferences preferences) {
        address = preferences.getString("address", "");
        model = preferences.getString("model", "");
        expectedModel = model;
        token = preferences.getString("token", "");
        certificatePin = preferences.getString("certificate_sha256", "");
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static synchronized SamsungTvSettings load(Context context) {
        return new SamsungTvSettings(preferences(context));
    }

    public boolean isConfigured() {
        return SamsungTvSecurity.validAddress(address) && SamsungTvSecurity.validModel(model);
    }

    public boolean isPaired() {
        return isConfigured() && SamsungTvSecurity.validToken(token)
                && SamsungTvSecurity.validPin(certificatePin);
    }

    public static synchronized void saveTarget(Context context, String address, String expectedModel) {
        if (address == null || expectedModel == null) throw new IllegalArgumentException("Enter the TV address and model.");
        address = address.trim();
        expectedModel = expectedModel.trim();
        if (!SamsungTvSecurity.validAddress(address)) {
            throw new IllegalArgumentException("Use the Samsung TV's private IPv4 address, such as 192.168.1.20.");
        }
        if (!SamsungTvSecurity.validModel(expectedModel)) {
            throw new IllegalArgumentException("Enter the exact Samsung model code from TV Settings.");
        }
        SamsungTvSettings before = load(context);
        SharedPreferences.Editor editor = preferences(context).edit();
        if (!before.address.equals(address) || !before.model.equals(expectedModel)) editor.clear();
        if (!editor.putString("address", address).putString("model", expectedModel).commit()) {
            throw new IllegalArgumentException("Could not save Samsung settings.");
        }
    }

    public static synchronized void forgetPairing(Context context) {
        preferences(context).edit().remove("token").remove("certificate_sha256").commit();
    }

    static synchronized boolean savePairing(Context context, SamsungTvSettings target, String token, String pin) {
        SamsungTvSettings current = load(context);
        if (!current.address.equals(target.address) || !current.model.equals(target.model)
                || !current.token.equals(target.token) || !current.certificatePin.equals(target.certificatePin)
                || !SamsungTvSecurity.validToken(token) || !SamsungTvSecurity.validPin(pin)) return false;
        return preferences(context).edit().putString("token", token)
                .putString("certificate_sha256", pin).commit();
    }
}
