package io.github.divineblock.boss;

import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CampfireBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Чёрный Схимник: a monk of the great schema who has turned to darkness. Strikes with his staff, throws black candle flames,
 * steps through the dark behind his foe, bows to the ground with a blow around him, and calls novices in later phases.
 * Ultimate «Вечная схима»: a dome of darkness (12 blocks) for twelve seconds — the lights inside go out (when block damage is
 * allowed) and everyone under it takes the «Обет молчания»: no food, no drink, no totem, no prayer. */
public final class SchemnikEntity extends SacredBoss {
    static final double DOME = 12;

    public SchemnikEntity(EntityType<? extends SchemnikEntity> type, World world) { super(type, world, BossBar.Color.PURPLE); }

    /** The vow's rules live in {@link BossEffects}: nothing of its own to hook. */
    static void registerHooks() { }

    @Override public BossKind kind() { return BossKind.BLACK_SCHEMNIK; }
    @Override protected String theme() { return "punishment_39"; }
    @Override protected float meleeDamage() { return 8f; }
    @Override protected double meleeReach() { return 4.4; }

    @Override protected int attack(LivingEntity target) {
        double distance = distanceTo(target);
        int roll = random.nextInt(3);
        if (distance < 5 && roll == 0) { bow(); return 70; }
        if (distance > 7 && roll != 2 || roll == 1) { candles(target); return 80; }
        stepThroughDark(target);
        return 90;
    }

    /** «Чёрные свечи»: soul flames gather at his candle, then three (five later) slow flames fly at the foe. */
    private void candles(LivingEntity target) {
        int count = phase >= 2 ? 5 : 3;
        cast("Чёрные свечи", 24, true, left -> {
            Vec3d hand = handPos();
            particles(ParticleTypes.SOUL_FIRE_FLAME, hand.x, hand.y, hand.z, 3, 0.2, 0.2, 0.2, 0.01);
            if (left == 24) sound(SoundEvents.BLOCK_CANDLE_EXTINGUISH, 2f, 0.5f);
        }, () -> {
            Vec3d hand = handPos();
            for (int i = 0; i < count; i++) {
                double spread = (i - (count - 1) / 2.0) * 0.18;
                Vec3d aim = target.getEyePos().subtract(hand).normalize();
                Vec3d dir = new Vec3d(aim.x * Math.cos(spread) - aim.z * Math.sin(spread), aim.y, aim.x * Math.sin(spread) + aim.z * Math.cos(spread));
                hazards.orb(hand, dir.multiply(0.42), 70, 0.4, ParticleTypes.SOUL_FIRE_FLAME, victim -> {
                    if (hurt(victim, 6f)) victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 1));
                    particles(ParticleTypes.SOUL, victim.getX(), victim.getBodyY(0.5), victim.getZ(), 10, 0.3, 0.3, 0.3, 0.02);
                });
            }
            sound(SoundEvents.ENTITY_EVOKER_CAST_SPELL, 2f, 0.6f);
        });
    }

    /** «Земной поклон»: he bows; the ground around him (4.5 blocks) is marked, then struck. */
    private void bow() {
        Vec3d center = getPos();
        cast("Земной поклон", 22, true, left -> { if (left == 22) sound(SoundEvents.ENTITY_WARDEN_ATTACK_IMPACT, 1.5f, 0.5f); }, () -> { });
        hazards.zone(center, 4.5, 22, ParticleTypes.SQUID_INK, at -> {
            particles(ParticleTypes.LARGE_SMOKE, at.x, at.y + 0.3, at.z, 40, 2, 0.2, 2, 0.05);
            sound(SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 1.2f, 0.6f);
        }, victim -> { if (hurt(victim, 7f)) push(victim, center, 1.1, 0.35); });
    }

    /** «Шаг сквозь тьму»: ink pools behind the foe; a second later he stands there, and the ground around him is struck. */
    private void stepThroughDark(LivingEntity target) {
        Vec3d behind = target.getPos().subtract(target.getRotationVector().multiply(3, 0, 3));
        BlockPos ground = BlockPos.ofFloored(behind);
        if (!getWorld().getBlockState(ground).getCollisionShape(getWorld(), ground).isEmpty()) behind = target.getPos();
        Vec3d to = behind;
        cast("Шаг сквозь тьму", 26, true, left -> {
            particles(ParticleTypes.SQUID_INK, to.x, to.y + getHeight() * 0.3, to.z, 5, 0.4, getHeight() * 0.25, 0.4, 0.01);
            particles(ParticleTypes.LARGE_SMOKE, getX(), getY() + getHeight() * 0.3, getZ(), 3, 0.5, getHeight() * 0.25, 0.5, 0.01);
        }, () -> {
            particles(ParticleTypes.LARGE_SMOKE, getX(), getY() + getHeight() * 0.4, getZ(), 40, 0.6, getHeight() * 0.3, 0.6, 0.02);
            requestTeleport(to.x, to.y, to.z);
            sound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.5f);
            hazards.zone(to, 2.6, 14, ParticleTypes.SOUL_FIRE_FLAME, at -> particles(ParticleTypes.SWEEP_ATTACK, at.x, at.y + 1, at.z, 6, 1.2, 0.3, 1.2, 0),
                    victim -> { if (hurt(victim, 9f)) push(victim, to, 0.7, 0.25); });
        });
    }

    @Override protected void ultimate(LivingEntity target) {
        Vec3d center = getPos();
        castUltimate(left -> {
            // The dome gathers: ink rises on its future border, darkness flows into him.
            for (int i = 0; i < 10; i++) {
                double yaw = random.nextDouble() * Math.PI * 2, pitch = random.nextDouble() * Math.PI / 2;
                particles(ParticleTypes.SQUID_INK, center.x + Math.cos(yaw) * Math.cos(pitch) * DOME, center.y + Math.sin(pitch) * DOME,
                        center.z + Math.sin(yaw) * Math.cos(pitch) * DOME, 1, 0, 0, 0, 0);
            }
            particles(ParticleTypes.SOUL, getX(), getY() + getHeight() * 0.55, getZ(), 3, 1, 1, 1, 0.05);
            if (left % 20 == 0) sound(SoundEvents.BLOCK_BELL_RESONATE, 2f, 0.4f);
        }, () -> eternalSchema(center));
    }

    private void eternalSchema(Vec3d center) {
        sound(SoundEvents.ENTITY_WITHER_SPAWN, 1.2f, 0.5f);
        int out = mayBreakBlocks() ? extinguish(center) : 0;
        debug("Вечная схима: погашено огней " + out);
        hazards.repeat(10, 24, step -> {
            for (int i = 0; i < 70; i++) {
                double yaw = random.nextDouble() * Math.PI * 2, pitch = random.nextDouble() * Math.PI / 2;
                particles(i % 3 == 0 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SQUID_INK, center.x + Math.cos(yaw) * Math.cos(pitch) * DOME,
                        center.y + Math.sin(pitch) * DOME, center.z + Math.sin(yaw) * Math.cos(pitch) * DOME, 1, 0, 0, 0, 0);
            }
            for (LivingEntity victim : victims(center, DOME)) {
                // A light in hand keeps the vow off (but not the dark): the counter to the dome.
                if (holdsLight(victim)) {
                    if (step == 0 && victim instanceof net.minecraft.entity.player.PlayerEntity player) player.sendMessage(Text.literal("Свет в руке хранит от обета").formatted(Formatting.YELLOW), true);
                    continue;
                }
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 70, 0, false, false));
                if (!victim.hasStatusEffect(BossEffects.VOW_OF_SILENCE)) {
                    BossEffects.afflict(victim, BossEffects.VOW_OF_SILENCE, 20 * 20);
                    victim.sendMessage(Text.literal("Обет молчания: ни есть, ни пить, ни молиться").formatted(Formatting.DARK_GRAY));
                }
            }
        });
    }

    /** Puts out the lights under the dome: torches and lanterns fall, candles and campfires go dark. */
    private int extinguish(Vec3d center) {
        if (!(getWorld() instanceof ServerWorld world)) return 0;
        int out = 0;
        BlockPos middle = BlockPos.ofFloored(center);
        int r = (int) DOME;
        for (BlockPos pos : BlockPos.iterate(middle.add(-r, -3, -r), middle.add(r, r, r))) {
            if (pos.getSquaredDistance(middle) > DOME * DOME) continue;
            BlockState state = world.getBlockState(pos);
            if (state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH) || state.isOf(Blocks.SOUL_TORCH) || state.isOf(Blocks.SOUL_WALL_TORCH)
                    || state.isOf(Blocks.LANTERN) || state.isOf(Blocks.SOUL_LANTERN)) {
                world.breakBlock(pos, true, this);
                out++;
            } else if ((state.getBlock() instanceof AbstractCandleBlock || state.getBlock() instanceof CampfireBlock || state.isIn(BlockTags.CANDLE_CAKES))
                    && state.contains(Properties.LIT) && state.get(Properties.LIT)) {
                world.setBlockState(pos, state.with(Properties.LIT, false));
                world.spawnParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 4, 0.1, 0.1, 0.1, 0.01);
                out++;
            } else if (state.isOf(Blocks.JACK_O_LANTERN)) {
                world.setBlockState(pos, Blocks.CARVED_PUMPKIN.getStateWithProperties(state));
                out++;
            }
        }
        return out;
    }

    // ---- The weak point: fire and light. ----

    /** Darkness fears fire: a burning arrow, a burning blade (he catches fire) or a light in the striking hand open him. */
    @Override protected boolean weakTrigger(net.minecraft.entity.damage.DamageSource source, float amount) {
        if (source.isIn(net.minecraft.registry.tag.DamageTypeTags.IS_FIRE)) return true;
        if (source.getSource() instanceof net.minecraft.entity.projectile.ProjectileEntity projectile && projectile.isOnFire()) return true;
        return source.getAttacker() instanceof LivingEntity attacker && source.getSource() == attacker && holdsLight(attacker);
    }
    /** A torch, a lantern or a candle in either hand. */
    static boolean holdsLight(LivingEntity who) {
        for (ItemStack stack : new ItemStack[] { who.getMainHandStack(), who.getOffHandStack() })
            if (stack.isOf(Items.TORCH) || stack.isOf(Items.SOUL_TORCH) || stack.isOf(Items.LANTERN) || stack.isOf(Items.SOUL_LANTERN)
                    || stack.isIn(net.minecraft.registry.tag.ItemTags.CANDLES)) return true;
        return false;
    }
    @Override protected String weakHint() { return "тьма боится огня — горящая стрела, Заговор огня или факел в левой руке"; }
    @Override protected String weakReason() { return "Огонь опалил схиму — бей!"; }
    @Override protected String ultHint() { return "выбеги из купола (12 блоков) или держи свет в руке — он хранит от обета"; }
    @Override protected String phaseName(int phase) { return phase == 2 ? "Послушники" : "Великая схима"; }
    @Override protected Vec3d weakSpot() { return handPos(); }
    @Override public void provokeWeakPoint(net.minecraft.server.network.ServerPlayerEntity player) { testArrow(player, 0.6, true); }

    @Override protected void onPhase(int phase) {
        sound(SoundEvents.ENTITY_EVOKER_PREPARE_SUMMON, 2f, 0.5f);
        for (int i = 0; i < 2; i++) novice();
        if (phase == 3) addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, StatusEffectInstance.INFINITE, 0, false, false));
    }

    /** A novice in black: a skeleton with a stone sword. */
    private void novice() {
        if (!(getWorld() instanceof ServerWorld world)) return;
        SkeletonEntity novice = EntityType.SKELETON.create(world);
        if (novice == null) return;
        double angle = random.nextDouble() * Math.PI * 2;
        BlockPos at = DivineBosses.room(world, BlockPos.ofFloored(getX() + Math.cos(angle) * 3.5, getY(), getZ() + Math.sin(angle) * 3.5), 2);
        if (at == null) return;
        novice.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, getYaw(), 0);
        ItemStack robe = new ItemStack(Items.LEATHER_CHESTPLATE), hood = new ItemStack(Items.LEATHER_HELMET);
        robe.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(0x18141C, false));
        hood.set(DataComponentTypes.DYED_COLOR, new DyedColorComponent(0x18141C, false));
        novice.equipStack(EquipmentSlot.CHEST, robe);
        novice.equipStack(EquipmentSlot.HEAD, hood);
        novice.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
        for (EquipmentSlot slot : EquipmentSlot.values()) novice.setEquipmentDropChance(slot, 0f);
        novice.setCustomName(Text.literal("Послушник").formatted(Formatting.DARK_GRAY));
        novice.setTarget(getTarget());
        world.spawnEntity(novice);
        world.spawnParticles(ParticleTypes.LARGE_SMOKE, novice.getX(), novice.getY() + 1, novice.getZ(), 20, 0.3, 0.8, 0.3, 0.02);
    }

    @Override protected void onDefeated() {
        for (LivingEntity victim : victims(getPos(), 32)) victim.removeStatusEffect(BossEffects.VOW_OF_SILENCE);
    }

    /** The candle in his left hand, held out before his chest (scaled with the model). */
    private Vec3d handPos() {
        float yaw = bodyYaw * MathHelper.RADIANS_PER_DEGREE;
        double side = getWidth() * 0.45, ahead = getWidth() * 0.85;
        return new Vec3d(getX() + Math.cos(yaw) * side - Math.sin(yaw) * ahead, getY() + getHeight() * 0.6,
                getZ() + Math.sin(yaw) * side + Math.cos(yaw) * ahead);
    }
}
