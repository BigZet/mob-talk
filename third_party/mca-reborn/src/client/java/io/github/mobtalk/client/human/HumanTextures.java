/*
 * Mob Talk — human villagers (track M).
 * Texture choice and colour palettes derived from MCA Reborn's SkinLayer, ClothingLayer, HairLayer, FaceLayer, FaceList,
 * ColorPalette and EyeTextureLayers (net.conczin.mca.client), Copyright (C) the MCA Reborn contributors, licensed under the
 * GNU General Public License v3.0. Modified for Mob Talk: pools are found by folder instead of MCA's JSON lists, clothes follow
 * the vanilla profession, and dyes, traits (albinism, heterochromia, rainbow), layered hair and the skin library are left out.
 * The textures under assets/mobtalk/human come from MCA Reborn (GPL-3.0), see third_party/mca-reborn/NOTICE.md.
 */
package io.github.mobtalk.client.human;

import io.github.mobtalk.human.HumanGenes;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ColorHelper;
import net.minecraft.util.math.MathHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Which skin, face, hair and clothes a human villager wears and in what colours. Pools are read from the resource folders on
 * first use and dropped on resource reload ({@link #reload}). Render thread only. */
public final class HumanTextures {
    private static final Logger LOGGER = LoggerFactory.getLogger("mobtalk");
    static final String ROOT = "human";
    private static final Map<String, List<Identifier>> POOLS = new ConcurrentHashMap<>();
    private static final Map<String, int[]> PALETTES = new ConcurrentHashMap<>();
    private static final Map<String, Identifier> EYES = new ConcurrentHashMap<>();
    static final Identifier BLINK = id("face/normal/blink.png");

    private HumanTextures() { }

    public static void reload() {
        TextureManagerHolder.release();
        POOLS.clear(); PALETTES.clear(); EYES.clear();
    }

    static Identifier id(String path) { return Identifier.of("mobtalk", ROOT + "/" + path); }

    // ---- Выбор текстур. ----

    public static Identifier skin(HumanLook look) {
        int index = (int) Math.min(4, Math.max(0, look.genes().skin() * 5));
        return id("skin/" + look.gender() + "/" + index + ".png");
    }
    public static Identifier face(HumanLook look) { return HumanGenes.pick(pool("face/normal"), look.genes().face()); }
    public static Identifier hair(HumanLook look) { return HumanGenes.pick(pool("hair/" + look.gender()), look.genes().hair()); }

    /** Clothes by age and profession: children wear the child (or toddler) sets; adults their profession's sets for their sex plus
     * the neutral ones, or everyday clothes when the profession has none (modded professions). */
    public static Identifier clothes(HumanLook look) {
        List<Identifier> pool;
        if (!look.outfit().isEmpty() && look.age() == HumanLook.Age.ADULT && !pool("clothing/wedding/" + look.outfit()).isEmpty())
            return HumanGenes.pick(pool("clothing/wedding/" + look.outfit()), look.genes().clothes()); // Свадебный наряд Mob Talk.
        if (look.age() != HumanLook.Age.ADULT) pool = pool("clothing/" + look.gender() + "/" + (look.age() == HumanLook.Age.TODDLER ? "toddler" : "child"));
        else {
            // Mob Talk: the village archer and guard (защита деревни) dress like the fletcher and the weaponsmith, the shop's cashier
            // like the librarian and its guard like the armorer.
            String job = switch (look.profession()) { case "archer" -> "fletcher"; case "guard" -> "weaponsmith"; case "cashier" -> "librarian"; case "shop_guard" -> "armorer"; default -> look.profession(); };
            pool = new ArrayList<>(pool("clothing/" + look.gender() + "/" + job));
            pool.addAll(pool("clothing/neutral/" + job));
            if (pool.isEmpty()) pool = pool("clothing/" + look.gender() + "/none");
        }
        return HumanGenes.pick(pool, look.genes().clothes());
    }

    /** The PNGs right inside {@code folder}, sorted by number then name, without "_overlay" companions. */
    static List<Identifier> pool(String folder) {
        return POOLS.computeIfAbsent(folder, f -> {
            String prefix = ROOT + "/" + f + "/";
            ResourceManager manager = MinecraftClient.getInstance().getResourceManager();
            List<Identifier> found = new ArrayList<>(manager.findResources(ROOT + "/" + f, id -> id.getNamespace().equals("mobtalk")
                    && id.getPath().endsWith(".png") && !id.getPath().endsWith("_overlay.png") && !id.getPath().endsWith("blink.png")
                    && id.getPath().indexOf('/', prefix.length()) < 0).keySet());
            found.sort(Comparator.comparing((Identifier id) -> number(id.getPath())).thenComparing(Identifier::getPath));
            return List.copyOf(found);
        });
    }
    private static int number(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
        try { return Integer.parseInt(name); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }
    static boolean exists(Identifier id) {
        return id != null && MinecraftClient.getInstance().getResourceManager().getResource(id).isPresent();
    }
    static Identifier overlay(Identifier texture) {
        if (texture == null) return null;
        Identifier o = Identifier.of(texture.getNamespace(), texture.getPath().replace(".png", "_overlay.png"));
        return POOLS.computeIfAbsent("?" + o, k -> exists(o) ? List.of(o) : List.of()).stream().findFirst().orElse(null);
    }

    // ---- Цвета. ----

    public static int skinColor(HumanLook look) { return palette("colormap/skin.png", look.genes().melanin(), look.genes().hemoglobin()); }
    public static int hairColor(HumanLook look) { return palette("colormap/hair.png", look.genes().eumelanin(), look.genes().pheomelanin()); }

    private static final int BLUE = 0xFF3A98E8, GREEN = 0xFF4CB346, HAZEL = 0xFFC29B35, BROWN = 0xFF7C4825;
    static final int DETAILS_TINT = 0xFF808080;
    public static int eyeColor(HumanLook look) {
        float e = MathHelper.fractionalPart(look.genes().face());
        int color = e < 0.35f ? ColorHelper.Argb.lerp(e / 0.35f, BLUE, GREEN)
                : e < 0.70f ? ColorHelper.Argb.lerp((e - 0.35f) / 0.35f, GREEN, HAZEL)
                : ColorHelper.Argb.lerp((e - 0.70f) / 0.30f, HAZEL, BROWN);
        float factor = 0.5f + MathHelper.clamp(look.genes().eyeBrightness(), 0, 1);
        return ColorHelper.Argb.getArgb(255, scale(ColorHelper.Argb.getRed(color), factor), scale(ColorHelper.Argb.getGreen(color), factor),
                scale(ColorHelper.Argb.getBlue(color), factor));
    }
    private static int scale(int channel, float factor) { return MathHelper.clamp(Math.round(channel * factor), 0, 255); }

    /** MCA's colour maps: 32×32, one gene down, the other across. */
    private static int palette(String path, float u, float v) {
        int[] data = PALETTES.computeIfAbsent(path, p -> {
            try (InputStream in = MinecraftClient.getInstance().getResourceManager().getResourceOrThrow(id(p)).getInputStream(); NativeImage image = NativeImage.read(in)) {
                int w = image.getWidth(), h = image.getHeight();
                int[] colors = new int[w * h + 2];
                colors[0] = w; colors[1] = h;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) colors[2 + y * w + x] = abgrToArgb(image.getColor(x, y));
                return colors;
            } catch (Exception e) {
                LOGGER.warn("Mob Talk: no colour map {}", p, e);
                return new int[] { 1, 1, 0xFFFFFFFF };
            }
        });
        int w = data[0], h = data[1];
        int x = (int) Math.floor(MathHelper.clamp(v * (w - 1), 0, w - 1)), y = (int) Math.floor(MathHelper.clamp(u * (h - 1), 0, h - 1));
        return data[2 + y * w + x] | 0xFF000000;
    }
    private static int abgrToArgb(int abgr) {
        return (abgr & 0xFF00FF00) | ((abgr & 0xFF) << 16) | ((abgr >> 16) & 0xFF);
    }

    // ---- Глаза: белок, детали и радужка из одной текстуры лица, чтобы красить радужку отдельно. ----

    enum EyePart { SCLERA, IRIS, DETAILS }

    static Identifier eyes(Identifier face, EyePart part) {
        return EYES.computeIfAbsent(face + "#" + part, key -> {
            Optional<Resource> resource = MinecraftClient.getInstance().getResourceManager().getResource(face);
            if (resource.isEmpty()) return face;
            try (InputStream in = resource.get().getInputStream(); NativeImage original = NativeImage.read(in)) {
                NativeImage split = new NativeImage(original.getWidth(), original.getHeight(), true);
                for (int x = 0; x < original.getWidth(); x++) for (int y = 0; y < original.getHeight(); y++) {
                    int pixel = original.getColor(x, y);
                    int a = (pixel >>> 24) & 0xFF, b = (pixel >> 16) & 0xFF, g = (pixel >> 8) & 0xFF, r = pixel & 0xFF;
                    if (a != 0 && part == partOf(a, r, g, b)) split.setColor(x, y, pixel);
                }
                Identifier id = Identifier.of("mobtalk", "dynamic/human_eye/" + part.name().toLowerCase(Locale.ROOT) + "/" + face.getPath().replace('/', '_'));
                TextureManagerHolder.register(id, new NativeImageBackedTexture(split));
                return id;
            } catch (Exception e) {
                LOGGER.warn("Mob Talk: eye layers of {} failed", face, e);
                return face;
            }
        });
    }
    static EyePart partOf(int a, int r, int g, int b) {
        int min = Math.min(r, Math.min(g, b)), max = Math.max(r, Math.max(g, b));
        boolean sclera = a == 1 || a == 255 && min >= 160 && max - min <= 32;
        return sclera ? EyePart.SCLERA : max >= 32 ? EyePart.IRIS : EyePart.DETAILS;
    }

    /** Dynamic eye textures to free on reload. */
    private static final class TextureManagerHolder {
        private static final Set<Identifier> REGISTERED = ConcurrentHashMap.newKeySet();
        static void register(Identifier id, NativeImageBackedTexture texture) { MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture); REGISTERED.add(id); }
        static void release() { for (Identifier id : REGISTERED) MinecraftClient.getInstance().getTextureManager().destroyTexture(id); REGISTERED.clear(); }
    }
}
