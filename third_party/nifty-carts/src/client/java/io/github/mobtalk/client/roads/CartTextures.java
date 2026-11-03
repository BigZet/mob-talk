package io.github.mobtalk.client.roads;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/** The carts' textures, put together from the game's own block textures (planks, logs, stone, wool) the first time a cart is drawn,
 * so that no vanilla texture is shipped and a resource pack's planks show on the carts too. The layouts and the copying are ported
 * from NiftyCarts' {@code AssembledTexture}, {@code Material}, {@code Fill} and the texture list in {@code ModelManagerMixin} (MIT,
 * third_party/nifty-carts/NOTICE.md); the block textures are read from the resource manager instead of the block atlas. */
final class CartTextures {
    private CartTextures() { }

    static final int[][] R0 = { { 1, 0 }, { 0, 1 } }, R90 = { { 0, -1 }, { 1, 0 } }, R270 = { { 0, 1 }, { -1, 0 } };

    private static final Map<Identifier, Identifier> MADE = new HashMap<>();

    /** Forgets the made textures (a resource reload may change the planks). */
    static void clear() {
        MinecraftClient client = MinecraftClient.getInstance();
        for (Identifier id : MADE.values()) client.getTextureManager().destroyTexture(id);
        MADE.clear();
    }

    static Identifier cart(String wood) { return made("road_cart/" + wood, () -> cartLayout(wood)); }

    static Identifier wagon(String wood) { return made("road_wagon/" + wood, () -> wagonLayout(wood)); }

    static Identifier roof(String color) {
        return made("road_roof/" + color, () -> new Layout(16, 16).add(new Material(block(color + "_wool"), 16).fill(0, 0, 16, 16, R0, 0, 0)));
    }

    private static Identifier made(String key, java.util.function.Supplier<Layout> layout) {
        Identifier id = Identifier.of("mobtalk", "dynamic/" + key);
        return MADE.computeIfAbsent(id, k -> {
            MinecraftClient client = MinecraftClient.getInstance();
            client.getTextureManager().registerTexture(k, new NativeImageBackedTexture(layout.get().assemble(client.getResourceManager())));
            return k;
        });
    }

    private static String log(String wood) { return wood.equals("crimson") || wood.equals("warped") ? "stem" : "log"; }

    private static Layout cartLayout(String wood) {
        return new Layout(64, 64)
                .add(new Material(block(wood + "_planks"), 16).fill(0, 0, 60, 38, R0, 0, 2).fill(0, 28, 20, 33, R90, 4, -2).fill(12, 30, 8, 31, R270, 0, 4))
                .add(new Material(block("stripped_" + wood + "_" + log(wood)), 16).fill(54, 54, 10, 10, R0, 0, 2))
                .add(new Material(block(wood + "_" + log(wood)), 16).fill(0, 21, 60, 4, R90, 0, 0).fill(46, 60, 8, 4, R90, 0, 0))
                .add(new Material(block("stone"), 16).fill(62, 55, 2, 9, R0, 0, 0));
    }

    private static Layout wagonLayout(String wood) {
        return new Layout(64, 64)
                .add(new Material(block(wood + "_planks"), 16).fill(0, 0, 64, 48, R0, 0, 0))
                .add(new Material(block("stripped_" + wood + "_" + log(wood)), 16).fill(54, 53, 10, 11, R0, 0, 2).fill(0, 32, 13, 19, R0, 1, 0))
                .add(new Material(block(wood + "_" + log(wood)), 16).fill(0, 60, 54, 4, R90, 0, 0))
                .add(new Material(block("stone"), 16).fill(62, 54, 2, 10, R0, 0, 0));
    }

    private static Identifier block(String name) { return Identifier.ofVanilla("textures/block/" + name + ".png"); }

    /** A texture of the given size in model pixels, drawn from materials in order. */
    private static final class Layout {
        final int width, height;
        final List<Material> materials = new ArrayList<>();
        Layout(int width, int height) { this.width = width; this.height = height; }
        Layout add(Material material) { materials.add(material); return this; }

        NativeImage assemble(ResourceManager resources) {
            List<NativeImage> sources = new ArrayList<>();
            int resolution = 1;
            for (Material material : materials) {
                NativeImage source = read(resources, material.texture);
                sources.add(source);
                resolution = Math.max(resolution, source.getWidth() / material.size);
            }
            NativeImage image = new NativeImage(width * resolution, height * resolution, true);
            for (int i = 0; i < materials.size(); i++) {
                NativeImage source = sources.get(i);
                for (Fill fill : materials.get(i).fills) fill.draw(image, source, Math.max(1, source.getWidth() / materials.get(i).size), resolution);
                source.close();
            }
            return image;
        }

        /** The block texture, or a plain grey square where it is missing (a resource pack without it). */
        private static NativeImage read(ResourceManager resources, Identifier id) {
            try (InputStream in = resources.getResourceOrThrow(id).getInputStream()) { return NativeImage.read(in); }
            catch (Exception missing) {
                NativeImage grey = new NativeImage(16, 16, false);
                grey.fillRect(0, 0, 16, 16, 0xFF808080);
                return grey;
            }
        }
    }

    /** One block texture and the rectangles of the cart texture it fills. */
    private static final class Material {
        final Identifier texture; final int size;
        final List<Fill> fills = new ArrayList<>();
        Material(Identifier texture, int size) { this.texture = texture; this.size = size; }
        Material fill(int x, int y, int width, int height, int[][] rot, int u, int v) { fills.add(new Fill(x, y, width, height, rot, u, v)); return this; }
    }

    /** A rectangle filled with the block texture, tiled, turned by {@code rot} and shifted by {@code u, v}. */
    private record Fill(int x, int y, int width, int height, int[][] rot, int u, int v) {
        void draw(NativeImage image, NativeImage source, int resolution, int outResolution) {
            int r = Math.max(1, outResolution / resolution), side = source.getWidth(); // an animated texture: the first frame
            int x1 = (x + width) * resolution, y1 = (y + height) * resolution, u0 = u * resolution, v0 = v * resolution;
            for (int py = y * resolution; py < y1; py++)
                for (int dy = 0; dy < r; dy++)
                    for (int px = x * resolution; px < x1; px++)
                        for (int dx = 0; dx < r; dx++) {
                            int u1 = px * rot[0][0] + py * rot[0][1] + Math.min(rot[0][0], rot[0][1]) + u0;
                            int v1 = px * rot[1][0] + py * rot[1][1] + Math.min(rot[1][0], rot[1][1]) + v0;
                            int color = source.getColor(Math.floorMod(u1, side), Math.floorMod(v1, side));
                            int ox = r * px + dx, oy = r * py + dy;
                            if (ox < image.getWidth() && oy < image.getHeight()) image.setColor(ox, oy, color);
                        }
        }
    }
}
