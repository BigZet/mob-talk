package io.github.rusflora.hogweed;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTask;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

/** Hogweed sap on the skin. It does nothing by itself and shows no icon: the furanocoumarins wait for light. Once the delay from
 * the config has passed, every second spent under open daytime sky turns into a burn; in partial shade only sometimes, at night,
 * in shade and under rain not at all. Washing in water removes the sap. The burn is added after the tick, because effects must
 * not be added while the entity is going through its effects. */
public final class SapEffect extends StatusEffect {
    /** Seconds after the delay during which the sap still reacts to light. */
    private static final int ACTIVE_SECONDS = 60;

    SapEffect() { super(StatusEffectCategory.HARMFUL, 0xC8D25A); }

    public static int duration() { return (HogweedConfig.get().burnDelaySeconds + ACTIVE_SECONDS) * 20; }

    @Override public boolean canApplyUpdateEffect(int duration, int amplifier) { return duration % 20 == 0; }

    @Override public boolean applyUpdateEffect(LivingEntity entity, int amplifier) {
        World world = entity.getWorld();
        if (world.isClient) return true;
        if (entity.isTouchingWater()) return false;
        StatusEffectInstance sap = entity.getStatusEffect(Hogweed.SAP);
        if (sap == null || duration() - sap.getDuration() < HogweedConfig.get().burnDelaySeconds * 20) return true;
        BlockPos head = BlockPos.ofFloored(entity.getEyePos());
        int sky = world.getLightLevel(LightType.SKY, head);
        if (!world.isDay() || world.hasRain(head) || sky < 9) return true;
        if (sky < 13 && entity.getRandom().nextBoolean()) return true;
        int level = sky >= 13 ? amplifier : 0;
        MinecraftServer server = world.getServer();
        if (server != null) server.send(new ServerTask(server.getTicks(), () -> {
            if (!entity.isAlive()) return;
            StatusEffectInstance burn = entity.getStatusEffect(Hogweed.BURN);
            if (burn == null || burn.getAmplifier() < level || burn.getDuration() < 80)
                entity.addStatusEffect(new StatusEffectInstance(Hogweed.BURN, 160, Math.max(level, burn == null ? 0 : burn.getAmplifier())));
        }));
        return true;
    }
}
