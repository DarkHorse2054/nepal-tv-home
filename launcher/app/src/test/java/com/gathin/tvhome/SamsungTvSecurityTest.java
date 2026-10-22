package com.gathin.tvhome;

import static org.junit.Assert.*;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import org.junit.Test;

/** No TV, HTTP, DNS, ADB, or Android runtime is involved in these trust-boundary tests. */
public class SamsungTvSecurityTest {
    private X509Certificate certificate(String suffix) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/samsung-test-" + suffix + ".pem")) {
            assertNotNull(stream);
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
        }
    }

    private void rejects(SamsungTvSecurity.TargetTrust trust, X509Certificate certificate) throws Exception {
        try {
            trust.checkServerTrusted(new X509Certificate[] {certificate}, "RSA");
            fail("An unapproved certificate was accepted");
        } catch (CertificateException expected) { }
    }

    @Test public void acceptsOnlyCanonicalPrivateIpv4Addresses() {
        for (String address : new String[] {"10.0.0.2", "172.16.0.2", "172.31.255.254", "192.168.1.20"}) {
            assertTrue(address, SamsungTvSecurity.validAddress(address));
        }
        for (String address : new String[] {"example.com", "127.0.0.1", "169.254.1.2", "8.8.8.8",
                "172.15.0.1", "172.32.0.1", "192.169.1.1", "192.168.01.2", "192.168.256.2",
                "192.168.1.2:8002", "192.168.1.2/path", "::1", "192.168.1.2\n", ""}) {
            assertFalse(address, SamsungTvSecurity.validAddress(address));
        }
        assertFalse(SamsungTvSecurity.validAddress(null));
    }

    @Test public void tokenAndModelCannotContainProtocolOrUrlSyntax() {
        assertTrue(SamsungTvSecurity.validModel("UN65TU7000BXZA"));
        assertTrue(SamsungTvSecurity.validToken("Abc-123_456"));
        for (String value : new String[] {"", "a&token=evil", "abc/def", "abc\n", "{\"event\":true}"}) {
            assertFalse(SamsungTvSecurity.validToken(value));
            assertFalse(SamsungTvSecurity.validModel(value));
        }
        assertFalse(SamsungTvSecurity.validToken("a".repeat(513)));
    }

    @Test public void normalConnectionCannotTrustAnUnpairedSelfSignedCertificate() throws Exception {
        rejects(new SamsungTvSecurity.TargetTrust("", false), certificate("a"));
    }

    @Test public void explicitFirstPairAcceptsOneLeafButRejectsChangesBetweenInfoAndSocket() throws Exception {
        X509Certificate first = certificate("a");
        SamsungTvSecurity.TargetTrust trust = new SamsungTvSecurity.TargetTrust("", true);
        trust.checkServerTrusted(new X509Certificate[] {first}, "RSA");
        assertEquals(SamsungTvSecurity.fingerprint(first), trust.observedPin());
        trust.checkServerTrusted(new X509Certificate[] {first}, "RSA");
        rejects(trust, certificate("b"));
    }

    @Test public void pinnedConnectionsAcceptOnlyTheSameLeaf() throws Exception {
        X509Certificate first = certificate("a");
        String pin = SamsungTvSecurity.fingerprint(first);
        SamsungTvSecurity.TargetTrust trust = new SamsungTvSecurity.TargetTrust(pin, false);
        trust.checkServerTrusted(new X509Certificate[] {first}, "RSA");
        rejects(trust, certificate("b"));
    }

    @Test public void pressingPairCannotSilentlyReplaceAnExistingCertificate() throws Exception {
        String pin = SamsungTvSecurity.fingerprint(certificate("a"));
        rejects(new SamsungTvSecurity.TargetTrust(pin, true), certificate("b"));
    }

    @Test public void invalidPinsAndEmptyChainsFailClosed() throws Exception {
        try {
            new SamsungTvSecurity.TargetTrust("not-a-fingerprint", true);
            fail("Invalid persisted pin was accepted");
        } catch (IllegalArgumentException expected) { }
        SamsungTvSecurity.TargetTrust trust = new SamsungTvSecurity.TargetTrust("", true);
        try {
            trust.checkServerTrusted(new X509Certificate[0], "RSA");
            fail("Empty certificate chain was accepted");
        } catch (CertificateException expected) { }
        assertNull(trust.observedPin());
    }
}
