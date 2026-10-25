package io.github.mobtalk.client;

import io.github.mobtalk.net.MobLabelS2C;
import io.github.mobtalk.net.MobStateS2C;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Строка над каждым знакомым существом: имя, слово состояния и отношение, как табличка с именем. Что писать, решает сервер
 * ({@link MobLabelS2C}, {@link MobStateS2C} в {@link MobViews}); текст пересобирается дважды в секунду на тике клиента, рендер только рисует. */
public final class MobLabels {
    static final double RANGE = 24;
    private final MobTalkClient owner;
    private final Map<UUID, Text> labels = new HashMap<>();
    /** Трек N: вторая строка под именем — «ждёт малыша · 3 из 5 дн.». */
    private final Map<UUID, Text> statuses = new HashMap<>();
    /** Мобы, над которыми этот кадр рисует подпись Mob Talk: ванильная табличка с CustomName над ними не рисуется (иначе две надписи на
     * одной высоте — так было у настоятеля храма). Поток рендера. */
    private static volatile java.util.Set<UUID> drawn = java.util.Set.of();
    public static boolean draws(UUID mob) { return drawn.contains(mob); }

    MobLabels(MobTalkClient owner) { this.owner = owner; }

    private long diagnosticTicks;
    void tick() {
        labels.clear(); statuses.clear(); drawn = java.util.Set.of();
        MinecraftClient client = owner.client;
        boolean diagnose = ++diagnosticTicks % 20 == 0; // раз в ~10 с: почему подписей нет, пока ищем пропавшие таблички на сервере
        if (!owner.enabled() || !owner.config().showNameTags || client.player == null || client.world == null) {
            if (diagnose) org.slf4j.LoggerFactory.getLogger("mobtalk").info("Mob Talk: подписи выключены (enabled {}, showNameTags {}, игрок {}, мир {})", owner.enabled(),
                    owner.config() != null && owner.config().showNameTags, client.player != null, client.world != null);
            return;
        }
        int around = 0, known = 0;
        for (MobEntity mob : client.world.getEntitiesByClass(MobEntity.class, client.player.getBoundingBox().expand(RANGE), Entity::isAlive)) {
            around++;
            MobLabelS2C label = owner.views.label(mob.getUuid());
            if (label == null || label.name().isBlank()) continue;
            known++;
            MobStateS2C state = owner.views.state(mob.getUuid());
            String word = state == null || state.word().isEmpty() ? "ровно" : Text.translatable("mobtalk.state." + state.word()).getString();
            int sign = state == null ? 0 : state.wordSign();
            int relation = state == null ? 0 : state.relationship();
            Text line = Text.literal(label.name()).formatted(Formatting.WHITE)
                    .append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(word).formatted(sign > 0 ? Formatting.GREEN : sign < 0 ? Formatting.RED : Formatting.GRAY))
                    .append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal((relation > 0 ? "+" : "") + relation).formatted(relation > 0 ? Formatting.GREEN : relation < 0 ? Formatting.RED : Formatting.GRAY));
            labels.put(mob.getUuid(), line);
            String status = label.line().isBlank() ? ChildView.status(mob.getUuid()) : label.line();
            if (!status.isBlank()) statuses.put(mob.getUuid(), Text.literal(status).formatted(Formatting.LIGHT_PURPLE));
        }
        drawn = java.util.Set.copyOf(labels.keySet());
        if (diagnose) org.slf4j.LoggerFactory.getLogger("mobtalk").info("Mob Talk: подписи: мобов рядом {}, с именем от сервера {}, строк к отрисовке {}, подписей в памяти клиента {}", around, known, labels.size(), owner.views.names().size());
    }

    void render(WorldRenderContext context) {
        if (labels.isEmpty() || context.matrixStack() == null || context.consumers() == null) return;
        MinecraftClient client = owner.client;
        if (client.world == null || client.options.hudHidden) return;
        Camera camera = context.camera();
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        TextRenderer textRenderer = client.textRenderer;
        float tickDelta = context.tickCounter().getTickDelta(true);
        Vec3d eye = camera.getPos();
        int background = (int) (client.options.getTextBackgroundOpacity(0.25f) * 255.0f) << 24;
        for (Entity entity : client.world.getEntities()) {
            Text label = labels.get(entity.getUuid());
            if (label == null || !entity.isAlive() || entity.isInvisible() || entity == camera.getFocusedEntity()) continue;
            if (entity.squaredDistanceTo(eye) > RANGE * RANGE) continue;
            Vec3d position = entity.getLerpedPos(tickDelta);
            double height = entity.getHeight() + 0.5;
            matrices.push();
            matrices.translate(position.x - eye.x, position.y + height - eye.y, position.z - eye.z);
            matrices.multiply(camera.getRotation());
            matrices.scale(0.025f, -0.025f, 0.025f);
            Matrix4f matrix = matrices.peek().getPositionMatrix();
            float x = -textRenderer.getWidth(label) / 2.0f;
            textRenderer.draw(label, x, 0, 0x20FFFFFF, false, matrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH, background, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            textRenderer.draw(label, x, 0, 0xFFFFFFFF, false, matrix, consumers, TextRenderer.TextLayerType.NORMAL, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            Text status = statuses.get(entity.getUuid());
            if (status != null) {
                float sx = -textRenderer.getWidth(status) / 2.0f;
                textRenderer.draw(status, sx, 10, 0x20FFFFFF, false, matrix, consumers, TextRenderer.TextLayerType.SEE_THROUGH, background, LightmapTextureManager.MAX_LIGHT_COORDINATE);
                textRenderer.draw(status, sx, 10, 0xFFFFFFFF, false, matrix, consumers, TextRenderer.TextLayerType.NORMAL, 0, LightmapTextureManager.MAX_LIGHT_COORDINATE);
            }
            matrices.pop();
        }
    }
}
