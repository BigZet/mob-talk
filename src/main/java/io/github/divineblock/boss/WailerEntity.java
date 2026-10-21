package io.github.divineblock.boss;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Плакальщица погоста: a tall mourner in a black shroud who glides over the graves. She wails in a cone («Плач»: harm,
 * slowness and weakness), reaches out with her long arms and drags the foe closer, slips through the air to another spot in a
 * trail of souls, and throws funeral candle flames. Ultimate «Отпевание»: the ground cracks at four to eight graves around the
 * foe and the dead rise there in white shrouds (more with power and in phase 2, never more than twelve at once); everyone
 * within 20 blocks takes the «Панихида». In phase 3 she wails without pause, slowing everyone near her. When she dies the
 * Panikhida is lifted and her dead lie down again. */
public final class WailerEntity extends SacredBoss {
    private static final TrackedData<Byte> MOVE = DataTracker.registerData(WailerEntity.class, TrackedDataHandlerRegistry.BYTE);
    public static final int MOVE_WAIL = 1, MOVE_GRASP = 2, MOVE_GLIDE = 3, MOVE_CANDLES = 4, MOVE_FUNERAL = 5;
    /** The command tag her risen dead carry, so they can be counted and laid to rest. */
    static final String RISEN = "divineblock_risen_dead";
    static final int MAX_DEAD = 12;
    static final double CONE_LENGTH = 11, CONE_HALF = Math.toRadians(32), FUNERAL_RADIUS = 20;
    /** How far her long arms rake: she is over five blocks tall and her arms nearly reach the ground. */
    static final double REACH = 5.4;
    static final int PANIKHIDA_TICKS = 20 * 180;
    private int wailTimer;

    public WailerEntity(EntityType<? extends WailerEntity> type, World world) { super(type, world, BossBar.Color.BLUE); }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(MOVE, (byte) 0);
    }
    /** On the client: which attack she is casting (meaningful while {@link #castState()} is not 0). */
    public int move() { return dataTracker.get(MOVE); }
    private void setMove(int move) { dataTracker.set(MOVE, (byte) move); }

    /** The Panikhida's rules live in {@link BossEffects}: nothing of its own to hook. */
    static void registerHooks() { }

    @Override public BossKind kind() { return BossKind.WAILER; }
    @Override protected String theme() { return "punishment_56"; }
    /** No plain melee: her long-armed grasp is telegraphed. */
    @Override protected double meleeReach() { return 0; }

    /** She glides to a few blocks from her foe and hovers there. */
    @Override protected void pursue(LivingEntity target) {
        if (age % 10 != 0) return;
        if (squaredDistanceTo(target) > REACH * REACH * 0.7) getNavigation().startMovingTo(target, 1.0);
        else getNavigation().stop();
    }

    @Override public void tick() {
        super.tick();
        if (getWorld().isClient && age % 4 == 0) {
            getWorld().addParticle(ParticleTypes.SMOKE, getX() + (random.nextDouble() - 0.5) * 0.8, getY() + 0.1, getZ() + (random.nextDouble() - 0.5) * 0.8, 0, 0.01, 0);
            if (random.nextInt(3) == 0) getWorld().addParticle(ParticleTypes.SOUL, getX(), getY() + 0.3, getZ(), 0, 0.02, 0);
        }
    }

    @Override protected void mobTick() {
        super.mobTick();
        // Phase 3: she never stops wailing; everyone near her is slowed.
        if (phase < 3 || getTarget() == null || isDead() || ++wailTimer < 50) return;
        wailTimer = 0;
        sound(SoundEvents.ENTITY_GHAST_AMBIENT, 1.6f, 0.45f);
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2 * i / 24;
            particles(ParticleTypes.SOUL, getX() + Math.cos(angle) * 7, getY() + 0.3, getZ() + Math.sin(angle) * 7, 1, 0, 0.1, 0, 0.01);
        }
        for (LivingEntity victim : victims(getPos(), 7)) victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 45, 0));
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        double distance = distanceTo(target);
        int quick = phase >= 3 ? 10 : 0;
        if (distance < REACH && random.nextInt(3) != 0) { grasp(target); return 40 - quick; }
        int roll = random.nextInt(4);
        if (roll == 0) { glide(target); return 45 - quick; }
        if (roll == 1 || distance > CONE_LENGTH) { candles(target); return 70 - quick; }
        wail(target);
        return 80 - quick;
    }

    /** «Плач»: the cone of her scream is drawn on the ground first; then whoever stands in it is hurt, slowed and weakened. */
    private void wail(LivingEntity target) {
        setMove(MOVE_WAIL);
        Vec3d from = getPos();
        Vec3d dir = flat(target.getPos().subtract(from), this);
        int warn = 26;
        cast("Плач", warn, true, left -> {
            if (left == warn) sound(SoundEvents.ENTITY_GHAST_WARN, 2f, 0.5f);
            if (left % 3 == 0) {
                for (double r = 1; r <= CONE_LENGTH; r += 0.8) for (int side = -1; side <= 1; side += 2) {
                    Vec3d at = from.add(dir.rotateY((float) (side * CONE_HALF)).multiply(r));
                    particles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.15, at.z, 1, 0, 0, 0, 0);
                }
                for (int i = -4; i <= 4; i++) {
                    Vec3d at = from.add(dir.rotateY((float) (i * CONE_HALF / 4)).multiply(CONE_LENGTH));
                    particles(ParticleTypes.SOUL_FIRE_FLAME, at.x, at.y + 0.15, at.z, 1, 0, 0, 0, 0);
                }
            }
            particles(ParticleTypes.SOUL, getX(), getY() + getHeight() * 0.8, getZ(), 1, 0.2, 0.1, 0.2, 0.01);
        }, () -> {
            sound(SoundEvents.ENTITY_GHAST_SCREAM, 3f, 0.45f);
            for (double r = 1; r <= CONE_LENGTH; r += 1.5) {
                Vec3d at = from.add(dir.multiply(r));
                particles(ParticleTypes.SCULK_SOUL, at.x, at.y + 1.4, at.z, 4, r * 0.25, 0.4, r * 0.25, 0.02);
            }
            for (LivingEntity victim : victims(from, CONE_LENGTH + 1)) {
                Vec3d rel = victim.getPos().subtract(from).multiply(1, 0, 1);
                double length = rel.length();
                if (length > CONE_LENGTH || length > 1 && Math.acos(MathHelper.clamp(rel.normalize().dotProduct(dir), -1, 1)) > CONE_HALF) continue;
                if (hurt(victim, 6f)) {
                    victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 1));
                    victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 160, 0));
                }
            }
        });
    }

    /** «Долгие руки»: she draws her arm back, then her long fingers rake the space in front of her and drag the foe closer. */
    private void grasp(LivingEntity target) {
        setMove(MOVE_GRASP);
        Vec3d from = getPos();
        Vec3d dir = flat(target.getPos().subtract(from), this);
        int warn = 16;
        cast("Долгие руки", warn, true, left -> {
            if (left == warn) sound(SoundEvents.ENTITY_VEX_CHARGE, 1.6f, 0.5f);
            if (left % 3 == 0) for (int i = -2; i <= 2; i++) {
                Vec3d at = from.add(dir.rotateY(i * 0.35f).multiply(REACH - 0.6));
                particles(ParticleTypes.SOUL, at.x, at.y + 0.3, at.z, 1, 0, 0, 0, 0);
            }
        }, () -> {
            swingHand(net.minecraft.util.Hand.MAIN_HAND);
            sound(SoundEvents.ENTITY_PHANTOM_BITE, 1.5f, 0.5f);
            for (LivingEntity victim : victims(from, REACH + 1.5)) {
                Vec3d rel = victim.getPos().subtract(from).multiply(1, 0, 1);
                double length = rel.length();
                if (length <= REACH + 0.2 && (length < 1.4 || rel.normalize().dotProduct(dir) > 0.3) && hurt(victim, 8f))
                    push(victim, from, -0.6, 0.15);
            }
        });
    }

    /** «Скольжение»: souls gather at a spot near the foe; she fades and appears there. */
    private void glide(LivingEntity target) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        setMove(MOVE_GLIDE);
        double angle = random.nextDouble() * Math.PI * 2, radius = 4 + random.nextDouble() * 3;
        BlockPos spot = ground(world, BlockPos.ofFloored(target.getX() + Math.cos(angle) * radius, target.getY(), target.getZ() + Math.sin(angle) * radius), MathHelper.ceil(getHeight()));
        Vec3d to = spot == null ? getPos() : Vec3d.ofBottomCenter(spot);
        int warn = 20;
        cast("Скольжение", warn, true, left -> {
            particles(ParticleTypes.SOUL, to.x, to.y + 1, to.z, 3, 0.4, getHeight() * 0.25, 0.4, 0.01);
            particles(ParticleTypes.SOUL_FIRE_FLAME, getX(), getY() + 0.3, getZ(), 2, 0.4, 0.2, 0.4, 0.01);
            if (left == warn) sound(SoundEvents.PARTICLE_SOUL_ESCAPE, 2f, 0.6f);
        }, () -> {
            particles(ParticleTypes.SOUL, getX(), getY() + getHeight() * 0.45, getZ(), 40, 0.5, getHeight() * 0.3, 0.5, 0.03);
            requestTeleport(to.x, to.y, to.z);
            particles(ParticleTypes.SOUL, to.x, to.y + getHeight() * 0.45, to.z, 40, 0.5, getHeight() * 0.3, 0.5, 0.03);
            sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.4f);
        });
    }

    /** «Поминальные свечи»: flames gather at her candle, then fly at the foe and set it alight. */
    private void candles(LivingEntity target) {
        setMove(MOVE_CANDLES);
        int count = phase >= 2 ? 4 : 3;
        int warn = 22;
        cast("Поминальные свечи", warn, true, left -> {
            Vec3d hand = candlePos();
            particles(ParticleTypes.FLAME, hand.x, hand.y, hand.z, 2, 0.15, 0.15, 0.15, 0.01);
            if (left == warn) sound(SoundEvents.BLOCK_CANDLE_AMBIENT, 2f, 0.6f);
        }, () -> {
            Vec3d hand = candlePos();
            Vec3d aim = target.getEyePos().subtract(hand).normalize();
            for (int i = 0; i < count; i++) {
                float spread = (float) ((i - (count - 1) / 2.0) * 0.2);
                hazards.orb(hand, aim.rotateY(spread).multiply(0.4), 70, 0.4, ParticleTypes.FLAME, victim -> {
                    if (hurt(victim, 5f)) victim.setOnFireFor(3);
                    particles(ParticleTypes.SMOKE, victim.getX(), victim.getBodyY(0.5), victim.getZ(), 8, 0.3, 0.3, 0.3, 0.02);
                });
            }
            sound(SoundEvents.ITEM_FIRECHARGE_USE, 1.5f, 0.6f);
        });
    }

    // ---- The ultimate. ----

    @Override protected void ultimate(LivingEntity target) {
        setMove(MOVE_FUNERAL);
        List<BlockPos> graves = new ArrayList<>();
        int rise = 0;
        if (getWorld() instanceof ServerWorld world) {
            rise = Math.max(0, Math.min(MAX_DEAD - risenCount(world), Math.min(8, 3 + power + (phase >= 2 ? 2 : 0))));
            int spots = Math.max(4, Math.min(8, Math.max(rise, 3 + power)));
            for (int i = 0, tries = 0; graves.size() < spots && tries < spots * 4; tries++, i++) {
                double angle = Math.PI * 2 * i / spots + random.nextDouble() * 0.5, radius = 3 + random.nextDouble() * 4;
                BlockPos at = ground(world, BlockPos.ofFloored(target.getX() + Math.cos(angle) * radius, target.getY(), target.getZ() + Math.sin(angle) * radius), 2);
                if (at != null) graves.add(at);
            }
        }
        int count = rise;
        castUltimate(left -> {
            for (BlockPos grave : graves) {
                BlockState soil = getWorld().getBlockState(grave.down());
                if (left % 3 == 0) particles(ParticleTypes.SOUL, grave.getX() + 0.5, grave.getY() + 0.2, grave.getZ() + 0.5, 2, 0.4, 0.1, 0.4, 0.01);
                if (left % 5 == 0 && !soil.isAir())
                    particles(new BlockStateParticleEffect(ParticleTypes.BLOCK, soil), grave.getX() + 0.5, grave.getY() + 0.1, grave.getZ() + 0.5, 6, 0.5, 0.05, 0.5, 0.05);
            }
            if (left % 20 == 0) {
                sound(SoundEvents.BLOCK_BELL_RESONATE, 2f, 0.45f);
                sound(SoundEvents.BLOCK_ROOTED_DIRT_BREAK, 2f, 0.5f);
            }
            particles(ParticleTypes.SOUL, getX(), getY() + getHeight() * 0.75, getZ(), 2, 0.5, 0.5, 0.5, 0.02);
        }, () -> funeral(graves, count));
    }

    /** «Отпевание»: the dead rise at the graves and everyone near takes the Panikhida. */
    private void funeral(List<BlockPos> graves, int count) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        sound(SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, 2.5f, 0.5f);
        sound(SoundEvents.ENTITY_GHAST_SCREAM, 2f, 0.4f);
        int risen = 0;
        for (int i = 0; i < count && !graves.isEmpty(); i++) if (raise(world, graves.get(i % graves.size()), i)) risen++;
        debug("Отпевание: поднялось " + risen);
        for (ServerPlayerEntity player : playersNear(FUNERAL_RADIUS)) {
            if (player.isCreative()) continue;
            BossEffects.afflict(player, BossEffects.PANIKHIDA, PANIKHIDA_TICKS);
            player.sendMessage(Text.literal("Панихида: силы уходят, пока плакальщица жива").formatted(Formatting.GRAY));
        }
    }

    /** One of the dead in a white shroud: a zombie or a skeleton named «Покойник», with no drops. */
    private boolean raise(ServerWorld world, BlockPos at, int index) {
        HostileEntity dead = index % 2 == 0 ? EntityType.ZOMBIE.create(world) : EntityType.SKELETON.create(world);
        if (dead == null) return false;
        dead.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360f, 0);
        NbtCompound data = new NbtCompound();
        dead.writeCustomDataToNbt(data);
        data.putString("DeathLootTable", "minecraft:empty");
        dead.readCustomDataFromNbt(data);
        ItemStack shroud = new ItemStack(Items.LEATHER_CHESTPLATE), cover = new ItemStack(Items.LEATHER_HELMET);
        shroud.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(0xE8E4D8, false));
        cover.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(0xE8E4D8, false));
        dead.equipStack(EquipmentSlot.CHEST, shroud);
        dead.equipStack(EquipmentSlot.HEAD, cover);
        for (EquipmentSlot slot : EquipmentSlot.values()) dead.setEquipmentDropChance(slot, 0f);
        dead.setCustomName(Text.literal("Покойник").formatted(Formatting.GRAY));
        dead.addCommandTag(RISEN);
        dead.setTarget(getTarget());
        world.spawnEntity(dead);
        BlockState soil = world.getBlockState(at.down());
        if (!soil.isAir()) world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, soil), dead.getX(), dead.getY() + 0.2, dead.getZ(), 30, 0.4, 0.2, 0.4, 0.1);
        world.spawnParticles(ParticleTypes.SOUL, dead.getX(), dead.getY() + 1, dead.getZ(), 12, 0.3, 0.6, 0.3, 0.02);
        return true;
    }

    private int risenCount(ServerWorld world) {
        return world.getEntitiesByClass(HostileEntity.class, new Box(getBlockPos()).expand(96), e -> e.isAlive() && e.getCommandTags().contains(RISEN)).size();
    }

    // ---- Phases and death. ----

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.ENTITY_GHAST_HURT, 2f, 0.4f);
        for (ServerPlayerEntity player : playersNear(48))
            player.sendMessage(Text.literal(phase == 2 ? "Плакальщица зовёт больше мёртвых" : "Плакальщица воет не смолкая").formatted(Formatting.GRAY));
    }

    /** The mourner is dead: the Panikhida is lifted and her dead lie down again. */
    @Override protected void onDefeated() {
        if (!(getWorld() instanceof ServerWorld world)) return;
        BossEffects.liftAll(world.getServer());
        for (HostileEntity dead : world.getEntitiesByClass(HostileEntity.class, new Box(getBlockPos()).expand(128), e -> e.isAlive() && e.getCommandTags().contains(RISEN))) {
            world.spawnParticles(ParticleTypes.SOUL, dead.getX(), dead.getY() + 1, dead.getZ(), 12, 0.3, 0.6, 0.3, 0.02);
            dead.discard();
        }
    }

    // ---- Helpers. ----

    /** A flat unit vector along {@code d}, or the boss's facing when {@code d} is too short. */
    private static Vec3d flat(Vec3d d, SacredBoss boss) {
        Vec3d flat = d.multiply(1, 0, 1);
        if (flat.lengthSquared() > 1e-4) return flat.normalize();
        float yaw = boss.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    /** A standing spot near {@code pos}: solid ground below and {@code clear} free blocks above; null when there is none. */
    private static BlockPos ground(ServerWorld world, BlockPos pos, int clear) {
        search:
        for (int dy = 3; dy >= -4; dy--) {
            BlockPos at = pos.up(dy);
            if (!world.getBlockState(at.down()).isSolidBlock(world, at.down())) continue;
            for (int up = 0; up < clear; up++) if (!world.getBlockState(at.up(up)).getCollisionShape(world, at.up(up)).isEmpty()) continue search;
            return at;
        }
        return null;
    }

    /** The flame of the candle she holds up before her face (scaled with the model). */
    private Vec3d candlePos() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        double ahead = getWidth() * 0.95, side = getWidth() * 0.4;
        return new Vec3d(getX() - Math.sin(yaw) * ahead + Math.cos(yaw) * side, getY() + getHeight() * 0.8,
                getZ() + Math.cos(yaw) * ahead + Math.sin(yaw) * side);
    }
}
