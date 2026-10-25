package io.github.alicespeaker.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Yandex Music through the unofficial mobile API (as MarshalX/yandex-music-api documents it), with the player's own
 * OAuth token from ~/.alice-speaker/music_token.txt or YA_MUSIC_TOKEN. Blocking calls: run them on a worker thread.
 */
final class YandexMusic {
    private static final String API = "https://api.music.yandex.net";
    private static final String CLIENT = "YandexMusicAndroid/24023621";
    private static final String SIGN_SALT = "XrQUXsGUdcvLrOMQrjSvsgSqnbsPdvRS";
    private static final int MAX_MP3 = 40 * 1024 * 1024;

    record Track(String id, String title, String artists) {
        String label() { return artists.isBlank() ? title : artists + " — " + title; }
    }

    /** Decoded track: mono 16-bit PCM at its own rate. */
    record Audio(byte[] pcm, int rate, boolean preview) { }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final String token;
    private final Consumer<String> log;

    YandexMusic(String token, Consumer<String> log) {
        this.token = token == null ? "" : token.trim();
        this.log = log;
    }

    /** Rejected token (401/403): a token minted from the in-game login can be renewed once. */
    static final class TokenRejected extends IOException {
        TokenRejected(String message) { super(message); }
    }

    interface Call<T> { T run(YandexMusic music) throws IOException; }

    /** Runs a call with the current token; if Yandex rejects it and the player logged in from the game, mints a new one and retries once. */
    static <T> T withToken(Consumer<String> log, Call<T> call) throws IOException {
        try { return call.run(new YandexMusic(loadToken(), log)); }
        catch (TokenRejected rejected) {
            String xToken = YandexLogin.savedXToken();
            if (xToken.isBlank()) throw rejected;
            log.accept("music token rejected, minting a new one from the login");
            String fresh = new YandexLogin().musicToken(xToken);
            YandexLogin.saveMusicToken(fresh);
            return call.run(new YandexMusic(fresh, log));
        }
    }

    /** YA_MUSIC_TOKEN, then the token from the in-game login, then the hand-made music_token.txt. */
    static String loadToken() {
        String value = System.getenv("YA_MUSIC_TOKEN");
        if (value != null && !value.isBlank()) return value.trim();
        String login = YandexLogin.savedMusicToken();
        if (!login.isBlank()) return login;
        try { return Files.readString(AliceAuth.DIR.resolve("music_token.txt"), StandardCharsets.UTF_8).replace("﻿", "").trim(); }
        catch (IOException missing) { return ""; }
    }

    boolean hasToken() { return !token.isBlank(); }

    /** Tracks for a spoken request; an empty query means "My wave" (needs the token). */
    List<Track> find(String query) throws IOException {
        if (query == null || query.isBlank()) return wave();
        JsonObject result = get("/search?type=track&page=0&nocorrect=false&text=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        JsonObject tracks = result.has("tracks") && result.get("tracks").isJsonObject() ? result.getAsJsonObject("tracks") : null;
        List<Track> found = new ArrayList<>();
        if (tracks != null && tracks.has("results")) for (JsonElement t : tracks.getAsJsonArray("results")) addTrack(found, t);
        log.accept("music search \"" + query + "\": " + found.size() + " tracks");
        return found;
    }

    private List<Track> wave() throws IOException {
        JsonObject result = get("/rotor/station/user:onyourwave/tracks?settings2=true");
        List<Track> found = new ArrayList<>();
        if (result.has("sequence")) for (JsonElement item : result.getAsJsonArray("sequence"))
            if (item.isJsonObject() && item.getAsJsonObject().has("track")) addTrack(found, item.getAsJsonObject().get("track"));
        log.accept("music wave: " + found.size() + " tracks");
        return found;
    }

    private static void addTrack(List<Track> into, JsonElement element) {
        if (!element.isJsonObject()) return;
        JsonObject t = element.getAsJsonObject();
        if (t.has("available") && !t.get("available").getAsBoolean()) return;
        String id = t.has("id") ? t.get("id").getAsString() : "";
        if (id.isBlank()) return;
        List<String> names = new ArrayList<>();
        if (t.has("artists")) for (JsonElement a : t.getAsJsonArray("artists"))
            if (a.isJsonObject() && a.getAsJsonObject().has("name")) names.add(a.getAsJsonObject().get("name").getAsString());
        into.add(new Track(id, t.has("title") ? t.get("title").getAsString() : "?", String.join(", ", names)));
    }

    /** Downloads the best MP3 of a track and decodes it. */
    Audio load(Track track) throws IOException {
        JsonElement infos = getElement("/tracks/" + track.id() + "/download-info");
        JsonObject best = null;
        if (infos.isJsonArray()) for (JsonElement e : infos.getAsJsonArray()) {
            JsonObject info = e.getAsJsonObject();
            if (!"mp3".equals(info.get("codec").getAsString())) continue;
            if (best == null || info.get("bitrateInKbps").getAsInt() > best.get("bitrateInKbps").getAsInt()) best = info;
        }
        if (best == null) throw new IOException("У трека нет MP3 для скачивания");
        boolean preview = best.has("preview") && best.get("preview").getAsBoolean();
        String xml = send(HttpRequest.newBuilder(URI.create(best.get("downloadInfoUrl").getAsString())), HttpResponse.BodyHandlers.ofString()).body();
        String host = tag(xml, "host"), path = tag(xml, "path"), ts = tag(xml, "ts"), s = tag(xml, "s");
        String sign = md5(SIGN_SALT + path.substring(1) + s);
        URI mp3Url = URI.create("https://" + host + "/get-mp3/" + sign + "/" + ts + path);
        byte[] mp3 = send(HttpRequest.newBuilder(mp3Url), HttpResponse.BodyHandlers.ofByteArray()).body();
        if (mp3.length > MAX_MP3) throw new IOException("Трек слишком большой");
        log.accept("music track " + track.id() + ": " + mp3.length + " bytes mp3, " + best.get("bitrateInKbps").getAsInt() + " kbps" + (preview ? ", preview" : ""));
        return decode(mp3, preview);
    }

    static Audio decode(byte[] mp3, boolean preview) throws IOException {
        try {
            Bitstream bitstream = new Bitstream(new ByteArrayInputStream(mp3));
            Decoder decoder = new Decoder();
            ByteArrayOutputStream out = new ByteArrayOutputStream(mp3.length * 6);
            int rate = 0;
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                SampleBuffer frame = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                if (rate == 0) rate = frame.getSampleFrequency();
                int channels = frame.getChannelCount();
                short[] buffer = frame.getBuffer();
                for (int i = 0; i + channels - 1 < frame.getBufferLength(); i += channels) {
                    int mixed = 0;
                    for (int c = 0; c < channels; c++) mixed += buffer[i + c];
                    short value = (short) (mixed / channels);
                    out.write(value & 255);
                    out.write((value >> 8) & 255);
                }
                bitstream.closeFrame();
            }
            bitstream.close();
            if (rate == 0 || out.size() < 2) throw new IOException("MP3 без звука");
            return new Audio(out.toByteArray(), rate, preview);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Не удалось раскодировать MP3: " + failure.getMessage(), failure);
        }
    }

    private JsonObject get(String path) throws IOException {
        JsonElement result = getElement(path);
        if (!result.isJsonObject()) throw new IOException("Яндекс Музыка ответила не тем: " + path);
        return result.getAsJsonObject();
    }

    private JsonElement getElement(String path) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(API + path)).header("X-Yandex-Music-Client", CLIENT);
        if (hasToken()) request.header("Authorization", "OAuth " + token);
        String body = send(request, HttpResponse.BodyHandlers.ofString()).body();
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("error")) {
            JsonElement error = root.get("error");
            String name = error.isJsonObject() && error.getAsJsonObject().has("name") ? error.getAsJsonObject().get("name").getAsString() : error.toString();
            if (name.contains("Legal")) throw new IOException("Яндекс Музыка недоступна в этом регионе (выключите VPN)");
            throw new IOException("Яндекс Музыка: " + name);
        }
        return root.get("result");
    }

    private <T> HttpResponse<T> send(HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            HttpResponse<T> response = http.send(request.timeout(Duration.ofSeconds(60)).GET().build(), handler);
            if (response.statusCode() == 401 || response.statusCode() == 403) throw new TokenRejected("Яндекс Музыка не приняла токен (" + response.statusCode() + ")");
            if (response.statusCode() == 451) throw new IOException("Яндекс Музыка недоступна в этом регионе (выключите VPN)");
            if (response.statusCode() >= 400 && response.statusCode() != 404) throw new IOException("Яндекс Музыка ответила " + response.statusCode());
            return response;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("прервано", interrupted);
        }
    }

    private static String tag(String xml, String name) throws IOException {
        Matcher m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        if (!m.find()) throw new IOException("Нет " + name + " в download-info");
        return m.group(1);
    }

    private static String md5(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    // ---- What the player asked for, read from their own words.
    enum Kind { PLAY, NEXT, STOP, RESUME }
    record Intent(Kind kind, String query) { }

    private static final Pattern STOP = Pattern.compile("(выключи|останови|стоп|хватит|пауза|поставь на паузу|убери)\\s*(музыку|музыка|песню|трек)?", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NEXT = Pattern.compile("(следующ|дальше|переключи|другую песню|другой трек|пропусти)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern RESUME = Pattern.compile("(продолжи|продолжай|сними с паузы|сними паузу|верни музыку|играй дальше|сыграй дальше)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PLAY = Pattern.compile("(?:включи|поставь|сыграй|играй|запусти)\\s*(.*)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern FILLER = Pattern.compile("^(мне\\s+)?(пожалуйста\\s+)?(музыку|музыка|песню|песни|песня|трек|треки|что-нибудь|что нибудь|мою волну|волну)?\\s*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Wake words and conversational openers before the command: "алис, нет, включи…", "ну давай про…". */
    private static final Pattern OPENER = Pattern.compile("^((алиса|алис|алисочка|слушай|нет|да|ну|а|так|ладно|давай|окей|ок)\\s+)+", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Null when the words are not about music. {@code musicPlaying} lets a bare "стоп" or "дальше" count;
     * {@code aliceSawMusic} (Alice answered with a player directive or "log in to listen") makes the whole phrase a
     * search, so her "authorize" refusal never reaches the player.
     */
    static Intent intent(String words, boolean musicPlaying, boolean aliceSawMusic) {
        if (words == null) return null;
        String w = words.toLowerCase().replace('ё', 'е').replaceAll("[.,!?…]", " ").replaceAll("\\s+", " ").trim();
        w = OPENER.matcher(w).replaceFirst("").replaceAll("\\s+(алиса|пожалуйста)$", "").trim();
        boolean aboutMusic = w.contains("музык") || w.contains("песн") || w.contains("трек") || w.contains("волн") || aliceSawMusic;
        if ((musicPlaying || aboutMusic) && STOP.matcher(w).lookingAt()) return new Intent(Kind.STOP, "");
        if (musicPlaying && NEXT.matcher(w).find()) return new Intent(Kind.NEXT, "");
        if (!musicPlaying && RESUME.matcher(w).lookingAt()) return new Intent(Kind.RESUME, "");
        // "включи свет" is not music; only treat non-music objects as songs when Alice saw music too or words say so.
        if (!aboutMusic) return null;
        Matcher play = PLAY.matcher(w);
        if (play.lookingAt() || play.reset().find()) return new Intent(Kind.PLAY, query(play.group(1)));
        // Fairy tales are audio books for her, not songs: searching tracks would play "Расскажи сказку" the song.
        if (aliceSawMusic && !w.isBlank() && !w.contains("сказк")) return new Intent(Kind.PLAY, query(w.replaceAll("^(.*\\s)?(про|о|об)\\s+", "")));
        return null;
    }

    private static String query(String rest) {
        return FILLER.matcher(rest.trim()).replaceFirst("").replaceAll("^(группы|группу|исполнителя|самого|самую|самой)\\s+", "").trim();
    }
}
