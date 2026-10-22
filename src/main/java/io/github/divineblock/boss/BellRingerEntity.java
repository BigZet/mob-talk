package io.github.divineblock.boss;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** Звонарь погоста: a giant living bronze bell that hops after its foe. It leaps up and lands on a marked circle with a short
 * ring around it, sends one ringing wave along the ground («Благовест», jump over it), swings its clapper at whoever stands in
 * front, and from the second phase throws small bells and shards of bronze. Ultimate «Набат»: three seconds of rising tolls,
 * then five sonic rings 0.6 s apart (in pairs in the third phase) with a heavy push; glass within 16 blocks shatters when block
 * damage is allowed. Everyone the rings catch hears «Звон в ушах». */
public final class BellRingerEntity extends SacredBoss {
    /** What the bell is casting, for the client's animation: one of the MOVE_ constants. */
    private static final TrackedData<Byte> MOVE = DataTracker.registerData(BellRingerEntity.class, TrackedDataHandlerRegistry.BYTE);
    public static final int MOVE_SLAM = 1, MOVE_TOLL = 2, MOVE_CLAPPER = 3, MOVE_SHARDS = 4, MOVE_NABAT = 5;
    static final double GLASS_RADIUS = 16;
    static final int RINGING_TICKS = 20 * 15;
    private static final ParticleEffect BRONZE = new DustParticleEffect(new Vector3f(0.78f, 0.55f, 0.26f), 1.8f);
    private static final ParticleEffect PATINA = new DustParticleEffect(new Vector3f(0.30f, 0.56f, 0.46f), 1.4f);

    private int hopCooldown, airTicks;

    public BellRingerEntity(EntityType<? extends BellRingerEntity> type, World world) { super(type, world, BossBar.Color.YELLOW); }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(MOVE, (byte) 0);
    }
    /** On the client: which attack the bell is casting (meaningful while {@link #castState()} is not 0). */
    public int move() { return dataTracker.get(MOVE); }
    private void setMove(int move) { dataTracker.set(MOVE, (byte) move); }

    /** «Звон в ушах» is drawn by the client (BossClient and the mixins): nothing to hook here. */
    static void registerHooks() {
        net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient && world.getBlockState(hit.getBlockPos()).isOf(Blocks.BELL))
                for (BellRingerEntity ringer : world.getEntitiesByClass(BellRingerEntity.class, new net.minecraft.util.math.Box(hit.getBlockPos()).expand(24), e -> e.isAlive()))
                    ringer.bellRung();
            return net.minecraft.util.ActionResult.PASS;
        });
    }

    /** A church bell rung within 24 blocks drowns his ringing: the bronze shudders and he stands open. */
    void bellRung() {
        if (!weakReady()) return;
        sound(SoundEvents.BLOCK_BELL_RESONATE, 3f, 1.2f);
        expose(EXPOSED_TICKS, "Настоящий колокол перебил его звон — бей!");
    }

    @Override public BossKind kind() { return BossKind.BELL_RINGER; }
    @Override protected String theme() { return "punishment_57"; }
    /** No plain melee: the clapper swing is its telegraphed blow. */
    @Override protected double meleeReach() { return 0; }

    // ---- Moving: slow, heavy hops. ----

    @Override protected void pursue(LivingEntity target) {
        double distance = squaredDistanceTo(target);
        if (age % 10 == 0) {
            if (distance > 16) getNavigation().startMovingTo(target, 0.8);
            else getNavigation().stop();
        }
        if (hopCooldown > 0) hopCooldown--;
        if (hopCooldown == 0 && isOnGround() && distance > 25) {
            Vec3d to = target.getPos().subtract(getPos()).multiply(1, 0, 1);
            to = to.lengthSquared() < 1e-4 ? Vec3d.ZERO : to.normalize();
            setVelocity(to.x * 0.34, 0.45, to.z * 0.34);
            velocityDirty = true;
            hopCooldown = (phase >= 2 ? 18 : 26) + random.nextInt(10);
        }
    }

    @Override public void tick() {
        super.tick();
        if (getWorld().isClient) return;
        if (!isOnGround()) { airTicks++; return; }
        if (airTicks > 4) land();
        airTicks = 0;
    }

    /** Every landing is a dull clang and a puff of dust. */
    private void land() {
        sound(SoundEvents.BLOCK_BELL_USE, 1.2f, 0.45f);
        BlockState ground = getWorld().getBlockState(getBlockPos().down());
        if (!ground.isAir()) particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, ground), getX(), getY() + 0.1, getZ(), 32, 1.6, 0.05, 1.6, 0.1);
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        double distance = distanceTo(target);
        int quick = phase >= 2 ? 10 : 0;
        if (distance < 5 && random.nextInt(3) != 0) { clapper(target); return 45 - quick; }
        int roll = random.nextInt(phase >= 2 ? 4 : 3);
        if (roll == 3) { shards(target); return 70 - quick; }
        if (roll == 0 || distance > 12) { slam(target); return 80 - quick; }
        blagovest();
        return 75 - quick;
    }

    /** «Удар колокола»: the bell leaps up and comes down on a circle marked under the foe; the landing sends a short ring. */
    private void slam(LivingEntity target) {
        setMove(MOVE_SLAM);
        Vec3d land = target.getPos();
        int warn = 30;
        cast("Удар колокола", warn, true, left -> {
            if (left == warn) {
                Vec3d d = land.subtract(getPos()).multiply(1, 0, 1);
                double flat = d.length(), speed = Math.min(1.1, flat / 11.0);
                Vec3d dir = flat < 0.01 ? Vec3d.ZERO : d.multiply(1 / flat);
                setVelocity(dir.x * speed, 0.95, dir.z * speed);
                velocityDirty = true;
                sound(SoundEvents.BLOCK_BELL_USE, 2f, 0.6f);
            }
            if (left % 4 == 0) particles(PATINA, land.x, land.y + 0.2, land.z, 6, 1.2, 0.05, 1.2, 0);
        }, () -> { });
        hazards.zone(land, 3.5, warn, BRONZE, at -> {
            sound(SoundEvents.BLOCK_BELL_USE, 3f, 0.4f);
            sound(SoundEvents.BLOCK_ANVIL_LAND, 1.2f, 0.5f);
            particles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 3, 1, 0.2, 1, 0);
            hazards.ring(at, 0.45, 7, 0.8, BRONZE, victim -> {
                if (hurt(victim, 5f)) { push(victim, at, 0.9, 0.35); deafen(victim, 20 * 6); }
            });
        }, victim -> { if (hurt(victim, 11f)) { push(victim, land, 1.2, 0.5); deafen(victim, 20 * 8); } });
    }

    /** «Благовест»: two slow strokes, then one wave of sound rolls out along the ground. Jump it. In phase 3 a second follows. */
    private void blagovest() {
        setMove(MOVE_TOLL);
        Vec3d center = getPos();
        int warn = 22;
        cast("Благовест", warn, true, left -> {
            if (left == warn || left == warn / 2) sound(SoundEvents.BLOCK_BELL_USE, 2.5f, 0.6f);
            if (left % 2 == 0) ringOfDust(center, 2.2 + (warn - left) * 0.05, BRONZE, 18);
        }, () -> {
            toll(center, 0.5, 15, 6f, 1.2, 0.45);
            if (phase >= 3) hazards.later(8, () -> toll(center, 0.5, 15, 6f, 1.2, 0.45));
        });
    }

    /** «Язык колокола»: the bell tips back and its clapper sweeps the ground in front of it. */
    private void clapper(LivingEntity target) {
        setMove(MOVE_CLAPPER);
        Vec3d from = getPos();
        Vec3d aim = target.getPos().subtract(from).multiply(1, 0, 1);
        Vec3d dir = aim.lengthSquared() < 1e-4 ? new Vec3d(0, 0, 1) : aim.normalize();
        int warn = 16;
        cast("Язык колокола", warn, true, left -> {
            if (left == warn) sound(SoundEvents.BLOCK_BELL_RESONATE, 1.5f, 1.2f);
            if (left % 3 == 0) for (int i = -3; i <= 3; i++) {
                Vec3d at = from.add(dir.rotateY(i * 0.3f).multiply(4.8));
                particles(ParticleTypes.CRIT, at.x, at.y + 0.3, at.z, 1, 0.05, 0.05, 0.05, 0);
            }
        }, () -> {
            sound(SoundEvents.BLOCK_ANVIL_LAND, 1.4f, 0.6f);
            sound(SoundEvents.BLOCK_BELL_USE, 2f, 0.8f);
            Vec3d front = from.add(dir.multiply(3.0));
            particles(ParticleTypes.SWEEP_ATTACK, front.x, front.y + 0.8, front.z, 5, 1.2, 0.2, 1.2, 0);
            for (LivingEntity victim : victims(from, 6.5)) {
                Vec3d rel = victim.getPos().subtract(from).multiply(1, 0, 1);
                double flat = rel.length();
                if (flat <= 5.5 && (flat < 2.0 || rel.normalize().dotProduct(dir) > 0.25) && hurt(victim, 10f)) {
                    push(victim, from, 1.4, 0.5);
                    deafen(victim, 20 * 5);
                }
            }
        });
    }

    /** «Бронзовые осколки» (phase 2+): small bells and shards of bronze fly from the crown in a fan. */
    private void shards(LivingEntity target) {
        setMove(MOVE_SHARDS);
        int count = phase >= 3 ? 6 : 4;
        ParticleEffect bell = new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.BELL));
        int warn = 20;
        cast("Бронзовые осколки", warn, true, left -> {
            Vec3d top = crownPos();
            particles(BRONZE, top.x, top.y, top.z, 3, 0.6, 0.3, 0.6, 0);
            if (left == warn) sound(SoundEvents.BLOCK_BELL_USE, 2f, 1.4f);
        }, () -> {
            Vec3d top = crownPos();
            Vec3d aim = target.getEyePos().subtract(top).normalize();
            for (int i = 0; i < count; i++) {
                float spread = (float) ((i - (count - 1) / 2.0) * 0.2);
                hazards.orb(top, aim.rotateY(spread).multiply(0.5), 60, 0.45, i % 2 == 0 ? bell : BRONZE, victim -> {
                    if (hurt(victim, 5f)) deafen(victim, 20 * 4);
                    particles(BRONZE, victim.getX(), victim.getBodyY(0.5), victim.getZ(), 8, 0.3, 0.3, 0.3, 0);
                });
            }
            sound(SoundEvents.BLOCK_ANVIL_LAND, 1f, 1.5f);
        });
    }

    // ---- The ultimate. ----

    @Override protected void ultimate(LivingEntity target) {
        setMove(MOVE_NABAT);
        Vec3d center = getPos();
        int every = phase >= 2 ? 10 : 15;
        castUltimate(left -> {
            int done = ULT_WARNING - left;
            if (done % every == 0) {
                sound(SoundEvents.BLOCK_BELL_USE, 3f, 0.5f + 0.45f * done / ULT_WARNING);
                ringOfDust(center, 3 + done * 0.15, BRONZE, 32);
            }
            if (left == ULT_WARNING || left == ULT_WARNING / 2) sound(SoundEvents.BLOCK_BELL_RESONATE, 3f, 0.5f);
            Vec3d top = crownPos();
            if (left % 2 == 0) particles(ParticleTypes.NOTE, top.x, top.y + 0.5, top.z, 1, 1, 0.5, 1, 0);
        }, () -> nabat(center));
    }

    /** «Набат»: five tolls 0.6 s apart, each a ring of sound; in phase 3 every toll rings twice. Glass shatters as they pass. */
    private void nabat(Vec3d center) {
        if (mayBreakBlocks()) debug("Набат: разбито стёкол " + shatterGlass(center));
        else glassEcho(center);
        hazards.repeat(12, 5, step -> {
            toll(center, 0.55, 20, 7f, 1.8, 0.55);
            if (phase >= 3) hazards.later(5, () -> toll(center, 0.55, 20, 7f, 1.8, 0.55));
        });
        // The tocsin rung out, the bronze is hot and the bell hangs open for a few seconds.
        hazards.later(12 * 5 + 20, () -> expose(80, "Набат отзвонил, медь раскалена — бей!"));
    }

    /** One stroke: its sound and a ring on the ground that deafens and throws back whoever it catches. */
    private void toll(Vec3d center, double speed, double radius, float damage, double strength, double lift) {
        sound(SoundEvents.BLOCK_BELL_USE, 3f, 0.5f);
        sound(SoundEvents.BLOCK_BELL_RESONATE, 2f, 0.6f);
        hazards.ring(center, speed, radius, 0.8, BRONZE, victim -> {
            if (hurt(victim, damage)) { push(victim, center, strength, lift); deafen(victim, RINGING_TICKS); }
        });
    }

    /** Shatters the glass within {@link #GLASS_RADIUS} blocks, each pane as the first ring reaches it; nothing drops. */
    private int shatterGlass(Vec3d center) {
        if (!(getWorld() instanceof ServerWorld world)) return 0;
        Map<Integer, List<BlockPos>> byTick = new TreeMap<>();
        BlockPos middle = BlockPos.ofFloored(center);
        int r = (int) GLASS_RADIUS, found = 0;
        for (BlockPos pos : BlockPos.iterate(middle.add(-r, -6, -r), middle.add(r, 12, r))) {
            double distance = Math.sqrt(pos.getSquaredDistance(center));
            if (distance > GLASS_RADIUS || !isGlass(world.getBlockState(pos))) continue;
            byTick.computeIfAbsent(Math.max(1, (int) (distance / 0.55)), k -> new ArrayList<>()).add(pos.toImmutable());
            if (++found >= 600) break;
        }
        for (var entry : byTick.entrySet()) {
            List<BlockPos> batch = entry.getValue();
            hazards.later(entry.getKey(), () -> {
                for (BlockPos pos : batch) if (isGlass(world.getBlockState(pos))) world.breakBlock(pos, false, this);
            });
        }
        return found;
    }

    private static boolean isGlass(BlockState state) {
        return state.isIn(ConventionalBlockTags.GLASS_BLOCKS) || state.isIn(ConventionalBlockTags.GLASS_PANES)
                || state.isOf(Blocks.GLASS) || state.isOf(Blocks.GLASS_PANE);
    }

    /** The same blow without breaking anything: the sound of shattering glass and shards around everyone it reaches. */
    private void glassEcho(Vec3d center) {
        ParticleEffect shards = new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.GLASS.getDefaultState());
        for (LivingEntity victim : victims(center, GLASS_RADIUS)) {
            int delay = Math.max(1, (int) (victim.distanceTo(this) / 0.55));
            hazards.later(delay, () -> {
                particles(shards, victim.getX(), victim.getEyeY(), victim.getZ(), 20, 0.6, 0.4, 0.6, 0.1);
                getWorld().playSound(null, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 1.2f, 0.8f);
            });
        }
    }

    // ---- Phases and death. ----

    @Override protected String weakHint() { return "ударь в настоящий колокол рядом (24 блока) или дождись конца набата"; }
    @Override protected String ultHint() { return "перепрыгивай звуковые кольца; после набата он открыт"; }
    @Override protected String phaseName(int phase) { return phase == 2 ? "Осколки меди" : "Двойной звон"; }
    @Override protected Vec3d weakSpot() { return crownPos(); }

    /** Tests: a bell beside the player, rung by the player's hand as in the game (the {@code UseBlockCallback} above). */
    @Override public void provokeWeakPoint(net.minecraft.server.network.ServerPlayerEntity player) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        BlockPos at = player.getBlockPos().east(2);
        BlockState was = world.getBlockState(at);
        world.setBlockState(at, Blocks.BELL.getDefaultState());
        var hit = new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(at), net.minecraft.util.math.Direction.WEST, at, false);
        player.interactionManager.interactBlock(player, world, net.minecraft.item.ItemStack.EMPTY, net.minecraft.util.Hand.MAIN_HAND, hit);
        hazards.later(60, () -> world.setBlockState(at, was));
    }

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.BLOCK_BELL_RESONATE, 3f, 0.4f + 0.1f * phase);
        for (var player : playersNear(48))
            player.sendMessage(Text.literal(phase == 2 ? "Звонарь бьёт чаще" : "Звонарь бьёт сдвоенно").formatted(Formatting.GOLD));
        if (phase == 3) addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, StatusEffectInstance.INFINITE, 0, false, false));
    }

    @Override protected void onDefeated() {
        sound(SoundEvents.BLOCK_BELL_RESONATE, 3f, 0.3f);
        for (LivingEntity victim : victims(getPos(), 32)) victim.removeStatusEffect(BossEffects.RINGING);
    }

    // ---- Helpers. ----

    /** «Звон в ушах» for {@code ticks}, never shortening a longer ringing already there. */
    private void deafen(LivingEntity victim, int ticks) {
        StatusEffectInstance now = victim.getStatusEffect(BossEffects.RINGING);
        if (now == null || now.getDuration() < ticks) BossEffects.afflict(victim, BossEffects.RINGING, ticks);
    }

    private void ringOfDust(Vec3d center, double radius, ParticleEffect dust, int points) {
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2 * i / points;
            particles(dust, center.x + Math.cos(angle) * radius, center.y + 0.2, center.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
        }
    }

    private Vec3d crownPos() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(getX() - Math.sin(yaw) * 0.8, getY() + getHeight() * 0.85, getZ() + Math.cos(yaw) * 0.8);
    }
}
