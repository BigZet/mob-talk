package io.github.mobtalk.client;

import io.github.mobtalk.game.ChildController;
import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.core.ChildLedger;
import io.github.mobtalk.core.Pregnancy;
import io.github.mobtalk.core.Officialdom;
import io.github.mobtalk.game.OfficialContent;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.VillagerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.model.VillagerResemblingModel;
import net.minecraft.client.model.*;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import io.github.mobtalk.client.human.HumanLooks;
import io.github.mobtalk.client.human.HumanModel;
import io.github.mobtalk.client.human.HumanTextures;
import io.github.mobtalk.client.human.HumanVillagerRenderer;

/** Трек N на клиенте: что видно глазами. Беременность — строка под именем (её рисует {@link MobLabels}) и округлый живот на фигурке
 * отдельным слоем рендера поверх модели жительницы (ванильной или человека трека M) и игрока: модель не меняется, в мире ничего
 * не хранится. Затемнения особой ночи и родов — поверх экрана. Данные раз в полсекунды берутся из памяти мира;
 * рендер только рисует. */
final class ChildView {
    private static volatile Map<UUID, Float> bellies = Map.of();
    private static volatile Map<UUID, String> statuses = Map.of();
    private final MobTalkClient owner;
    ChildView(MobTalkClient owner) { this.owner = owner; }

    /** Строка под именем у носящей ("" — нет). */
    static String status(UUID id) { return statuses.getOrDefault(id, ""); }
    static float belly(UUID id) { Float f = forced.get(id); return f != null ? f : bellies.getOrDefault(id, 0f); }
    /** Самотест облика (трек M): живот без беременности в памяти мира; пустая карта — снять. */
    private static volatile Map<UUID, Float> forced = Map.of();
    static void forceBelly(Map<UUID, Float> bellies) { forced = Map.copyOf(bellies); }
    /** Чиновники: сколько из ста честны (из настроек); зажравшийся глава и инспектор носят живот, честный — нет. */
    private static volatile int officialHonest = 20;
    static float officialBelly(VillagerEntity villager) {
        Officialdom.Rank rank = OfficialContent.rank(villager);
        if (rank == null || rank == Officialdom.Rank.CLERK || villager.isBaby()) return 0f;
        double greed = Officialdom.greed(villager.getUuid(), officialHonest);
        if (greed <= 0) return 0f;
        return (float) ((rank == Officialdom.Rank.HEAD ? 0.7 : 0.4) + (rank == Officialdom.Rank.HEAD ? 0.5 : 0.3) * greed);
    }

    void tick() {
        MinecraftClient client = owner.client;
        officialHonest = owner.config().officialHonestPercent;
        MobGameBridge game = client.getServer() == null ? null : MobGameBridge.active(client.getServer());
        if (!owner.enabled() || game == null || client.world == null || !owner.config().pregnancy) { bellies = Map.of(); statuses = Map.of(); return; }
        long tick = client.world.getTimeOfDay();
        Map<UUID, Float> belly = new HashMap<>();
        Map<UUID, String> status = new HashMap<>();
        for (ChildLedger.Pregnant p : game.memory.children().pregnancies()) {
            if (!p.known(tick)) continue;
            UUID who = p.carrier() == Pregnancy.Carrier.PLAYER ? p.player() : p.villager();
            Pregnancy.Term term = p.term(tick);
            if (owner.config().pregnancyBelly) belly.put(who, Pregnancy.belly(term));
            if (owner.config().pregnancyStatus) status.put(who, Pregnancy.status(term, p.day(tick), p.days()));
        }
        bellies = Map.copyOf(belly); statuses = Map.copyOf(status);
    }

    // ---- Живот на фигурке. ----

    static void register() {
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof VillagerEntityRenderer villager)
                helper.register(new Belly<VillagerEntity, VillagerResemblingModel<VillagerEntity>>(villager, model -> model.getPart().getChild("body"), true, 9f, -3.5f,
                        19, 26, ChildView::robe, 3, 50, null)); // Ниже сложенных рук.
            else if (renderer instanceof HumanVillagerRenderer human) // Трек M: туловище человека, сверху его одежда, кожа своего цвета.
                helper.register(new Belly<VillagerEntity, HumanModel>(human, model -> model.body, false, 6f, -2.25f,
                        17, 22, v -> HumanTextures.clothes(HumanLooks.of(v)), 17, 22, v -> HumanTextures.skinColor(HumanLooks.of(v))));
            else if (renderer instanceof PlayerEntityRenderer player)
                helper.register(new Belly<AbstractClientPlayerEntity, PlayerEntityModel<AbstractClientPlayerEntity>>(player, model -> model.body, false, 5f, -2.25f,
                        17, 22, null, 0, 0, null));
        });
    }
    /** Роба жителя его типа (равнины, снега…): в базовой текстуре жителя роба прозрачна, её рисует слой типа. */
    private static Identifier robe(VillagerEntity villager) {
        Identifier type = Registries.VILLAGER_TYPE.getId(villager.getVillagerData().getType());
        return Identifier.of(type.getNamespace(), "textures/entity/villager/type/" + type.getPath() + ".png");
    }

    /** Округлость спереди на туловище: кубик 7×6×3 от высоты {@code top}, по сроку глубже. Низ — туловище из текстуры самой фигурки
     * (у жителя под робой, у игрока — скин), поверх у жителя — роба его типа, где она есть. */
    private static final class Belly<T extends LivingEntity, M extends EntityModel<T>> extends FeatureRenderer<T, M> {
        private final Function<M, ModelPart> body;
        private final boolean fromRoot;
        private final ModelPart part, cover;
        private final Function<T, Identifier> coverTexture;
        private final float front;
        private final ToIntFunction<T> tint;
        Belly(FeatureRendererContext<T, M> context, Function<M, ModelPart> body, boolean fromRoot, float top, float front,
              int u, int v, Function<T, Identifier> coverTexture, int cu, int cv, ToIntFunction<T> tint) {
            super(context);
            this.body = body; this.fromRoot = fromRoot; this.front = front; this.coverTexture = coverTexture; this.tint = tint;
            part = cube(u, v, top, Dilation.NONE);
            cover = coverTexture == null ? null : cube(cu, cv, top, new Dilation(0.05f));
        }
        private static ModelPart cube(int u, int v, float top, Dilation dilation) {
            ModelData data = new ModelData();
            data.getRoot().addChild("belly", ModelPartBuilder.create().uv(u, v).cuboid(-3.5f, top, -3f, 7f, 6f, 3f, dilation), ModelTransform.NONE);
            return TexturedModelData.of(data, 64, 64).createModel().getChild("belly");
        }
        @Override public void render(MatrixStack matrices, VertexConsumerProvider consumers, int light, T entity, float limbAngle, float limbDistance,
                                     float tickDelta, float animationProgress, float headYaw, float headPitch) {
            float amount = Math.max(belly(entity.getUuid()), entity instanceof VillagerEntity v ? officialBelly(v) : 0f);
            if (amount <= 0 || entity.isInvisible() || entity.isBaby()) return;
            M model = getContextModel();
            matrices.push();
            if (fromRoot && model instanceof VillagerResemblingModel<?> villager) villager.getPart().rotate(matrices);
            body.apply(model).rotate(matrices);
            matrices.translate(0, 0, front / 16f);
            matrices.scale(1f, 1f, amount);
            int overlay = LivingEntityRenderer.getOverlay(entity, 0);
            part.render(matrices, consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(getTexture(entity))), light, overlay, tint == null ? -1 : tint.applyAsInt(entity));
            Identifier coverId = cover == null ? null : coverTexture.apply(entity);
            if (coverId != null) cover.render(matrices, consumers.getBuffer(RenderLayer.getEntityCutoutNoCull(coverId)), light, overlay);
            matrices.pop();
        }
    }

    // ---- Затемнения. ----

    void renderFade(DrawContext context, RenderTickCounter counter) {
        MinecraftClient client = owner.client;
        MobGameBridge game = client.getServer() == null || client.player == null ? null : MobGameBridge.active(client.getServer());
        if (game == null || !owner.config().familyFade) return;
        ChildController.Fade fade = game.children.fade(client.player.getUuid());
        if (fade == null) return;
        long age = System.currentTimeMillis() - fade.startMillis();
        float alpha; String line;
        switch (fade.kind()) {
            case NIGHT -> {
                if (!client.player.isSleeping()) return;
                alpha = Math.min(0.88f, age / 4000f * 0.88f); line = age > 3000 ? "…" : "";
            }
            case MORNING -> {
                alpha = age < 1500 ? 1f : Math.max(0f, 1f - (age - 1500) / (float) (ChildController.MORNING_MILLIS - 1500));
                line = "Наутро…";
            }
            default -> {
                alpha = age < 600 ? age / 600f : age < 5000 ? 1f : Math.max(0f, 1f - (age - 5000) / (float) (ChildController.BIRTH_MILLIS - 5000));
                line = age < 2500 ? "" : "Слышен детский плач…";
            }
        }
        if (alpha <= 0.01f) return;
        int w = context.getScaledWindowWidth(), h = context.getScaledWindowHeight();
        context.fill(0, 0, w, h, ((int) (alpha * 255) << 24));
        if (!line.isEmpty() && alpha > 0.1f)
            context.drawCenteredTextWithShadow(client.textRenderer, Text.literal(line), w / 2, h / 2 - 4, ((int) (alpha * 255) << 24) | 0xF0E6FF);
    }
}
