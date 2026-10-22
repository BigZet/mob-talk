package io.github.divineblock.boss;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.game.Fx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.network.packet.s2c.play.StopSoundS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** «Бог с булавой»: the god himself comes out of the clouds over the sinner and strikes with his mace for a while, then
 * goes back. This is the controller and the handle callers keep ({@link #dismiss} sends him back early, on repentance);
 * {@link GodAvatarEntity} is only the figure the client draws. Every strike is telegraphed — a glowing circle (or band, or
 * marks for lightning) on the ground while the mace rises — then falls with a shockwave, knockback and capped damage. A
 * crater opens only where bosses may break blocks and the god's protection allows. With the model switched off in F9 the
 * same punishment comes as a lightning storm with shockwave rings and no figure. Ticked on the server thread. */
public final class GodWrath {
    /** One hit never takes more than this share of a player's maximum health (as with the bosses). */
    private static final float MAX_HIT_SHARE = SacredBoss.MAX_HIT_SHARE;
    private static final int ARRIVE_TICKS = 90, LEAVE_TICKS = 90, STRIKE_TICKS = 16, IMPACT_AT = 5;
    /** The cloud bank (his waist) this far above the sinner and this far off to one side: the mortal looks up at him at
     * some 35–45 degrees, and the figure leans over the clouds toward him (on the client). */
    private static final double HEIGHT = 28, DISTANCE = 34;
    private static final List<String> CHANTS = List.of("punishment_02", "punishment_06", "punishment_20",
            "punishment_21", "punishment_39", "punishment_56", "punishment_57", "punishment_59", "punishment_61",
            "punishment_62", "punishment_63", "punishment_64", "punishment_65", "punishment_66", "punishment_67");
    private static final List<String> LINES = List.of("Аз есмь Сущий. Склонись, смертный!", "Сам пришёл Я судить тебя.",
            "Узри десницу Мою и трепещи!", "Не пред идолом стоишь — пред Ветхим денми.", "Покайся, пока булава Моя в воздухе.");
    private static final Map<UUID, GodWrath> ACTIVE = new HashMap<>();
    private static final DustParticleEffect GOLD_DUST = new DustParticleEffect(new Vector3f(1f, 0.82f, 0.3f), 2.2f);
    private static final DustParticleEffect PALE_DUST = new DustParticleEffect(new Vector3f(0.75f, 0.85f, 1f), 1.8f);

    private static final int STORM_GREY = 0xFF6E6E7A;

    private enum Stage { ARRIVE, HOVER, WARN, STRIKE, LEAVE, DONE }

    private final ServerWorld world;
    private final ServerPlayerEntity player;
    private final int power, duration;
    private final BlockPos altar;
    private final Vec3d station;
    private final GodAvatarEntity avatar;
    private final String chant;
    private final List<Wave> waves = new ArrayList<>();
    private Stage stage = Stage.ARRIVE;
    private int age, left = ARRIVE_TICKS, strikeKind, warnTicks, strikes, sameInRow, lastKind = -1;
    private boolean spoke, stormed;
    private Vec3d target = Vec3d.ZERO;
    private final List<Vec3d> marks = new ArrayList<>();

    private GodWrath(ServerWorld world, ServerPlayerEntity player, int power, int duration, BlockPos altar, boolean model) {
        this.world = world; this.player = player; this.altar = altar == null ? null : altar.toImmutable();
        this.power = Math.max(1, Math.min(5, power));
        this.duration = Math.max(20 * 10, Math.min(20 * 90, duration));
        double angle = nextAngle != null ? nextAngle : world.random.nextDouble() * Math.PI * 2;
        nextAngle = null;
        this.station = new Vec3d(player.getX() + Math.cos(angle) * DISTANCE, player.getY() + HEIGHT, player.getZ() + Math.sin(angle) * DISTANCE);
        this.chant = CHANTS.get(world.random.nextInt(CHANTS.size()));
        GodAvatarEntity figure = null;
        if (model) {
            figure = DivineBosses.GOD_AVATAR.create(world);
            if (figure != null) {
                figure.refreshPositionAndAngles(station.x, station.y, station.z, yawTo(player.getPos()), 0);
                figure.wrath = this;
                figure.stage(GodAvatarEntity.ARRIVE, ARRIVE_TICKS);
                if (!world.spawnEntity(figure)) figure = null;
            }
        }
        this.avatar = figure;
    }

    // ---- The API (through DivineBosses). ----

    static GodWrath start(ServerWorld world, ServerPlayerEntity player, int power, int duration, BlockPos altar) {
        if (world.getDimension().hasCeiling() || player.isRemoved() || !player.isAlive()) return null;
        GodWrath old = ACTIVE.remove(player.getUuid());
        if (old != null) old.end();
        DivineConfig config = DivineBlockMod.config();
        GodWrath wrath = new GodWrath(world, player, power, duration, altar, !config.disabledBosses.contains(DivineBosses.GOD_AVATAR_ID));
        ACTIVE.put(player.getUuid(), wrath);
        wrath.begin();
        return wrath;
    }
    static GodWrath of(ServerPlayerEntity player) { return ACTIVE.get(player.getUuid()); }

    /** The god goes back into the clouds now (the strike in flight still lands). */
    public void dismiss() { if (stage != Stage.LEAVE && stage != Stage.DONE && stage != Stage.STRIKE) leave(); else if (stage == Stage.STRIKE) left = Math.min(left, IMPACT_AT + 1); dismissed = true; }
    private boolean dismissed;
    /** Over: gone back into the clouds, or cancelled. */
    public boolean finished() { return stage == Stage.DONE; }
    /** Whether the figure is drawn (off in F9 brings the storm without it). */
    public boolean hasFigure() { return avatar != null; }
    public GodAvatarEntity avatar() { return avatar; }
    /** For tests: strikes landed so far, and where the figure waits. */
    public int strikes() { return strikes; }
    public Vec3d station() { return station; }
    public String stageName() { return stage.name(); }

    // ---- For the self-test (a staged scene): where he stands, what he strikes, and a strike on demand. ----

    private static Double nextAngle;
    private net.minecraft.entity.Entity focus;
    private int forced = -1;
    /** The next god stands at this angle from the player (radians: 0 is +X, -PI/2 is north). */
    public static void placeNextAt(double angle) { nextAngle = angle; }
    /** His strikes fall on {@code focus} instead of the player (null: the player again). */
    public void aimAt(net.minecraft.entity.Entity focus) { this.focus = focus; }
    /** The next strike is {@code kind} ({@link GodAvatarEntity#OVERHEAD} ...) and, when he waits between strikes, begins now. */
    /** Holds him between strikes (a staged portrait); false lets him strike again. */
    public void hold(boolean held) { this.held = held; }
    private boolean held;
    public void strikeNow(int kind) { forced = kind; if (stage == Stage.HOVER) left = 1; }
    private Vec3d aim() { return focus != null && !focus.isRemoved() ? focus.getPos() : player.getPos(); }

    static void tickAll(MinecraftServer server) {
        if (ACTIVE.isEmpty()) return;
        for (GodWrath wrath : new ArrayList<>(ACTIVE.values())) {
            try { wrath.tick(); } catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: Бог с булавой упал", failure); wrath.end(); }
            if (wrath.finished()) ACTIVE.values().remove(wrath);
        }
    }
    static void clearAll() { for (GodWrath wrath : new ArrayList<>(ACTIVE.values())) wrath.end(); ACTIVE.clear(); }

    // ---- The sequence. ----

    private void begin() {
        // The omen: thunder, the sky darkens, the clouds gather where he will come.
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 4f, 0.5f);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMBIENT_CAVE.value(), SoundCategory.HOSTILE, 2f, 0.5f);
        if (world.getRegistryKey() == World.OVERWORLD && !world.isThundering()) { world.setWeather(0, duration + ARRIVE_TICKS + LEAVE_TICKS + 200, true, true); stormed = true; }
        // The bank itself is drawn by the client round his figure; a puff of storm smoke marks where it gathers.
        Fx.smoke(world, station.add(0, 4, 0), STORM_GREY, 14);
        Fx.embers(world, station, Fx.GOLD, 18, ARRIVE_TICKS);
        int volume = DivineBlockMod.config().chantVolume;
        if (volume > 0) world.playSound(null, player.getX(), player.getY(), player.getZ(), RegistryEntry.of(chantEvent()), SoundCategory.HOSTILE,
                1.5f * volume / 100f, 1f, world.random.nextLong());
        DivineBlockMod.LOG.info("Божественный блок: Бог с булавой над {} ×{} на {} с{}", player.getName().getString(), power, duration / 20,
                avatar == null ? " (без фигуры: гроза)" : "");
    }

    private void tick() {
        if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world || avatar != null && avatar.isRemoved() && stage != Stage.DONE) { end(); return; }
        age++;
        tickWaves();
        if (avatar != null) avatar.setYaw(turn(avatar.getYaw(), yawTo(aim()), 3f));
        left--;
        switch (stage) {
            case ARRIVE -> {
                if (age == ARRIVE_TICKS / 2) {
                    // He is heard from where he comes, and light falls round the mortal.
                    world.playSound(null, station.x, station.y, station.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 8f, 0.6f);
                    world.playSound(null, station.x, station.y, station.z, SoundEvents.BLOCK_BEACON_ACTIVATE, SoundCategory.HOSTILE, 8f, 0.5f);
                    Fx.pillar(world, player.getPos(), Fx.HOLY, 60);
                }
                if (left <= 0) hover(30);
            }
            case HOVER -> {
                if (!spoke && age > ARRIVE_TICKS + 10) { spoke = true; speak(); }
                if (held && forced < 0) left = Math.max(left, 1);
                if (left > 0) return;
                if ((age - ARRIVE_TICKS >= duration || dismissed) && forced < 0) leave();
                else warn();
            }
            case WARN -> {
                telegraph();
                if (left <= 0) strike();
            }
            case STRIKE -> {
                if (left == STRIKE_TICKS - IMPACT_AT) impact();
                if (left <= 0) hover(forced >= 0 ? 2 : Math.max(22, 50 - power * 6) + world.random.nextInt(20));
            }
            case LEAVE -> {
                if (left == LEAVE_TICKS / 2) Fx.smoke(world, station.add(0, 4, 0), STORM_GREY, 12);
                if (left <= 0) end();
            }
            case DONE -> { }
        }
    }

    private void hover(int ticks) {
        stage = Stage.HOVER; left = ticks;
        if (avatar != null) avatar.stage(GodAvatarEntity.HOVER, ticks);
    }

    private void leave() {
        stage = Stage.LEAVE; left = LEAVE_TICKS;
        if (avatar != null) avatar.stage(GodAvatarEntity.LEAVE, LEAVE_TICKS);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 3f, 0.7f);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.HOSTILE, 3f, 0.5f);
        Fx.embers(world, station, Fx.HOLY, 16, LEAVE_TICKS);
    }

    /** Over: the figure goes, the music stops and a storm he brought clears. */
    private void end() {
        if (stage == Stage.DONE) return;
        stage = Stage.DONE;
        waves.clear();
        if (avatar != null && !avatar.isRemoved()) avatar.discard();
        Identifier id = Identifier.of(DivineBlockMod.ID, "chant." + chant);
        if (!player.isRemoved()) player.networkHandler.sendPacket(new StopSoundS2CPacket(id, SoundCategory.HOSTILE));
        if (stormed && world.getRegistryKey() == World.OVERWORLD) world.setWeather(20 * 60 * 5, 0, false, false);
    }

    private void speak() {
        try { io.github.divineblock.game.Prayers.proclaim(player, LINES.get(world.random.nextInt(LINES.size()))); }
        catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: слово Бога с булавой не прозвучало", failure); }
    }

    // ---- One strike: choose, telegraph, fall. ----

    private void warn() {
        int kind;
        if (avatar == null) kind = world.random.nextInt(3) == 0 ? GodAvatarEntity.OVERHEAD : GodAvatarEntity.LIGHTNING;
        else {
            int roll = world.random.nextInt(10);
            kind = roll < 4 ? GodAvatarEntity.OVERHEAD : roll < 7 ? GodAvatarEntity.SWEEP : GodAvatarEntity.LIGHTNING;
            if (kind == lastKind && sameInRow >= 1) kind = (kind + 1 + world.random.nextInt(2)) % 3;
        }
        if (forced >= 0) { kind = avatar == null && forced == GodAvatarEntity.SWEEP ? GodAvatarEntity.OVERHEAD : forced; forced = -1; }
        sameInRow = kind == lastKind ? sameInRow + 1 : 0;
        lastKind = kind;
        strikeKind = kind;
        target = ground(aim());
        marks.clear();
        if (kind == GodAvatarEntity.LIGHTNING) {
            marks.add(target);
            int extra = 1 + power / 2;
            for (int i = 0; i < extra; i++) {
                double a = world.random.nextDouble() * Math.PI * 2, r = 3 + world.random.nextDouble() * 4;
                marks.add(ground(target.add(Math.cos(a) * r, 0, Math.sin(a) * r)));
            }
        }
        // At least a second and a half to see it coming; never less than 0.75 s whatever the power.
        warnTicks = Math.max(24, 44 - power * 3);
        stage = Stage.WARN; left = warnTicks;
        if (avatar != null) { avatar.strike(kind, BlockPos.ofFloored(target)); avatar.stage(GodAvatarEntity.WARN, warnTicks); }
        SoundEvent cue = switch (kind) {
            case GodAvatarEntity.OVERHEAD -> SoundEvents.BLOCK_BEACON_POWER_SELECT;
            case GodAvatarEntity.SWEEP -> SoundEvents.ENTITY_WARDEN_SONIC_CHARGE;
            default -> SoundEvents.ENTITY_EVOKER_PREPARE_ATTACK;
        };
        world.playSound(null, target.x, target.y, target.z, cue, SoundCategory.HOSTILE, 3f, 0.5f);
        String name = switch (kind) { case GodAvatarEntity.OVERHEAD -> "Булава над тобой"; case GodAvatarEntity.SWEEP -> "Булава заносится вбок"; default -> "Молнии из десницы"; };
        player.sendMessage(net.minecraft.text.Text.literal("⚠ " + name + " — уходи из круга!").formatted(net.minecraft.util.Formatting.RED), true);
    }

    private double radius() { return 3.5 + power * 0.5; }
    /** The sweep's band: through the target, across the line from the god. */
    private Vec3d across() {
        Vec3d to = target.subtract(station).multiply(1, 0, 1);
        to = to.lengthSquared() < 1e-4 ? new Vec3d(1, 0, 0) : to.normalize();
        return new Vec3d(-to.z, 0, to.x);
    }
    private static final double SWEEP_HALF = 14, SWEEP_WIDTH = 2.5;

    private void telegraph() {
        if (left % 2 != 0) return;
        float closing = 1f - left / (float) warnTicks;
        switch (strikeKind) {
            case GodAvatarEntity.OVERHEAD -> {
                circle(target, radius(), GOLD_DUST, 48);
                circle(target, radius() * closing, PALE_DUST, 24);
                // The mace's shadow darkens at the centre as it comes.
                particles(ParticleTypes.LARGE_SMOKE, target.add(0, 0.2, 0), (int) (2 + closing * 8), radius() * 0.35, 0.02);
            }
            case GodAvatarEntity.SWEEP -> {
                Vec3d side = across(), along = side.crossProduct(new Vec3d(0, 1, 0)).normalize();
                for (int i = -28; i <= 28; i++) {
                    Vec3d at = target.add(side.multiply(i * SWEEP_HALF / 28));
                    particles(GOLD_DUST, at.add(along.multiply(SWEEP_WIDTH)).add(0, 0.2, 0), 1, 0, 0);
                    particles(GOLD_DUST, at.add(along.multiply(-SWEEP_WIDTH)).add(0, 0.2, 0), 1, 0, 0);
                }
                particles(ParticleTypes.LARGE_SMOKE, target.add(0, 0.3, 0), (int) (2 + closing * 6), SWEEP_HALF * 0.4, 0.02);
            }
            default -> {
                for (Vec3d mark : marks) {
                    circle(mark, 2.2, PALE_DUST, 20);
                    particles(ParticleTypes.ELECTRIC_SPARK, mark.add(0, 0.3, 0), 3 + (int) (closing * 6), 1.2, 0.05);
                }
            }
        }
    }

    private void strike() {
        stage = Stage.STRIKE; left = STRIKE_TICKS;
        if (avatar != null) avatar.stage(GodAvatarEntity.STRIKE, STRIKE_TICKS);
        world.playSound(null, target.x, target.y, target.z, SoundEvents.ITEM_MACE_SMASH_AIR, SoundCategory.HOSTILE, 4f, 0.5f);
    }

    private void impact() {
        strikes++;
        DivineConfig config = DivineBlockMod.config();
        switch (strikeKind) {
            case GodAvatarEntity.OVERHEAD -> {
                Fx.pillar(world, target, Fx.HOLY, 10);
                Fx.impact(world, target, Fx.GOLD, 2.6f + power * 0.2f);
                Fx.flash(world, target, Fx.HOLY, 0.6f);
                Fx.shake(world, target, 0.9f, 28);
                Fx.ring(world, target, Fx.GOLD, 16);
                world.playSound(null, target.x, target.y, target.z, SoundEvents.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 5f, 0.5f);
                world.playSound(null, target.x, target.y, target.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.HOSTILE, 4f, 0.6f);
                if (avatar == null) Fx.bolt(world, target, Fx.HOLY);
                for (LivingEntity victim : victims(target, radius() + 0.5)) { hurt(victim, 10); push(victim, target, 1.6, 0.6); }
                waves.add(new Wave(target, 16, 1.0));
                if (mayCrater(config)) crater(target, config);
            }
            case GodAvatarEntity.SWEEP -> {
                Vec3d side = across();
                for (int i = -2; i <= 2; i++) Fx.impact(world, target.add(side.multiply(i * SWEEP_HALF / 2.5)), Fx.GOLD, 2f);
                Fx.shake(world, target, 0.6f, 20);
                Fx.ring(world, target, Fx.HOLY, 10);
                world.playSound(null, target.x, target.y, target.z, SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 5f, 0.4f);
                world.playSound(null, target.x, target.y, target.z, SoundEvents.ITEM_MACE_SMASH_GROUND, SoundCategory.HOSTILE, 5f, 0.5f);
                Vec3d along = side.crossProduct(new Vec3d(0, 1, 0)).normalize();
                for (LivingEntity victim : victims(target, SWEEP_HALF + 2)) {
                    Vec3d off = victim.getPos().subtract(target);
                    if (Math.abs(off.dotProduct(along)) > SWEEP_WIDTH + 0.4 || Math.abs(off.dotProduct(side)) > SWEEP_HALF || off.y > 3) continue;
                    hurt(victim, 8);
                    victim.addVelocity(side.x * 2.0, 0.55, side.z * 2.0);
                    victim.velocityModified = true;
                }
            }
            default -> {
                for (Vec3d mark : marks) {
                    Fx.bolt(world, mark, Fx.SOUL);
                    Fx.ring(world, mark, Fx.SOUL, 4);
                    bolt(mark);
                    for (LivingEntity victim : victims(mark, 2.4)) { hurt(victim, 7); push(victim, mark, 0.8, 0.4); }
                }
                Fx.flash(world, target, Fx.SOUL, 0.4f);
                Fx.shake(world, target, 0.4f, 14);
            }
        }
    }

    /** A cosmetic vanilla bolt (thunder and the sky flash; the drawn bolt covers it); fire only where blocks may burn. */
    private void bolt(Vec3d at) {
        var bolt = net.minecraft.entity.EntityType.LIGHTNING_BOLT.create(world);
        if (bolt == null) return;
        bolt.refreshPositionAfterTeleport(at);
        bolt.setCosmetic(true);
        world.spawnEntity(bolt);
        DivineConfig config = DivineBlockMod.config();
        BlockPos pos = BlockPos.ofFloored(at);
        if (mayCrater(config) && world.getBlockState(pos).isAir() && Blocks.FIRE.getDefaultState().canPlaceAt(world, pos)
                && !io.github.divineblock.game.Protection.spares(world, player, altar, pos, config))
            world.setBlockState(pos, Blocks.FIRE.getDefaultState());
    }

    // ---- Shockwaves: rings on the ground, low enough to jump. ----

    private final class Wave {
        final Vec3d center; final double max, speed; double radius = 0.5; final Set<LivingEntity> struck = new HashSet<>();
        Wave(Vec3d center, double max, double speed) { this.center = center; this.max = max; this.speed = speed; }
        boolean tick() {
            radius += speed;
            if ((int) radius % 2 == 0) circle(center, radius, ParticleTypes.CLOUD, (int) Math.min(120, radius * 6 + 8));
            for (LivingEntity victim : victims(center, radius + 1)) {
                double flat = Math.hypot(victim.getX() - center.x, victim.getZ() - center.z), above = victim.getY() - center.y;
                if (Math.abs(flat - radius) < 1.0 && above < 1.2 && above > -2 && struck.add(victim)) { hurt(victim, 5); push(victim, center, 1.1, 0.45); }
            }
            return radius < max;
        }
    }
    private void tickWaves() {
        if (waves.isEmpty()) return;
        waves.removeIf(wave -> !wave.tick());
    }

    // ---- Harm, always capped; blocks only where allowed. ----

    private List<LivingEntity> victims(Vec3d center, double radius) {
        return world.getEntitiesByClass(LivingEntity.class, new Box(center, center).expand(radius, 6, radius), living -> living.isAlive()
                && !(living instanceof ArmorStandEntity) && (living instanceof ServerPlayerEntity p ? !p.isCreative() && !p.isSpectator() : living instanceof MobEntity)
                && Math.hypot(living.getX() - center.x, living.getZ() - center.z) <= radius);
    }

    private void hurt(LivingEntity victim, float base) {
        DivineConfig config = DivineBlockMod.config();
        float scale = (0.7f + 0.15f * power) * config.bossDifficulty / 100f;
        float amount = Math.min(base * scale, victim.getMaxHealth() * MAX_HIT_SHARE);
        victim.damage(avatar != null ? world.getDamageSources().indirectMagic(avatar, avatar) : world.getDamageSources().magic(), amount);
    }
    private static void push(LivingEntity victim, Vec3d from, double strength, double lift) { SacredBoss.push(victim, from, strength, lift); }

    private boolean mayCrater(DivineConfig config) {
        return config.bossBlockDamage && !config.protectBlocks && world.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING);
    }

    /** A shallow bowl under the mace, sparing the altar, the home, unbreakable blocks and anything with a block entity. */
    private void crater(Vec3d at, DivineConfig config) {
        int r = 2 + power / 2;
        BlockPos center = BlockPos.ofFloored(at);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            double d2 = (dx * dx + dz * dz) / (double) (r * r);
            if (d2 > 1) continue;
            int depth = (int) Math.round((1 - d2) * r * 0.7);
            for (int dy = -depth; dy <= 0; dy++) {
                BlockPos pos = center.add(dx, dy - 1, dz);
                BlockState state = world.getBlockState(pos);
                if (state.isAir() || state.getHardness(world, pos) < 0 || world.getBlockEntity(pos) != null || !state.getFluidState().isEmpty()) continue;
                if (io.github.divineblock.game.Protection.spares(world, player, altar, pos, config)) continue;
                world.breakBlock(pos, false);
            }
        }
    }

    // ---- Small helpers. ----

    private Vec3d ground(Vec3d at) {
        BlockPos column = BlockPos.ofFloored(at);
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
        // Under a roof (a cave, a house) the strike lands where he stands, not on the roof.
        double y = top > at.y + 3 ? Math.floor(at.y) : top;
        return new Vec3d(column.getX() + 0.5, y, column.getZ() + 0.5);
    }

    private void circle(Vec3d center, double radius, ParticleEffect effect, int points) {
        if (radius < 0.3) return;
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            particles(effect, center.add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius), 1, 0, 0);
        }
    }
    private void particles(ParticleEffect effect, Vec3d at, int count, double spread, double speed) {
        for (ServerPlayerEntity viewer : world.getPlayers(p -> p.squaredDistanceTo(at) < 128 * 128))
            world.spawnParticles(viewer, effect, true, at.x, at.y, at.z, count, spread, spread * 0.1, spread, speed);
    }

    private float yawTo(Vec3d to) {
        return (float) (Math.atan2(to.z - station.z, to.x - station.x) * 180 / Math.PI) - 90f;
    }
    private static float turn(float from, float to, float max) {
        float delta = net.minecraft.util.math.MathHelper.wrapDegrees(to - from);
        return from + Math.max(-max, Math.min(max, delta));
    }
    private SoundEvent chantEvent() { return SoundEvent.of(Identifier.of(DivineBlockMod.ID, "chant." + chant)); }
}
