package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** The screenshot that goes with a question: what the player sees on screen, handed to the model as one small JPEG next to the
 * text (Mantella's "NPCs see what you see"). The facts of the place block stay the authority: the picture adds the look of
 * things, the buildings and the player's gear, never the geometry. Pure Java; the client takes the pixels, this class decides
 * when a picture is worth sending, shrinks and encodes it and builds the message part. */
public final class Vision {
    private Vision() { }

    public static final String OFF = "off", UNUSUAL = "unusual", ALWAYS = "always";
    public static final List<String> MODES = List.of(OFF, UNUSUAL, ALWAYS);
    /** Longest side of the picture sent; a block world reads fine at this size and costs a few hundred tokens. */
    public static final int MAX_SIDE = 512;
    static final float JPEG_QUALITY = 0.6f;
    /** How many questions went out with a picture (self-test bookkeeping). */
    static final AtomicInteger ATTACHED = new AtomicInteger();

    static final String CAPTION = "К вопросу приложен снимок с экрана игрока: это то, что видит он (ты — где-то в кадре или рядом). "
            + "Используй его, чтобы понимать обстановку, постройки, внешность игрока и то, на что он смотрит, но не описывай снимок и не говори "
            + "«на картинке»; если он спорит с фактами блока «Место», верны факты.";

    private static final Pattern ASKS_TO_LOOK = Pattern.compile("(?iU)\\b(видишь|видно|видал|смотри|посмотри|погляди|гляди|взгляни|оглянись|"
            + "что это|что тут|что здесь|что вокруг|что за|как тебе|как я выгляж|что на мне|нравится|красив|построил|построила|сделал тут)\\b");

    public static String normalize(String mode) {
        String value = mode == null ? "" : mode.strip().toLowerCase(Locale.ROOT);
        return MODES.contains(value) ? value : UNUSUAL;
    }

    /** Whether this question deserves a picture: always, never, or when the place is odd, the player asks to look, or it is a first meeting. */
    public static boolean wanted(String mode, MobSnapshot snapshot, boolean firstMeeting, String question) {
        String value = normalize(mode);
        if (value.equals(OFF)) return false;
        if (value.equals(ALWAYS)) return true;
        return firstMeeting || asksToLook(question) || unusual(snapshot);
    }

    static boolean asksToLook(String question) { return question != null && ASKS_TO_LOOK.matcher(question).find(); }

    static boolean unusual(MobSnapshot snapshot) {
        if (snapshot == null || !(snapshot.environmentDetails().get("place") instanceof Map<?, ?> place)) return false;
        if (Boolean.TRUE.equals(place.get("trapped")) || Boolean.TRUE.equals(place.get("enclosed"))) return true;
        Object kind = place.get("kind");
        return kind != null && List.of("PIT", "CHAMBER", "FUNNEL", "CAGE", "PEN").contains(String.valueOf(kind));
    }

    /** The user message with the picture: text first, then the image part at low detail. */
    static JsonObject message(String text, byte[] jpeg) {
        JsonArray content = new JsonArray();
        JsonObject part = new JsonObject(); part.addProperty("type", "text"); part.addProperty("text", text + "\n\n" + CAPTION); content.add(part);
        JsonObject image = new JsonObject(); image.addProperty("type", "image_url");
        JsonObject url = new JsonObject(); url.addProperty("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)); url.addProperty("detail", "low");
        image.add("image_url", url); content.add(image);
        JsonObject message = new JsonObject(); message.addProperty("role", "user"); message.add("content", content);
        return message;
    }

    /** Drops every image part from the request (a provider that rejects images with 400 gets the text alone). Returns whether any was dropped. */
    static boolean stripImages(JsonObject body) {
        if (!(body.get("messages") instanceof JsonArray messages)) return false;
        boolean stripped = false;
        for (JsonElement element : messages) {
            if (!(element instanceof JsonObject message) || !(message.get("content") instanceof JsonArray parts)) continue;
            StringBuilder text = new StringBuilder();
            for (JsonElement part : parts) if (part instanceof JsonObject p && "text".equals(ReplySchema.optional(p, "type"))) text.append(ReplySchema.optional(p, "text"));
            message.addProperty("content", text.toString());
            stripped = true;
        }
        return stripped;
    }

    static boolean hasImage(JsonObject body) {
        if (!(body.get("messages") instanceof JsonArray messages)) return false;
        for (JsonElement element : messages) if (element instanceof JsonObject message && message.get("content") instanceof JsonArray) return true;
        return false;
    }

    public static int attached() { return ATTACHED.get(); }

    /** Shrinks RGB pixels (row-major, 0xRRGGBB) to at most {@link #MAX_SIDE} on the long side and encodes a JPEG. */
    public static byte[] jpeg(int[] rgb, int width, int height) throws IOException {
        if (rgb == null || width <= 0 || height <= 0 || rgb.length < width * height) throw new IOException("Пустой снимок.");
        int step = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) MAX_SIDE));
        int w = Math.max(1, width / step), h = Math.max(1, height / step);
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // A box average over the step: cheaper than a true resample and good enough for blocks.
                int r = 0, g = 0, b = 0, n = 0;
                for (int dy = 0; dy < step; dy++) for (int dx = 0; dx < step; dx++) {
                    int sx = x * step + dx, sy = y * step + dy;
                    if (sx >= width || sy >= height) continue;
                    int c = rgb[sy * width + sx]; r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF; n++;
                }
                row[x] = n == 0 ? 0 : ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
            image.setRGB(0, y, w, 1, row, 0, w);
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT); param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally { writer.dispose(); }
        return out.toByteArray();
    }
}
