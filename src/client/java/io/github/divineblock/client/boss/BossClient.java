package io.github.divineblock.client.boss;

import io.github.divineblock.boss.BossEffects;
import io.github.divineblock.boss.DivineBosses;
import io.github.divineblock.boss.FacelessIconEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** The bosses on the client: their renderers and the afflictions only the client can show: «Звон в ушах» (camera shake,
 * muffled world, a ringing tone, a wobbling crosshair; see the mixins) and «Взор лика» (the camera is drawn to the icon). */
public final class BossClient {
    /** Read by the mixins every frame. */
    public static volatile boolean ringing;
    /** The F9 «Тряска камеры» setting, read now and then (the camera asks every frame). */
    public static volatile boolean shakeAllowed = true;
    private static SoundInstance tinnitus;

    private BossClient() { }

    public static void register() {
        EntityModelLayerRegistry.registerModelLayer(SchemnikModel.LAYER, SchemnikModelData::create);
        EntityModelLayerRegistry.registerModelLayer(BellRingerModel.LAYER, BellRingerModelData::create);
        EntityModelLayerRegistry.registerModelLayer(SeraphModel.LAYER, SeraphModelData::create);
        EntityModelLayerRegistry.registerModelLayer(FacelessIconModel.LAYER, FacelessIconModelData::create);
        EntityModelLayerRegistry.registerModelLayer(WailerModel.LAYER, WailerModelData::create);
        EntityModelLayerRegistry.registerModelLayer(ArchangelModel.LAYER, ArchangelModelData::create);
        EntityModelLayerRegistry.registerModelLayer(LeviathanModel.LAYER, LeviathanModelData::create);
        EntityModelLayerRegistry.registerModelLayer(BehemothModel.LAYER, BehemothModelData::create);
        EntityModelLayerRegistry.registerModelLayer(GoliathModel.LAYER, GoliathModelData::create);
        EntityModelLayerRegistry.registerModelLayer(HorsemenModel.LAYER, HorsemenModelData::create);
        EntityModelLayerRegistry.registerModelLayer(DestroyingAngelModel.LAYER, DestroyingAngelModelData::create);
        EntityModelLayerRegistry.registerModelLayer(CherubModel.LAYER, CherubModelData::create);
        EntityModelLayerRegistry.registerModelLayer(BeastModel.LAYER, BeastModelData::create);
        EntityModelLayerRegistry.registerModelLayer(NimrodModel.LAYER, NimrodModelData::create);
        EntityRendererRegistry.register(DivineBosses.BLACK_SCHEMNIK, context -> new SacredBossRenderer<>(context, new SchemnikModel(context.getPart(SchemnikModel.LAYER)), "black_schemnik", 1.5f, 2f));
        EntityRendererRegistry.register(DivineBosses.BELL_RINGER, context -> new SacredBossRenderer<>(context, new BellRingerModel(context.getPart(BellRingerModel.LAYER)), "bell_ringer", 1.8f, 1.65f));
        EntityRendererRegistry.register(DivineBosses.SERAPH, context -> new SacredBossRenderer<>(context, new SeraphModel(context.getPart(SeraphModel.LAYER)), "seraph", 2.4f, 2.2f));
        EntityRendererRegistry.register(DivineBosses.FACELESS_ICON, context -> new SacredBossRenderer<>(context, new FacelessIconModel(context.getPart(FacelessIconModel.LAYER)), "faceless_icon", 1.5f, 0.8f));
        EntityRendererRegistry.register(DivineBosses.WAILER, context -> new SacredBossRenderer<>(context, new WailerModel(context.getPart(WailerModel.LAYER)), "wailer", 1.3f, 2f));
        EntityRendererRegistry.register(DivineBosses.ARCHANGEL, context -> new SacredBossRenderer<>(context, new ArchangelModel(context.getPart(ArchangelModel.LAYER)), "archangel", 2.2f, 2.5f));
        EntityRendererRegistry.register(DivineBosses.LEVIATHAN, context -> new SacredBossRenderer<>(context, new LeviathanModel(context.getPart(LeviathanModel.LAYER)), "leviathan", 2f, 1f));
        EntityRendererRegistry.register(DivineBosses.BEHEMOTH, context -> new SacredBossRenderer<>(context, new BehemothModel(context.getPart(BehemothModel.LAYER)), "behemoth", 2f, 1.2f));
        EntityRendererRegistry.register(DivineBosses.GOLIATH, context -> new SacredBossRenderer<>(context, new GoliathModel(context.getPart(GoliathModel.LAYER)), "goliath", 2.0f, 1.75f));
        EntityRendererRegistry.register(DivineBosses.HORSEMEN, HorsemenRenderer::new);
        EntityRendererRegistry.register(DivineBosses.DESTROYING_ANGEL, context -> new SacredBossRenderer<>(context, new DestroyingAngelModel(context.getPart(DestroyingAngelModel.LAYER)), "destroying_angel", 1.6f, 2.25f));
        EntityRendererRegistry.register(DivineBosses.CHERUB, context -> new SacredBossRenderer<>(context, new CherubModel(context.getPart(CherubModel.LAYER)), "cherub", 1.6f, 2.3f));
        EntityRendererRegistry.register(DivineBosses.BEAST, context -> new SacredBossRenderer<>(context, new BeastModel(context.getPart(BeastModel.LAYER)), "beast", 3.6f, 2.2f));
        EntityRendererRegistry.register(DivineBosses.NIMROD, context -> new SacredBossRenderer<>(context, new NimrodModel(context.getPart(NimrodModel.LAYER)), "nimrod", 1.3f, 1.35f));
        BellRingerModel.registerClient();
        SeraphModel.registerClient();
        FacelessIconModel.registerClient();
        WailerModel.registerClient();
        ArchangelModel.registerClient();
        LeviathanModel.registerClient();
        BehemothModel.registerClient();
        GoliathModel.registerClient();
        HorsemenModel.registerClient();
        DestroyingAngelModel.registerClient();
        CherubModel.registerClient();
        BeastModel.registerClient();
        NimrodModel.registerClient();
        ClientTickEvents.END_CLIENT_TICK.register(BossClient::tick);
    }

    private static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) { ringing = false; return; }
        ringing = player.hasStatusEffect(BossEffects.RINGING);
        if (player.age % 40 == 0) shakeAllowed = io.github.divineblock.DivineBlockMod.config().cameraShake;
        if (ringing && player.age % 40 == 0 && (tinnitus == null || !client.getSoundManager().isPlaying(tinnitus))) {
            tinnitus = PositionedSoundInstance.master(SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 2.0f, 0.5f);
            client.getSoundManager().play(tinnitus);
        }
        if (player.hasStatusEffect(BossEffects.GAZE) && !client.isPaused()) drawGaze(client, player);
        // «Тяжесть глубин»: the deep pulls down; swimming up does not help.
        if (player.hasStatusEffect(BossEffects.DEPTHS) && player.isTouchingWater() && !client.isPaused()) {
            Vec3d velocity = player.getVelocity();
            player.setVelocity(velocity.x * 0.9, Math.min(velocity.y, 0) - 0.03, velocity.z * 0.9);
        }
    }

    /** Whether a sound is the ringing tone itself (the one sound not muffled). */
    public static boolean isTinnitus(SoundInstance sound) { return sound == tinnitus; }

    /** Small offsets in degrees for the camera while the ears ring, smooth between frames. */
    public static float shakeYaw() { double t = System.nanoTime() / 1e9; return (float) (Math.sin(t * 21) * 0.9 + Math.sin(t * 34) * 0.5); }
    public static float shakePitch() { double t = System.nanoTime() / 1e9; return (float) (Math.cos(t * 27) * 0.7 + Math.sin(t * 13) * 0.4); }
    /** Pixels the crosshair wanders by. */
    public static float wobbleX() { double t = System.nanoTime() / 1e9; return (float) (Math.sin(t * 3.1) * 6 + Math.sin(t * 7.3) * 2); }
    public static float wobbleY() { double t = System.nanoTime() / 1e9; return (float) (Math.cos(t * 2.7) * 5 + Math.sin(t * 6.1) * 2); }

    /** «Взор лика»: the camera turns a little toward the nearest icon every tick; looking away takes effort. */
    private static void drawGaze(MinecraftClient client, ClientPlayerEntity player) {
        FacelessIconEntity icon = null;
        double best = Double.MAX_VALUE;
        for (FacelessIconEntity candidate : client.world.getEntitiesByClass(FacelessIconEntity.class, new Box(player.getBlockPos()).expand(40), FacelessIconEntity::isAlive)) {
            double distance = candidate.squaredDistanceTo(player);
            if (distance < best) { best = distance; icon = candidate; }
        }
        if (icon == null) return;
        Vec3d to = icon.getPos().add(0, icon.getHeight() * 0.6, 0).subtract(player.getEyePos());
        float yaw = (float) (MathHelper.atan2(to.z, to.x) * 57.2957763671875) - 90f;
        float pitch = (float) -(MathHelper.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * 57.2957763671875);
        float pull = 0.05f;
        player.setYaw(player.getYaw() + MathHelper.wrapDegrees(yaw - player.getYaw()) * pull);
        player.setPitch(MathHelper.clamp(player.getPitch() + (pitch - player.getPitch()) * pull, -90f, 90f));
    }
}
