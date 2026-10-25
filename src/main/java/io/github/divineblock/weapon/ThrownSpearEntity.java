package io.github.divineblock.weapon;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** The spear of St George in flight: it strikes once (8, threefold a serpent, and pins), then comes straight back to the
 * hand that threw it, as a trident of Loyalty does; when the thrower is gone it drops where it is. */
public final class ThrownSpearEntity extends PersistentProjectileEntity {
    private boolean dealtDamage;
    private int returning;

    public ThrownSpearEntity(EntityType<? extends ThrownSpearEntity> type, World world) { super(type, world); }

    public ThrownSpearEntity(World world, LivingEntity owner, ItemStack stack) {
        super(DivineWeapons.THROWN_SPEAR, owner, world, stack.copyWithCount(1), null);
    }

    @Override protected ItemStack getDefaultItemStack() { return new ItemStack(DivineWeapons.GEORGE_SPEAR); }

    /** On the client too: whether it is flying back. */
    public boolean isReturning() { return returning > 0; }

    @Override public void tick() {
        if (inGroundTime > 4) dealtDamage = true;
        Entity owner = getOwner();
        if ((dealtDamage || isNoClip()) && owner != null) {
            if (!owner.isAlive() || owner instanceof PlayerEntity player && player.isSpectator()) {
                if (!getWorld().isClient && pickupType == PickupPermission.ALLOWED) dropStack(asItemStack(), 0.1f);
                discard();
            } else {
                setNoClip(true);
                Vec3d to = owner.getEyePos().subtract(getPos());
                setPos(getX(), getY() + to.y * 0.06, getZ());
                if (getWorld().isClient) lastRenderY = getY();
                setVelocity(getVelocity().multiply(0.9).add(to.normalize().multiply(0.2)));
                if (returning == 0) playSound(SoundEvents.ITEM_TRIDENT_RETURN, 1f, 1.2f);
                returning++;
            }
        }
        super.tick();
    }

    @Override protected void onEntityHit(EntityHitResult hit) {
        Entity struck = hit.getEntity();
        Entity owner = getOwner();
        DamageSource source = getDamageSources().trident(this, owner == null ? this : owner);
        dealtDamage = true;
        if (struck.damage(source, 8f * DivineWeapons.power())) {
            if (struck.getType() == EntityType.ENDERMAN) return;
            if (struck instanceof LivingEntity victim) {
                if (getWorld() instanceof ServerWorld world) {
                    net.minecraft.enchantment.EnchantmentHelper.onTargetDamaged(world, victim, source, getWeaponStack());
                    if (owner instanceof LivingEntity thrower) GeorgeSpearItem.strike(world, victim, thrower, 8f);
                }
                onHit(victim);
            }
        }
        setVelocity(getVelocity().multiply(-0.01, -0.1, -0.01));
        playSound(SoundEvents.ITEM_TRIDENT_HIT, 1f, 1f);
    }

    @Override protected boolean tryPickup(PlayerEntity player) {
        return super.tryPickup(player) || isNoClip() && isOwner(player) && player.getInventory().insertStack(asItemStack());
    }

    @Override protected net.minecraft.sound.SoundEvent getHitSound() { return SoundEvents.ITEM_TRIDENT_HIT_GROUND; }

    @Override public void onPlayerCollision(PlayerEntity player) {
        if (isOwner(player) || getOwner() == null) super.onPlayerCollision(player);
    }

    @Override public void writeCustomDataToNbt(NbtCompound nbt) { super.writeCustomDataToNbt(nbt); nbt.putBoolean("dealtDamage", dealtDamage); }
    @Override public void readCustomDataFromNbt(NbtCompound nbt) { super.readCustomDataFromNbt(nbt); dealtDamage = nbt.getBoolean("dealtDamage"); }

    @Override protected float getDragInWater() { return 0.99f; }
    @Override public boolean shouldRender(double cameraX, double cameraY, double cameraZ) { return true; }

    /** Never despawns in the ground: it is coming back. */
    @Override protected void age() { }
}
