package io.github.alicespeaker.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Logging in to Yandex by QR code, the way Yandex's own apps and AlexxIT/YandexStation (MIT, core/yandex_session.py)
 * do it: the phone confirms a passport "magic" link, the browser session becomes an x_token, and the x_token gives
 * the Yandex Music token and fresh cookies. Blocking calls: run them on a worker thread. Tokens and cookies live only
 * in ~/.alice-speaker and are never logged.
 */
final class YandexLogin {
    static final Path X_TOKEN = AliceAuth.DIR.resolve("x_token.txt"), ACCOUNT = AliceAuth.DIR.resolve("account.txt"),
            MUSIC_TOKEN = AliceAuth.DIR.resolve("music_token_login.txt");
    private static final String PASSPORT = "https://passport.yandex.ru/pwl-yandex";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";
    // The Yandex app's passport client (x_token) and the Yandex Music app (music token), as public apps ship them.
    private static final String X_CLIENT_ID = "c0ebe342af7d48fbbbfcf2d2eedb8f9e", X_CLIENT_SECRET = "ad0a908f0aa341a182a37ecd75bc319e";
    private static final String MUSIC_CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d", MUSIC_CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300";

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private String csrf;
    private JsonObject track;

    /** Starts a QR login; the link goes into the QR code (or a browser where the player is already logged in). */
    String qrLink() throws IOException {
        Matcher m = Pattern.compile("__CSRF__ = \"([^\"]+)").matcher(send(request(PASSPORT)).body());
        if (!m.find()) throw new IOException("Яндекс не отдал страницу входа");
        csrf = m.group(1);
        track = json(send(request(PASSPORT + "/api/passport/auth/password/submit").header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"retpath\":\"https://passport.yandex.ru/\"}"))));
        if (!track.has("track_id")) throw new IOException("Яндекс не начал вход: " + errors(track));
        JsonObject code = json(send(form(PASSPORT + "/api/passport/auth/magic/code",
                Map.of("location_id", "0", "magic_track_id", track.get("track_id").getAsString(), "track_id", "")).header("X-CSRF-Token", csrf)));
        if (!code.has("link")) throw new IOException("Яндекс не выдал QR-код: " + errors(code));
        return code.get("link").getAsString();
    }

    /** Null while the phone has not confirmed yet; the x_token once it has. */
    String poll() throws IOException {
        JsonObject status = json(send(request(PASSPORT + "/api/passport/auth/magic/code/status").header("X-CSRF-Token", csrf)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(track.toString()))));
        if (status.has("errors") && !status.getAsJsonArray("errors").isEmpty()) throw new IOException("QR-код устарел, обновите его (" + errors(status) + ")");
        if (!"otp_auth_finished".equals(status.has("state") ? status.get("state").getAsString() : "")) return null;
        send(form(PASSPORT + "/api/passport/sessions/get_session", Map.of("track_id", status.get("trackId").getAsString())).header("X-CSRF-Token", csrf));
        String session = cookies.getCookieStore().getCookies().stream().filter(c -> c.getDomain() != null && c.getDomain().endsWith("yandex.ru"))
                .map(c -> c.getName() + "=" + c.getValue()).collect(Collectors.joining("; "));
        JsonObject token = json(send(form("https://mobileproxy.passport.yandex.net/1/bundle/oauth/token_by_sessionid",
                Map.of("client_id", X_CLIENT_ID, "client_secret", X_CLIENT_SECRET))
                .header("Ya-Client-Host", "passport.yandex.ru").header("Ya-Client-Cookie", session)));
        if (!token.has("access_token")) throw new IOException("Яндекс не выдал токен входа: " + errors(token));
        return token.get("access_token").getAsString();
    }

    /** The account's display name for the settings screen, or "" when the x_token no longer works. */
    String accountName(String xToken) throws IOException {
        JsonObject info = json(send(request("https://mobileproxy.passport.yandex.net/1/bundle/account/short_info/?avatar_size=islands-300")
                .header("Authorization", "OAuth " + xToken)));
        if (!"ok".equals(info.has("status") ? info.get("status").getAsString() : "")) return "";
        for (String key : new String[] {"display_name", "display_login", "normalized_display_login"})
            if (info.has(key) && info.get(key).isJsonPrimitive() && !info.get(key).getAsString().isBlank()) return info.get(key).getAsString();
        return "аккаунт Яндекса";
    }

    /** A Yandex Music token minted from the x_token (the same grant Yandex's own apps use). */
    String musicToken(String xToken) throws IOException {
        JsonObject token = json(send(form("https://oauth.mobile.yandex.net/1/token", Map.of("client_id", MUSIC_CLIENT_ID,
                "client_secret", MUSIC_CLIENT_SECRET, "grant_type", "x-token", "access_token", xToken))));
        if (!token.has("access_token")) throw new IOException("Яндекс не выдал токен Музыки: " + errors(token));
        return token.get("access_token").getAsString();
    }

    /** Fresh session cookies for the domain of {@code retpath} (yandex.ru or ya.ru), as a Cookie header. */
    String sessionCookies(String xToken, String retpath) throws IOException {
        JsonObject start = json(send(form("https://mobileproxy.passport.yandex.net/1/bundle/auth/x_token/", Map.of("type", "x-token", "retpath", retpath))
                .header("Ya-Consumer-Authorization", "OAuth " + xToken)));
        if (!"ok".equals(start.has("status") ? start.get("status").getAsString() : "")) throw new IOException("Яндекс не принял вход: " + errors(start));
        send(request(start.get("passport_host").getAsString() + "/auth/session/?track_id=" + URLEncoder.encode(start.get("track_id").getAsString(), StandardCharsets.UTF_8)));
        String domain = URI.create(retpath).getHost().replaceFirst("^www\\.", "");
        return cookies.getCookieStore().getCookies().stream().filter(c -> c.getDomain() != null && c.getDomain().replaceFirst("^\\.", "").endsWith(domain))
                .map(c -> c.getName() + "=" + c.getValue()).collect(Collectors.joining("; "));
    }

    // ---- What is saved: the x_token, the account name and the derived music token, each in its own file.
    static String savedXToken() { return read(X_TOKEN); }
    static String savedAccount() { return read(ACCOUNT); }
    static String savedMusicToken() { return read(MUSIC_TOKEN); }

    static void save(String xToken, String account, String musicToken) throws IOException {
        Files.createDirectories(AliceAuth.DIR);
        Files.writeString(X_TOKEN, xToken, StandardCharsets.UTF_8);
        Files.writeString(ACCOUNT, account, StandardCharsets.UTF_8);
        if (!musicToken.isBlank()) Files.writeString(MUSIC_TOKEN, musicToken, StandardCharsets.UTF_8);
    }

    static void saveMusicToken(String musicToken) throws IOException { Files.writeString(MUSIC_TOKEN, musicToken, StandardCharsets.UTF_8); }

    /** Logs out of the game only: the hand-made sync.json and music_token.txt stay. */
    static void forget() throws IOException {
        Files.deleteIfExists(X_TOKEN);
        Files.deleteIfExists(ACCOUNT);
        Files.deleteIfExists(MUSIC_TOKEN);
    }

    private static String read(Path file) {
        try { return Files.readString(file, StandardCharsets.UTF_8).replace("﻿", "").trim(); } catch (IOException missing) { return ""; }
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", USER_AGENT);
    }

    private static HttpRequest.Builder form(String url, Map<String, String> fields) {
        String body = fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return request(url).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException {
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) throw new IOException("Яндекс ответил " + response.statusCode());
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано", interrupted);
        }
    }

    private static JsonObject json(HttpResponse<String> response) throws IOException {
        try { return JsonParser.parseString(response.body()).getAsJsonObject(); }
        catch (RuntimeException notJson) { throw new IOException("Яндекс ответил не JSON (" + response.statusCode() + ")"); }
    }

    /** Error codes only: Yandex's answers never carry tokens in "errors". */
    private static String errors(JsonObject answer) {
        return answer.has("errors") ? answer.get("errors").toString() : answer.has("error") ? answer.get("error").toString() : "нет ответа";
    }
}
