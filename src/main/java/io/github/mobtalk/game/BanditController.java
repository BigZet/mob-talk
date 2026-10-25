package io.github.mobtalk.game;

import io.github.mobtalk.core.BanditHaggle;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.place.Structures;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
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
import net.minecraft.text.Text;
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
        if (hour >= 11500 && hour < 13000) warn(world, day);
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
    private long lastMiss = -1000;
    private BlockPos spot(ServerWorld world, ServerPlayerEntity player, boolean inTerritory) {
        Random random = world.random;
        float back = player.getYaw() + 180f;
        BlockPos best = null; int bestScore = Integer.MIN_VALUE, ground = 0, inside = 0;
        for (int attempt = 0; attempt < 40; attempt++) {
            float angle = attempt < 20 ? back + (random.nextFloat() - 0.5f) * 140f : random.nextFloat() * 360f;
            double distance = 14 + random.nextInt(11);
            int x = MathHelper.floor(player.getX() - Math.sin(Math.toRadians(angle)) * distance);
            int z = MathHelper.floor(player.getZ() + Math.cos(Math.toRadians(angle)) * distance);
            BlockPos pos = standable(world, x, z, player.getBlockY());
            if (pos == null) continue;
            ground++;
            if (inTerritory && !territory(world, pos)) continue;
            inside++;
            if (!world.isSkyVisible(pos)) continue; // in the yard, by the stairwell door, not inside somebody's flat
            int score = lurk(world, pos) - (inSight(world, player, pos) ? 40 : 0) - 10 * Math.abs(pos.getY() - player.getBlockY());
            if (score > bestScore) { bestScore = score; best = pos; }
        }
        if (best == null && world.getTime() - lastMiss > 200) {
            lastMiss = world.getTime();
            LOGGER.info("Mob Talk: no place for a gang near {} (of 40 tries: ground {}, in the city {}, under the sky none)", player.getBlockPos().toShortString(), ground, inside);
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
    static boolean watched(ServerWorld world, BlockPos pos) { return watched(world, pos, 24); }
    static boolean watched(ServerWorld world, BlockPos pos, int radius) {
        var types = Registries.POINT_OF_INTEREST_TYPE;
        java.util.function.Predicate<RegistryEntry<PointOfInterestType>> post = entry -> entry.matchesKey(DefenseContent.GUARD_POST_POI)
                || entry.getKey().map(key -> key.getValue().equals(POLICE_POST)).orElse(false);
        if (types.get(POLICE_POST) == null && DefenseContent.GUARD_POST == null) return false;
        return world.getPointOfInterestStorage().getInCircle(post, pos, radius, PointOfInterestStorage.OccupationStatus.ANY).findAny().isPresent();
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

    /** Room for a man: something firm underfoot (a full block, a path, a slab: the city's paving is not all full blocks), and air
     * for his body. */
    private static boolean free(ServerWorld world, BlockPos pos) {
        net.minecraft.util.shape.VoxelShape floor = world.getBlockState(pos.down()).getCollisionShape(world, pos.down());
        return !floor.isEmpty() && floor.getMax(net.minecraft.util.math.Direction.Axis.Y) >= 0.5 && floor.getMax(net.minecraft.util.math.Direction.Axis.Y) <= 1.0 && world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
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
                    if (config.banditFearLight && lit(mark)) {
                        // A light in his hand: they keep to the dark at a distance, and give up after a minute of it.
                        if (bandit.shy(now) > 1200) {
                            bandit.say(mark, pick(world.random, "Светит, гад… Пошли отсюда.", "С фонарём ходит, умный. Ладно, в другой раз.", "Не, со светом не полезем. Уходим."));
                            for (BanditEntity mate : bandit.mates()) mate.leave();
                            bandit.leave();
                            return;
                        }
                        bandit.getLookControl().lookAt(mark, 30f, 30f);
                        if (distance < 7) bandit.backOff(mark.getPos());
                        else if (distance > 16) bandit.getNavigation().startMovingTo(mark, 0.8);
                        else bandit.getNavigation().stop();
                        return;
                    }
                    bandit.unshy();
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
                        news(bandit, mark, "ночью бандиты зарезали во дворе " + MobGameBridge.playerName(mark) + " и сняли с него всё", 4, 0);
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
            if (bridge.config.banditFearLight && lit(player)) continue;
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

    /** A torch or a lantern in either hand. */
    static boolean lit(PlayerEntity player) {
        for (net.minecraft.item.ItemStack stack : List.of(player.getMainHandStack(), player.getOffHandStack()))
            if (stack.isOf(net.minecraft.item.Items.TORCH) || stack.isOf(net.minecraft.item.Items.SOUL_TORCH)
                    || stack.isOf(net.minecraft.item.Items.LANTERN) || stack.isOf(net.minecraft.item.Items.SOUL_LANTERN)) return true;
        return false;
    }

    /** The gang's signal: a finger whistle from this bandit, heard across the yard. */
    static void whistle(BanditEntity bandit) {
        if (BanditContent.WHISTLE != null)
            bandit.getWorld().playSound(null, bandit.getBlockPos(), BanditContent.WHISTLE, net.minecraft.sound.SoundCategory.HOSTILE, 1.6f, 0.95f + bandit.getRandom().nextFloat() * 0.1f);
    }

    private void attack(BanditEntity bandit, ServerPlayerEntity player, String line) {
        whistle(bandit);
        bandit.say(player, line);
        for (BanditEntity mate : bandit.mates()) mate.fight(player);
        bandit.fight(player);
    }

    String thanks(Random random) { return pick(random, "Приятно иметь дело.", "Вот и умница. Гуляй.", "Живи пока. Мы тебя запомнили."); }

    void robbed(BanditEntity bandit, ServerPlayerEntity player, String what) {
        LOGGER.info("Mob Talk: {} robbed {} of {}", bandit.getName().getString(), player.getName().getString(), what);
        news(bandit, player, "ночью у подъезда бандиты обобрали " + MobGameBridge.playerName(player), 3, 0);
    }

    void robbedVillager(BanditEntity bandit, VillagerEntity villager) {
        LOGGER.info("Mob Talk: {} mugged a villager at {}", bandit.getName().getString(), villager.getBlockPos().toShortString());
        String name = bridge.memory.displayName(villager.getUuid());
        if (name.isBlank()) name = villager.getName().getString();
        // The victim cries out to anyone near.
        Text cry = Text.literal("<" + name + "> ").append(Text.literal(pick(villager.getRandom(), "Помогите! Грабят!", "Караул! Ограбили!", "Держи вора!")));
        for (ServerPlayerEntity near : ((ServerWorld) villager.getWorld()).getPlayers(p -> p.squaredDistanceTo(villager) < 32 * 32)) near.sendMessage(cry, false);
        news(bandit, null, "ночью бандиты ограбили во дворе " + name, 3, 0);
    }

    /** The night's deed becomes the city's news ({@code banditRumors}): every resident of the village there knows it, told by a
     * villager who saw it, if one did. {@code player} is the rumour's subject, or null. */
    void news(BanditEntity bandit, ServerPlayerEntity player, String text, int importance, int sign) {
        if (!bridge.config.banditRumors || !(bandit.getWorld() instanceof ServerWorld world)) return;
        var village = bridge.villageAt(world, bandit.getBlockPos());
        if (village == null && bridge.config.villageSites) {
            // As the god's deeds do (Б1): news in a city nobody talked in yet founds its village; its first villager's biography names it.
            var site = VillageSites.at(world, bandit.getBlockPos());
            if (site != null) village = bridge.memory.createVillage(world.getRegistryKey().getValue().toString(), site.x(), site.z(), "", "", site.key());
        }
        if (village == null) return;
        VillagerEntity teller = null;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, bandit.getBoundingBox().expand(24), v -> v.isAlive() && v.canSee(bandit)))
            if (teller == null || villager.squaredDistanceTo(bandit) < teller.squaredDistanceTo(bandit)) teller = villager;
        String id = bridge.memory.villageNews(village.id(), "bandits-" + world.getTime(), text, teller == null ? null : teller.getUuid(),
                player == null ? "" : MobGameBridge.playerName(player), player == null ? null : player.getUuid(), importance, sign, System.currentTimeMillis());
        if (!id.isBlank()) LOGGER.info("Mob Talk: the city hears that {}", text);
    }

    /** At dusk, once a day, a villager near a player in the city warns him off the yards when the city talks of a robbery. */
    private void warn(ServerWorld world, long day) {
        if (!bridge.config.banditRumors) return;
        long now = System.currentTimeMillis();
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!player.isAlive() || player.isSpectator() || warned.getOrDefault(player.getUuid(), -1L) == day) continue;
            if (!territory(world, player.getBlockPos())) continue;
            var village = bridge.villageAt(world, player.getBlockPos());
            if (village == null) continue;
            io.github.mobtalk.core.VillageRumors.View story = null;
            for (var rumor : bridge.memory.villageRumors(village.id(), now))
                if (rumor.id().startsWith("n") && rumor.text().contains("бандит") && (story == null || rumor.time() > story.time())) story = rumor;
            if (story == null) continue;
            VillagerEntity speaker = null;
            for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(12), v -> v.isAlive() && !v.isBaby() && v.canSee(player)))
                if (speaker == null || villager.squaredDistanceTo(player) < speaker.squaredDistanceTo(player)) speaker = villager;
            if (speaker == null) continue;
            warned.put(player.getUuid(), day);
            String name = bridge.memory.displayName(speaker.getUuid());
            if (name.isBlank()) name = speaker.getName().getString();
            String told = story.text(), you = MobGameBridge.playerName(player);
            String line = you.equals(story.subject())
                    ? pick(world.random, "Ты бы ночью дворами не ходил, " + you + ": бандиты тебя уже приметили.",
                            "Слыхал я про твою ночку, " + you + ". Держись у фонарей, они вернутся.")
                    : null;
            if (line != null) player.sendMessage(Text.literal("<" + name + "> ").append(Text.literal(line)), false);
            else player.sendMessage(Text.literal("<" + name + "> ").append(Text.literal(pick(world.random,
                    "Ты бы ночью дворами не ходил, " + MobGameBridge.playerName(player) + ". Говорят, " + told + ".",
                    "Темнеет… Слыхал? " + Character.toUpperCase(told.charAt(0)) + told.substring(1) + ". Держись у фонарей.",
                    "Осторожней вечером, " + MobGameBridge.playerName(player) + ": " + told + "."))), false);
            LOGGER.info("Mob Talk: {} warned {} about the bandits", name, player.getName().getString());
        }
    }
    private final Map<UUID, Long> warned = new HashMap<>();

    private static String pick(Random random, String... lines) { return lines[random.nextInt(lines.length)]; }

    void clearRuntime() { nights.clear(); warned.clear(); }

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
        news(bandit, player, MobGameBridge.playerName(player) + " отбился ночью от бандитов и одного уложил", 4, 1);
        Grudge grudge = saved(world).of(player.getUuid());
        grudge.killed++;
        String nick = bandit.getName().getString();
        grudge.victim = nick.startsWith("Бандит ") ? nick.substring(7) : nick;
        saved(world).markDirty();
        for (BanditEntity mate : bandit.mates()) if (mate != bandit && mate.isAlive()) mate.say(player, pick(world.random, "Ты за это ответишь!", "Мы тебя найдём.", "Запомни мою рожу."));
    }

    /** Whether a threat to call for help frightens the gang: a guard or police post within 40 blocks, or a guard, a golem or a
     * policeman within 24. */
    static boolean guarded(ServerWorld world, BlockPos pos) {
        if (watched(world, pos, 40)) return true;
        return !world.getEntitiesByClass(LivingEntity.class, new net.minecraft.util.math.Box(pos).expand(24), BanditController::defender).isEmpty();
    }
    private static boolean defender(LivingEntity entity) {
        if (!entity.isAlive()) return false;
        if (entity instanceof net.minecraft.entity.passive.IronGolemEntity) return true;
        if (Registries.ENTITY_TYPE.getId(entity.getType()).getPath().contains("police")) return true; // the police track's man, by name only
        if (!(entity instanceof VillagerEntity villager)) return false;
        var profession = villager.getVillagerData().getProfession();
        if (Registries.VILLAGER_PROFESSION.getId(profession).getPath().contains("police")) return true;
        return profession == DefenseContent.GUARD || profession == DefenseContent.HEAVY || profession == DefenseContent.CAVALRY;
    }

    /** The robbery as {@link BanditHaggle} sees it, for this bandit asking this player; null when he asks nothing of him. */
    BanditHaggle.State haggleState(ServerWorld world, BanditEntity bandit, ServerPlayerEntity player) {
        if (bandit.mode() != BanditEntity.Mode.DEMAND || !player.getUuid().equals(bandit.mark())) return null;
        return new BanditHaggle.State(bandit.demand(), bandit.paidSoFar(), BanditEntity.emeralds(player), guarded(world, bandit.getBlockPos()), bandit.haggled());
    }

    /** The player said something to a bandit who robs him (a Mob Talk conversation, or /bandits say): the game reads it as a move
     * and the whole gang acts on it. Returns the note for the player, "" when the words were no move. */
    String haggle(ServerWorld world, BanditEntity bandit, ServerPlayerEntity player, String said) {
        if (!bridge.config.banditHaggle) return "";
        BanditHaggle.State state = haggleState(world, bandit, player);
        if (state == null) return "";
        BanditHaggle.Outcome outcome = BanditHaggle.decide(BanditHaggle.read(said), state);
        List<BanditEntity> gang = bandit.mates();
        if (!gang.contains(bandit)) gang.add(bandit);
        switch (outcome.kind()) {
            case NONE -> { return ""; }
            case LOWER, REFUSE, RAISE -> { for (BanditEntity mate : gang) mate.rebargain(outcome.demand(), outcome.seconds(), outcome.used()); }
            case LEAVE -> {
                bandit.say(player, pick(world.random, "Тихо-тихо, не шуми… Уходим, пацаны.", "Ладно, живи. Шухер!", "Не ори. Мы уже уходим."));
                for (BanditEntity mate : gang) mate.leave();
            }
            case FIGHT -> attack(bandit, player, pick(world.random, "Достал. Мочи его!", "Ну всё, сам напросился."));
        }
        LOGGER.info("Mob Talk: {} haggled with {}: {} -> {}", player.getName().getString(), bandit.getName().getString(), outcome.kind(), outcome.note());
        return outcome.note();
    }

    /** A player paid in full: next time they ask him for more. */
    void paid(ServerWorld world, ServerPlayerEntity player, BanditEntity bandit) {
        saved(world).of(player.getUuid()).paid++; saved(world).markDirty();
        news(bandit, player, MobGameBridge.playerName(player) + " ночью откупился от бандитов изумрудами", 2, 0);
    }

    /** For /bandits status. */
    String memory(ServerWorld world, ServerPlayerEntity player) {
        Grudge grudge = saved(world).of(player.getUuid());
        return "убито вами: " + grudge.killed + (grudge.killed > 0 ? " (" + grudge.victim + ")" : "") + ", платили: " + grudge.paid;
    }
}
