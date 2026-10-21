package io.github.divineblock.boss;

import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** Ангел-губитель (Exodus 12:12–13, 23): the night angel with a drawn sword (1 Chronicles 21:16). He dashes through his foe as a
 * shadow (a telegraphed line; twice in a row from phase 2), sweeps with the sword, and breathes a cone of death-wind. Ultimate
 * «Десятая казнь»: everyone who sees him is marked with «Печать первенца» for a minute; the mark wounds harder and harder and
 * kills if ignored. It fades only in a doorway with blood on the lintel (a red banner or red carpet just above the door), and
 * then the destroyer passes over and loses his target for a while. In phase 3 his ordinary blows mark too. He walks only at
 * night: under the open sky by day he fades away within five seconds, and the mark goes with him. */
public final class DestroyingAngelEntity extends SacredBoss {
    /** How long the mark lasts; it wounds every {@link #PULSE} ticks. */
    static final int MARK_TICKS = 20 * 60, PULSE = 60;
    /** How long the destroyer leaves a spared player alone. */
    static final int PASS_OVER = 20 * 10;
    private static final DustParticleEffect BLOOD = new DustParticleEffect(new Vector3f(0.6f, 0.02f, 0.05f), 1.4f);
    private static final DustParticleEffect COLD = new DustParticleEffect(new Vector3f(0.75f, 0.85f, 1f), 1.2f);

    private int sunTicks, passOver;
    private UUID spared;

    public DestroyingAngelEntity(EntityType<? extends DestroyingAngelEntity> type, World world) { super(type, world, BossBar.Color.RED); }

    /** «Печать первенца»: the mark wounds on a rising scale and fades only under a blood-red lintel. */
    static void registerHooks() {
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            for (ServerPlayerEntity player : world.getPlayers()) {
                StatusEffectInstance mark = player.getStatusEffect(BossEffects.FIRSTBORN_MARK);
                if (mark != null && player.isAlive()) tickMark(world, player, mark);
            }
        });
    }

    private static void tickMark(ServerWorld world, ServerPlayerEntity player, StatusEffectInstance mark) {
        if (player.age % 5 == 0) {
            if (underBloodLintel(world, player)) { passedOver(world, player); return; }
            world.spawnParticles(BLOOD, player.getX(), player.getY() + 1.9, player.getZ(), 1, 0.15, 0.05, 0.15, 0);
        }
        int elapsed = Math.max(0, MARK_TICKS - mark.getDuration());
        if (elapsed % 40 == 20) {
            boolean door = doorway(world, player) != null;
            player.sendMessage(Text.literal(door ? "Над дверью нет крови… (красный флаг или ковёр над косяком)"
                    : "Печать первенца: кровь на косяке дверей спасёт… (" + mark.getDuration() / 20 + " с)").formatted(Formatting.DARK_RED), true);
        }
        if (elapsed > 0 && elapsed % PULSE == 0) {
            int pulse = elapsed / PULSE;
            float difficulty = io.github.divineblock.DivineBlockMod.config().bossDifficulty / 100f;
            float amount = Math.min((1.5f + 0.6f * pulse) * difficulty, player.getMaxHealth() * SacredBoss.MAX_HIT_SHARE);
            player.damage(world.getDamageSources().magic(), amount);
            world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 1.5f, 0.6f);
            world.spawnParticles(BLOOD, player.getX(), player.getY() + 1, player.getZ(), 12, 0.3, 0.5, 0.3, 0);
        }
    }

    /** The door the player stands in (at his feet or head), or null. */
    static BlockPos doorway(World world, PlayerEntity player) {
        BlockPos feet = player.getBlockPos();
        for (BlockPos at : new BlockPos[] { feet, feet.up() })
            if (world.getBlockState(at).getBlock() instanceof DoorBlock) return at;
        return null;
    }

    /** Whether the player stands in a doorway with blood on the lintel: a red banner or red carpet within two blocks above the
     * door's top (directly above, or on the wall beside it). */
    static boolean underBloodLintel(World world, PlayerEntity player) {
        BlockPos door = doorway(world, player);
        if (door == null) return false;
        BlockPos top = door;
        while (world.getBlockState(top.up()).getBlock() instanceof DoorBlock) top = top.up();
        for (int dy = 1; dy <= 2; dy++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    if (blood(world.getBlockState(top.add(dx, dy, dz)))) return true;
        return false;
    }

    private static boolean blood(BlockState state) {
        return state.isOf(Blocks.RED_BANNER) || state.isOf(Blocks.RED_WALL_BANNER) || state.isOf(Blocks.RED_CARPET);
    }

    /** The blood is seen: the mark is lifted and every destroyer nearby leaves this player alone for a while. */
    private static void passedOver(ServerWorld world, ServerPlayerEntity player) {
        player.removeStatusEffect(BossEffects.FIRSTBORN_MARK);
        player.sendMessage(Text.literal("Губитель прошёл мимо").formatted(Formatting.GOLD, Formatting.BOLD), true);
        player.sendMessage(Text.literal("«И увижу кровь, и пройду мимо вас» (Исх 12:13)").formatted(Formatting.GOLD));
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.5f, 0.6f);
        world.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1, player.getZ(), 20, 0.4, 0.8, 0.4, 0.02);
        for (DestroyingAngelEntity angel : world.getEntitiesByClass(DestroyingAngelEntity.class, new Box(player.getBlockPos()).expand(64), LivingEntity::isAlive)) {
            angel.spared = player.getUuid();
            angel.passOver = PASS_OVER;
            if (angel.getTarget() == player) angel.setTarget(null);
            angel.debug("прошёл мимо " + player.getName().getString());
        }
    }

    @Override public BossKind kind() { return BossKind.DESTROYING_ANGEL; }
    @Override protected String theme() { return "punishment_59"; }
    @Override protected float meleeDamage() { return 7f; }

    @Override public void setTarget(LivingEntity target) {
        if (target != null && passOver > 0 && target.getUuid().equals(spared)) target = null;
        super.setTarget(target);
    }

    /** In phase 3 every blow that lands also sets the mark. */
    @Override public boolean hurt(LivingEntity victim, float base) {
        boolean hit = super.hurt(victim, base);
        if (hit && phase >= 3 && victim instanceof ServerPlayerEntity player && !player.hasStatusEffect(BossEffects.FIRSTBORN_MARK)) mark(player);
        return hit;
    }

    @Override protected void mobTick() {
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld world) || isRemoved()) return;
        if (passOver > 0 && --passOver == 0) spared = null;
        if (age % 10 == 0) daylight(world);
    }

    /** By day under the open sky he fades: smoke for five seconds, then he is gone and his marks with him. */
    private void daylight(ServerWorld world) {
        boolean sun = world.isDay() && world.isSkyVisible(getBlockPos().up(2));
        if (!sun) { sunTicks = 0; return; }
        sunTicks += 10;
        particles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1.5, getZ(), 8, 0.4, 0.8, 0.4, 0.02);
        if (sunTicks == 10) for (ServerPlayerEntity player : playersNear(48))
            player.sendMessage(Text.literal("Светает: губитель тает в свете дня…").formatted(Formatting.GRAY), true);
        if (sunTicks < 100) return;
        particles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1.4, getZ(), 60, 0.5, 1, 0.5, 0.04);
        sound(SoundEvents.ENTITY_VEX_DEATH, 2f, 0.4f);
        liftMarks();
        debug("растаял при свете дня");
        discard();
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        double distance = distanceTo(target);
        int roll = random.nextInt(3);
        if (distance < 4 && roll == 0) { sweep(target); return 50; }
        if (distance < 9 && roll == 1) { deathWind(target); return 70; }
        dash(target, phase >= 2);
        return phase >= 2 ? 60 : 75;
    }

    /** «Тень губителя»: a line of smoke through the foe, then he is a shadow along it and stands four blocks beyond. */
    private void dash(LivingEntity target, boolean again) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        int warn = phase >= 2 ? 13 : 20;
        Vec3d dir = target.getPos().subtract(getPos()).multiply(1, 0, 1);
        dir = dir.lengthSquared() < 1e-4 ? getRotationVector().multiply(1, 0, 1).normalize() : dir.normalize();
        Vec3d end = target.getPos().add(dir.multiply(4));
        BlockPos room = DivineBosses.room(world, BlockPos.ofFloored(end), 2);
        Vec3d land = room == null ? target.getPos() : new Vec3d(end.x, room.getY(), end.z);
        Vec3d from = getPos().add(0, 1.2, 0), to = land.add(0, 1.2, 0);
        cast("Тень губителя", warn, true, left -> {
            particles(ParticleTypes.SQUID_INK, getX(), getY() + 1.4, getZ(), 3, 0.3, 0.6, 0.3, 0.01);
            if (left == warn) sound(SoundEvents.ENTITY_PHANTOM_SWOOP, 2f, 0.5f);
        }, () -> {
            for (int i = 0; i <= 12; i++) {
                Vec3d at = from.lerp(to, i / 12.0);
                particles(ParticleTypes.LARGE_SMOKE, at.x, at.y, at.z, 3, 0.2, 0.4, 0.2, 0.01);
            }
            requestTeleport(land.x, land.y, land.z);
            sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.6f, 0.4f);
            if (again && target.isAlive()) dash(target, false);
        });
        hazards.beam(from, to, warn, 3, 0.8, ParticleTypes.SMOKE, ParticleTypes.SQUID_INK, victim -> {
            if (hurt(victim, 9f)) victim.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 30, 0, false, false));
        });
    }

    /** «Взмах меча»: the ground before him glows cold, then the sword sweeps across it. */
    private void sweep(LivingEntity target) {
        Vec3d dir = target.getPos().subtract(getPos()).multiply(1, 0, 1);
        dir = dir.lengthSquared() < 1e-4 ? getRotationVector().multiply(1, 0, 1).normalize() : dir.normalize();
        Vec3d front = getPos().add(dir.multiply(1.8));
        cast("Взмах меча", 16, true, left -> { if (left == 16) sound(SoundEvents.ITEM_TRIDENT_RETURN, 1.6f, 1.4f); }, () -> { });
        hazards.zone(front, 2.6, 16, COLD, at -> {
            swingHand(Hand.MAIN_HAND);
            sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 2f, 0.6f);
            particles(ParticleTypes.SWEEP_ATTACK, at.x, at.y + 1, at.z, 6, 1.2, 0.3, 1.2, 0);
        }, victim -> { if (hurt(victim, 8f)) push(victim, getPos(), 0.8, 0.25); });
    }

    /** «Смертный ветер»: a cone ten blocks long fills with ash, then the wind of death blows through it. */
    private void deathWind(LivingEntity target) {
        Vec3d dir = target.getPos().subtract(getPos()).multiply(1, 0, 1);
        Vec3d aim = dir.lengthSquared() < 1e-4 ? getRotationVector().multiply(1, 0, 1).normalize() : dir.normalize();
        Vec3d origin = getPos();
        double length = 10, half = Math.toRadians(30);
        cast("Смертный ветер", 26, true, left -> {
            if (left == 26) sound(SoundEvents.ENTITY_PHANTOM_AMBIENT, 2f, 0.4f);
            if (left % 3 != 0) return;
            for (double side : new double[] { -half, 0, half }) {
                Vec3d edge = rotate(aim, side);
                for (int i = 2; i <= length; i += 2)
                    particles(ParticleTypes.ASH, origin.x + edge.x * i, origin.y + 0.2, origin.z + edge.z * i, 2, 0.1, 0.05, 0.1, 0);
            }
        }, () -> {
            sound(SoundEvents.ENTITY_WITHER_SHOOT, 1.6f, 0.4f);
            for (int i = 0; i < 40; i++) {
                Vec3d ray = rotate(aim, (random.nextDouble() * 2 - 1) * half).multiply(1 + random.nextDouble() * (length - 1));
                particles(ParticleTypes.SOUL, origin.x + ray.x, origin.y + 1 + random.nextDouble(), origin.z + ray.z, 1, 0.1, 0.1, 0.1, 0.02);
            }
            for (LivingEntity victim : victims(origin, length)) {
                Vec3d to = victim.getPos().subtract(origin).multiply(1, 0, 1);
                if (to.lengthSquared() < 1e-4 || Math.acos(Math.max(-1, Math.min(1, to.normalize().dotProduct(aim)))) > half + 0.08) continue;
                if (hurt(victim, 6f)) {
                    victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 60, 0));
                    push(victim, origin, 0.9, 0.2);
                }
            }
        });
    }

    // ---- The ultimate. ----

    @Override protected void ultimate(LivingEntity target) {
        castUltimate(left -> {
            particles(ParticleTypes.SQUID_INK, getX(), getY() + 1.5, getZ(), 4, 1.2, 1.2, 1.2, 0.02);
            particles(BLOOD, getX(), getY() + 2.6, getZ(), 2, 0.6, 0.3, 0.6, 0);
            if (left % 20 == 0) sound(SoundEvents.ENTITY_WARDEN_HEARTBEAT, 3f, 0.5f);
            if (left == ULT_WARNING) for (ServerPlayerEntity player : playersNear(48))
                player.sendMessage(Text.literal("«В эту самую ночь пройду по земле Египетской и поражу всякого первенца» (Исх 12:12) — спрячься от его взгляда!")
                        .formatted(Formatting.DARK_RED));
        }, this::tenthPlague);
    }

    /** Everyone within 32 blocks whom he can see is marked; a wall between saves. */
    private void tenthPlague() {
        sound(SoundEvents.ENTITY_WARDEN_SONIC_BOOM, 2f, 0.5f);
        particles(ParticleTypes.SCULK_SOUL, getX(), getY() + 1.5, getZ(), 40, 2, 1, 2, 0.05);
        int marked = 0;
        for (ServerPlayerEntity player : playersNear(32)) {
            if (player.isCreative() || player.isSpectator()) continue;
            if (!canSee(player)) { player.sendMessage(Text.literal("Тень губителя скользнула мимо твоего укрытия").formatted(Formatting.GRAY), true); continue; }
            mark(player);
            marked++;
        }
        debug("Десятая казнь: печать на " + marked);
    }

    private void mark(ServerPlayerEntity player) {
        BossEffects.afflict(player, BossEffects.FIRSTBORN_MARK, MARK_TICKS);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 60, 0, false, false));
        player.sendMessage(Text.literal("Печать первенца на тебе! Кровь на косяке дверей спасёт…").formatted(Formatting.DARK_RED, Formatting.BOLD));
        particles(BLOOD, player.getX(), player.getY() + 1.9, player.getZ(), 20, 0.2, 0.1, 0.2, 0);
    }

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.ENTITY_VEX_CHARGE, 2f, 0.4f);
        for (ServerPlayerEntity player : playersNear(48))
            player.sendMessage(Text.literal(phase == 3 ? "Каждый удар губителя теперь ставит печать" : "Губитель ускоряется").formatted(Formatting.DARK_RED));
        if (phase == 2) addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 0, false, false));
    }

    @Override protected void onDefeated() { liftMarks(); }

    private void liftMarks() {
        for (ServerPlayerEntity player : playersNear(96))
            if (player.hasStatusEffect(BossEffects.FIRSTBORN_MARK)) {
                player.removeStatusEffect(BossEffects.FIRSTBORN_MARK);
                player.sendMessage(Text.literal("Печать первенца угасла").formatted(Formatting.GOLD), true);
            }
    }

    private static Vec3d rotate(Vec3d v, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return new Vec3d(v.x * cos - v.z * sin, 0, v.x * sin + v.z * cos);
    }
}
