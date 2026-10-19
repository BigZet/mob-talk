package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VisionTest {
    private static MobSnapshot snapshot(Map<String, Object> place) {
        Map<String, Object> environment = place == null ? Map.of() : Map.of("place", place);
        return new MobSnapshot(UUID.randomUUID(), "minecraft:cow", "Корова", 10, 10, "minecraft:overworld", "plains", "day", "clear", 3, "minecraft:air",
                List.of(), List.of(), UUID.randomUUID(), Map.of(), Map.of(), environment, List.of(), List.of(ActionType.NONE), 0);
    }

    @Test void modesAndReasons() {
        assertEquals("unusual", Vision.normalize(null)); assertEquals("always", Vision.normalize(" Always ")); assertEquals("off", Vision.normalize("off"));
        assertFalse(Vision.wanted("off", snapshot(Map.of("trapped", true)), true, "видишь?"));
        assertTrue(Vision.wanted("always", snapshot(null), false, "привет"));
        assertTrue(Vision.wanted("unusual", snapshot(null), true, "привет"));
        assertTrue(Vision.wanted("unusual", snapshot(null), false, "Ты видишь, что я построил?"));
        assertTrue(Vision.wanted("unusual", snapshot(Map.of("kind", "PIT", "trapped", true)), false, "привет"));
        assertFalse(Vision.wanted("unusual", snapshot(Map.of("kind", "OPEN", "trapped", false)), false, "привет"));
    }

    @Test void theMessageCarriesThePictureAndCanLoseIt() throws Exception {
        int[] rgb = new int[64 * 48];
        for (int i = 0; i < rgb.length; i++) rgb[i] = (i % 64) << 16 | (i / 64) << 10;
        byte[] jpeg = Vision.jpeg(rgb, 64, 48);
        assertTrue(jpeg.length > 100 && (jpeg[0] & 0xFF) == 0xFF && (jpeg[1] & 0xFF) == 0xD8, "a JPEG");
        JsonObject message = Vision.message("Игрок говорит тебе: «Привет»", jpeg);
        JsonArray parts = message.getAsJsonArray("content");
        assertEquals("text", parts.get(0).getAsJsonObject().get("type").getAsString());
        assertTrue(parts.get(0).getAsJsonObject().get("text").getAsString().contains("Привет"));
        assertTrue(parts.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString().startsWith("data:image/jpeg;base64,"));
        JsonObject body = new JsonObject(); JsonArray messages = new JsonArray(); messages.add(ReplySchema.message("system", "x")); messages.add(message); body.add("messages", messages);
        assertTrue(Vision.hasImage(body));
        assertTrue(Vision.stripImages(body));
        assertFalse(Vision.hasImage(body));
        assertTrue(body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString().contains("Привет"));
    }

    @Test void bigPicturesShrink() throws Exception {
        int[] rgb = new int[1920 * 1080];
        byte[] jpeg = Vision.jpeg(rgb, 1920, 1080);
        javax.imageio.ImageIO.setUseCache(false);
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
        assertTrue(image.getWidth() <= Vision.MAX_SIDE && image.getHeight() <= Vision.MAX_SIDE, image.getWidth() + "x" + image.getHeight());
    }
}
