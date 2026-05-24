package com.github.grepHammerspace.tailscale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;

/**
 * Helper class to extract the tailscale identity of someone who makes a request to the server.
 */
public class TailscaleIdentityHelper {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient client = new OkHttpClient.Builder().build();

    public static String getUser(HttpServletRequest request) throws IOException {
        String clientIp = request.getRemoteAddr();
        int clientPort = request.getRemotePort();

        Request whoisRequest = new Request.Builder()
                .url("http://127.0.0.1:41112/localapi/v0/whois?addr=" + clientIp + ":" + clientPort)
                .build();

        try (Response response = client.newCall(whoisRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new SecurityException("Could not identify Tailscale caller");
            }

            JsonNode root = mapper.readTree(response.body().string());
            String user = root.path("UserProfile").path("LoginName").asText();

            if (user == null || user.isEmpty()) {
                throw new SecurityException("Empty user identity returned from Tailscale");
            }

            return user;
        }
    }
}
