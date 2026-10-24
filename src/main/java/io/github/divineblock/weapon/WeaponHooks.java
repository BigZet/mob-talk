package io.github.divineblock.weapon;

import io.github.divineblock.boss.BeastEntity;
import io.github.divineblock.boss.DestroyingAngelEntity;
import io.github.divineblock.boss.LeviathanEntity;
import io.github.divineblock.boss.WailerEntity;
import io.github.divineblock.cemetery.CemeteryBlocks;
import io.github.divineblock.core.Righteousness;
import io.github.divineblock.game.DeedWatcher;
import io.github.divineblock.game.Fx;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.EntityTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

/** The weapons' rules that live outside a blow: what a weapon does while it is merely held (the archistrategos' weight on a
 * sinner, the war sword's unrest), what an arrow of Nimrod's bow does to a beast, what a kill with a stake or the war
 * sword means to the god, the rout of Gideon's pitchers, and the merchants' refusal of the sword of war. */
public final class WeaponHooks {
    private static final Set<MobEntity> ROUTED = new HashSet<>();
    private static int ticks;
    private static Set<Block> graveBlocks;

    private WeaponHooks() { }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(WeaponHooks::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ROUTED.clear());
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            // Nimrod's arrows in a beast: twofold.
            if (!blocked && damageTaken > 0 && entity instanceof AnimalEntity && nimrodArrow(source) && source.getAttacker() instanceof LivingEntity archer && DivineWeapons.enabled(WeaponKind.NIMROD_BOW)) {
                DivineWeapons.extra(entity, archer, damageTaken);
                if (entity.getWorld() instanceof ServerWorld world) Fx.sparks(world, entity.getPos().add(0, entity.getHeight() * 0.6, 0), 0xFFC08040, 0.5f);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity.getWorld() instanceof ServerWorld world) || !(source.getAttacker() instanceof ServerPlayerEntity player)) return;
            ItemStack held = player.getMainHandStack();
            if (entity instanceof AnimalEntity && nimrodArrow(source) && DivineWeapons.enabled(WeaponKind.NIMROD_BOW)) {
                // Pride before the god: a beast slain for sport counts twice.
                DeedWatcher.record(player, Righteousness.Deed.SLAUGHTER, "луком Нимрода");
                DeedWatcher.record(player, Righteousness.Deed.SLAUGHTER, "луком Нимрода");
                Fx.smoke(world, player.getPos().add(0, 2.2, 0), 0xFF201810, 1.2f);
                player.sendMessage(Text.literal("Сильный зверолов пред Господом… бог видит гордыню").formatted(Formatting.GRAY), true);
            }
            if (held.isOf(DivineWeapons.ASPEN_STAKE) && unclean(entity) && DivineWeapons.enabled(WeaponKind.ASPEN_STAKE)) {
                Fx.smoke(world, entity.getPos().add(0, 0.5, 0), 0xFF9A9A9A, 1f);
                world.playSound(null, entity.getX(), entity.getY(), entity.getZ(), SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 0.5f, 0.6f);
                player.sendMessage(Text.literal("Упокоен").formatted(Formatting.GRAY), true);
            }
            if (held.isOf(DivineWeapons.WAR_SWORD) && entity instanceof VillagerEntity && DivineWeapons.enabled(WeaponKind.WAR_SWORD)) {
                DeedWatcher.record(player, Righteousness.Deed.VANDALISM, "мечом Войны");
                DeedWatcher.record(player, Righteousness.Deed.VANDALISM, "мечом Войны");
                Fx.smoke(world, entity.getPos().add(0, 0.5, 0), 0xFF400808, 1.5f);
            }
        });
        // No merchant trades with the sword of war in hand.
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (entity instanceof MerchantEntity && player.getMainHandStack().isOf(DivineWeapons.WAR_SWORD) && DivineWeapons.enabled(WeaponKind.WAR_SWORD)) {
                if (!world.isClient) player.sendMessage(Text.literal("С мечом Войны в руке с тобой не торгуют").formatted(Formatting.GOLD), true);
                if (entity instanceof VillagerEntity villager && !world.isClient) villager.getBrain().remember(MemoryModuleType.NEAREST_HOSTILE, (LivingEntity) player, 100L);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }

    static boolean nimrodArrow(DamageSource source) {
        return source.getSource() instanceof PersistentProjectileEntity arrow && arrow.getWeaponStack() != null && arrow.getWeaponStack().isOf(DivineWeapons.NIMROD_BOW);
    }

    /** The unclean: the undead, and the god's own dark ones (the Beast, the destroying angel, the wailer and her dead). */
    public static boolean unclean(Entity entity) {
        return entity.getType().isIn(EntityTypeTags.UNDEAD) || entity instanceof BeastEntity || entity instanceof DestroyingAngelEntity || entity instanceof WailerEntity;
    }

    /** Serpents, for the spear of St George: Leviathan, the Beast from the abyss, the dragon, the wither. */
    public static boolean serpent(Entity entity) {
        return entity instanceof LeviathanEntity || entity instanceof BeastEntity || entity instanceof EnderDragonEntity || entity instanceof WitherEntity;
    }

    /** Whether a spot is on one of the god's cemeteries: a grave of the mod's own within six blocks. */
    public static boolean onCemetery(ServerWorld world, BlockPos at) {
        if (graveBlocks == null) graveBlocks = new HashSet<>(CemeteryBlocks.all());
        for (BlockPos pos : BlockPos.iterate(at.add(-6, -2, -6), at.add(6, 3, 6))) if (graveBlocks.contains(world.getBlockState(pos).getBlock())) return true;
        return false;
    }

    /** A mob under Gideon's rout: it turns on the nearest other mob and keeps turning while the rout lasts. */
    static void routTarget(MobEntity mob) {
        ROUTED.add(mob);
        retarget(mob);
    }

    private static void retarget(MobEntity mob) {
        if (!(mob.getWorld() instanceof ServerWorld world)) return;
        MobEntity nearest = null;
        double best = 8 * 8;
        for (MobEntity other : world.getEntitiesByClass(MobEntity.class, mob.getBoundingBox().expand(8), o -> o != mob && o.isAlive() && o instanceof HostileEntity)) {
            double d = other.squaredDistanceTo(mob);
            if (d < best) { best = d; nearest = other; }
        }
        if (nearest != null) mob.setTarget(nearest);
    }

    private static void tick(MinecraftServer server) {
        if (++ticks % 10 != 0) return;
        // The rout: every half second the routed look for a new foe among their own.
        if (!ROUTED.isEmpty()) {
            List<MobEntity> gone = new ArrayList<>();
            for (MobEntity mob : ROUTED) {
                if (!mob.isAlive() || !mob.hasStatusEffect(DivineWeapons.ROUT)) { gone.add(mob); continue; }
                if (mob.getTarget() == null || mob.getTarget() instanceof PlayerEntity || ticks % 40 == 0) retarget(mob);
            }
            ROUTED.removeAll(gone);
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            ItemStack held = player.getMainHandStack();
            WeaponKind kind = DivineWeapons.kindOf(held);
            if (kind == null || !DivineWeapons.enabled(kind)) continue;
            ServerWorld world = player.getServerWorld();
            switch (kind) {
                case ARCHISTRATEGOS_SWORD -> {
                    // Heavy in a sinner's hands, light in a saint's.
                    if (DivineWeapons.sinner(player)) {
                        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 0, false, false));
                        if (ticks % 500 == 0) player.sendMessage(Text.literal("Меч архистратига тяжёл в руках грешника").formatted(Formatting.GOLD), true);
                    } else if (DivineWeapons.saint(player)) {
                        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 30, 0, false, false));
                    }
                }
                case WAR_SWORD -> {
                    // Peace taken from the earth: the hostile grow stronger and come, the villagers run.
                    for (HostileEntity foe : world.getEntitiesByClass(HostileEntity.class, player.getBoundingBox().expand(16), LivingEntity::isAlive)) {
                        foe.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, 40, 0, false, false));
                        if (foe.getTarget() == null && !player.isCreative() && !player.isSpectator() && foe.canTarget(player)) foe.setTarget(player);
                    }
                    for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(12), LivingEntity::isAlive)) {
                        villager.getBrain().remember(MemoryModuleType.NEAREST_HOSTILE, (LivingEntity) player, 60L);
                        if (ticks % 20 == 0) world.spawnParticles(ParticleTypes.ANGRY_VILLAGER, villager.getX(), villager.getY() + villager.getHeight() + 0.3, villager.getZ(), 2, 0.3, 0.1, 0.3, 0);
                    }
                    if (ticks % 100 == 0) Fx.ring(world, player.getPos(), Fx.BLOOD, 4f);
                }
                default -> { }
            }
        }
    }
}
