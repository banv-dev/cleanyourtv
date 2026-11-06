package com.gathin.tvhome;

import android.content.Context;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;

/** Tokens stay in memory; never log exceptions from credential libraries (may contain tokens). */
final class DriveAuthManager {
    private final GoogleCredentials credentials;

    DriveAuthManager(Context context) throws IOException {
        DriveLog.event("stage=auth.load reading bundled service-account asset");
        try (InputStream input = context.getAssets().open("service-account.json")) {
            ServiceAccountCredentials account = ServiceAccountCredentials.fromStream(input);
            if (!"https://oauth2.googleapis.com/token".equals(account.getTokenServerUri().toString()))
                throw new IOException("Unsupported token endpoint");
            DriveLog.event("stage=auth.load service-account parsed; HTTPS token endpoint accepted");
            credentials = account.createScoped(Collections.singleton("https://www.googleapis.com/auth/drive.readonly"));
        }
    }

    synchronized String token(boolean refresh) throws IOException {
        boolean fetching = refresh || credentials.getAccessToken() == null
                || credentials.getAccessToken().getExpirationTime() == null
                || credentials.getAccessToken().getExpirationTime().getTime() <= System.currentTimeMillis() + 300000L;
        if (fetching) DriveLog.event("stage=auth.token refreshing; deviceUtc=" + java.time.Instant.now());
        try {
            if (refresh) credentials.refresh();
            else credentials.refreshIfExpired();
        } catch (IOException | RuntimeException failure) {
            DriveLog.failure("auth.token", failure);
            throw failure;
        }
        if (fetching) DriveLog.event("stage=auth.token access token available (value hidden)");
        if (credentials.getAccessToken() == null) throw new IOException("Credentials unavailable");
        return credentials.getAccessToken().getTokenValue();
    }
}
