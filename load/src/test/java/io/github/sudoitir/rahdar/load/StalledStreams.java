package io.github.sudoitir.rahdar.load;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Event-stream subscribers that stop reading. Gatling's own client always drains the socket, so these are plain
 * sockets: they send the request, shrink the receive buffer and never call read, which is what a hung browser tab
 * or a stuck proxy looks like. The server must coalesce, then drop them, without its own memory growing.
 */
final class StalledStreams {

    private static final List<java.net.Socket> SOCKETS = new ArrayList<>();
    private static int opened;
    private static long unreadBytes;

    private StalledStreams() {}

    static synchronized void open(int count, List<Login> users, List<String> clusterIds) throws Exception {
        var base = URI.create(Params.URL);
        for (int i = 0; i < count; i++) {
            Login user = users.get(i % users.size());
            String cluster = clusterIds.get(i % clusterIds.size());
            String cookie = signIn(base, user);
            var socket = new java.net.Socket();
            socket.setReceiveBufferSize(1024);
            socket.connect(new java.net.InetSocketAddress(base.getHost(), base.getPort()), 5000);
            String request = "GET /api/v1/stream?clusterId=" + cluster + "&topics=topology,health,queues,events HTTP/1.1\r\n"
                    + "Host: " + base.getHost() + ":" + base.getPort() + "\r\n"
                    + "Accept: text/event-stream\r\nCookie: " + cookie + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            SOCKETS.add(socket);
            opened++;
        }
    }

    static synchronized int opened() {
        return opened;
    }

    /** Bytes that reached the stalled sockets and were never read, counted by the last {@link #closedByServer}. */
    static synchronized long unreadBytes() {
        return unreadBytes;
    }

    /** Sockets the server has closed by now: a read that returns end-of-stream without blocking. */
    static synchronized int closedByServer() {
        int closed = 0;
        for (var socket : SOCKETS) {
            try {
                socket.setSoTimeout(1);
                // Drain whatever is buffered; -1 means the server closed. A timeout means it is still open.
                byte[] sink = new byte[65536];
                int read;
                do {
                    read = socket.getInputStream().read(sink);
                    unreadBytes += Math.max(read, 0);
                } while (read > 0);
                if (read < 0) {
                    closed++;
                }
            } catch (java.net.SocketTimeoutException stillOpen) {
                // not closed
            } catch (IOException reset) {
                closed++;
            }
        }
        return closed;
    }

    static synchronized void closeAll() {
        for (var socket : SOCKETS) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing a socket the server already closed
            }
        }
        SOCKETS.clear();
    }

    /** Signs in over HTTP and returns the Cookie header for the session. */
    private static String signIn(URI base, Login user) throws Exception {
        var cookies = new CookieManager();
        var client = HttpClient.newBuilder().cookieHandler(cookies).build();
        client.send(
                HttpRequest.newBuilder(base.resolve("/api/v1/auth/me")).build(), HttpResponse.BodyHandlers.discarding());
        String xsrf = cookieValue(cookies, "XSRF-TOKEN");
        var login = HttpRequest.newBuilder(base.resolve("/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", xsrf)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + user.username() + "\",\"password\":\"" + user.password() + "\"}"))
                .build();
        var response = client.send(login, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !response.body().contains("AUTHENTICATED")) {
            throw new IOException("stalled subscriber sign-in failed: HTTP " + response.statusCode());
        }
        return cookies.getCookieStore().getCookies().stream()
                .map(c -> c.getName() + "=" + c.getValue())
                .collect(Collectors.joining("; "));
    }

    private static String cookieValue(CookieManager cookies, String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(name))
                .map(java.net.HttpCookie::getValue)
                .findFirst()
                .orElseThrow();
    }

    record Login(String username, String password) {}
}
