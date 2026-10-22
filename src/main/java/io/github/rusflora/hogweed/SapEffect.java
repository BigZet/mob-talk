package io.github.rusflora.hogweed;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTask;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Hogweed sap on the skin. It is shown with its timer, so the player sees how long until the skin turns sensitive to
 * sunlight; when the timer runs out the sap becomes a burn (see BurnEffect) that hurts only in the sun. Washing in water
 * before then removes the sap. The burn is added after the tick, because effects must not be added while the entity is
 * going through its effects. */
public final class SapEffect extends StatusEffect {
    SapEffect() { super(StatusEffectCategory.HARMFUL, 0xC8D25A); }

    /** Ticks from contact to the burn. */
    public static int duration() { return Math.max(1, HogweedConfig.get().burnDelaySeconds * 20); }

    @Override public boolean canApplyUpdateEffect(int duration, int amplifier) { return duration % 10 == 0 || duration == 1; }

    @Override public boolean applyUpdateEffect(LivingEntity entity, int amplifier) {
        World world = entity.getWorld();
        if (world.isClient) return true;
        if (entity.isTouchingWater()) return false;
        StatusEffectInstance sap = entity.getStatusEffect(Hogweed.SAP);
        if (sap == null || sap.getDuration() > 1) return true;
        MinecraftServer server = world.getServer();
        if (server != null) server.send(new ServerTask(server.getTicks(), () -> {
            if (entity.isAlive()) entity.addStatusEffect(new StatusEffectInstance(Hogweed.BURN, BurnEffect.DURATION, amplifier, false, false, true));
        }));
        return false;
    }

    /** Daylight on the skin: daytime, no rain and nothing solid (roof, tree crown) over the head. */
    public static boolean inSun(LivingEntity entity) {
        World world = entity.getWorld();
        BlockPos head = BlockPos.ofFloored(entity.getEyePos());
        return world.isDay() && !world.hasRain(head) && HogweedBlock.openSky(world, head);
    }
}
