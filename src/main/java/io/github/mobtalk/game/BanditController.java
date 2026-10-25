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
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestType;
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

    public static BanditController of(MinecraftServer server) {
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
                Grudge grudge = saved(world).of(player.getUuid());
                int chance = bridge.config.banditNightChance + (grudge.killed > 0 ? 30 : 0); // a gang that lost a man comes back sooner
                night[1] = world.random.nextInt(100) < chance ? 13500 + world.random.nextInt(8000) : -1;
            }
            if (night[1] <= 0 || hour < night[1]) continue;
            if (!territory(world, player.getBlockPos())) { if (hour > 22000) night[1] = -1; continue; }
            if (!bandits(world, player.getBoundingBox().expand(48)).isEmpty()) continue;
            Grudge grudge = saved(world).of(player.getUuid());
            int size = 1 + world.random.nextInt(Math.max(1, bridge.config.banditGangMax)) + (grudge.killed > 0 ? 1 : 0);
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

    /** Where the gang steps out: of up to forty candidate spots 14–24 blocks from the player (behind their back first), the best by
     * {@link #lurk}: dark, by a door or under an arch, out of the player's sight, far from a police or guard post. */
    private BlockPos spot(ServerWorld world, ServerPlayerEntity player, boolean inTerritory) {
        Random random = world.random;
        float back = player.getYaw() + 180f;
        BlockPos best = null; int bestScore = Integer.MIN_VALUE;
        for (int attempt = 0; attempt < 40; attempt++) {
            float angle = attempt < 20 ? back + (random.nextFloat() - 0.5f) * 140f : random.nextFloat() * 360f;
            double distance = 14 + random.nextInt(11);
            int x = MathHelper.floor(player.getX() - Math.sin(Math.toRadians(angle)) * distance);
            int z = MathHelper.floor(player.getZ() + Math.cos(Math.toRadians(angle)) * distance);
            BlockPos pos = standable(world, x, z, player.getBlockY());
            if (pos == null) continue;
            if (inTerritory && !territory(world, pos)) continue;
            if (!world.isSkyVisible(pos)) continue; // in the yard, by the stairwell door, not inside somebody's flat
            int score = lurk(world, pos) - (inSight(world, player, pos) ? 40 : 0) - 10 * Math.abs(pos.getY() - player.getBlockY());
            if (score > bestScore) { bestScore = score; best = pos; }
        }
        return best;
    }

    /** How good a spot is to wait in: darkness (block light; the city's lamps light the streets), a door within three blocks (a
     * stairwell, a yard gate), and no post of the police or the guard within 24 blocks. */
    public static int lurk(ServerWorld world, BlockPos pos) {
        int score = (15 - world.getLightLevel(net.minecraft.world.LightType.BLOCK, pos)) * 3;
        for (BlockPos near : BlockPos.iterate(pos.add(-3, -1, -3), pos.add(3, 2, 3)))
            if (world.getBlockState(near).getBlock() instanceof net.minecraft.block.DoorBlock) { score += 12; break; }
        if (watched(world, pos)) score -= 60;
        return score;
    }

    /** A police post (the police track's {@code mobtalk:police_post}, when that mod part is present) or a guard stand near. */
    static boolean watched(ServerWorld world, BlockPos pos) {
        var types = Registries.POINT_OF_INTEREST_TYPE;
        java.util.function.Predicate<RegistryEntry<PointOfInterestType>> post = entry -> entry.matchesKey(DefenseContent.GUARD_POST_POI)
                || entry.getKey().map(key -> key.getValue().equals(POLICE_POST)).orElse(false);
        if (types.get(POLICE_POST) == null && DefenseContent.GUARD_POST == null) return false;
        return world.getPointOfInterestStorage().getInCircle(post, pos, 24, PointOfInterestStorage.OccupationStatus.ANY).findAny().isPresent();
    }
    static final Identifier POLICE_POST = Identifier.of("mobtalk", "police_post");

    /** Whether the player would see a man standing there (a clear line from their eyes to his head). */
    private static boolean inSight(ServerWorld world, ServerPlayerEntity player, BlockPos pos) {
        net.minecraft.util.math.Vec3d head = new net.minecraft.util.math.Vec3d(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5);
        return world.raycast(new net.minecraft.world.RaycastContext(player.getEyePos(), head, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE, player)).getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    /** The ground at x, z nearest the player's height: the street he walks, not a roof or a balcony above it. */
    private static BlockPos standable(ServerWorld world, int x, int z, int y) {
        if (!world.isChunkLoaded(new BlockPos(x, y, z))) return null;
        for (int i = 0; i <= 4; i++) {
            int dy = (i + 1) / 2 * (i % 2 == 0 ? 1 : -1); // 0, -1, 1, -2, 2…
            BlockPos pos = new BlockPos(x, y + dy, z);
            if (free(world, pos)) return pos;
        }
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
        Grudge grudge = saved(world).of(player.getUuid());
        if (grudge.killed > 0) {
            // Revenge: no talk, no price. The grudge is settled with this gang, whatever comes of it.
            bandit.say(player, pick(world.random, "Это ты " + grudge.victim + " положил? Ну всё, " + name + ", молись.",
                    "За " + grudge.victim + " ответишь, " + name + ".", "Узнаёшь? Мы за " + grudge.victim + "."));
            grudge.killed = 0; saved(world).markDirty();
            for (BanditEntity mate : gang) mate.fight(player);
            return;
        }
        if (emeralds <= 0) {
            bandit.say(player, pick(world.random, "Пустой? Тогда снимай, что есть.", "Ни изумруда? Ну, сам виноват.", "Голодранец… Ладно, разденем."));
            for (BanditEntity mate : gang) mate.fight(player);
            return;
        }
        // One who paid before is an easy mark: every payment remembered adds a tenth, up to three.
        int percent = Math.min(100, config.banditDemandPercent + 10 * Math.min(3, grudge.paid));
        int amount = MathHelper.clamp(Math.round(emeralds * percent / 100f), 1, 64);
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

    // ---- What the gangs remember of each player (saved with the world) ----

    /** A player as the city's gangs know him: bandits he killed (and the last one's nick) and how many times he paid. */
    static final class Grudge { int killed, paid; String victim = ""; }

    static final class Saved extends net.minecraft.world.PersistentState {
        final Map<UUID, Grudge> players = new HashMap<>();
        static final Type<Saved> TYPE = new Type<>(Saved::new, Saved::read, null);
        Grudge of(UUID player) { return players.computeIfAbsent(player, id -> new Grudge()); }
        static Saved read(net.minecraft.nbt.NbtCompound tag, net.minecraft.registry.RegistryWrapper.WrapperLookup lookup) {
            Saved saved = new Saved();
            net.minecraft.nbt.NbtCompound all = tag.getCompound("players");
            for (String key : all.getKeys()) {
                try {
                    net.minecraft.nbt.NbtCompound row = all.getCompound(key);
                    Grudge grudge = saved.of(UUID.fromString(key));
                    grudge.killed = row.getInt("killed"); grudge.paid = row.getInt("paid"); grudge.victim = row.getString("victim");
                } catch (IllegalArgumentException ignored) { }
            }
            return saved;
        }
        @Override public net.minecraft.nbt.NbtCompound writeNbt(net.minecraft.nbt.NbtCompound tag, net.minecraft.registry.RegistryWrapper.WrapperLookup lookup) {
            net.minecraft.nbt.NbtCompound all = new net.minecraft.nbt.NbtCompound();
            players.forEach((id, grudge) -> {
                net.minecraft.nbt.NbtCompound row = new net.minecraft.nbt.NbtCompound();
                row.putInt("killed", grudge.killed); row.putInt("paid", grudge.paid); row.putString("victim", grudge.victim);
                all.put(id.toString(), row);
            });
            tag.put("players", all);
            return tag;
        }
    }

    static Saved saved(ServerWorld world) { return world.getServer().getOverworld().getPersistentStateManager().getOrCreate(Saved.TYPE, "mobtalk_bandits"); }

    /** A player killed a bandit: the city's gangs will come for him (a likelier night, one more man, no talk). */
    void killed(ServerWorld world, ServerPlayerEntity player, BanditEntity bandit) {
        Grudge grudge = saved(world).of(player.getUuid());
        grudge.killed++;
        String nick = bandit.getName().getString();
        grudge.victim = nick.startsWith("Бандит ") ? nick.substring(7) : nick;
        saved(world).markDirty();
        for (BanditEntity mate : bandit.mates()) if (mate != bandit && mate.isAlive()) mate.say(player, pick(world.random, "Ты за это ответишь!", "Мы тебя найдём.", "Запомни мою рожу."));
    }

    /** A player paid in full: next time they ask him for more. */
    void paid(ServerWorld world, ServerPlayerEntity player) { saved(world).of(player.getUuid()).paid++; saved(world).markDirty(); }

    /** For /bandits status. */
    String memory(ServerWorld world, ServerPlayerEntity player) {
        Grudge grudge = saved(world).of(player.getUuid());
        return "убито вами: " + grudge.killed + (grudge.killed > 0 ? " (" + grudge.victim + ")" : "") + ", платили: " + grudge.paid;
    }
}
