package io.github.divineblock.boss;

import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** What sleeps under a {@link BossSealBlock}, saved with the block (so a structure template can carry it):
 * <ul>
 * <li>{@code boss}: a boss id, or a place — {@code church}, {@code cemetery}, {@code god} — for a random boss of it (default church);</li>
 * <li>{@code power} 1..5 (default 3); {@code radius}: how near a player must come (default 10);</li>
 * <li>{@code night}: rises only at night (the cemetery);</li>
 * <li>{@code cooldownDays}: -1 rises once and never again (the crypt), otherwise again after that many days if the last one died.</li>
 * </ul>
 * While its boss lives, the seal waits. {@link #configure} sets it from code. */
public final class BossSealBlockEntity extends BlockEntity {
    private String boss = "church";
    private int power = 3, radius = 10, cooldownDays = -1;
    private boolean night;
    private long lastDay = Long.MIN_VALUE;
    private UUID risen;

    public BossSealBlockEntity(BlockPos pos, BlockState state) { super(DivineBosses.SEAL_ENTITY, pos, state); }

    /** Sets what sleeps here: e.g. {@code configure(world, pos, "cemetery", 2, true, 1)} for a grave that wakes every night. */
    public static boolean configure(World world, BlockPos pos, String boss, int power, boolean night, int cooldownDays) {
        if (!(world.getBlockEntity(pos) instanceof BossSealBlockEntity seal)) return false;
        seal.boss = boss == null || boss.isBlank() ? "church" : boss.strip();
        seal.power = Math.max(1, Math.min(5, power));
        seal.night = night;
        seal.cooldownDays = cooldownDays;
        seal.markDirty();
        return true;
    }

    /** Whether this seal lies in the church at the world spawn (worked out once per load). */
    private Boolean atSpawn;

    static void tick(World world, BlockPos pos, BlockState state, BossSealBlockEntity seal) {
        if (!(world instanceof ServerWorld server) || (world.getTime() + pos.hashCode()) % 20 != 0) return;
        if (seal.risen != null) {
            Entity previous = server.getEntity(seal.risen);
            if (previous instanceof LivingEntity living && living.isAlive()) return;
            seal.risen = null;
            seal.markDirty();
        }
        long day = world.getTimeOfDay() / 24000L;
        if (seal.lastDay != Long.MIN_VALUE && (seal.cooldownDays < 0 || day - seal.lastDay < seal.cooldownDays)) return;
        if (seal.night && world.isDay()) return;
        PlayerEntity near = world.getClosestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, seal.radius,
                entity -> entity instanceof PlayerEntity player && !player.isSpectator() && !player.isCreative());
        if (near == null) return;
        // A church keeps no guardian unless the settings ask for one, and the church at the world spawn (ChurchSpawn), where every
        // new player comes to pray, never.
        if (seal.atSpawn == null) seal.atSpawn = "church".equals(seal.boss) && io.github.divineblock.game.ChurchSpawn.atSpawnChurch(server, pos);
        if ("church".equals(seal.boss) && (!io.github.divineblock.DivineBlockMod.config().churchGuardians || seal.atSpawn)) return;
        LivingEntity risen = seal.wake(server, pos, near);
        if (risen == null) return;
        seal.risen = risen.getUuid();
        seal.lastDay = day;
        seal.markDirty();
    }

    private LivingEntity wake(ServerWorld world, BlockPos pos, PlayerEntity near) {
        BossKind kind = BossKind.byId(boss);
        if (kind != null) return DivineBosses.spawn(kind, world, pos.up(), power, near);
        BossKind.Place place = switch (boss.toLowerCase(java.util.Locale.ROOT)) {
            case "cemetery" -> BossKind.Place.CEMETERY; case "god" -> BossKind.Place.GOD; case "final" -> BossKind.Place.FINAL;
            default -> BossKind.Place.CHURCH;
        };
        var pool = BossKind.at(place);
        return DivineBosses.spawn(pool.get(world.random.nextInt(pool.size())), world, pos.up(), power, near);
    }

    @Override protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt, registries);
        nbt.putString("boss", boss);
        nbt.putInt("power", power);
        nbt.putInt("radius", radius);
        nbt.putBoolean("night", night);
        nbt.putInt("cooldownDays", cooldownDays);
        if (lastDay != Long.MIN_VALUE) nbt.putLong("lastDay", lastDay);
        if (risen != null) nbt.putUuid("risen", risen);
    }

    @Override protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);
        if (nbt.contains("boss")) boss = nbt.getString("boss");
        if (nbt.contains("power")) power = Math.max(1, Math.min(5, nbt.getInt("power")));
        if (nbt.contains("radius")) radius = Math.max(2, Math.min(48, nbt.getInt("radius")));
        night = nbt.getBoolean("night");
        if (nbt.contains("cooldownDays")) cooldownDays = nbt.getInt("cooldownDays");
        lastDay = nbt.contains("lastDay") ? nbt.getLong("lastDay") : Long.MIN_VALUE;
        risen = nbt.containsUuid("risen") ? nbt.getUuid("risen") : null;
    }
}
