package io.github.mobtalk.overlay;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class OverlayWebTest {
    private OverlayFeed feed;
    private OverlayWeb web;

    @BeforeEach void start() throws Exception { feed = new OverlayFeed(200); web = OverlayWeb.start(0, feed); }
    @AfterEach void stop() { web.stop(); }

    private HttpURLConnection get(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + web.port() + path).toURL().openConnection(java.net.Proxy.NO_PROXY);
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    @Test void servesPageAndWidgets() throws Exception {
        for (String path : new String[]{"/overlay/", "/overlay/card", "/overlay/full", "/overlay/subtitles", "/overlay/god", "/overlay/mercy", "/overlay/now", "/overlay/overlay.js", "/overlay/overlay.css"}) {
            HttpURLConnection connection = get(path);
            assertEquals(200, connection.getResponseCode(), path);
            assertTrue(connection.getHeaderField("Content-Security-Policy").contains("default-src 'self'"), path);
        }
        assertEquals(302, get("/").getResponseCode());
        assertEquals(404, get("/overlay/../secret").getResponseCode());
        assertEquals(404, get("/overlay/nope.html").getResponseCode());
    }

    @Test void refusesForeignHostAndWrites() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", web.port())) {
            socket.getOutputStream().write("GET /overlay/state HTTP/1.1\r\nHost: evil.example:8790\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String status = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertTrue(status.contains(" 403 "), status);
        }
        HttpURLConnection post = get("/overlay/state");
        post.setRequestMethod("POST");
        assertEquals(405, post.getResponseCode());
        assertTrue(OverlayWeb.localHost("localhost:8790"));
        assertTrue(OverlayWeb.localHost("127.0.0.1"));
        assertTrue(OverlayWeb.localHost("[::1]:8790"));
        assertFalse(OverlayWeb.localHost("127.0.0.1.evil.example"));
        assertFalse(OverlayWeb.localHost(null));
    }

    @Test void eventsStartWithConfigAndStateThenLines() throws Exception {
        JsonObject config = new JsonObject(); config.addProperty("style", "parchment");
        feed.config(config);
        JsonObject state = new JsonObject(); state.addProperty("you", "u");
        feed.state(state);
        HttpURLConnection connection = get("/overlay/events");
        assertEquals(200, connection.getResponseCode());
        assertTrue(connection.getContentType().startsWith("text/event-stream"));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder seen = new StringBuilder();
            while (!seen.toString().contains("event: state")) seen.append(reader.readLine()).append('\n');
            assertTrue(seen.toString().contains("parchment"));
            long deadline = System.currentTimeMillis() + 2000;
            while (feed.pages() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10);
            JsonObject line = new JsonObject(); line.addProperty("text", "Кузнец за колодцем");
            feed.line(line);
            String next;
            do next = reader.readLine(); while (next != null && !next.startsWith("data: {\"text\""));
            assertNotNull(next);
            assertTrue(next.contains("Кузнец за колодцем"));
        }
    }
}
