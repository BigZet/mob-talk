package io.github.mobtalk.server.web;

import com.google.gson.JsonElement;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** What a route answers: a body with its type, or a stream (server-sent events) the server keeps writing to. */
public final class Response {
    /** Writes until the client leaves or the server stops. */
    public interface Stream { void write(OutputStream out) throws IOException, InterruptedException; }

    public final int status;
    public final String contentType;
    public final byte[] body;
    public final Stream stream;
    public final Map<String, String> headers = new LinkedHashMap<>();

    private Response(int status, String contentType, byte[] body, Stream stream) {
        this.status = status; this.contentType = contentType; this.body = body; this.stream = stream;
    }

    public static Response json(JsonElement tree) { return json(200, tree); }
    public static Response json(int status, JsonElement tree) {
        return new Response(status, "application/json; charset=utf-8", Json.GSON.toJson(tree).getBytes(StandardCharsets.UTF_8), null);
    }
    public static Response bytes(int status, String contentType, byte[] body) { return new Response(status, contentType, body, null); }
    public static Response text(int status, String contentType, String body) { return bytes(status, contentType, body.getBytes(StandardCharsets.UTF_8)); }
    public static Response events(Stream stream) { return new Response(200, "text/event-stream; charset=utf-8", null, stream); }

    public Response header(String name, String value) { headers.put(name, value); return this; }

    public String bodyText() { return body == null ? "" : new String(body, StandardCharsets.UTF_8); }
}
