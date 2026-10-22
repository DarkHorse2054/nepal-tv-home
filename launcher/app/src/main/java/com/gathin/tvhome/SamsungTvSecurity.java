package com.gathin.tvhome;

import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.X509TrustManager;

/** Pure Java validation and one-target certificate trust, also exercised by offline tests. */
final class SamsungTvSecurity {
    private SamsungTvSecurity() { }

    static boolean validAddress(String address) {
        if (address == null || !address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] pieces = address.split("\\.");
        int[] octets = new int[4];
        for (int index = 0; index < pieces.length; index++) {
            if (pieces[index].length() > 1 && pieces[index].charAt(0) == '0') return false;
            octets[index] = Integer.parseInt(pieces[index]);
            if (octets[index] > 255) return false;
        }
        return octets[0] == 10 || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168);
    }

    static boolean validModel(String model) {
        return model != null && model.matches("[A-Za-z0-9][A-Za-z0-9_.-]{1,63}");
    }

    static boolean validToken(String token) {
        return token != null && token.matches("[A-Za-z0-9_-]{1,512}");
    }

    static boolean validPin(String pin) {
        return pin != null && pin.matches("[0-9a-f]{64}");
    }

    static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            byte[] encoded = certificate.getEncoded();
            if (encoded.length > 32768) throw new CertificateException("certificate_too_large");
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format(Locale.US, "%02x", item & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new CertificateException("sha256_unavailable");
        }
    }

    /**
     * A Samsung self-signed leaf is trusted only for the selected client. Initial trust is allowed
     * exclusively during explicit pairing. That tentative leaf must remain identical for identity
     * lookup and WebSocket authorization, and is persisted only with the TV's approval token.
     * Existing pins are never silently replaced, including when Pair is pressed again.
     */
    static final class TargetTrust implements X509TrustManager {
        private final String existingPin;
        private final boolean firstPair;
        private String observedPin;

        TargetTrust(String existingPin, boolean firstPair) {
            if (existingPin != null && !existingPin.isEmpty() && !validPin(existingPin)) {
                throw new IllegalArgumentException("invalid_pin");
            }
            this.existingPin = existingPin == null ? "" : existingPin;
            this.firstPair = firstPair;
        }

        @Override public synchronized void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0 || chain.length > 8 || chain[0] == null) {
                throw new CertificateException("invalid_certificate");
            }
            String candidate = fingerprint(chain[0]);
            if (!existingPin.isEmpty() && !existingPin.equals(candidate)) {
                throw new CertificateException("certificate_changed");
            }
            if (existingPin.isEmpty() && !firstPair) throw new CertificateException("pair_required");
            if (observedPin != null && !observedPin.equals(candidate)) {
                throw new CertificateException("certificate_changed");
            }
            observedPin = candidate;
        }

        synchronized String observedPin() { return observedPin; }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("client_certificates_unsupported");
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
