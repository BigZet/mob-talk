package io.github.divineblock.weapon;

import io.github.divineblock.boss.GoliathEntity;
import io.github.divineblock.game.Fx;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.World;

/** A stone from the sling: hurts by how hard it was slung (up to 5), five times that in Goliath, and stuns a small mob for a
 * second. A righteous slinger's stone shines gold and bursts in sparks. A snowball or egg slung instead keeps its own
 * nature as well: it is still a projectile with a thrower. */
public final class SlingStoneEntity extends ThrownItemEntity {
    private static final TrackedData<Boolean> SHINING = DataTracker.registerData(SlingStoneEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private float charge = 1f;

    public SlingStoneEntity(EntityType<? extends ThrownItemEntity> type, World world) { super(type, world); }

    public SlingStoneEntity(World world, LivingEntity owner, ItemStack item, float charge, boolean shining) {
        super(DivineWeapons.SLING_STONE_ENTITY, owner, world);
        setItem(item);
        this.charge = charge;
        dataTracker.set(SHINING, shining);
    }

    @Override protected void initDataTracker(DataTracker.Builder builder) { super.initDataTracker(builder); builder.add(SHINING, false); }
    @Override protected Item getDefaultItem() { return DivineWeapons.SLING_STONE; }
    public boolean shining() { return dataTracker.get(SHINING); }

    @Override public void tick() {
        super.tick();
        if (getWorld().isClient && shining() && age % 2 == 0) getWorld().addParticle(ParticleTypes.END_ROD, getX(), getY(), getZ(), 0, 0, 0);
    }

    @Override protected void onEntityHit(EntityHitResult hit) {
        super.onEntityHit(hit);
        if (!(getWorld() instanceof ServerWorld world)) return;
        float base = 2f + 3f * charge;
        if (hit.getEntity() instanceof GoliathEntity) base *= 5f;
        float amount = base * DivineWeapons.power();
        hit.getEntity().damage(getDamageSources().thrown(this, getOwner()), amount);
        if (hit.getEntity() instanceof LivingEntity victim) {
            if (victim.getHeight() <= 1.5f && !(victim instanceof net.minecraft.entity.player.PlayerEntity)) {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20, 3, false, false));
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 20, 0, false, false));
            }
            if (shining()) Fx.sparks(world, hit.getPos(), Fx.GOLD, 1f);
            world.playSound(null, getX(), getY(), getZ(), SoundEvents.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 0.8f, 1.2f);
        }
    }

    @Override protected void onCollision(HitResult hit) {
        super.onCollision(hit);
        if (getWorld() instanceof ServerWorld world) {
            world.spawnParticles(new net.minecraft.particle.ItemStackParticleEffect(ParticleTypes.ITEM, getStack()), getX(), getY(), getZ(), 6, 0.1, 0.1, 0.1, 0.05);
            if (hit.getType() == HitResult.Type.BLOCK) world.playSound(null, getX(), getY(), getZ(), SoundEvents.BLOCK_STONE_HIT, SoundCategory.PLAYERS, 0.8f, 1.4f);
            discard();
        }
    }

    @Override public void writeCustomDataToNbt(NbtCompound nbt) { super.writeCustomDataToNbt(nbt); nbt.putFloat("charge", charge); nbt.putBoolean("shining", shining()); }
    @Override public void readCustomDataFromNbt(NbtCompound nbt) { super.readCustomDataFromNbt(nbt); charge = nbt.getFloat("charge"); dataTracker.set(SHINING, nbt.getBoolean("shining")); }
}
