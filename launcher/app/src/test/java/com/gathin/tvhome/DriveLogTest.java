package com.gathin.tvhome;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpHeaders;
import java.io.IOException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DriveLogTest {
    @Test public void oauthDetailsAreRedactedButClockHintIsVisible() {
        HttpResponseException oauth = new HttpResponseException.Builder(400, "secret-token", new HttpHeaders())
                .setContent("{\"error\":\"invalid_grant\",\"error_description\":\"private-key secret-token someone@example.com\"}")
                .build();
        String result = DriveLog.describe(new IOException("secret-token", oauth));
        assertTrue(result.contains("HTTP=400"));
        assertTrue(result.contains("oauth=invalid_grant"));
        assertTrue(result.contains("date/time"));
        assertFalse(result.contains("secret-token"));
        assertFalse(result.contains("private-key"));
        assertFalse(result.contains("someone@example.com"));
    }
    @Test public void clockSkewIsIdentifiedWithoutLoggingResponseDescription() {
        HttpResponseException oauth = new HttpResponseException.Builder(400, "secret", new HttpHeaders())
                .setContent("{\"error\":\"invalid_grant\",\"error_description\":\"secret: check iat and exp values\"}").build();
        String result = DriveLog.describe(oauth);
        assertTrue(result.contains("reason=clock_skew"));
        assertFalse(result.contains("secret"));
    }
    @Test public void unknownResponseAndExceptionMessagesAreNeverPrinted() {
        HttpResponseException response = new HttpResponseException.Builder(403, "secret", new HttpHeaders())
                .setContent("{\"error\":\"secret\"}").build();
        String result = DriveLog.describe(new IOException("secret /private/user/path", response));
        assertTrue(result.contains("HTTP=403"));
        assertFalse(result.contains("secret"));
        assertFalse(result.contains("/private/user/path"));
    }
    @Test public void networkAndLocalFailureReasonsRemainActionable() {
        assertTrue(DriveLog.describe(new java.net.UnknownHostException("secret-host")).contains("DNS/network"));
        assertFalse(DriveLog.describe(new java.net.UnknownHostException("secret-host")).contains("secret-host"));
        assertTrue(DriveLog.describe(new IOException("Cache full")).contains("Cache full"));
        assertTrue(DriveLog.describe(new IOException("Checksum mismatch")).contains("Checksum mismatch"));
        assertTrue(DriveLog.describe(new javax.net.ssl.SSLHandshakeException("secret")).contains("TLS certificates"));
    }
}
