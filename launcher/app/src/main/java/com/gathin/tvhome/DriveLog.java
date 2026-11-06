package com.gathin.tvhome;

import android.util.Log;
import com.google.api.client.http.HttpResponseException;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Diagnostic text is built from allowlisted codes/classes, never arbitrary exception messages. */
final class DriveLog {
    static final String TAG = "TvHome.Drive";
    private static final Set<String> LOCAL = new HashSet<>(Arrays.asList(
            "Theme config unavailable", "Theme text exceeds limit", "Invalid theme text", "Invalid Drive library",
            "Invalid theme folder tree", "Duplicate or invalid theme path", "Unsupported Drive document or shortcut", "Theme has too many files",
            "Ambiguous wallpapers folder", "Duplicate theme image path", "Invalid theme image path",
            "Obsolete sync", "Cancelled", "Unsupported token endpoint", "Credentials unavailable",
            "Source is not an available folder", "Incomplete Drive listing", "Invalid file ID", "Repeated Drive page",
            "Image exceeds cache/file limit", "Unexpected image Content-Type", "Image exceeds limit", "Incomplete image",
            "Response too large", "Checksum mismatch", "Invalid image", "Image cannot decode", "Cache unavailable", "Cache full", "Captions exceed limit", "Invalid caption text", "Unexpected asset Content-Type"));
    private static final Set<String> OAUTH = new HashSet<>(Arrays.asList(
            "invalid_grant", "invalid_client", "invalid_scope", "unauthorized_client", "access_denied", "temporarily_unavailable"));
    static void event(String message) { Log.i(TAG, message); }
    static String describe(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause) && seen.size() <= 8; cause = cause.getCause()) {
            if (result.length() > 0) result.append(" <- ");
            result.append(cause.getClass().getSimpleName());
            if (cause instanceof DriveImageRepository.ApiException) {
                DriveImageRepository.ApiException api = (DriveImageRepository.ApiException) cause;
                result.append(" HTTP=").append(api.status).append(" retryable=").append(api.retryable);
            }
            if (cause instanceof HttpResponseException) {
                HttpResponseException response = (HttpResponseException) cause;
                result.append(" HTTP=").append(response.getStatusCode());
                try {
                    JSONObject body = new JSONObject(response.getContent());
                    String code = body.optString("error");
                    if (OAUTH.contains(code)) {
                        result.append(" oauth=").append(code);
                        if ("invalid_grant".equals(code)) {
                            String description = body.optString("error_description").toLowerCase(java.util.Locale.ROOT);
                            if (description.contains("reasonable timeframe") || description.contains("iat") && description.contains("exp"))
                                result.append(" reason=clock_skew [enable TV automatic date/time]");
                            else if (description.contains("invalid jwt signature"))
                                result.append(" reason=invalid_signature [check bundled key and key revocation]");
                            else result.append(" [check TV automatic date/time, revoked key, service-account status]");
                        }
                    }
                } catch (Exception ignored) { /* Never print raw response bodies. */ }
            }
            String message = cause.getMessage();
            if (LOCAL.contains(message)) result.append(" [").append(message).append(']');
            if (cause instanceof java.io.FileNotFoundException) result.append(" [required asset/cache file missing or inaccessible]");
            if (cause instanceof java.net.UnknownHostException) result.append(" [DNS/network unavailable]");
            if (cause instanceof java.net.SocketTimeoutException) result.append(" [network timeout]");
            if (cause instanceof javax.net.ssl.SSLException || cause instanceof java.security.cert.CertificateException)
                result.append(" [check TV date/time, TLS certificates and network]");
            StackTraceElement[] stack = cause.getStackTrace();
            if (stack.length > 0) result.append(" at ").append(stack[0].getClassName()).append('.')
                    .append(stack[0].getMethodName()).append(':').append(stack[0].getLineNumber());
        }
        return result.toString();
    }
    static String failure(String stage, Throwable failure) {
        String message = "stage=" + stage + " failed: " + describe(failure);
        Log.w(TAG, message);
        return message;
    }
}
