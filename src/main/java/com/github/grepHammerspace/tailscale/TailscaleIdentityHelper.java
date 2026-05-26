package com.github.grepHammerspace.tailscale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.List;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.net.StandardProtocolFamily;
import java.util.concurrent.TimeUnit;

/**
 * Helper class to extract the tailscale identity of someone who makes a request to the server.
 */
public class TailscaleIdentityHelper {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String TAILSCALE_SOCKET = "/run/tailscale/tailscaled.sock";

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .dns(hostname -> List.of(InetAddress.getLoopbackAddress()))
            .socketFactory(new SocketFactory() {
                @Override public Socket createSocket() { return new UnixSocket(TAILSCALE_SOCKET); }
                @Override public Socket createSocket(String h, int p) { return createSocket(); }
                @Override public Socket createSocket(String h, int p, InetAddress l, int lp) { return createSocket(); }
                @Override public Socket createSocket(InetAddress h, int p) { return createSocket(); }
                @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) { return createSocket(); }
            })
            .build();
    private static final Logger log = LoggerFactory.getLogger(TailscaleIdentityHelper.class);

    /**
     * Calls the Tailscale local API {@code /localapi/v0/whois} with the caller's IP and port to
     * look up their Tailscale identity. The request travels over a Unix domain socket
     * (see {@link UnixSocket}) rather than TCP; OkHttp is configured with a custom
     * {@link SocketFactory} and a dummy DNS resolver to make this transparent.
     *
     * @throws SecurityException if Tailscale returns a non-200 or an empty login name
     */
    public static String getUser(HttpServletRequest request) throws IOException {
        String clientIp = request.getRemoteAddr();
        int clientPort = request.getRemotePort();

        Request whoisRequest = new Request.Builder()
                .url("http://local-tailscaled.sock/localapi/v0/whois?addr=" + clientIp + ":" + clientPort)
                .header("Sec-Tailscale", "localapi")
                .build();

        try (Response response = client.newCall(whoisRequest).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                String body = response.body() != null ? response.body().string() : "(empty body)";
                throw new SecurityException("Tailscale whois failed: HTTP " + response.code() + " - " + body);
            }

            JsonNode root = mapper.readTree(response.body().string());
            String user = root.path("UserProfile").path("LoginName").asText();

            if (user == null || user.isEmpty()) {
                throw new SecurityException("Empty user identity returned from Tailscale");
            }

            return user;
        }
    }

    // Wraps a Unix domain SocketChannel as a java.net.Socket for OkHttp compatibility.
    private static class UnixSocket extends Socket {
        private final String socketPath;
        private volatile SocketChannel channel;

        UnixSocket(String socketPath) {
            this.socketPath = socketPath;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socketPath));
        }

        @Override public InputStream getInputStream() throws IOException { return Channels.newInputStream(channel); }
        @Override public OutputStream getOutputStream() throws IOException { return Channels.newOutputStream(channel); }
        @Override public synchronized void close() throws IOException { if (channel != null) channel.close(); }
        @Override public boolean isConnected() { return channel != null && channel.isConnected(); }
        @Override public boolean isClosed() { return channel == null || !channel.isOpen(); }
        @Override
        public void setSoTimeout(int timeout) throws SocketException {
            // Unix domain SocketChannel does not support .socket() — no-op here.
            // OkHttp's callTimeout watchdog enforces the overall deadline instead.
        }
        @Override public void setTcpNoDelay(boolean on) {}
        @Override public void setKeepAlive(boolean on) {}
        @Override public int getLocalPort() { return -1; }
    }
}