package io.github.mobtalk.client;

import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.core.MobCard;
import io.github.mobtalk.core.MobState;
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

/** A line over every creature the player knows: its name, the word of its state and the relationship, drawn like a name tag.
 * The text is rebuilt twice a second from the world memory on the client tick; the render pass only draws. */
final class MobLabels {
    static final double RANGE = 24;
    private final MobTalkClient owner;
    private final Map<UUID, Text> labels = new HashMap<>();
    /** Трек N: вторая строка под именем — «ждёт малыша · 3 из 5 дн.». */
    private final Map<UUID, Text> statuses = new HashMap<>();

    MobLabels(MobTalkClient owner) { this.owner = owner; }

    void tick() {
        labels.clear(); statuses.clear();
        MinecraftClient client = owner.client;
        if (!owner.enabled() || !owner.config().showNameTags || client.player == null || client.world == null || client.getServer() == null) return;
        MobGameBridge game = MobGameBridge.active(client.getServer());
        if (game == null) return;
        UUID player = client.player.getUuid();
        for (MobEntity mob : client.world.getEntitiesByClass(MobEntity.class, client.player.getBoundingBox().expand(RANGE), Entity::isAlive)) {
            // Only creatures the player has a history with (a conversation or a life story) and a name of their own: a stranger
            // that merely saw something gets no label.
            if (!game.memory.acquainted(mob.getUuid())) continue;
            MobCard card = game.memory.card(mob.getUuid(), player);
            if (card == null) continue;
            String name = game.memory.displayName(mob.getUuid());
            if (name.isBlank()) continue;
            MobState.Descriptor marker = card.state() == null ? null : card.state().marker();
            String word = marker == null ? "ровно" : Text.translatable("mobtalk.state." + marker.key()).getString();
            int sign = marker == null ? 0 : marker.sign();
            int relation = card.relationship();
            Text label = Text.literal(name).formatted(Formatting.WHITE)
                    .append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(word).formatted(sign > 0 ? Formatting.GREEN : sign < 0 ? Formatting.RED : Formatting.GRAY))
                    .append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal((relation > 0 ? "+" : "") + relation).formatted(relation > 0 ? Formatting.GREEN : relation < 0 ? Formatting.RED : Formatting.GRAY));
            labels.put(mob.getUuid(), label);
            String status = ChildView.status(mob.getUuid());
            if (!status.isBlank()) statuses.put(mob.getUuid(), Text.literal(status).formatted(Formatting.LIGHT_PURPLE));
        }
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
            // Above the vanilla name tag when the creature has one, else where the tag would be.
            double height = entity.getHeight() + 0.5 + (entity.hasCustomName() && entity.isCustomNameVisible() ? 0.3 : 0);
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
