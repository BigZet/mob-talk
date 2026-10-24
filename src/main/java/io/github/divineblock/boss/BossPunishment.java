package io.github.divineblock.boss;

import io.github.divineblock.core.Miracle;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/** The god's punishment group «святые стражи» (and the Archangel in «конец света»): the boss rises a dozen blocks from the
 * sinner, already set on him. Its power grows with the god's power and the severity of the sin. */
public final class BossPunishment {
    private BossPunishment() { }

    /** The boss a punishment of «святые стражи» sends, or null for any other act. */
    public static BossKind kind(Miracle miracle) {
        return switch (miracle) {
            case BOSS_SCHEMNIK -> BossKind.BLACK_SCHEMNIK;
            case BOSS_BELL_RINGER -> BossKind.BELL_RINGER;
            case BOSS_SERAPH -> BossKind.SERAPH;
            case BOSS_FACELESS -> BossKind.FACELESS_ICON;
            case BOSS_WAILER -> BossKind.WAILER;
            case BOSS_ARCHANGEL -> BossKind.ARCHANGEL;
            case BOSS_LEVIATHAN -> BossKind.LEVIATHAN;
            case BOSS_BEHEMOTH -> BossKind.BEHEMOTH;
            case BOSS_GOLIATH -> BossKind.GOLIATH;
            case BOSS_DESTROYING_ANGEL -> BossKind.DESTROYING_ANGEL;
            case BOSS_CHERUB -> BossKind.CHERUB;
            case BOSS_NIMROD -> BossKind.NIMROD;
            case BOSS_HORSEMEN -> BossKind.HORSEMEN;
            case BOSS_BEAST -> BossKind.BEAST;
            default -> null;
        };
    }

    /** The act that sends this boss, or null (a church guardian, the night's bosses rise by themselves). */
    public static Miracle deed(BossKind kind) {
        for (Miracle miracle : Miracle.values()) if (kind(miracle) == kind) return miracle;
        return null;
    }

    public static String summon(Miracle miracle, ServerWorld world, ServerPlayerEntity player, int power, int severity) {
        BossKind kind = kind(miracle);
        if (kind == null) return null;
        int strength = Math.max(1, Math.min(5, power + severity - 2));
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = world.random.nextDouble() * Math.PI * 2, distance = 10 + world.random.nextInt(5);
            BlockPos column = BlockPos.ofFloored(player.getX() + Math.cos(angle) * distance, player.getY(), player.getZ() + Math.sin(angle) * distance);
            // The ground under any water: a flood or a lake must not hide the earth from the god's servant.
            BlockPos ground = world.getDimension().hasCeiling() ? column : world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
            // Under the crowns of a forest, not on them; under water when the land is drowned.
            if (!world.getDimension().hasCeiling() && !world.getFluidState(ground.down()).isEmpty()) ground = world.getTopPosition(Heightmap.Type.OCEAN_FLOOR, column);
            if (Math.abs(ground.getY() - player.getBlockY()) > 8) continue;
            // Never inside the church or against its walls: the boss would stand in the blocks of an inviolable house.
            if (io.github.divineblock.game.ChurchArea.nearChurchBox(world, ground, 3)) continue;
            LivingEntity boss = DivineBosses.spawn(kind, world, ground, strength, player);
            if (boss != null) return "явился " + kind.title + " ×" + strength;
        }
        // Nowhere around (cliffs, caves, a drowned land): he comes beside the sinner.
        LivingEntity boss = DivineBosses.spawn(kind, world, player.getBlockPos(), strength, player);
        return boss == null ? null : "явился " + kind.title + " ×" + strength;
    }
}
