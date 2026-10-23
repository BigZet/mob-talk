package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.boss.DivineBosses;
import io.github.divineblock.boss.GodAvatarEntity;
import io.github.divineblock.boss.SacredBoss;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.Miracle;
import io.github.divineblock.world.ChurchSites;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** «Небесный суд»: the strongest punishments (the Archangel, the Four Horsemen, the Beast, the god with the mace, withers
 * and dragons) are not fought by a little church. The god takes the mortal up to a cloud arena high in the sky, well away from
 * the world spawn (a death without a bed must not rise on its floor) —
 * white stone, clouds and gold, a wide flat floor with a hidden layer of barrier under it (no fall-through, even when withers
 * blow the floor away) and an invisible wall at the rim. One arena per world (its centre is saved), mended before every
 * court. Defeat the foe and the god returns you to the church; die and you rise as always. */
public final class SkyCourt {
    /** Radius of the stone floor; the clouds of the rim go a few blocks further, the invisible wall stands at WALL. */
    static final int RADIUS = 42, RIM = 7, WALL = RADIUS + RIM + 2, CLEAR_HEIGHT = 16;
    /** The longest a court lasts before the god lets the mortal go anyway. */
    static final int TIMEOUT_TICKS = 20 * 60 * 6;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static final class Session {
        final UUID player; final RegistryKey<World> fromWorld; final Vec3d from; final float yaw;
        final BlockPos returnTo; final BlockPos center; final Miracle act;
        int age; boolean ending;
        Session(UUID player, RegistryKey<World> fromWorld, Vec3d from, float yaw, BlockPos returnTo, BlockPos center, Miracle act) {
            this.player = player; this.fromWorld = fromWorld; this.from = from; this.yaw = yaw; this.returnTo = returnTo; this.center = center; this.act = act;
        }
    }

    private SkyCourt() { }

    public static boolean inCourt(ServerPlayerEntity player) { return SESSIONS.containsKey(player.getUuid()); }
    /** The mortals in court now. */
    public static List<UUID> judged() { return new ArrayList<>(SESSIONS.keySet()); }

    /** The admin ends every court: the god lets each mortal go and returns him as when a court runs out of time (the foes
     * left in the arena go with it). Returns how many courts ended. */
    public static int endAll(MinecraftServer server) {
        int ended = 0;
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            if (session.ending) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.player);
            if (player == null || !player.isAlive()) end(server.getOverworld(), session);
            else victory(server, player, session, true);
            ended++;
        }
        return ended;
    }

    /** How far from the world spawn the arena hangs: a mortal who dies without a bed rises at the spawn, on whatever is highest
     * there — once that was the arena's floor, with no way down (Dmitriy, 23 Oct). */
    static final int FROM_SPAWN = 2000;
    /** Arenas left over the spawn by older versions, taken down at start; mortals still on one are brought down. */
    private static final List<BlockPos> OLD = new ArrayList<>();

    /** The floor's centre in the overworld (y is the floor block), chosen once: high in the sky, {@link #FROM_SPAWN} east of the spawn. */
    public static BlockPos center(MinecraftServer server) {
        DivineState state = DivineState.of(server);
        if (state.skyCourt == null) {
            ServerWorld overworld = server.getOverworld();
            BlockPos spawn = overworld.getSpawnPos();
            int y = Math.min(overworld.getTopY() - 40, 280);
            state.skyCourt = new int[] { spawn.getX() + FROM_SPAWN, y, spawn.getZ() };
            state.markDirty();
        }
        return new BlockPos(state.skyCourt[0], state.skyCourt[1], state.skyCourt[2]);
    }

    /** At start: an arena an older version hung over the spawn is taken down and the next court builds a new one away from it. */
    public static void relocateOld(MinecraftServer server) {
        DivineState state = DivineState.of(server);
        if (state.skyCourt == null) return;
        ServerWorld overworld = server.getOverworld();
        BlockPos spawn = overworld.getSpawnPos(), old = new BlockPos(state.skyCourt[0], state.skyCourt[1], state.skyCourt[2]);
        double dx = old.getX() - spawn.getX(), dz = old.getZ() - spawn.getZ();
        if (Math.sqrt(dx * dx + dz * dz) > FROM_SPAWN / 2.0) return;
        long began = System.nanoTime();
        demolish(overworld, old);
        OLD.add(old);
        state.skyCourt = null;
        state.markDirty();
        DivineBlockMod.LOG.info("Божественный блок: старая небесная арена над спавном {} разобрана за {} мс, новая будет в {} блоках",
                old.toShortString(), (System.nanoTime() - began) / 1_000_000, FROM_SPAWN);
    }

    /** Takes an arena down to the air: the floor, the clouds under it, the pillars and the invisible walls. */
    static void demolish(ServerWorld world, BlockPos center) {
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        int bottom = Math.max(world.getBottomY(), center.getY() - 48), top = Math.min(world.getTopY() - 1, center.getY() + 40);
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int dx = -WALL - 2; dx <= WALL + 2; dx++) for (int dz = -WALL - 2; dz <= WALL + 2; dz++) {
            if (dx * dx + dz * dz > (WALL + 2) * (WALL + 2)) continue;
            for (int y = bottom; y <= top; y++) {
                at.set(center.getX() + dx, y, center.getZ() + dz);
                if (!world.getBlockState(at).isAir()) world.setBlockState(at, Blocks.AIR.getDefaultState(), flags);
            }
        }
    }

    /** A mortal on an arena with no court of his (he died in court and rose at the spawn, or joined there): down to the
     * ground at the world spawn, by the church. */
    private static void rescue(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        List<BlockPos> arenas = new ArrayList<>(OLD);
        DivineState state = DivineState.of(server);
        if (state.skyCourt != null) arenas.add(new BlockPos(state.skyCourt[0], state.skyCourt[1], state.skyCourt[2]));
        if (arenas.isEmpty()) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getServerWorld() != overworld || !player.isAlive() || SESSIONS.containsKey(player.getUuid())) continue;
            for (BlockPos arena : arenas) {
                double dx = player.getX() - arena.getX() - 0.5, dz = player.getZ() - arena.getZ() - 0.5;
                if (dx * dx + dz * dz > (WALL + 8) * (WALL + 8) || player.getY() < arena.getY() - 50) continue;
                BlockPos spawn = overworld.getSpawnPos();
                overworld.getChunk(spawn);
                BlockPos ground = overworld.getTopPosition(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, spawn);
                player.teleport(overworld, ground.getX() + 0.5, ground.getY(), ground.getZ() + 0.5, player.getYaw(), 0f);
                player.fallDistance = 0;
                Prayers.godChat(player, "Суд твой окончен. Ступай по земле.");
                DivineBlockMod.LOG.info("Божественный блок: {} был на небесной арене без суда — спущен к спавну", player.getName().getString());
                break;
            }
        }
    }

    /** Where the mortal stands when he comes: on the floor, a dozen blocks south of the centre, facing it. */
    public static Vec3d arrival(BlockPos center) { return Vec3d.ofBottomCenter(center.add(0, 1, 16)); }

    /** The god takes the mortal up and the act falls there. Returns what was done (the act itself comes a few seconds later). */
    public static String summon(Miracle miracle, int power, ServerPlayerEntity player, BlockPos altar, String prayer, DivineConfig config, int severity,
                                ChurchSites.Site church) {
        MinecraftServer server = player.getServer();
        ServerWorld court = server.getOverworld();
        BlockPos center = center(server);
        if (inCourt(player)) return Zones.performWithFallback(miracle, power, player, center.up(), prayer, config, severity, Miracle.Place.SKY);
        ServerWorld from = player.getServerWorld();
        Zones.relocating(player);
        Zones.title(player, Text.literal("Небесный суд").formatted(Formatting.GOLD), Text.literal("Восстань пред престолом моим").formatted(Formatting.YELLOW));
        Prayers.godChat(player, "Не в малом храме судить тебя. Восстань на небесный суд — там примешь кару свою.");
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 50, 1, false, false));
        from.spawnParticles(ParticleTypes.END_ROD, player.getX(), player.getY() + 1, player.getZ(), 80, 0.6, 1.5, 0.6, 0.08);
        Miracles.sound(from, player.getBlockPos(), SoundEvents.BLOCK_BEACON_ACTIVATE, 2f, 0.6f);
        BlockPos returnTo = church == null ? null : church.naveCenter();
        Session session = new Session(player.getUuid(), from.getRegistryKey(), player.getPos(), player.getYaw(), returnTo, center, miracle);
        SESSIONS.put(player.getUuid(), session);
        Scheduler.later(45, () -> {
            if (player.isRemoved() || !player.isAlive()) { SESSIONS.remove(player.getUuid()); return; }
            long began = System.nanoTime();
            build(court, center);
            DivineBlockMod.LOG.info("Божественный блок: небесный суд построен у {} за {} мс", center.toShortString(), (System.nanoTime() - began) / 1_000_000);
            Vec3d at = arrival(center);
            player.removeStatusEffect(StatusEffects.LEVITATION);
            player.teleport(court, at.x, at.y, at.z, 180f, 0f);
            player.fallDistance = 0;
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOW_FALLING, 100, 0, false, false));
            court.spawnParticles(ParticleTypes.END_ROD, at.x, at.y + 1, at.z, 120, 1.5, 1.5, 1.5, 0.1);
            Miracles.sound(court, BlockPos.ofFloored(at), SoundEvents.BLOCK_BELL_RESONATE, 3f, 0.6f);
            Scheduler.later(50, () -> {
                if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != court) return;
                Zones.title(player, Text.literal("Небесный суд").formatted(Formatting.GOLD), Text.literal("Одолей — и вернёшься в храм").formatted(Formatting.YELLOW));
                String done = Zones.performWithFallback(miracle, power, player, center.up(), prayer, config, severity, Miracle.Place.SKY);
                DivineBlockMod.LOG.info("Божественный блок: на небесном суде {} — {}", miracle.id(), done);
            });
        });
        return "небесный суд: " + miracle.id();
    }

    public static void tick(MinecraftServer server) {
        if (server.getTicks() % 10 != 0) return;
        rescue(server);
        if (SESSIONS.isEmpty()) return;
        for (Session session : new ArrayList<>(SESSIONS.values())) {
            session.age += 10;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(session.player);
            ServerWorld court = server.getOverworld();
            if (player == null || !player.isAlive()) { DivineBlockMod.LOG.info("Божественный блок: небесный суд окончен — грешник пал или ушёл"); end(court, session); continue; }
            if (session.ending || session.age < 20 * 8) continue;
            if (player.getServerWorld() != court || player.getPos().distanceTo(Vec3d.ofCenter(session.center)) > WALL + 40) {
                // He left the court another way (an exile, the ban): the court is over.
                end(court, session); continue;
            }
            if (player.getY() < session.center.getY() - 6) {
                Vec3d at = arrival(session.center);
                player.teleport(court, at.x, at.y, at.z, player.getYaw(), player.getPitch());
                player.fallDistance = 0;
            }
            boolean foes = !foes(court, session.center).isEmpty() || DivineBosses.godAvatarActive(player);
            if (!foes || session.age > TIMEOUT_TICKS) victory(server, player, session, foes);
        }
    }

    /** The bosses, withers, dragons and the god's figure in the arena. */
    public static List<Entity> foes(ServerWorld court, BlockPos center) {
        return court.getEntitiesByClass(Entity.class, new Box(center).expand(WALL + 40, 80, WALL + 40),
                e -> e.isAlive() && (e instanceof SacredBoss || e instanceof WitherEntity || e instanceof EnderDragonEntity || e instanceof GodAvatarEntity));
    }

    private static void victory(MinecraftServer server, ServerPlayerEntity player, Session session, boolean timedOut) {
        session.ending = true;
        if (timedOut) Zones.title(player, Text.literal("Суд окончен").formatted(Formatting.GOLD), Text.literal("Бог отпускает тебя").formatted(Formatting.YELLOW));
        else Zones.title(player, Text.literal("Суд окончен").formatted(Formatting.GOLD), Text.literal("Ты устоял пред гневом моим").formatted(Formatting.YELLOW));
        Prayers.godChat(player, timedOut ? "Довольно. Ступай назад и помни этот суд." : "Устоял ты. Ступай назад в храм — и не гневи меня впредь.");
        Scheduler.later(60, () -> {
            ServerWorld court = server.getOverworld();
            if (player.isRemoved() || !player.isAlive()) { end(court, session); return; }
            ServerWorld back = session.returnTo != null ? court : server.getWorld(session.fromWorld);
            if (back == null) back = court;
            Vec3d to = session.returnTo != null ? Vec3d.ofBottomCenter(session.returnTo) : session.from;
            player.teleport(back, to.x, to.y, to.z, session.yaw, 0f);
            player.fallDistance = 0;
            back.spawnParticles(ParticleTypes.END_ROD, to.x, to.y + 1, to.z, 60, 0.6, 1.2, 0.6, 0.05);
            Miracles.sound(back, BlockPos.ofFloored(to), SoundEvents.BLOCK_BEACON_DEACTIVATE, 2f, 0.8f);
            end(court, session);
        });
    }

    /** The court is over: what is left of the foes and their servants leaves the arena. */
    private static void end(ServerWorld court, Session session) {
        SESSIONS.remove(session.player);
        for (Entity foe : court.getEntitiesByClass(Entity.class, new Box(session.center).expand(WALL + 40, 80, WALL + 40),
                e -> e.isAlive() && !(e instanceof PlayerEntity) && (e instanceof HostileEntity || e instanceof WitherEntity || e instanceof EnderDragonEntity
                        || e instanceof GodAvatarEntity || e instanceof net.minecraft.entity.ItemEntity))) foe.discard();
    }

    static void clear() { SESSIONS.clear(); OLD.clear(); }

    // ---- The arena. ----

    /** Builds (or mends) the arena around {@code center}: floor at center.y. About forty thousand blocks, once per court. */
    public static void build(ServerWorld world, BlockPos center) {
        int cx = center.getX(), cy = center.getY(), cz = center.getZ();
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
        int top = Math.min(world.getTopY() - 1, cy + 40);
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int dx = -WALL - 1; dx <= WALL + 1; dx++) for (int dz = -WALL - 1; dz <= WALL + 1; dz++) {
            int x = cx + dx, z = cz + dz;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > WALL + 1.5) continue;
            double n = noise(x, z, 6), m = noise(x + 911, z - 377, 3);
            double edge = RADIUS + 2 + n * (RIM - 1); // where the cloud bank ends, ragged
            // Clear the air of the arena from the last court's debris.
            for (int y = cy + 1; y <= Math.min(top, cy + CLEAR_HEIGHT); y++) set(world, at.set(x, y, z), Blocks.AIR.getDefaultState(), flags);
            // The invisible wall at the rim and the invisible floor beyond the clouds.
            if (d >= WALL - 0.5) { for (int y = cy; y <= top; y++) set(world, at.set(x, y, z), Blocks.BARRIER.getDefaultState(), flags); continue; }
            set(world, at.set(x, cy - 1, z), Blocks.BARRIER.getDefaultState(), flags);
            if (d > edge) { set(world, at.set(x, cy, z), Blocks.BARRIER.getDefaultState(), flags); continue; }
            set(world, at.set(x, cy, z), floor(dx, dz, d, x, z), flags);
            // The puffy underside: deepest under the middle.
            double lump = noise(x - 5000, z + 2000, 4), bulge = noise(x + 7000, z - 3000, 9);
            int depth = 1 + (int) Math.round(Math.pow(Math.max(0, 1 - d / edge), 0.5) * 13 * (0.3 + 0.7 * m) + lump * 3
                    + Math.max(0, bulge - 0.55) * 22 * Math.max(0, 1 - d / edge));
            for (int k = 0; k < depth; k++) set(world, at.set(x, cy - 2 - k, z), cloud(x, cy - 2 - k, z), flags);
            // The cloud bank of the rim: puffs one to three high, thinning inward.
            if (d > RADIUS - 2) {
                double rise = (d - (RADIUS - 2)) / (edge - (RADIUS - 2) + 0.01);
                double tuft = noise(x + 300, z + 900, 4);
                int puff = (int) Math.round((0.2 + 0.8 * tuft) * 4.5 * Math.sin(Math.PI * Math.min(1, rise))) - 1;
                for (int y = 1; y <= puff; y++) set(world, at.set(x, cy + y, z), cloud(x, cy + y, z), flags);
            }
        }
        // Eight pillars of white stone with gold capitals and a lamp.
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 8 + i * Math.PI / 4;
            int px = cx + (int) Math.round(Math.cos(a) * (RADIUS - 7)), pz = cz + (int) Math.round(Math.sin(a) * (RADIUS - 7));
            set(world, at.set(px, cy, pz), Blocks.CHISELED_QUARTZ_BLOCK.getDefaultState(), flags);
            for (int y = 1; y <= 6; y++) set(world, at.set(px, cy + y, pz), Blocks.QUARTZ_PILLAR.getDefaultState(), flags);
            set(world, at.set(px, cy + 7, pz), Blocks.GOLD_BLOCK.getDefaultState(), flags);
            set(world, at.set(px, cy + 8, pz), Blocks.LANTERN.getDefaultState(), flags);
        }
    }

    private static void set(ServerWorld world, BlockPos pos, BlockState state, int flags) {
        if (world.getBlockState(pos) != state) world.setBlockState(pos, state, flags);
    }

    /** The floor: white stone of several kinds, a gold heart, two gold rings, eight rays and lamps set into the stone. */
    private static BlockState floor(int dx, int dz, double d, int x, int z) {
        if (d < 2.5) return Blocks.GOLD_BLOCK.getDefaultState();
        if (d < 3.6) return Blocks.CHISELED_QUARTZ_BLOCK.getDefaultState();
        if (d >= 11 && d < 12) return Blocks.GOLD_BLOCK.getDefaultState();
        if (d >= RADIUS - 4 && d < RADIUS - 3) return Blocks.GOLD_BLOCK.getDefaultState();
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        double ray = Math.abs(MathHelper.wrapDegrees(angle - Math.round(angle / 45.0) * 45.0));
        if (d < 11 && ray * d / 57.3 < 0.7) return Blocks.QUARTZ_BRICKS.getDefaultState();
        if ((Math.abs(d - 20) < 0.6 || Math.abs(d - 31) < 0.6) && ray * d / 57.3 < 0.6) return Blocks.SEA_LANTERN.getDefaultState();
        if (d >= 24 && d < 25) return Blocks.GOLD_BLOCK.getDefaultState();
        int h = hash(x, z) & 0xFF;
        if (h < 140) return Blocks.SMOOTH_QUARTZ.getDefaultState();
        if (h < 200) return Blocks.CALCITE.getDefaultState();
        if (h < 240) return Blocks.WHITE_CONCRETE.getDefaultState();
        return Blocks.SNOW_BLOCK.getDefaultState();
    }

    /** Cloud stuff: white wool mostly, snow and a little white glass for the shine. */
    private static BlockState cloud(int x, int y, int z) {
        int h = hash(x * 31 + y, z) & 0xFF;
        if (h < 170) return Blocks.WHITE_WOOL.getDefaultState();
        if (h < 235) return Blocks.SNOW_BLOCK.getDefaultState();
        return Blocks.WHITE_STAINED_GLASS.getDefaultState();
    }

    private static int hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }
    /** Smooth value noise 0..1 over cells of {@code scale} blocks. */
    static double noise(int x, int z, int scale) {
        int gx = Math.floorDiv(x, scale), gz = Math.floorDiv(z, scale);
        double fx = (x - gx * (double) scale) / scale, fz = (z - gz * (double) scale) / scale;
        fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
        double a = (hash(gx, gz) & 0xFFFF) / 65535.0, b = (hash(gx + 1, gz) & 0xFFFF) / 65535.0;
        double c = (hash(gx, gz + 1) & 0xFFFF) / 65535.0, e = (hash(gx + 1, gz + 1) & 0xFFFF) / 65535.0;
        return MathHelper.lerp(fz, MathHelper.lerp(fx, a, b), MathHelper.lerp(fx, c, e));
    }
}
