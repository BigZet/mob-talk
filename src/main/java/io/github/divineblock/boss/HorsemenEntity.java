package io.github.divineblock.boss;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.world.World;

/** Stub: its attacks and ultimate come in this boss's own change. */
public final class HorsemenEntity extends SacredBoss {
    public HorsemenEntity(EntityType<? extends HorsemenEntity> type, World world) { super(type, world, BossBar.Color.WHITE); }

    /** Event listeners for this boss's affliction (called once from {@link DivineBosses#register}). */
    static void registerHooks() { }

    @Override public BossKind kind() { return BossKind.HORSEMEN; }
    @Override protected String theme() { return "punishment_20"; }
    @Override protected int attack(LivingEntity target) { return 60; }
    @Override protected void ultimate(LivingEntity target) { castUltimate(left -> { }, () -> { }); }
}
