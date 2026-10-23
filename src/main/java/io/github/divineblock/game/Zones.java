package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.core.DivineConfig;
import io.github.divineblock.core.Miracle;
import io.github.divineblock.core.MiracleGroup;
import io.github.divineblock.world.ChurchSites;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

/** Where a punishment falls (see {@link Miracle.Place}). The church is small and inviolable, so:
 * <ul>
 *   <li>small curses fall inside it — and the candles go out, the eyes darken, the dome answers;</li>
 *   <li>fights go to the churchyard: «Изыди из храма» — a few seconds to walk out, then the god throws him out;</li>
 *   <li>the strongest go to the Sky Court ({@link SkyCourt});</li>
 *   <li>what changes the land never strikes within {@link ChurchArea#ZONE_RADIUS} of a church: «Ступай в дом свой и узри гнев
 *       мой» — the mortal is taken home (his bed), or 60–100 blocks out into open land if he has none, and it strikes there.</li>
 * </ul>
 * The god always says what is happening: a title and a line in chat. Away from a church the acts fall as they always did, but
 * the zone around every church is still spared ({@link Protection}). One punishment moves the mortal at most once
 * ({@link io.github.divineblock.core.Penance#mayRelocate}): an act that would move him again falls where he stands or not at all. */
public final class Zones {
    /** Seconds to walk out of the church before the god throws the mortal out. */
    static final int CAST_OUT_SECONDS = 5;
    /** The last placement decisions, for the self-test's report. */
    public static volatile java.util.function.Consumer<String> decided = line -> { };

    private Zones() { }

    /** Performs the act in its place. Returns what was done, or null when it cannot happen at all (another act of the group
     * is tried by the caller); a deferred act (cast out, home, the Sky Court) returns at once with what is under way. */
    public static String perform(Miracle miracle, int power, ServerPlayerEntity player, BlockPos altar, String prayer, DivineConfig config, int severity) {
        ServerWorld world = player.getServerWorld();
        Miracle.Place place = miracle.place();
        Optional<ChurchSites.Site> church = ChurchArea.near(world, player.getBlockPos());
        if (church.isEmpty() && altar != null) church = ChurchArea.near(world, altar);
        // This punishment already moved him (cast out, taken home or up): no second move. The act falls where he stands when it
        // can (out of the church), or not at all and the caller picks another.
        if (!mayRelocate(player) && !SkyCourt.inCourt(player)) {
            boolean inChurch = church.isPresent() && ChurchArea.inside(church.get(), player.getBlockPos());
            if (place == Miracle.Place.SKY || place == Miracle.Place.HOME || place == Miracle.Place.YARD && inChurch) {
                decided.accept(miracle.id() + " → не переносит второй раз");
                return null;
            }
            if (place == Miracle.Place.YARD) return Miracles.perform(miracle, power, player, altar, prayer, config, severity);
        }
        if (place == Miracle.Place.SKY && world.getRegistryKey() == World.OVERWORLD) {
            decided.accept(miracle.id() + " → небесный суд");
            return SkyCourt.summon(miracle, power, player, altar, prayer, config, severity, church.orElse(null));
        }
        if (church.isEmpty() || SkyCourt.inCourt(player)) return Miracles.perform(miracle, power, player, altar, prayer, config, severity);
        ChurchSites.Site site = church.get();
        boolean inside = ChurchArea.inside(site, player.getBlockPos());
        switch (place) {
            case CHURCH -> {
                if (!inside || miracle.kind != Miracle.Kind.WRATH) return Miracles.perform(miracle, power, player, altar, prayer, config, severity);
                decided.accept(miracle.id() + " → в храме");
                String done = miracle == Miracle.LIGHTNING ? thresholdLightning(world, player, site, power) : Miracles.perform(miracle, power, player, altar, prayer, config, severity);
                if (done != null) done += ", " + churchCurse(world, player, site);
                return done;
            }
            case YARD -> {
                if (!inside && ChurchArea.distance(site.church(), player.getBlockPos()) >= 6) {
                    decided.accept(miracle.id() + " → там, где стоит (у церкви, но не в ней)");
                    return Miracles.perform(miracle, power, player, altar, prayer, config, severity);
                }
                decided.accept(miracle.id() + " → изгнание из храма во двор");
                return castOut(miracle, power, player, altar, prayer, config, severity, site);
            }
            case HOME -> {
                if (config.protectBlocks) return Miracles.perform(miracle, power, player, altar, prayer, config, severity);
                return strikeHome(miracle, power, player, prayer, config, severity, site);
            }
            default -> { return Miracles.perform(miracle, power, player, altar, prayer, config, severity); }
        }
    }

    /** Whether the running punishment may still move him (once per punishment). */
    static boolean mayRelocate(ServerPlayerEntity player) {
        return Prayers.PENANCE.mayRelocate(player.getUuid(), player.getServer().getOverworld().getTime());
    }
    /** The running punishment moves him now: the only move it makes. */
    static void relocating(ServerPlayerEntity player) { Prayers.PENANCE.relocated(player.getUuid(), player.getServer().getOverworld().getTime()); }

    /** The act, or (if it cannot happen here) another of its group that falls in the same place. */
    static String performWithFallback(Miracle miracle, int power, ServerPlayerEntity player, BlockPos altar, String prayer, DivineConfig config, int severity,
                                      Miracle.Place place) {
        String done = safely(miracle, power, player, altar, prayer, config, severity);
        lastPerformed = miracle;
        if (done != null) return done;
        List<Miracle> others = new ArrayList<>();
        for (MiracleGroup group : MiracleGroup.values())
            if (group.members.contains(miracle)) for (Miracle other : group.members) if (other != miracle && other.place() == place && config.allows(other)) others.add(other);
        Collections.shuffle(others);
        for (Miracle other : others) {
            done = safely(other, Math.max(other.minTier, Math.min(other.maxTier, power)), player, altar, prayer, config, severity);
            if (done != null) { lastPerformed = other; DivineBlockMod.LOG.info("Божественный блок: {} здесь невозможно, вместо него {}", miracle.id(), other.id()); return done; }
        }
        return null;
    }
    /** The act {@link #performWithFallback} really did (the asked one or its replacement), for the god's title. */
    static Miracle lastPerformed;
    private static String safely(Miracle miracle, int power, ServerPlayerEntity player, BlockPos altar, String prayer, DivineConfig config, int severity) {
        try { return Miracles.perform(miracle, power, player, altar, prayer, config, severity); }
        catch (RuntimeException failure) { DivineBlockMod.LOG.warn("Божественный блок: чудо {} не удалось", miracle.id(), failure); return null; }
    }

    // ---- Inside the church. ----

    /** Lightning does not come through the dome: it strikes the threshold, and the sinner is scorched where he stands. */
    static String thresholdLightning(ServerWorld world, ServerPlayerEntity player, ChurchSites.Site site, int power) {
        BlockPos porch = site.bossPoints().size() > 2 ? site.bossPoints().get(2) : site.origin().west(4);
        BlockPos ground = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, porch);
        int bolts = power >= 3 ? 3 : 1;
        Miracles.bolt(world, ground, true);
        if (bolts > 1) Scheduler.repeat(12, bolts - 1, i -> Miracles.bolt(world, ground.add(world.random.nextInt(3) - 1, 0, world.random.nextInt(3) - 1), true));
        player.damage(world.getDamageSources().lightningBolt(), 3f + 2f * power);
        player.setOnFireFor(3);
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, player.getX(), player.getY() + 1, player.getZ(), 60, 0.4, 0.9, 0.4, 0.2);
        return (bolts > 1 ? "три молнии" : "молния") + " в порог храма, грешник опалён";
    }

    /** What every curse in the church brings with it: the candles go out, the eyes darken, a voice moans under the dome. */
    static String churchCurse(ServerWorld world, ServerPlayerEntity player, ChurchSites.Site site) {
        int out = 0;
        BlockBox box = site.church();
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int x = box.getMinX(); x <= box.getMaxX(); x++) for (int y = box.getMinY(); y <= Math.min(box.getMaxY(), box.getMinY() + 12); y++)
            for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
                BlockState state = world.getBlockState(at.set(x, y, z));
                if ((state.getBlock() instanceof AbstractCandleBlock || state.isIn(BlockTags.CANDLE_CAKES)) && state.contains(Properties.LIT) && state.get(Properties.LIT)) {
                    world.setBlockState(at, state.with(Properties.LIT, false), 3);
                    world.spawnParticles(ParticleTypes.SMOKE, x + 0.5, y + 0.7, z + 0.5, 4, 0.05, 0.05, 0.05, 0.01);
                    out++;
                }
            }
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 20 * 4, 0));
        BlockPos dome = site.naveCenter().up(14);
        Miracles.sound(world, dome, SoundEvents.AMBIENT_CAVE.value(), 3f, 0.5f);
        Miracles.sound(world, dome, SoundEvents.BLOCK_BELL_RESONATE, 2f, 0.4f);
        world.spawnParticles(ParticleTypes.SOUL, dome.getX() + 0.5, dome.getY() - 2, dome.getZ() + 0.5, 40, 2, 1, 2, 0.02);
        return "свечи погасли (" + out + "), слепота, голос из-под купола";
    }

    // ---- The churchyard. ----

    /** «Изыди из храма»: a few seconds to walk out, then the god throws him out into the yard, and the act falls there. */
    static String castOut(Miracle miracle, int power, ServerPlayerEntity player, BlockPos altar, String prayer, DivineConfig config, int severity, ChurchSites.Site site) {
        ServerWorld world = player.getServerWorld();
        relocating(player);
        title(player, Text.literal("Изыди из храма!").formatted(Formatting.DARK_RED), Text.literal("Ступай во двор — там свершится суд").formatted(Formatting.RED));
        Prayers.godChat(player, "Изыди из храма! Не оскверню дом мой кровью твоей — во дворе примешь кару.");
        Miracles.sound(world, site.naveCenter().up(10), SoundEvents.BLOCK_BELL_USE, 3f, 0.5f);
        step(miracle, power, player, altar, prayer, config, severity, site, world, 0);
        return "изгнан из храма во двор: " + miracle.id();
    }
    private static void step(Miracle miracle, int power, ServerPlayerEntity player, BlockPos altar, String prayer, DivineConfig config, int severity,
                             ChurchSites.Site site, ServerWorld world, int second) {
        Scheduler.later(20, () -> {
            if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) return;
            boolean out = !ChurchArea.inside(site, player.getBlockPos());
            if (!out && second + 1 < CAST_OUT_SECONDS) {
                player.sendMessage(Text.literal("Выйди из храма: " + (CAST_OUT_SECONDS - second - 1) + "…").formatted(Formatting.RED), true);
                step(miracle, power, player, altar, prayer, config, severity, site, world, second + 1);
                return;
            }
            if (!out || ChurchArea.distance(site.church(), player.getBlockPos()) < 10) {
                Vec3d yard = ChurchArea.yardSpot(world, site);
                Vec3d from = ChurchArea.center(site);
                float yaw = (float) Math.toDegrees(Math.atan2(-(yard.x - from.x), yard.z - from.z));
                world.spawnParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 1, player.getZ(), 40, 0.4, 0.8, 0.4, 0.05);
                player.teleport(world, yard.x, yard.y, yard.z, yaw, 0f);
                player.fallDistance = 0;
                world.spawnParticles(ParticleTypes.CLOUD, yard.x, yard.y + 1, yard.z, 40, 0.4, 0.8, 0.4, 0.05);
                Miracles.sound(world, BlockPos.ofFloored(yard), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, 2f, 0.6f);
                if (!out) player.sendMessage(Text.literal("Бог вышвырнул тебя из храма").formatted(Formatting.DARK_RED), true);
            }
            Scheduler.later(20, () -> {
                if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) return;
                String done = performWithFallback(miracle, power, player, altar, prayer, config, severity, Miracle.Place.YARD);
                DivineBlockMod.LOG.info("Божественный блок: во дворе церкви {} — {}", miracle.id(), done);
            });
        });
    }

    // ---- At home. ----

    /** «Ступай в дом свой и узри гнев мой»: the mortal is taken home (or into open land) and the disaster strikes there. A
     * curse that lingers around the home (the blight) does not take him: he is shown the way to it. */
    static String strikeHome(Miracle miracle, int power, ServerPlayerEntity player, String prayer, DivineConfig config, int severity, ChurchSites.Site site) {
        ServerWorld world = player.getServerWorld();
        BlockPos bed = player.getSpawnPointPosition();
        boolean home = bed != null && player.getSpawnPointDimension() == world.getRegistryKey() && !ChurchArea.inZone(world, bed);
        Vec3d spot = home ? standNear(world, bed) : null;
        if (spot == null) { home = false; spot = openLand(world, site); }
        if (spot == null) { DivineBlockMod.LOG.info("Божественный блок: {} — нет ни дома, ни чистого поля вдали от церкви", miracle.id()); return null; }
        BlockPos center = home ? bed : BlockPos.ofFloored(spot);
        // What wipes out its own centre (the great crater, the cut-out land) opens beside the home, not under the bed.
        BlockPos strike = center;
        if (home && (miracle == Miracle.GREAT_CRATER || miracle == Miracle.CHUNK_VOID)) {
            int reach = (miracle == Miracle.GREAT_CRATER ? Cataclysm.CRATER_RADIUS[Math.max(1, Math.min(5, severity)) - 1] : 16) + Math.max(8, config.protectRadius);
            double angle = world.random.nextDouble() * Math.PI * 2;
            BlockPos column = center.add((int) Math.round(Math.cos(angle) * reach), 0, (int) Math.round(Math.sin(angle) * reach));
            world.getChunk(column);
            strike = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
        }
        BlockPos target = strike;
        if (miracle != Miracle.BLIGHT) relocating(player); // the blight only shows him the way
        decided.accept(miracle.id() + " → " + (home ? "дом у кровати " + bed.toShortString() : "чистое поле " + center.toShortString()));
        if (miracle == Miracle.BLIGHT) {
            String done = Miracles.perform(miracle, power, player, center, prayer, config, severity);
            if (done == null) return null;
            title(player, Text.literal(home ? "Порча на доме твоём" : "Порча на земле").formatted(Formatting.DARK_RED),
                    Text.literal("Ступай и узри, что сталось с жилищем твоим").formatted(Formatting.RED));
            Prayers.godChat(player, home ? "Не трону храм мой — но земля у дома твоего почернеет. Ступай и узри." : "Нет у тебя дома — так почернеет земля в чистом поле. Ступай и узри.");
            pointTo(player, center, name(miracle), 40);
            return done + " (вдали, указан путь)";
        }
        title(player, Text.literal(home ? "Ступай в дом свой" : "Нет у тебя дома").formatted(Formatting.DARK_RED),
                Text.literal(home ? "и узри гнев мой" : "узри же гнев мой в чистом поле").formatted(Formatting.RED));
        Prayers.godChat(player, home ? "Храм мой не трону. Ступай в дом свой и узри гнев мой." : "Храм мой не трону, а дома у тебя нет. Узри же гнев мой в чистом поле.");
        boolean atHome = home;
        Vec3d to = spot;
        Scheduler.later(60, () -> {
            if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) return;
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, player.getX(), player.getY() + 1, player.getZ(), 60, 0.4, 0.9, 0.4, 0.05);
            float yaw = (float) Math.toDegrees(Math.atan2(-(center.getX() + 0.5 - to.x), center.getZ() + 0.5 - to.z));
            player.teleport(world, to.x, to.y, to.z, atHome ? yaw : player.getYaw(), 0f);
            player.fallDistance = 0;
            Miracles.sound(world, BlockPos.ofFloored(to), SoundEvents.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.6f);
            Scheduler.later(40, () -> {
                if (player.isRemoved() || !player.isAlive() || player.getServerWorld() != world) return;
                String done = performWithFallback(miracle, power, player, target, prayer, config, severity, Miracle.Place.HOME);
                DivineBlockMod.LOG.info("Божественный блок: у дома {} — {}", miracle.id(), done);
                if (done == null) { Prayers.godChat(player, "Но земля здесь не подалась гневу моему. Помни же, куда я мог обратить его."); return; }
                String what = name(lastPerformed == null ? miracle : lastPerformed);
                title(player, Text.literal(atHome ? "Узри жилище своё" : "Узри гнев мой").formatted(Formatting.DARK_RED), Text.literal(what).formatted(Formatting.RED));
                Prayers.godChat(player, (atHome ? "Узри, что сталось с жилищем твоим: " : "Узри гнев мой: ") + what + ".");
                if (player.getPos().distanceTo(Vec3d.ofCenter(target)) > 32) pointTo(player, target, what, 30);
            });
        });
        return "кара у " + (home ? "дома" : "чистого поля") + ": " + miracle.id();
    }

    /** Where to stand by the bed: a free spot with ground under it, or the bed's column top. */
    static Vec3d standNear(ServerWorld world, BlockPos bed) {
        world.getChunk(bed);
        // Outside first, under the open sky by the house, so he sees what falls on it; else by the bed.
        for (int r = 2; r <= 7; r++) for (int dy = 1; dy >= -2; dy--) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            BlockPos at = bed.add(dx, dy, dz);
            if (world.isSkyVisible(at) && world.getBlockState(at.down()).isSideSolidFullSquare(world, at.down(), Direction.UP)
                    && world.getBlockState(at).getCollisionShape(world, at).isEmpty() && world.getBlockState(at.up()).getCollisionShape(world, at.up()).isEmpty()
                    && world.getBlockState(at).getFluidState().isEmpty() && world.getBlockState(at.down()).getFluidState().isEmpty()) return Vec3d.ofBottomCenter(at);
        }
        for (int r = 1; r <= 3; r++) for (int dy = 0; dy >= -1; dy--) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            BlockPos at = bed.add(dx, dy, dz);
            if (world.getBlockState(at.down()).isSideSolidFullSquare(world, at.down(), Direction.UP)
                    && world.getBlockState(at).getCollisionShape(world, at).isEmpty() && world.getBlockState(at.up()).getCollisionShape(world, at.up()).isEmpty()
                    && world.getBlockState(at).getFluidState().isEmpty()) return Vec3d.ofBottomCenter(at);
        }
        BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, bed);
        return top.getY() > world.getBottomY() + 1 ? Vec3d.ofBottomCenter(top) : null;
    }

    /** Open land about a thousand blocks from the church (far from the spawn and its people), dry, out of the protected zone. */
    static Vec3d openLand(ServerWorld world, ChurchSites.Site site) {
        Vec3d from = ChurchArea.center(site);
        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = world.random.nextDouble() * Math.PI * 2, distance = 950 + world.random.nextInt(101);
            BlockPos column = BlockPos.ofFloored(from.x + Math.cos(angle) * distance, 0, from.z + Math.sin(angle) * distance);
            world.getChunk(column);
            BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column);
            BlockState ground = world.getBlockState(top.down());
            if (ChurchArea.inZone(world, top) || !ground.getFluidState().isEmpty() || ground.isIn(BlockTags.LEAVES) || top.getY() <= world.getSeaLevel() - 2) continue;
            if (attempt < 6 && !ChurchArea.openSky(world, top)) continue; // open land first, a forest clearing only if there is none
            return Vec3d.ofBottomCenter(top);
        }
        return null;
    }

    /** The way to a punishment that falls away from the mortal: an arrow and the distance over the hotbar for a while. */
    public static void pointTo(ServerPlayerEntity player, BlockPos target, String what, int seconds) {
        ServerWorld world = player.getServerWorld();
        Scheduler.repeat(10, seconds * 2, i -> {
            if (player.isRemoved() || player.getServerWorld() != world) return;
            player.sendMessage(Text.literal(hint(player.getX(), player.getZ(), player.getYaw(), target, what)).formatted(Formatting.GOLD), true);
        });
    }
    public static String hint(double x, double z, float yaw, BlockPos target, String what) {
        double dx = target.getX() + 0.5 - x, dz = target.getZ() + 0.5 - z;
        float toward = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float relative = MathHelper.wrapDegrees(toward - yaw);
        String[] arrows = { "↑", "↗", "→", "↘", "↓", "↙", "←", "↖" };
        String arrow = arrows[Math.floorMod(Math.round(relative / 45f), 8)];
        return "Гнев мой — там " + arrow + " " + Math.round(Math.sqrt(dx * dx + dz * dz)) + " бл.: " + what;
    }

    static String name(Miracle miracle) {
        return switch (miracle) {
            case METEOR -> "метеориты"; case EARTHQUAKE -> "землетрясение"; case SINKHOLE -> "провал земли"; case TNT_RAIN -> "огненный динамит";
            case TORNADO -> "смерч"; case BLIGHT -> "порча земли"; case METEOR_SHOWER -> "метеоритный дождь"; case CHUNK_VOID -> "земля вырезана до бездны";
            case RIFTS -> "разломы до бездны"; case GREAT_CRATER -> "великая воронка"; case SODOM -> "огонь Содома"; case FLOOD -> "потоп";
            case BABEL -> "падение Вавилона"; case PLAGUES -> "казни египетские";
            default -> miracle.description;
        };
    }

    public static void title(ServerPlayerEntity player, Text title, Text subtitle) {
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(10, 70, 20));
        player.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        player.networkHandler.sendPacket(new TitleS2CPacket(title));
    }
}
