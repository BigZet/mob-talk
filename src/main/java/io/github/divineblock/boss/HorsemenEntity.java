package io.github.divineblock.boss;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

/** Четыре всадника Апокалипсиса (Revelation 6:1–8): one horse and rider, one fight in four phases. Each quarter of health
 * is another seal; when it breaks the horse rears in a burst of light, the boss cannot be hurt for two seconds, and the next
 * rider comes. A single blow never skips a seal, so all four always ride.
 * <ul>
 * <li>Белый всадник «Победа»: keeps his distance and shoots volleys of light arrows, lays the «Венец победы» (a crown of
 * marked circles) around the foe, and charges.</li>
 * <li>Рыжий всадник «Война»: sword charges, sweeps and a trampling ring; his aura sets the hostile mobs around on each
 * other and on the player, and brings angry illagers when there are none.</li>
 * <li>Вороной всадник «Голод»: the scales weigh the foe (heavier for the hungry) and throw measures of wheat that bring
 * «Голод»: food rots in the inventory, one item every five seconds (golden food is spared), and hunger burns faster.</li>
 * <li>Бледный всадник «Смерть»: a withering aura around him, scythe sweeps, a ring of death to jump over, and «Ад следует
 * за ним»: skeletons and wither skeletons rise from the ground.</li>
 * </ul>
 * Ultimate «Снятие печатей»: the current rider's signature, then four seals fall on the foe one after another, white,
 * red, black and pale, each with its rider's harm. Summoned mobs are capped at {@link #MAX_FOLLOWERS} and leave with him. */
public final class HorsemenEntity extends SacredBoss {
    public static final int STANCE_NONE = 0, STANCE_REAR = 1, STANCE_CHARGE = 2;
    private static final TrackedData<Byte> STANCE = DataTracker.registerData(HorsemenEntity.class, TrackedDataHandlerRegistry.BYTE);
    static final int MAX_FOLLOWERS = 8;
    /** The pause while a seal breaks and the next rider comes: no attacks, no harm to him. */
    static final int SEAL_PAUSE = 40;
    static final double WITHER_AURA = 6;

    private static final String[] ORDINALS = { "первая", "вторая", "третья", "четвёртая" };
    private static final String[] RIDERS = { "Белый всадник", "Рыжий всадник", "Вороной всадник", "Бледный всадник" };
    private static final String[] NAMES = { "Победа", "Война", "Голод", "Смерть" };
    private static final Formatting[] COLORS = { Formatting.WHITE, Formatting.RED, Formatting.DARK_GRAY, Formatting.GREEN };
    private static final BossBar.Color[] BARS = { BossBar.Color.WHITE, BossBar.Color.RED, BossBar.Color.PURPLE, BossBar.Color.GREEN };
    private static final String[] VERSES = {
            "«Иди и смотри». …и вот, конь белый, и на нём всадник, имеющий лук, и дан был ему венец; и вышел он как победоносный, и чтобы победить.",
            "«Иди и смотри». …и вышел другой конь, рыжий; и сидящему на нём дано взять мир с земли, и чтобы убивали друг друга; и дан ему большой меч.",
            "«Иди и смотри». …и вот, конь вороной, и на нём всадник, имеющий меру в руке своей. Хиникс пшеницы за динарий…",
            "«Иди и смотри». …и вот, конь бледный, и на нём всадник, которому имя «смерть»; и ад следовал за ним." };
    private static final DustParticleEffect[] SEAL_DUST = {
            new DustParticleEffect(new Vector3f(1f, 0.96f, 0.82f), 1.8f), new DustParticleEffect(new Vector3f(0.86f, 0.12f, 0.06f), 1.8f),
            new DustParticleEffect(new Vector3f(0.3f, 0.26f, 0.16f), 1.8f), new DustParticleEffect(new Vector3f(0.55f, 0.95f, 0.72f), 1.8f) };
    private static final DustParticleEffect GOLD_DUST = new DustParticleEffect(new Vector3f(1f, 0.8f, 0.3f), 1.4f);

    private final List<MobEntity> followers = new ArrayList<>();
    private int sealTicks, announced;

    public HorsemenEntity(EntityType<? extends HorsemenEntity> type, World world) { super(type, world, BossBar.Color.WHITE); }

    @Override protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(STANCE, (byte) STANCE_NONE);
    }
    /** On the client too: 0 riding, 1 the horse rears (a seal breaks, the ultimate), 2 charging. */
    public int stance() { return dataTracker.get(STANCE); }
    private void setStance(int stance) { dataTracker.set(STANCE, (byte) stance); }

    // ---- «Голод»: food rots in the inventory. ----

    /** «Голод»: while it lasts, every five seconds one food item in the inventory rots into rotten flesh (golden food is
     * spared), and a strong vanilla hunger keeps the food bar draining. */
    static void registerHooks() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTicks() % 20 != 0) return;
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                if (!player.isAlive() || player.isCreative() || player.isSpectator() || !player.hasStatusEffect(BossEffects.FAMINE)) continue;
                StatusEffectInstance hunger = player.getStatusEffect(StatusEffects.HUNGER);
                if (hunger == null || hunger.getDuration() < 40) player.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 120, 1, false, false, true));
                if (server.getTicks() % 100 == 0) rot(player);
            }
        });
    }

    /** One food item in the player's inventory turns to rotten flesh. */
    static boolean rot(ServerPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        List<Integer> food = new ArrayList<>();
        for (int i = 0; i < inventory.size(); i++) if (rots(inventory.getStack(i))) food.add(i);
        if (food.isEmpty()) return false;
        int slot = food.get(player.getRandom().nextInt(food.size()));
        ItemStack stack = inventory.getStack(slot);
        Text name = stack.getName();
        stack.decrement(1);
        ItemStack flesh = new ItemStack(Items.ROTTEN_FLESH);
        if (stack.isEmpty()) inventory.setStack(slot, flesh);
        else if (!inventory.insertStack(flesh)) player.dropItem(flesh, false);
        player.sendMessage(Text.literal("Голод: ").append(name).append(" истлевает").formatted(Formatting.DARK_GRAY), true);
        player.getWorld().playSound(null, player.getBlockPos(), SoundEvents.BLOCK_COMPOSTER_FILL, SoundCategory.PLAYERS, 1f, 0.5f);
        return true;
    }
    static boolean rots(ItemStack stack) {
        return !stack.isEmpty() && stack.contains(DataComponentTypes.FOOD) && !stack.isOf(Items.ROTTEN_FLESH)
                && !stack.isOf(Items.GOLDEN_APPLE) && !stack.isOf(Items.ENCHANTED_GOLDEN_APPLE) && !stack.isOf(Items.GOLDEN_CARROT);
    }

    // ---- Who he is. ----

    @Override public BossKind kind() { return BossKind.HORSEMEN; }
    @Override protected String theme() { return "punishment_20"; }
    @Override protected int phaseCount() { return 4; }
    @Override protected double meleeReach() { return 4.4; }
    @Override protected float meleeDamage() { return phase == 2 ? 9f : 7f; }
    private int rider() { return Math.max(0, Math.min(3, phase - 1)); }

    /** He gallops: the white rider keeps a bow's distance, the others ride the foe down. */
    @Override protected void pursue(LivingEntity target) {
        if (sealTicks > 0 || stance() == STANCE_CHARGE) { getNavigation().stop(); return; }
        if (age % 10 != 0) return;
        double distance = squaredDistanceTo(target);
        if (phase == 1) {
            if (distance < 7 * 7) {
                Vec3d away = getPos().subtract(target.getPos()).multiply(1, 0, 1);
                away = away.lengthSquared() < 1e-3 ? new Vec3d(8, 0, 0) : away.normalize().multiply(8);
                getNavigation().startMovingTo(getX() + away.x, getY(), getZ() + away.z, 1.3);
            } else if (distance > 14 * 14) getNavigation().startMovingTo(target, 1.3);
            else getNavigation().stop();
            return;
        }
        if (distance > meleeReach() * meleeReach() * 0.6) getNavigation().startMovingTo(target, phase == 4 ? 1.05 : 1.3);
        else getNavigation().stop();
    }

    /** No harm while a seal breaks, and no blow takes him past the next seal. */
    @Override public boolean damage(DamageSource source, float amount) {
        boolean absolute = source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY);
        if (sealTicks > 0 && !absolute) {
            if (!getWorld().isClient && age % 4 == 0) sound(SoundEvents.ITEM_SHIELD_BLOCK, 1.2f, 0.5f);
            return false;
        }
        if (phase < phaseCount() && !absolute) {
            float floor = getMaxHealth() * (1f - (phase + 1) / (float) phaseCount()) + 1f;
            amount = Math.min(amount, (getHealth() - floor) / (exposed() ? TAKEN_EXPOSED : TAKEN_NORMAL));
            if (amount <= 0) return false;
        }
        return super.damage(source, amount);
    }

    @Override protected void mobTick() {
        super.mobTick();
        if (!(getWorld() instanceof ServerWorld world) || !isAlive()) return;
        followers.removeIf(mob -> !mob.isAlive() || mob.isRemoved());
        if (sealTicks > 0 && --sealTicks == 0 && stance() == STANCE_REAR) setStance(STANCE_NONE);
        if (getTarget() == null) return;
        if (announced < phase) announce(phase);
        if (age % 6 == 0 && getVelocity().horizontalLengthSquared() > 0.004) sound(SoundEvents.ENTITY_HORSE_GALLOP, 0.9f, 0.8f);
        if (sealTicks > 0) return;
        if (phase == 2 && age % 100 == 0) warAura(world, 24);
        if (phase == 3 && age % 200 == 0) famine(20, 20 * 20, false);
        if (phase == 4 && age % 20 == 0) witherAura(WITHER_AURA, age % 60 == 0);
    }

    // ---- Seals. ----

    // ---- The weak point: the rider. ----

    /** An arrow or a trident in the rider (above the horse's back) throws him back in the saddle. */
    @Override protected boolean weakTrigger(DamageSource source, float amount) {
        if (!(source.getSource() instanceof net.minecraft.entity.projectile.ProjectileEntity projectile)) return false;
        Vec3d pos = projectile.getPos(), velocity = projectile.getVelocity();
        Vec3d at = getBoundingBox().expand(0.3).raycast(pos.subtract(velocity), pos.add(velocity.multiply(1.5))).orElse(pos);
        return at.y >= getY() + getHeight() * 0.6;
    }
    @Override protected String weakHint() { return "конь в броне — стреляй во всадника (стрела или трезубец выше конской спины)"; }
    @Override protected String weakReason() { return "Всадник сбит в седле — бей!"; }
    @Override protected String ultHint() { return "печати падают на тебя по одной — не стой на месте"; }
    @Override protected String phaseName(int phase) { return switch (phase) { case 2 -> "Конь рыжий"; case 3 -> "Конь вороной"; default -> "Конь бледный"; }; }
    @Override protected Vec3d weakSpot() { return new Vec3d(getX(), getY() + getHeight() * 0.8, getZ()); }
    @Override public void provokeWeakPoint(net.minecraft.server.network.ServerPlayerEntity player) { arrowAt(player, 0.8, false); }
    /** Tests: an arrow from {@code player} that strikes at {@code share} of the boss's height. */
    private void arrowAt(net.minecraft.server.network.ServerPlayerEntity player, double share, boolean burning) {
        var arrow = new net.minecraft.entity.projectile.ArrowEntity(getWorld(), player, new net.minecraft.item.ItemStack(net.minecraft.item.Items.ARROW), null);
        Vec3d at = new Vec3d(getX(), getY() + getHeight() * share, getZ());
        Vec3d from = player.getEyePos().multiply(1, 0, 1).add(0, at.y, 0);
        Vec3d dir = at.subtract(from).lengthSquared() < 1e-3 ? new Vec3d(0, 0, 1) : at.subtract(from).normalize();
        arrow.setPosition(at.subtract(dir.multiply(getWidth() * 0.5 + 0.8)));
        arrow.setVelocity(dir.multiply(2.5));
        if (burning) arrow.setOnFireFor(5);
        damage(getDamageSources().arrow(arrow, player), 6f);
        arrow.discard();
    }

    @Override protected void onPhase(int phase) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        hazards.clear();
        sealTicks = SEAL_PAUSE;
        setStance(STANCE_REAR);
        getNavigation().stop();
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world);
        if (bolt != null) { bolt.setCosmetic(true); bolt.refreshPositionAfterTeleport(getPos()); world.spawnEntity(bolt); }
        particles(ParticleTypes.FLASH, getX(), getY() + getHeight() * 0.45, getZ(), 3, 0.5, 0.5, 0.5, 0);
        particles(ParticleTypes.END_ROD, getX(), getY() + getHeight() * 0.45, getZ(), 160, 1.5, 2, 1.5, 0.25);
        sound(SoundEvents.BLOCK_BEACON_ACTIVATE, 3f, 0.6f);
        sound(SoundEvents.ENTITY_HORSE_ANGRY, 2.5f, 0.6f);
        announce(phase);
        int rider = rider();
        cast(null, SEAL_PAUSE, true, left -> {
            for (double y = 0; y < 14; y += 0.7) particles(SEAL_DUST[rider], getX(), getY() + y, getZ(), 1, 0.25, 0.1, 0.25, 0);
            if (left % 10 == 0) particles(ParticleTypes.END_ROD, getX(), getY() + getHeight() * 0.35, getZ(), 20, 1.2, 1.2, 1.2, 0.08);
            if (left == 20) sound(SoundEvents.BLOCK_BELL_RESONATE, 2.5f, 0.5f + 0.15f * rider);
        }, () -> arrive(phase));
        removeStatusEffect(StatusEffects.SPEED);
        if (phase == 2) addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 1, false, false));
        if (phase == 3) addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 0, false, false));
    }

    /** A title for everyone around: which seal is broken, who rides out, and the verse. */
    private void announce(int phase) {
        announced = phase;
        int rider = Math.max(0, Math.min(3, phase - 1));
        bar.setColor(BARS[rider]);
        bar.setName(Text.literal(kind().title + ": " + RIDERS[rider] + " «" + NAMES[rider] + "»").formatted(Formatting.GOLD));
        for (ServerPlayerEntity player : playersNear(64)) {
            player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 50, 15));
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(RIDERS[rider] + " — «" + NAMES[rider] + "»").formatted(COLORS[rider])));
            player.networkHandler.sendPacket(new TitleS2CPacket(Text.literal("Снята " + ORDINALS[rider] + " печать").formatted(Formatting.GOLD, Formatting.BOLD)));
            player.sendMessage(Text.literal(VERSES[rider]).formatted(Formatting.GRAY, Formatting.ITALIC), false);
        }
        debug("печать " + phase + ": " + RIDERS[rider]);
    }

    /** The new rider is here: War sets the land on itself, Famine falls on everyone, Hades rises with Death. */
    private void arrive(int phase) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        setStance(STANCE_NONE);
        sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.7f);
        switch (phase) {
            case 2 -> warAura(world, 24);
            case 3 -> famine(24, 30 * 20, true);
            case 4 -> { LivingEntity target = getTarget(); for (int i = 0; i < 2; i++) raiseFollower(world, target != null ? target.getPos() : getPos(), 4); }
            default -> { }
        }
    }

    // ---- Attacks. ----

    @Override protected int attack(LivingEntity target) {
        if (sealTicks > 0) return 20;
        double distance = distanceTo(target);
        int roll = random.nextInt(3);
        return switch (phase) {
            case 1 -> roll == 0 && distance > 6 ? charge(target, "Победоносный натиск", 8f, ParticleTypes.END_ROD)
                    : roll == 1 ? crownOfLight(target) : volley(target);
            case 2 -> distance < 6 && roll != 0 ? sweep(target, "Большой меч", 5.5, 0.15, 9f, ParticleTypes.FLAME, victim -> victim.setOnFireFor(3))
                    : roll == 2 ? deathTread("Топот войны", ParticleTypes.FLAME, victim -> { })
                    : charge(target, "Натиск войны", 10f, ParticleTypes.FLAME);
            case 3 -> roll == 0 ? weigh(target) : roll == 1 ? measure(target)
                    : distance > 6 ? charge(target, "Вороной натиск", 8f, ParticleTypes.LARGE_SMOKE) : weigh(target);
            default -> roll == 0 && followers.size() < MAX_FOLLOWERS ? hadesFollows(target)
                    : distance < 7 ? sweep(target, "Коса смерти", 6, -0.5, 9f, ParticleTypes.SOUL_FIRE_FLAME,
                            victim -> victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 60, 0)))
                    : roll == 1 ? charge(target, "Бледный натиск", 8f, ParticleTypes.SOUL_FIRE_FLAME)
                    : deathTread("Поступь смерти", ParticleTypes.SOUL_FIRE_FLAME, victim -> victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 60, 0)));
        };
    }

    /** A charge: a dotted line on the ground toward the foe, then the horse runs it down, trampling whoever stands on it. */
    private int charge(LivingEntity target, String name, float damage, ParticleEffect trail) {
        Vec3d from = getPos();
        Vec3d flat = target.getPos().subtract(from).multiply(1, 0, 1);
        if (flat.lengthSquared() < 1e-3) flat = getRotationVector().multiply(1, 0, 1);
        Vec3d dir = flat.normalize();
        double length = clearRun(from, dir, Math.min(18, flat.length() + 5));
        if (length < 3) return volleyOrSweep(target);
        float yaw = (float) (MathHelper.atan2(dir.z, dir.x) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        cast(name, 22, true, left -> {
            if (left % 2 == 0) for (double s = 1; s <= length; s += 0.8) {
                Vec3d at = from.add(dir.multiply(s));
                particles(trail, at.x, at.y + 0.15, at.z, 1, 0.05, 0, 0.05, 0);
            }
            setYaw(yaw); bodyYaw = yaw; headYaw = yaw;
            if (left == 22) sound(SoundEvents.ENTITY_HORSE_ANGRY, 2.5f, 0.6f);
            if (left % 5 == 0) particles(ParticleTypes.CLOUD, getX(), getY() + 0.1, getZ(), 4, 0.6, 0, 0.6, 0.02);
        }, () -> run(dir, length, damage, trail));
        return 70;
    }

    private int volleyOrSweep(LivingEntity target) {
        return phase == 1 ? volley(target) : sweep(target, phase == 4 ? "Коса смерти" : "Большой меч", 5.5, 0.1, 8f, ParticleTypes.SWEEP_ATTACK, victim -> { });
    }

    /** How far the horse can run along {@code dir} before a wall stops it. */
    private double clearRun(Vec3d from, Vec3d dir, double max) {
        World world = getWorld();
        for (double s = 0.5; s <= max; s += 0.5) {
            Vec3d at = from.add(dir.multiply(s));
            for (double up : new double[] { getHeight() * 0.25, getHeight() * 0.5 }) {
                BlockPos pos = BlockPos.ofFloored(at.x, at.y + up, at.z);
                if (!world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()) return s - 1;
            }
        }
        return max;
    }

    private void run(Vec3d dir, double length, float damage, ParticleEffect trail) {
        setStance(STANCE_CHARGE);
        sound(SoundEvents.ENTITY_RAVAGER_ROAR, 1.5f, 1.2f);
        int steps = (int) Math.ceil(length / 1.1);
        Set<LivingEntity> struck = new HashSet<>();
        hazards.repeat(1, steps, step -> {
            getNavigation().stop();
            setVelocity(dir.x * 1.1, getVelocity().y, dir.z * 1.1);
            velocityModified = true;
            particles(trail, getX(), getY() + 0.4, getZ(), 6, 0.6, 0.3, 0.6, 0.02);
            particles(ParticleTypes.CLOUD, getX(), getY() + 0.1, getZ(), 3, 0.5, 0, 0.5, 0.02);
            if (step % 2 == 0) sound(SoundEvents.ENTITY_HORSE_GALLOP, 2f, 0.6f);
            for (LivingEntity victim : victims(getPos(), getWidth() * 0.5 + 1.6))
                if (struck.add(victim) && hurt(victim, damage)) {
                    push(victim, getPos().subtract(dir), 1.3, 0.45);
                    sound(SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 2f, 0.6f);
                }
            if (step == steps - 1) { setStance(STANCE_NONE); setVelocity(getVelocity().multiply(0.3, 1, 0.3)); }
        });
    }

    /** An arc in front of him ({@code cosHalf} is the cosine of its half-angle): marked on the ground, then swept by the blade. */
    private int sweep(LivingEntity target, String name, double radius, double cosHalf, float damage, ParticleEffect mark, Consumer<LivingEntity> extra) {
        Vec3d center = getPos();
        Vec3d flat = target.getPos().subtract(center).multiply(1, 0, 1);
        Vec3d dir = flat.lengthSquared() < 1e-3 ? getRotationVector().multiply(1, 0, 1).normalize() : flat.normalize();
        double half = Math.acos(MathHelper.clamp(cosHalf, -1, 1));
        cast(name, 18, true, left -> {
            if (left % 3 == 0) for (double r = 1.5; r <= radius; r += 1.1) arc(center, dir, half, r, mark, 0.15);
            if (left == 18) sound(SoundEvents.ITEM_TRIDENT_RIPTIDE_1.value(), 1.5f, 0.6f);
        }, () -> {
            swingHand(Hand.MAIN_HAND);
            sound(SoundEvents.ENTITY_PLAYER_ATTACK_SWEEP, 2.5f, 0.5f);
            arc(center, dir, half, radius * 0.7, ParticleTypes.SWEEP_ATTACK, 1.2);
            for (LivingEntity victim : victims(center, radius + 1)) {
                Vec3d to = victim.getPos().subtract(center).multiply(1, 0, 1);
                if (to.length() > radius + 0.5) continue;
                if (to.lengthSquared() > 1 && to.normalize().dotProduct(dir) < cosHalf) continue;
                if (hurt(victim, damage)) { push(victim, center, 1.0, 0.3); extra.accept(victim); }
            }
        });
        return 60;
    }

    private void arc(Vec3d center, Vec3d dir, double half, double radius, ParticleEffect particle, double y) {
        double base = Math.atan2(dir.z, dir.x);
        int points = (int) Math.max(4, half * radius * 2.5);
        for (int i = 0; i <= points; i++) {
            double angle = base - half + 2 * half * i / points;
            particles(particle, center.x + Math.cos(angle) * radius, center.y + y, center.z + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
        }
    }

    /** «Стрелы победы»: light gathers on the bow, then five (seven at high power) arrows of light fly in a fan. */
    private int volley(LivingEntity target) {
        int count = power >= 4 ? 7 : 5;
        cast("Стрелы победы", 20, true, left -> {
            Vec3d hand = handPos();
            particles(ParticleTypes.END_ROD, hand.x, hand.y, hand.z, 2, 0.15, 0.3, 0.15, 0.01);
            if (left == 20) sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 1.5f, 1.6f);
        }, () -> {
            Vec3d hand = handPos();
            Vec3d aim = target.getEyePos().subtract(0, 0.4, 0).subtract(hand).normalize();
            for (int i = 0; i < count; i++) {
                double spread = (i - (count - 1) / 2.0) * 0.14;
                Vec3d dir = new Vec3d(aim.x * Math.cos(spread) - aim.z * Math.sin(spread), aim.y, aim.x * Math.sin(spread) + aim.z * Math.cos(spread));
                hazards.orb(hand, dir.multiply(0.9), 40, 0.35, ParticleTypes.END_ROD, victim -> {
                    hurt(victim, 5f);
                    particles(ParticleTypes.FLASH, victim.getX(), victim.getBodyY(0.5), victim.getZ(), 1, 0, 0, 0, 0);
                });
            }
            swingHand(Hand.OFF_HAND);
            sound(SoundEvents.ENTITY_ARROW_SHOOT, 2f, 0.6f);
        });
        return 55;
    }

    /** «Венец победы»: a circle of light under the foe and six crown points around it; a moment later they burn. */
    private int crownOfLight(LivingEntity target) {
        Vec3d center = target.getPos();
        cast("Венец победы", 28, false, left -> { if (left % 7 == 0) sound(SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 2f, 0.8f + 0.1f * (28 - left) / 7); }, () -> { });
        sound(SoundEvents.BLOCK_BEACON_ACTIVATE, 2f, 1.4f);
        hazards.zone(center, 2.5, 30, ParticleTypes.END_ROD, at -> pillar(at, ParticleTypes.END_ROD), victim -> {
            if (hurt(victim, 8f)) {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 200, 0));
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, 0));
            }
        });
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI * 2 * i / 6;
            Vec3d point = center.add(Math.cos(angle) * 5, 0, Math.sin(angle) * 5);
            hazards.zone(point, 1.6, 30, GOLD_DUST, at -> pillar(at, GOLD_DUST), victim -> hurt(victim, 6f));
        }
        return 75;
    }

    /** «Весы»: a pan of the scales hangs over the foe and comes down; a second pan follows. The hungry weigh less and are
     * found wanting: the blow is heavier for them. */
    private int weigh(LivingEntity target) {
        cast("Весы", 26, false, left -> {
            Vec3d hand = handPos();
            particles(GOLD_DUST, hand.x, hand.y, hand.z, 2, 0.3, 0.1, 0.3, 0);
            if (left % 8 == 0) sound(SoundEvents.BLOCK_CHAIN_PLACE, 2f, 0.6f);
        }, () -> { });
        pan(target.getPos());
        hazards.later(16, () -> { if (target.isAlive()) pan(target.getPos()); });
        return 80;
    }

    private void pan(Vec3d center) {
        hazards.zone(center, 2.6, 26, SEAL_DUST[2], at -> {
            for (int i = 0; i < 12; i++) particles(GOLD_DUST, at.x, at.y + 6 - i * 0.5, at.z, 4, 1.2, 0, 1.2, 0);
            particles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.2, at.z, 20, 1.5, 0.1, 1.5, 0.02);
            getWorld().playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 1.4f, 0.5f);
        }, victim -> {
            int food = victim instanceof PlayerEntity player ? player.getHungerManager().getFoodLevel() : 20;
            if (hurt(victim, 5f + (20 - food) * 0.3f)) {
                victim.setVelocity(victim.getVelocity().multiply(0.3, 0, 0.3).add(0, -0.8, 0));
                victim.velocityModified = true;
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2));
            }
        });
    }

    /** «Мера пшеницы»: three slow measures of grain; whoever they touch takes «Голод». */
    private int measure(LivingEntity target) {
        ItemStackParticleEffect wheat = new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.WHEAT));
        cast("Мера пшеницы", 22, true, left -> {
            Vec3d hand = handPos();
            particles(wheat, hand.x, hand.y, hand.z, 3, 0.2, 0.2, 0.2, 0.02);
            if (left == 22) sound(SoundEvents.BLOCK_CHAIN_PLACE, 2f, 0.5f);
        }, () -> {
            Vec3d hand = handPos();
            Vec3d aim = target.getEyePos().subtract(0, 0.4, 0).subtract(hand).normalize();
            for (int i = -1; i <= 1; i++) {
                double spread = i * 0.25;
                Vec3d dir = new Vec3d(aim.x * Math.cos(spread) - aim.z * Math.sin(spread), aim.y, aim.x * Math.sin(spread) + aim.z * Math.cos(spread));
                hazards.orb(hand, dir.multiply(0.45), 70, 0.45, wheat, victim -> {
                    hurt(victim, 5f);
                    starve(victim, 20 * 20);
                    particles(ParticleTypes.LARGE_SMOKE, victim.getX(), victim.getBodyY(0.5), victim.getZ(), 10, 0.3, 0.3, 0.3, 0.02);
                });
            }
            sound(SoundEvents.ENTITY_WITCH_THROW, 2f, 0.5f);
        });
        return 70;
    }

    /** A ring spreading from the hooves along the ground: jump over it. */
    private int deathTread(String name, ParticleEffect edge, Consumer<LivingEntity> extra) {
        cast(name, 24, true, left -> {
            if (left == 24) setStance(STANCE_REAR);
            particles(edge, getX(), getY() + 0.2, getZ(), 6, 1.2, 0.05, 1.2, 0.02);
            if (left % 8 == 0) sound(SoundEvents.ENTITY_HORSE_ANGRY, 2f, 0.5f);
        }, () -> {
            setStance(STANCE_NONE);
            Vec3d center = getPos();
            sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.2f, 0.5f);
            hazards.ring(center, 0.35, 13, 1.1, edge, victim -> { if (hurt(victim, 6f)) { push(victim, center, 0.6, 0.3); extra.accept(victim); } });
        });
        return 75;
    }

    /** «Ад следует за ним»: souls gather on the ground around the foe, then the dead climb out. */
    private int hadesFollows(LivingEntity target) {
        if (!(getWorld() instanceof ServerWorld world)) return 40;
        List<Vec3d> spots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double angle = random.nextDouble() * Math.PI * 2, distance = 2.5 + random.nextDouble() * 2.5;
            spots.add(target.getPos().add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance));
        }
        cast("Ад следует за ним", 30, true, left -> {
            for (Vec3d spot : spots) {
                particles(ParticleTypes.SOUL, spot.x, spot.y + 0.2, spot.z, 2, 0.4, 0.05, 0.4, 0.01);
                particles(ParticleTypes.LARGE_SMOKE, spot.x, spot.y + 0.2, spot.z, 1, 0.3, 0.05, 0.3, 0.01);
            }
            if (left % 10 == 0) sound(SoundEvents.ENTITY_WITHER_SKELETON_AMBIENT, 2f, 0.5f);
        }, () -> {
            sound(SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, 2f, 0.5f);
            for (Vec3d spot : spots) raiseFollower(world, spot, 0);
        });
        return 100;
    }

    // ---- Auras. ----

    /** «Война»: the hostile mobs around turn on each other and on the player; with none about, angry illagers come. */
    private void warAura(ServerWorld world, double radius) {
        List<HostileEntity> mobs = world.getEntitiesByClass(HostileEntity.class, getBoundingBox().expand(radius), mob -> mob.isAlive() && !(mob instanceof SacredBoss));
        if (mobs.size() < 2) {
            for (int i = 0; i < 3; i++) summon(world, random.nextBoolean() ? EntityType.VINDICATOR : EntityType.PILLAGER, getPos(), 4,
                    Text.literal("Воин").formatted(Formatting.RED), ParticleTypes.ANGRY_VILLAGER);
            mobs = world.getEntitiesByClass(HostileEntity.class, getBoundingBox().expand(radius), mob -> mob.isAlive() && !(mob instanceof SacredBoss));
        }
        List<ServerPlayerEntity> players = playersNear(radius + 8).stream().filter(p -> !p.isCreative()).toList();
        int turned = 0;
        for (HostileEntity mob : mobs) {
            LivingEntity pick = null;
            if (mobs.size() > 1 && (players.isEmpty() || random.nextInt(3) != 0)) {
                HostileEntity other = mobs.get(random.nextInt(mobs.size()));
                if (other != mob) pick = other;
            }
            if (pick == null && !players.isEmpty()) pick = players.get(random.nextInt(players.size()));
            if (pick == null) continue;
            mob.setTarget(pick);
            particles(ParticleTypes.ANGRY_VILLAGER, mob.getX(), mob.getEyeY() + 0.4, mob.getZ(), 3, 0.3, 0.2, 0.3, 0);
            turned++;
        }
        sound(SoundEvents.EVENT_RAID_HORN.value(), 1.5f, 0.8f);
        for (ServerPlayerEntity player : players) player.sendMessage(Text.literal("Война: да убивают друг друга").formatted(Formatting.RED), true);
        debug("Война: озлоблено " + turned + " из " + mobs.size());
    }

    /** «Голод» on everyone around who is not already starving (or on all, when {@code all}). */
    private void famine(double radius, int ticks, boolean all) {
        for (LivingEntity victim : victims(getPos(), radius))
            if (all || !victim.hasStatusEffect(BossEffects.FAMINE)) starve(victim, ticks);
        particles(new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.WHEAT)), getX(), getY() + getHeight() * 0.45, getZ(), 40, radius * 0.3, 1, radius * 0.3, 0.05);
    }

    private void starve(LivingEntity victim, int ticks) {
        boolean had = victim.hasStatusEffect(BossEffects.FAMINE);
        BossEffects.afflict(victim, BossEffects.FAMINE, ticks);
        victim.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, Math.min(ticks, 200), 1));
        if (!had) victim.sendMessage(Text.literal("Голод: хиникс пшеницы за динарий — еда гниёт в котомке").formatted(Formatting.DARK_GRAY));
    }

    /** «Смерть»: everyone within a few blocks withers (a short, weak wither, renewed only when it runs low). */
    private void witherAura(double radius, boolean strike) {
        int points = 28;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2 * i / points + age * 0.02;
            particles(ParticleTypes.SOUL, getX() + Math.cos(angle) * radius, getY() + 0.2, getZ() + Math.sin(angle) * radius, 1, 0, 0.05, 0, 0);
        }
        if (!strike) return;
        for (LivingEntity victim : victims(getPos(), radius)) {
            StatusEffectInstance wither = victim.getStatusEffect(StatusEffects.WITHER);
            if (wither == null || wither.getDuration() < 30) victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 80, power >= 5 ? 1 : 0));
        }
    }

    // ---- Followers. ----

    private void raiseFollower(ServerWorld world, Vec3d near, double spread) {
        boolean wither = random.nextInt(3) == 0;
        MobEntity dead = summon(world, wither ? EntityType.WITHER_SKELETON : EntityType.SKELETON, near, spread,
                Text.literal("Из ада").formatted(Formatting.DARK_GRAY), ParticleTypes.SOUL);
        if (dead != null) world.spawnParticles(ParticleTypes.LARGE_SMOKE, dead.getX(), dead.getY() + 0.5, dead.getZ(), 20, 0.4, 0.4, 0.4, 0.02);
    }

    /** A follower of the riders near {@code near}; never more than {@link #MAX_FOLLOWERS} alive. */
    private MobEntity summon(ServerWorld world, EntityType<? extends MobEntity> type, Vec3d near, double spread, Text name, ParticleEffect sign) {
        followers.removeIf(mob -> !mob.isAlive() || mob.isRemoved());
        if (followers.size() >= MAX_FOLLOWERS) return null;
        double angle = random.nextDouble() * Math.PI * 2;
        BlockPos at = DivineBosses.room(world, BlockPos.ofFloored(near.x + Math.cos(angle) * spread, near.y, near.z + Math.sin(angle) * spread), 2);
        if (at == null) return null;
        MobEntity mob = type.create(world);
        if (mob == null) return null;
        mob.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, random.nextFloat() * 360f, 0);
        mob.initialize(world, world.getLocalDifficulty(at), SpawnReason.MOB_SUMMONED, null);
        for (EquipmentSlot slot : EquipmentSlot.values()) mob.setEquipmentDropChance(slot, 0f);
        mob.setCustomName(name);
        mob.setTarget(getTarget());
        world.spawnEntity(mob);
        followers.add(mob);
        world.spawnParticles(sign, mob.getX(), mob.getY() + 1, mob.getZ(), 12, 0.4, 0.6, 0.4, 0.02);
        return mob;
    }

    private void dismissFollowers() {
        for (MobEntity mob : followers)
            if (mob.isAlive()) {
                if (mob.getWorld() instanceof ServerWorld world)
                    world.spawnParticles(ParticleTypes.SOUL, mob.getX(), mob.getY() + 1, mob.getZ(), 10, 0.3, 0.5, 0.3, 0.02);
                mob.discard();
            }
        followers.clear();
    }

    // ---- Ultimate. ----

    /** «Снятие печатей»: four seals circle above him while the horse rears; then the rider's own signature, and the four
     * seals fall on the foe one after another, each with its rider's harm. */
    @Override protected void ultimate(LivingEntity target) {
        int rider = rider();
        castUltimate(left -> {
            for (int i = 0; i < 4; i++) {
                double angle = age * 0.15 + i * Math.PI / 2;
                particles(SEAL_DUST[i], getX() + Math.cos(angle) * 3.2, getY() + getHeight() * 0.9, getZ() + Math.sin(angle) * 3.2, 3, 0.1, 0.1, 0.1, 0);
            }
            if (left % 15 == 0) sound(SoundEvents.BLOCK_BELL_RESONATE, 2.5f, 0.5f + 0.1f * (4 - left / 15));
            if (left == 30) setStance(STANCE_REAR);
        }, () -> {
            setStance(STANCE_NONE);
            sound(SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, 2f, 0.6f);
            signature(rider, target);
            hazards.repeat(16, 4, seal -> sealFalls(seal, target));
        });
    }

    /** The current rider's part of the ultimate. */
    private void signature(int rider, LivingEntity target) {
        if (!(getWorld() instanceof ServerWorld world)) return;
        switch (rider) {
            case 0 -> {
                // Arrows of light rain on marked spots around the foe.
                for (int i = 0; i < 8; i++) {
                    Vec3d spot = target.getPos().add(random.nextDouble() * 10 - 5, 0, random.nextDouble() * 10 - 5);
                    hazards.zone(spot, 1.4, 20 + i * 3, ParticleTypes.END_ROD, at -> pillar(at, ParticleTypes.END_ROD), victim -> hurt(victim, 5f));
                }
            }
            case 1 -> warAura(world, 28);
            case 2 -> famine(24, 30 * 20, true);
            default -> {
                for (int i = 0; i < 3; i++) raiseFollower(world, target.getPos(), 4);
                witherAura(12, true);
            }
        }
    }

    private void sealFalls(int seal, LivingEntity target) {
        Vec3d at = target.isAlive() ? target.getPos() : getPos();
        for (ServerPlayerEntity player : playersNear(48))
            player.sendMessage(Text.literal("Печать " + (seal + 1) + ": " + NAMES[seal]).formatted(COLORS[seal]), true);
        sound(SoundEvents.BLOCK_BEACON_POWER_SELECT, 2f, 0.6f + 0.15f * seal);
        hazards.zone(at, 3.5, 24, SEAL_DUST[seal], center -> {
            pillar(center, SEAL_DUST[seal]);
            particles(ParticleTypes.FLASH, center.x, center.y + 1, center.z, 1, 0, 0, 0, 0);
            sound(SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 2f, 0.7f + 0.1f * seal);
            if (seal == 1 && getWorld() instanceof ServerWorld world) warAura(world, 16);
        }, victim -> {
            switch (seal) {
                case 0 -> {
                    if (hurt(victim, 7f)) {
                        victim.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 200, 0));
                        victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, 0));
                    }
                }
                case 1 -> { if (hurt(victim, 9f)) push(victim, at, 1.2, 0.5); }
                case 2 -> { hurt(victim, 6f); starve(victim, 20 * 20); victim.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 200, 2)); }
                default -> { if (hurt(victim, 7f)) victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 100, 1)); }
            }
        });
    }

    // ---- Effects and bookkeeping. ----

    private void pillar(Vec3d at, ParticleEffect particle) {
        for (double y = 0; y < 9; y += 0.5) particles(particle, at.x, at.y + y, at.z, 2, 0.25, 0.1, 0.25, 0);
    }

    /** The rider's hand, up on the horse's back. */
    private Vec3d handPos() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(getX() - Math.sin(yaw) * 0.6 + Math.cos(yaw) * 0.5, getY() + getHeight() * 0.7, getZ() + Math.cos(yaw) * 0.6 + Math.sin(yaw) * 0.5);
    }

    @Override protected void onDefeated() {
        dismissFollowers();
        for (ServerPlayerEntity player : playersNear(64)) {
            player.removeStatusEffect(BossEffects.FAMINE);
            player.sendMessage(Text.literal("Печати сняты — всадники уходят. Голод отступает.").formatted(Formatting.GOLD), false);
        }
    }

    @Override public void remove(RemovalReason reason) {
        super.remove(reason);
        if (reason.shouldDestroy()) dismissFollowers();
    }

    @Override public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("HorsemenAnnounced", announced);
    }
    @Override public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        announced = nbt.getInt("HorsemenAnnounced");
    }
}
