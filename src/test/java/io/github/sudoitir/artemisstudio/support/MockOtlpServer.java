package io.github.sudoitir.artemisstudio.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An OTLP/HTTP endpoint that accepts everything and keeps the body of each request, by path
 * ({@code /v1/metrics}, {@code /v1/traces}, {@code /v1/logs}). A test that asserts on what
 * arrived has the protobuf bytes to search; attribute values are plain UTF-8 in them.
 */
public final class MockOtlpServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, List<byte[]>> received = new ConcurrentHashMap<>();

    public MockOtlpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.computeIfAbsent(exchange.getRequestURI().getPath(), k -> new CopyOnWriteArrayList<>())
                    .add(body);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    /** The endpoint to give Studio as {@code OTEL_EXPORTER_OTLP_ENDPOINT}. */
    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** The bodies received on {@code path}. */
    public List<byte[]> bodies(String path) {
        return received.getOrDefault(path, List.of());
    }

    /** Every request received, on any path. */
    public int requests() {
        return received.values().stream().mapToInt(List::size).sum();
    }

    /** Whether any body received on any path holds {@code text}. */
    public boolean anyBodyContains(String text) {
        byte[] needle = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return received.values().stream().flatMap(List::stream).anyMatch(body -> contains(body, needle));
    }

    public boolean bodyContains(String path, String text) {
        byte[] needle = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return bodies(path).stream().anyMatch(body -> contains(body, needle));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
