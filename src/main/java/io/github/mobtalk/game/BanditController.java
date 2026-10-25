package io.github.mobtalk.game;

import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.place.Structures;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.gen.structure.Structure;

/** Night bandits in the cities (docs/bandits.md). Once a night each player standing in a city may draw a gang (chance
 * {@code banditNightChance}); it comes at a random hour of that night from the dark a street away, picks the player and asks for a
 * share of their emeralds. The decisions of each bandit are made here every quarter second ({@link #think}); the entity itself only
 * fights, robs and runs. Dawn sends them all away. A city is told by the structure the chunk belongs to ({@code mobtalk:city_*});
 * with {@code banditsInVillages} plain villages count too. */
public final class BanditController {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("mobtalk-bandits");
    private final MobGameBridge bridge;
    /** Per player: the night (day number) already drawn, and the hour of that night its gang comes (-1: none tonight, 0: came). */
    private final Map<UUID, long[]> nights = new HashMap<>();

    BanditController(MobGameBridge bridge) { this.bridge = bridge; }

    static BanditController of(MinecraftServer server) {
        MobGameBridge bridge = server == null ? null : MobGameBridge.active(server);
        return bridge == null ? null : bridge.bandits;
    }
    static MobTalkConfig config(MinecraftServer server) {
        MobGameBridge bridge = server == null ? null : MobGameBridge.active(server);
        return bridge == null ? null : bridge.config;
    }
    private boolean enabled() { return bridge.config.enabled && bridge.config.bandits && BanditContent.BANDIT != null; }

    static boolean night(World world) {
        long time = world.getTimeOfDay() % 24000;
        return time >= 13000 && time < 23000;
    }

    // ---- Where bandits walk ----

    /** Whether the spot lies in a city (any chunk of a {@code mobtalk:city_*} structure), or with {@code banditsInVillages} a village. */
    public boolean territory(ServerWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos)) return false;
        Chunk chunk = world.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return false;
        Map<Structure, ?> refs = chunk.getStructureReferences();
        if (refs.isEmpty()) return false;
        Registry<Structure> structures = world.getRegistryManager().get(RegistryKeys.STRUCTURE);
        for (Structure structure : refs.keySet()) {
            Identifier id = structures.getId(structure);
            if (id == null) continue;
            if (city(id)) return true;
            if (bridge.config.banditsInVillages && Structures.village(id.toString())) return true;
        }
        return false;
    }

    static boolean city(Identifier id) { return id.getNamespace().equals("mobtalk") && id.getPath().startsWith("city_"); }

    // ---- The night's draw ----

    void tick(ServerWorld world) {
        if (world.getRegistryKey() != World.OVERWORLD || world.getTime() % 20 != 7 || !enabled()) return;
        long time = world.getTimeOfDay(), hour = time % 24000, day = time / 24000;
        if (!night(world)) {
            for (BanditEntity bandit : bandits(world)) if (bandit.mode() == BanditEntity.Mode.PROWL || bandit.mode() == BanditEntity.Mode.DEMAND) bandit.leave();
            return;
        }
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
            long[] night = nights.computeIfAbsent(player.getUuid(), id -> new long[] { -1, -1 });
            if (night[0] != day) {
                night[0] = day;
                night[1] = world.random.nextInt(100) < bridge.config.banditNightChance ? 13500 + world.random.nextInt(8000) : -1;
            }
            if (night[1] <= 0 || hour < night[1]) continue;
            if (!territory(world, player.getBlockPos())) { if (hour > 22000) night[1] = -1; continue; }
            if (!bandits(world, player.getBoundingBox().expand(48)).isEmpty()) continue;
            int size = 1 + world.random.nextInt(Math.max(1, bridge.config.banditGangMax));
            if (!spawnGang(world, player, size, true).isEmpty()) night[1] = 0;
        }
    }

    /** Tonight's draw for this player (for /bandits status): "нет", "придут в 19:30", "уже были". */
    String tonight(ServerWorld world, ServerPlayerEntity player) {
        long[] night = nights.get(player.getUuid());
        if (night == null || night[0] != world.getTimeOfDay() / 24000) return "ещё не разыграно";
        if (night[1] == 0) return "уже приходили";
        if (night[1] < 0) return "не придут";
        long hour = (night[1] + 6000) % 24000;
        return String.format("придут около %02d:%02d", hour / 1000, hour % 1000 * 60 / 1000);
    }

    /** Makes the gang come for this player tonight, now (for /bandits night). */
    void callTonight(ServerWorld world, ServerPlayerEntity player) {
        nights.put(player.getUuid(), new long[] { world.getTimeOfDay() / 24000, Math.max(1, world.getTimeOfDay() % 24000) });
    }

    /** A gang of {@code size} out of the dark 14–24 blocks from the player, behind their back if it can; in a city unless
     * {@code inTerritory} is false (the command). Empty when no spot was found. */
    List<BanditEntity> spawnGang(ServerWorld world, ServerPlayerEntity player, int size, boolean inTerritory) {
        List<BanditEntity> gang = new ArrayList<>();
        BlockPos spot = spot(world, player, inTerritory);
        if (spot == null) return gang;
        UUID id = UUID.randomUUID();
        int firstSkin = world.random.nextInt(BanditEntity.SKINS);
        for (int i = 0; i < size; i++) {
            BanditEntity bandit = BanditContent.BANDIT.create(world);
            if (bandit == null) break;
            BlockPos at = i == 0 ? spot : near(world, spot);
            bandit.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, world.random.nextFloat() * 360f, 0);
            bandit.initialize(world, world.getLocalDifficulty(at), SpawnReason.EVENT, null);
            bandit.dress(id, firstSkin + i);
            bandit.tune(bridge.config);
            bandit.stalk(player.getUuid());
            if (world.spawnEntity(bandit)) gang.add(bandit);
        }
        LOGGER.info("Mob Talk: a gang of {} bandits came for {} at {}", gang.size(), player.getName().getString(), spot.toShortString());
        return gang;
    }

    private BlockPos spot(ServerWorld world, ServerPlayerEntity player, boolean inTerritory) {
        Random random = world.random;
        float back = player.getYaw() + 180f;
        for (int attempt = 0; attempt < 40; attempt++) {
            float angle = attempt < 20 ? back + (random.nextFloat() - 0.5f) * 140f : random.nextFloat() * 360f;
            double distance = 14 + random.nextInt(11);
            int x = MathHelper.floor(player.getX() - Math.sin(Math.toRadians(angle)) * distance);
            int z = MathHelper.floor(player.getZ() + Math.cos(Math.toRadians(angle)) * distance);
            BlockPos pos = standable(world, x, z, player.getBlockY());
            if (pos == null) continue;
            if (inTerritory && !territory(world, pos)) continue;
            if (attempt < 30 && inSight(world, player, pos)) continue;
            return pos;
        }
        return null;
    }

    /** Whether the player would see a man standing there (a clear line from their eyes to his head). */
    private static boolean inSight(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
        net.minecraft.util.math.Vec3d head = new net.minecraft.util.math.Vec3d(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5);
        return world.raycast(new net.minecraft.world.RaycastContext(player.getEyePos(), head, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, player)).getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    /** The ground at x, z near this height: the street, not a roof (the surface may be a roof, so a few heights are tried). */
    private static BlockPos standable(ServerWorld world, int x, int z, int y) {
        if (!world.isChunkLoaded(new BlockPos(x, y, z))) return null;
        int top = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(top - y) <= 4 && free(world, new BlockPos(x, top, z))) return new BlockPos(x, top, z);
        for (int dy = -4; dy <= 4; dy++) if (free(world, new BlockPos(x, y + dy, z))) return new BlockPos(x, y + dy, z);
        return null;
    }

    private static boolean free(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos.down()).isSolidBlock(world, pos.down()) && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
                && world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty() && world.getFluidState(pos).isEmpty();
    }

    private static BlockPos near(ServerWorld world, BlockPos spot) {
        for (int i = 0; i < 8; i++) {
            BlockPos pos = standable(world, spot.getX() + world.random.nextInt(5) - 2, spot.getZ() + world.random.nextInt(5) - 2, spot.getY());
            if (pos != null) return pos;
        }
        return spot;
    }

    static List<BanditEntity> bandits(ServerWorld world) {
        List<BanditEntity> all = new ArrayList<>();
        if (BanditContent.BANDIT != null) all.addAll(world.getEntitiesByType(BanditContent.BANDIT, BanditEntity::isAlive));
        return all;
    }
    static List<BanditEntity> bandits(ServerWorld world, net.minecraft.util.math.Box box) {
        return world.getEntitiesByClass(BanditEntity.class, box, BanditEntity::isAlive);
    }

    // ---- One bandit's mind, four times a second ----

    void think(ServerWorld world, BanditEntity bandit) {
        long now = world.getTime();
        MobTalkConfig config = bridge.config;
        ServerPlayerEntity mark = bandit.mark() == null ? null : world.getServer().getPlayerManager().getPlayer(bandit.mark());
        switch (bandit.mode()) {
            case PROWL -> {
                if (!night(world)) { bandit.leave(); return; }
                // The gang came out for one player: they walk up to him and stop him once he is near and in sight.
                if (mark != null && mark.isAlive() && mark.getWorld() == world && !mark.isCreative() && !mark.isSpectator() && bandit.squaredDistanceTo(mark) < 64 * 64) {
                    double distance = bandit.distanceTo(mark);
                    if (distance <= 12 && bandit.canSee(mark)) { accost(world, bandit, mark); return; }
                    if (bandit.getNavigation().isIdle() || bandit.age % 40 == 0) bandit.getNavigation().startMovingTo(mark, 0.9);
                    return;
                }
                ServerPlayerEntity player = victim(world, bandit);
                if (player != null) { accost(world, bandit, player); return; }
                if (config.banditsRobVillagers && bandit.getRandom().nextInt(40) == 0) {
                    List<VillagerEntity> prey = bandit.prey(12);
                    if (!prey.isEmpty()) bandit.fight(prey.get(bandit.getRandom().nextInt(prey.size())));
                }
            }
            case DEMAND -> {
                if (mark == null || !mark.isAlive() || mark.getWorld() != world || mark.isCreative() || mark.isSpectator()) { bandit.leave(); return; }
                if (bridge.actions.busy(bandit)) { bandit.hold(5); return; } // talking: the clock stops, the knife stays in the pocket
                bandit.getLookControl().lookAt(mark, 30f, 30f);
                double distance = bandit.distanceTo(mark);
                if (distance > 2.5) bandit.getNavigation().startMovingTo(mark, 1.0); else bandit.getNavigation().stop();
                if (bandit.getAttacker() == mark) { attack(bandit, mark, "Ах ты так?! Мочи его!"); return; }
                if (bandit.ranOff(mark, 12)) { attack(bandit, mark, "Куда собрался?! Стоять!"); return; }
                if (bandit.late(now)) { attack(bandit, mark, "Время вышло. Сам напросился."); return; }
                if (bandit.remind(now)) bandit.say(mark, "Я жду. " + bandit.owed() + " изумр., быстро.");
            }
            case FIGHT -> {
                LivingEntity target = bandit.getTarget();
                if (target == null || !target.isAlive()) {
                    if (mark != null && !mark.isAlive()) {
                        bandit.pickUpSpoils();
                        for (BanditEntity mate : bandit.mates()) { mate.pickUpSpoils(); mate.leave(); }
                        bandit.say(null, "Готов. Сваливаем.");
                    }
                    if (bandit.mode() == BanditEntity.Mode.FIGHT) bandit.leave();
                    return;
                }
                if (bandit.squaredDistanceTo(target) > 40 * 40) bandit.leave();
            }
            case FLEE -> bandit.runOff(world, mark != null ? mark.getPos() : null);
            case LEAVE -> bandit.runOff(world, mark != null ? mark.getPos() : null);
        }
    }

    /** The player the gang goes for: in reach and in sight, not flying in creative, not already someone's business. */
    private ServerPlayerEntity victim(ServerWorld world, BanditEntity bandit) {
        ServerPlayerEntity best = null; double nearest = 20 * 20;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isCreative() || player.isSpectator()) continue;
            double distance = player.squaredDistanceTo(bandit);
            if (distance < nearest && bandit.canSee(player)) { nearest = distance; best = player; }
        }
        return best;
    }

    /** "Your money or your life": a share of the emeralds the player has, or a fight when there are none. */
    private void accost(ServerWorld world, BanditEntity bandit, ServerPlayerEntity player) {
        MobTalkConfig config = bridge.config;
        int emeralds = BanditEntity.emeralds(player);
        List<BanditEntity> gang = bandit.mates();
        if (!gang.contains(bandit)) gang.add(bandit);
        String name = MobGameBridge.playerName(player);
        if (emeralds <= 0) {
            bandit.say(player, pick(world.random, "Пустой? Тогда снимай, что есть.", "Ни изумруда? Ну, сам виноват.", "Голодранец… Ладно, разденем."));
            for (BanditEntity mate : gang) mate.fight(player);
            return;
        }
        int amount = MathHelper.clamp(Math.round(emeralds * config.banditDemandPercent / 100f), 1, 64);
        for (BanditEntity mate : gang) mate.ask(player, amount, world.getTime(), config.banditDemandSeconds);
        bandit.say(player, pick(world.random,
                "Слышь, " + name + ", кошелёк или жизнь. С тебя " + amount + " изумр.",
                "Стоять, " + name + ". " + amount + " изумр. — и разойдёмся по-хорошему.",
                "Тихо, " + name + ", без резких движений. Гони " + amount + " изумр.") + " (ПКМ изумрудами по бандиту)");
    }

    private void attack(BanditEntity bandit, ServerPlayerEntity player, String line) {
        bandit.say(player, line);
        for (BanditEntity mate : bandit.mates()) mate.fight(player);
        bandit.fight(player);
    }

    String thanks(Random random) { return pick(random, "Приятно иметь дело.", "Вот и умница. Гуляй.", "Живи пока. Мы тебя запомнили."); }

    void robbed(BanditEntity bandit, ServerPlayerEntity player, String what) {
        LOGGER.info("Mob Talk: {} robbed {} of {}", bandit.getName().getString(), player.getName().getString(), what);
    }

    void robbedVillager(BanditEntity bandit, VillagerEntity villager) {
        LOGGER.info("Mob Talk: {} mugged a villager at {}", bandit.getName().getString(), villager.getBlockPos().toShortString());
    }

    private static String pick(Random random, String... lines) { return lines[random.nextInt(lines.length)]; }

    void clearRuntime() { nights.clear(); }
}
