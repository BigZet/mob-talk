package io.github.divineblock.game;

import io.github.divineblock.DivineBlockMod;
import io.github.divineblock.world.ChurchSites;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;

/** The rector of every church (confession, Mob Talk's priests): when a mortal first comes to a church, a male cleric with a name
 * from the saints' calendar appears by the analoy before the iconostasis, tagged {@link #TAG} — Mob Talk knows him by it and hears
 * confessions with him in the church. He keeps within the church's wall; killed, a new one comes after a game day (the killer
 * bears the heavy sin, {@link DeedWatcher}). Who stands where is remembered with the world ({@link DivineState#abbots}).
 * Server thread only; off with the setting {@code abbots}. */
public final class Abbots {
    /** The command tag of a church's rector. */
    public static final String TAG = "divineblock_abbot";
    private static final int PULSE = 100;
    private static final long RETURN_TICKS = 24_000;
    /** Priests' names after the saints, in the form a priest is called by: «отец Сергий». */
    static final List<String> NAMES = List.of("Сергий", "Николай", "Серафим", "Иоанн", "Алексий", "Феодор", "Димитрий", "Василий", "Павел",
            "Пётр", "Михаил", "Андрей", "Георгий", "Тихон", "Игнатий", "Макарий", "Амвросий", "Филарет", "Иов", "Варнава", "Паисий", "Никон",
            "Иларион", "Антоний", "Феодосий", "Владимир", "Константин", "Григорий", "Леонид", "Афанасий");
    private static int ticks;

    /** One church's rector as the world remembers him: who, and the tick he died (−1 alive or never yet called). */
    public static final class Rector { public String uuid = "", name = ""; public long diedTick = -1, missingTick = -1; }
    /** A rector not found by his church for this long (taken away, removed by a command) is replaced. */
    private static final long MISSING_TICKS = 24_000;

    /** The saved map, kept in {@link DivineState}. */
    public static final class Book { public Map<String, Rector> rectors = new HashMap<>(); }

    private Abbots() { }

    static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof VillagerEntity villager) || !villager.getCommandTags().contains(TAG) || villager.getServer() == null) return;
            DivineState state = DivineState.of(villager.getServer());
            for (Rector rector : state.abbots.values())
                if (rector.uuid.equals(villager.getUuidAsString())) { rector.diedTick = villager.getServer().getOverworld().getTime(); state.markDirty(); }
        });
    }

    static void tick(MinecraftServer server) {
        if (++ticks % PULSE != 0 || !DivineBlockMod.config().abbots) return;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.isSpectator()) continue;
            ServerWorld world = player.getServerWorld();
            Optional<ChurchSites.Site> found = ChurchArea.near(world, player.getBlockPos());
            if (found.isPresent()) visit(world, found.get());
        }
    }

    /** A mortal is at this church: its rector is called, brought back inside the wall, or replaced a day after his death. */
    public static VillagerEntity visit(ServerWorld world, ChurchSites.Site site) {
        DivineState state = DivineState.of(world.getServer());
        String key = key(world, site);
        Rector rector = state.abbots.computeIfAbsent(key, k -> new Rector());
        long now = world.getServer().getOverworld().getTime();
        BlockPos spot = site.rectorSpot();
        if (!world.isChunkLoaded(spot.getX() >> 4, spot.getZ() >> 4)) return null;
        // One rector per church: the remembered one, else a tagged one found by the church (an older save, a lost record); any
        // other tagged villager here becomes an ordinary cleric — two rectors on one spot drew two names over one head.
        VillagerEntity alive = rector.uuid.isBlank() ? null : world.getEntity(UUID.fromString(rector.uuid)) instanceof VillagerEntity v && v.isAlive() ? v : null;
        List<VillagerEntity> tagged = world.getEntitiesByClass(VillagerEntity.class, area(site), v -> v.isAlive() && v.getCommandTags().contains(TAG));
        if (alive == null && !tagged.isEmpty()) {
            alive = tagged.get(0);
            rector.uuid = alive.getUuidAsString(); rector.diedTick = -1;
            if (alive.hasCustomName()) rector.name = alive.getCustomName().getString();
            state.markDirty();
        }
        for (VillagerEntity extra : tagged) if (extra != alive) demote(extra);
        if (alive != null) {
            rector.missingTick = -1;
            keepInside(alive, site, spot);
            return alive;
        }
        if (!rector.uuid.isBlank() && rector.diedTick < 0) {
            // Not among the loaded entities: perhaps in an unloaded corner. Replaced only after a game day of visits without him.
            if (rector.missingTick < 0) { rector.missingTick = now; state.markDirty(); return null; }
            if (now - rector.missingTick < MISSING_TICKS) return null;
        }
        if (rector.diedTick >= 0 && now - rector.diedTick < RETURN_TICKS) return null;
        VillagerEntity priest = EntityType.VILLAGER.create(world);
        if (priest == null) return null;
        if (rector.name.isBlank()) rector.name = "отец " + NAMES.get(Math.floorMod(site.origin().hashCode() + rector.uuid.hashCode(), NAMES.size()));
        else if (rector.diedTick >= 0) rector.name = "отец " + NAMES.get(Math.floorMod(rector.name.hashCode() + (int) now, NAMES.size()));
        priest.refreshPositionAndAngles(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0, 0);
        priest.setVillagerData(priest.getVillagerData().withProfession(VillagerProfession.CLERIC).withLevel(5));
        priest.setExperience(250); // A master keeps his calling even far from a brewing stand.
        priest.setPersistent();
        priest.setCustomName(Text.literal(rector.name));
        priest.addCommandTag(TAG);
        if (!world.spawnEntity(priest)) return null;
        rector.uuid = priest.getUuidAsString(); rector.diedTick = -1; rector.missingTick = -1;
        state.markDirty();
        DivineBlockMod.LOG.info("Божественный блок: в церкви у {} {} {} настоятель — {}", spot.getX(), spot.getY(), spot.getZ(), rector.name);
        return priest;
    }

    /** The rector keeps to his church: further than 12 blocks from the analoy he walks back, further than 40 (pushed, lured, wandered
     * off towards unloaded ground) he is brought back at once. */
    private static void keepInside(VillagerEntity priest, ChurchSites.Site site, BlockPos spot) {
        double distance = priest.getBlockPos().getSquaredDistance(spot);
        if (distance <= 12 * 12) return;
        if (distance > 40 * 40) priest.refreshPositionAndAngles(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, priest.getYaw(), 0);
        else priest.getNavigation().startMovingTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, 0.6);
    }
    /** The church, its yard and a margin: where its rector may be. */
    private static net.minecraft.util.math.Box area(ChurchSites.Site site) {
        BlockBox yard = site.yard(), church = site.church();
        return new net.minecraft.util.math.Box(Math.min(yard.getMinX(), church.getMinX()) - 16, Math.min(yard.getMinY(), church.getMinY()) - 16,
                Math.min(yard.getMinZ(), church.getMinZ()) - 16, Math.max(yard.getMaxX(), church.getMaxX()) + 17, Math.max(yard.getMaxY(), church.getMaxY()) + 17,
                Math.max(yard.getMaxZ(), church.getMaxZ()) + 17);
    }
    /** A second rector becomes an ordinary cleric: no tag, no title. */
    private static void demote(VillagerEntity extra) {
        extra.removeCommandTag(TAG);
        extra.setCustomName(null);
        DivineBlockMod.LOG.info("Божественный блок: второй настоятель у церкви стал простым клириком ({})", extra.getUuidAsString());
    }

    private static String key(ServerWorld world, ChurchSites.Site site) {
        BlockPos o = site.origin();
        return world.getRegistryKey().getValue() + "|" + o.getX() + "|" + o.getY() + "|" + o.getZ();
    }

    /** Every church's rector the world remembers, for the self-test and the admin. */
    public static Map<String, Rector> all(MinecraftServer server) { return new HashMap<>(DivineState.of(server).abbots); }
}
