package io.github.mobtalk.client.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.game.city.CarEntity;
import io.github.mobtalk.game.city.CarTypes;
import io.github.mobtalk.game.city.CityCars;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

/** The city's cars on the client: the models made by tools/city2/cars.py (assets/mobtalk/models/car/*.json, quads in parts) and
 * their renderer. Opaque faces draw cut out, the glass translucent; lit lamps draw again from the glow maps at full brightness.
 * The doors swing on their hinges, the front wheels steer, all wheels roll by the distance, the steering wheel turns with the
 * front wheels, the beacon flashes. */
public final class CarClient {
    private CarClient() { }

    static final Map<String, Model> MODELS = new HashMap<>();
    /** The ratio of the steering wheel's turn to the road wheels'. */
    private static final float STEERING_RATIO = 12f;

    public static void register() {
        CityCars.register();
        EntityRendererRegistry.register(CarEntity.TYPE, Renderer::new);
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public Identifier getFabricId() { return Identifier.of("mobtalk", "car_models"); }
            @Override public void reload(ResourceManager manager) { load(manager); }
        });
    }

    // ------------------------------------------------------------ the models

    /** One part: its role (body, door, wheel, steer, beacon), its pivot (units), and its quads: per quad 4 × (x, y, z, u, v) then
     * the face's direction (0..5: +x -x +y -y +z -z), its layer (0 opaque, 1 glass) and its glow channels. */
    record Part(String name, String role, float px, float py, float pz, float tilt, boolean front, int side, int door, float open,
                float[] quads, int count) { }

    record Model(int texW, int texH, List<Part> parts) { }

    static void load(ResourceManager manager) {
        MODELS.clear();
        for (CarTypes.Type t : CarTypes.ALL) {
            Identifier id = Identifier.of("mobtalk", "models/car/" + t.id() + ".json");
            Resource res = manager.getResource(id).orElse(null);
            if (res == null) continue;
            try (Reader reader = res.getReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray tex = json.getAsJsonArray("texture");
                List<Part> parts = new ArrayList<>();
                for (JsonElement e : json.getAsJsonArray("parts")) {
                    JsonObject p = e.getAsJsonObject();
                    JsonArray piv = p.getAsJsonArray("pivot");
                    JsonArray qs = p.getAsJsonArray("quads");
                    float[] data = new float[qs.size() * 23];
                    int k = 0;
                    for (JsonElement q : qs) for (JsonElement v : q.getAsJsonArray()) data[k++] = v.getAsFloat();
                    parts.add(new Part(p.get("name").getAsString(), p.get("role").getAsString(), piv.get(0).getAsFloat(), piv.get(1).getAsFloat(),
                            piv.get(2).getAsFloat(), p.has("tilt") ? p.get("tilt").getAsFloat() : 0, p.has("front") && p.get("front").getAsBoolean(),
                            p.has("side") ? p.get("side").getAsInt() : 1, p.has("door") ? p.get("door").getAsInt() : -1,
                            p.has("open") ? p.get("open").getAsFloat() : 70, data, qs.size()));
                }
                MODELS.put(t.id(), new Model(tex.get(0).getAsInt(), tex.get(1).getAsInt(), parts));
            } catch (Exception failure) {
                org.slf4j.LoggerFactory.getLogger("mobtalk").warn("car model {} failed to load", id, failure);
            }
        }
    }

    static Identifier texture(String car, String scheme) { return Identifier.of("mobtalk", "textures/entity/car/" + car + "/" + scheme + ".png"); }
    static Identifier glow(String car, String channel) { return Identifier.of("mobtalk", "textures/entity/car/" + car + "/glow_" + channel + ".png"); }

    // ------------------------------------------------------------ the renderer

    static final class Renderer extends EntityRenderer<CarEntity> {
        Renderer(EntityRendererFactory.Context ctx) { super(ctx); shadowRadius = 1.0f; }

        @Override public Identifier getTexture(CarEntity car) { return texture(car.type().id(), car.scheme()); }

        @Override public void render(CarEntity car, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider consumers, int light) {
            CarTypes.Type t = car.type();
            Model model = MODELS.get(t.id());
            if (model == null) return;
            Identifier tex = texture(t.id(), car.scheme());
            float wheel = MathHelper.lerp(tickDelta, car.prevWheel, car.wheel);
            float steer = car.steer();
            boolean beacon = car.hasBeacon();
            long time = car.getWorld().getTime();
            // the lamps that shine now: head and tail lights; the brake; the beacon flashes blue twice, then (an ambulance's) red
            int glow = 0;
            if (car.light(CarEntity.HEAD)) glow |= 1 | 2;
            if (car.light(CarEntity.BRAKE)) glow |= 2;
            if (beacon && car.light(CarEntity.BEACON)) {
                int phase = (int) ((time + car.getId() * 7L) % 16);
                if (phase < 2 || phase >= 4 && phase < 6) glow |= 4;
                if (phase >= 8 && phase < 10 || phase >= 12 && phase < 14) glow |= 8;
            }
            if (car.light(CarEntity.HAZARD) && (time / 8) % 2 == 0) glow |= 16;
            matrices.push();
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-yaw));
            matrices.scale(1 / 16f, 1 / 16f, 1 / 16f);
            // each part's pose once; then the layers one after another (a buffer is good only until the next getBuffer)
            List<Part> parts = new ArrayList<>();
            List<MatrixStack.Entry> poses = new ArrayList<>();
            for (Part part : model.parts()) {
                if (part.role().equals("beacon") && !beacon) continue;
                matrices.push();
                matrices.translate(part.px(), part.py(), part.pz());
                switch (part.role()) {
                    case "door" -> {
                        int d = part.door();
                        float open = d >= 0 && d < car.door.length ? MathHelper.lerp(tickDelta, car.prevDoor[d], car.door[d]) : 0;
                        open = open * open * (3 - 2 * open);
                        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-part.side() * part.open() * open));
                    }
                    case "wheel" -> {
                        if (part.front()) matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(steer));
                        matrices.multiply(RotationAxis.POSITIVE_X.rotation(wheel));
                    }
                    case "steer" -> {
                        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(part.tilt()));
                        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(-steer * STEERING_RATIO));
                    }
                    default -> { }
                }
                parts.add(part);
                poses.add(matrices.peek().copy());
                matrices.pop();
            }
            VertexConsumer solid = consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(tex));
            for (int i = 0; i < parts.size(); i++) draw(poses.get(i), solid, parts.get(i), model, 0, -1, light);
            VertexConsumer glass = consumers.getBuffer(RenderLayer.getEntityTranslucent(tex));
            for (int i = 0; i < parts.size(); i++) draw(poses.get(i), glass, parts.get(i), model, 1, -1, light);
            String[] channels = {"head", "tail", "blue", "red", "turn"};
            for (int c = 0; c < channels.length; c++) {
                if ((glow & (1 << c)) == 0) continue;
                VertexConsumer lit = consumers.getBuffer(RenderLayer.getEyes(glow(t.id(), channels[c])));
                for (int i = 0; i < parts.size(); i++) draw(poses.get(i), lit, parts.get(i), model, -1, 1 << c, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            }
            matrices.pop();
            super.render(car, yaw, tickDelta, matrices, consumers, light);
        }

        private static final float[][] NORMALS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

        /** Emits the part's quads of a layer (or, with layer -1, those carrying the glow channel). */
        private static void draw(MatrixStack.Entry entry, VertexConsumer out, Part part, Model model, int layer, int channel, int light) {
            float[] q = part.quads();
            float tw = model.texW(), th = model.texH();
            for (int i = 0; i < part.count(); i++) {
                int b = i * 23;
                int l = (int) q[b + 21], g = (int) q[b + 22];
                if (layer >= 0 ? l != layer : (g & channel) == 0) continue;
                float[] n = NORMALS[(int) q[b + 20]];
                for (int v = 0; v < 4; v++) {
                    int o = b + v * 5;
                    out.vertex(entry, q[o], q[o + 1], q[o + 2]).color(255, 255, 255, 255).texture(q[o + 3] / tw, q[o + 4] / th)
                            .overlay(OverlayTexture.DEFAULT_UV).light(light).normal(entry, n[0], n[1], n[2]);
                }
            }
        }
    }
}
