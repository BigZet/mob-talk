package io.github.mobtalk.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.*;
import io.github.mobtalk.net.MobSpeechS2C;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BellBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillageGossipType;
import net.minecraft.village.VillagerProfession;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/** Idea Б1 «Жители знают о гневе бога» in the world: the divine block reports each deed ({@link DivineTidings.Deed}) — pushed through
 * the {@code mobtalk-events} entrypoint, or pulled every ten seconds through its {@code mobtalk-admin} source as a fallback — and here
 * the villages it reached learn of it: the rumour and the relationship changes ({@link ConversationStore#divineVillage}), a surcharge
 * or a discount through vanilla gossip, and the scene in the street (the bell, panic, shouts, the cleric at the bell, the golem
 * stepping up). Three punishments for one sinner in a day call the bell assembly: no penance by sunset and the village drives them
 * out. Every part keeps to its F7/F9 switch, and nothing here waits on an LLM. Server thread only. */
final class DivineController {
    private static final long PULL_TICKS = 200, ASSEMBLY_TICKS = 100;
    /** Events older than this (an old buffer after a restart) are counted as seen but stir nobody. */
    private static final long STALE_MILLIS = 10 * 60_000L;
    private static final double WITNESS_RANGE = 16, SHOUT_RANGE = 32, GOLEM_RANGE = 32, GOSSIP_RANGE = 64, WORLD_REACH = 96;
    /** Vanilla golems turn on a player at reputation −100; the surcharge never takes anyone past this. */
    private static final int GOSSIP_FLOOR = -60;
    /** Where the admin history hears «деревня узнала»; set by the admin link, null without the web admin. */
    static volatile BiConsumer<String, JsonObject> journal;

    private final MobGameBridge bridge;
    private final Deque<DivineTidings.Deed> queue = new ArrayDeque<>();
    private List<UnaryOperator<String>> sources;

    DivineController(MobGameBridge bridge) { this.bridge = bridge; }

    private boolean enabled() { return bridge.config.enabled && bridge.config.divineTidings; }

    /** One event from the divine block, as JSON; any thread. Bad events are dropped quietly. */
    void offer(String json) {
        DivineTidings.Deed deed;
        try { deed = DivineTidings.parse(json); } catch (IllegalArgumentException bad) { return; }
        bridge.server.execute(() -> queue.addLast(deed));
    }

    void tick(ServerWorld world) {
        if (world != bridge.server.getOverworld()) return;
        if (!enabled()) { queue.clear(); return; }
        long time = world.getTime();
        if (time % PULL_TICKS == 13) pull();
        for (int i = 0; i < 4 && !queue.isEmpty(); i++) {
            DivineTidings.Deed deed = queue.removeFirst();
            try { handle(deed); } catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger("mobtalk-divine").warn("Событие бога {} не разобрано: {}", deed.id(), failure.toString()); }
        }
        if (time % ASSEMBLY_TICKS == 41) assemblies();
    }

    void clearRuntime() { queue.clear(); sources = null; }

    // ---- The pull fallback: the divine block's own admin source, op «events». ----

    private void pull() {
        if (sources == null) {
            sources = new ArrayList<>();
            for (var entry : FabricLoader.getInstance().getEntrypointContainers("mobtalk-admin", UnaryOperator.class)) {
                if ("mobtalk".equals(entry.getProvider().getMetadata().getId())) continue;
                try { @SuppressWarnings("unchecked") UnaryOperator<String> source = (UnaryOperator<String>) entry.getEntrypoint(); sources.add(source); }
                catch (Throwable ignored) { }
            }
        }
        long since = bridge.memory.divinePulledSince();
        for (UnaryOperator<String> source : sources) {
            try {
                JsonObject request = new JsonObject();
                request.addProperty("op", "events"); request.addProperty("since", since); request.addProperty("limit", 50);
                String answer = source.apply(request.toString());
                JsonElement parsed = answer == null ? null : JsonParser.parseString(answer);
                if (parsed == null || !parsed.isJsonObject() || !parsed.getAsJsonObject().has("items")) continue;
                JsonArray items = parsed.getAsJsonObject().getAsJsonArray("items");
                long newest = since;
                for (JsonElement item : items) {
                    try {
                        DivineTidings.Deed deed = DivineTidings.parse(item.toString());
                        newest = Math.max(newest, deed.time());
                        queue.addLast(deed);
                    } catch (IllegalArgumentException ignored) { }
                }
                bridge.memory.divinePulled(newest);
            } catch (Throwable ignored) { } // A source that does not know «events» (an older divine block) answers with an error.
        }
    }

    // ---- One deed. ----

    private record Reach(ConversationStore.Village village, boolean far, Map<UUID, String> sufferers, Map<UUID, String> witnesses) { }

    private void handle(DivineTidings.Deed deed) {
        long now = MobClock.now();
        // Continuations share the punishment's id (its end, the penance that lifts it), so the kind is part of the key.
        if (!bridge.memory.divineFirst(deed.id() + "|" + deed.kind().name(), now) || now - deed.time() > STALE_MILLIS) return;
        ServerWorld world = bridge.world(deed.dimension());
        DivineTidings.Soul soul = deed.kind() == DivineTidings.Kind.MIRACLE ? deed.beneficiary() : deed.culprit();
        ServerPlayerEntity player = soul == null ? null : bridge.server.getPlayerManager().getPlayer(soul.id());
        if (player != null && player.getServerWorld() != world) player = null;
        List<Reach> reached = reach(deed, world, player);
        Map<String, VillageStanding.View> before = new HashMap<>();
        if (soul != null) for (Reach r : reached) before.put(r.village().id(), bridge.memory.social().standing(r.village().id(), soul.id(), now));
        List<DivineTidings.AssemblyView> waiting = deed.kind() == DivineTidings.Kind.FORGIVEN && soul != null
                ? bridge.memory.divineAssemblies().stream().filter(a -> a.player().equals(soul.id())).toList() : List.of();

        List<String> cursed = new ArrayList<>();
        for (Reach r : reached) {
            boolean known = !r.far() && known(deed, r);
            boolean heard = r.far() ? deed.named() || anyKnown(deed, reached) : known;
            ConversationStore.DivineOutcome outcome = bridge.memory.divineVillage(deed, r.village().id(), heard, r.far(), r.sufferers(), r.witnesses(), now);
            if (!r.far()) cursed.add(r.village().id());
            BiConsumer<String, JsonObject> history = journal;
            if (history != null && !outcome.rumorId().isBlank())
                history.accept("Деревня «" + r.village().name() + "» узнала: " + deed.title() + (outcome.blamed().isBlank() ? "" : " — винят " + outcome.blamed()),
                        DivineTidings.summary(deed, r.village().id(), r.village().name(), outcome.blamed(), outcome.change()));
            if (world == null || r.far()) continue;
            gossip(deed, world, r, player, heard);
            if (bridge.config.divinePanic) scene(deed, world, r, player, outcome.blamed());
            if (soul != null && !outcome.blamed().isBlank() && bridge.config.divineAssemblyCurses > 0 && outcome.wraths() >= bridge.config.divineAssemblyCurses)
                assemble(world, r.village(), soul, now);
        }
        List<String> lifted = bridge.memory.divineSettle(deed, cursed, now);
        if (world != null) for (String id : lifted) {
            ConversationStore.Village village = bridge.memory.village(id);
            if (village != null && village.dimension().equals(deed.dimension()))
                shout(world, villagers(world, village, center(world, village), 48).stream().findFirst().orElse(null), "Слава Богу, миновало!");
        }
        for (DivineTidings.AssemblyView a : waiting) {
            ConversationStore.Village village = bridge.memory.village(a.village());
            if (village != null) tell(village, a.player(), Text.translatable("mobtalk.divine.dismissed", village.name(), a.name()));
        }
        if (soul != null) for (Reach r : reached) {
            VillageStanding.View old = before.get(r.village().id());
            VillageStanding.View fresh = bridge.memory.social().standing(r.village().id(), soul.id(), now);
            ServerPlayerEntity online = bridge.server.getPlayerManager().getPlayer(soul.id());
            if (online != null && old != null && old.rank() != fresh.rank())
                online.sendMessage(Text.translatable("mobtalk.divine.rank", r.village().name(), fresh.rank().word, fresh.points()), false);
        }
    }

    /** The villages the deed reached: the one around it and those within its reach, near; with К5 and the ban, every other known
     * village of the dimension, far (it only hears the news). */
    private List<Reach> reach(DivineTidings.Deed deed, ServerWorld world, ServerPlayerEntity player) {
        List<Reach> out = new ArrayList<>();
        Set<String> near = new LinkedHashSet<>();
        BlockPos at = new BlockPos(deed.x(), deed.y(), deed.z());
        ConversationStore.Village here = world == null ? bridge.memory.villageNear(deed.dimension(), deed.x(), deed.z()) : bridge.villageAt(world, at);
        if (here == null && world != null && bridge.config.villageSites) {
            // Б1: a deed in a village structure nobody talked in yet founds the village; its first villager's biography names it.
            ConversationStore.Site site = VillageSites.at(world, at);
            if (site != null) here = bridge.memory.createVillage(deed.dimension(), site.x(), site.z(), "", "", site.key());
        }
        if (here != null) near.add(here.id());
        int reach = deed.world() ? Math.max(deed.radius(), (int) WORLD_REACH) : bridge.config.divinePersonalRadius;
        for (ConversationStore.Village village : bridge.memory.villages()) {
            if (!village.dimension().equals(deed.dimension())) continue;
            long dx = village.x() - deed.x(), dz = village.z() - deed.z();
            if (dx * dx + dz * dz <= (long) reach * reach) near.add(village.id());
        }
        for (String id : near) {
            ConversationStore.Village village = bridge.memory.village(id);
            if (village == null) continue;
            Map<UUID, String> sufferers = new LinkedHashMap<>(), witnesses = new LinkedHashMap<>();
            if (world != null) {
                if (deed.world()) {
                    int radius = Math.max(8, deed.radius());
                    for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, new Box(at).expand(radius), v -> v.isAlive() && !v.isBaby()))
                        if (belongs(world, v, village)) sufferers.put(v.getUuid(), name(v));
                }
                if (player != null)
                    for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(WITNESS_RANGE), v -> v.isAlive() && !v.isBaby()))
                        if (v.canSee(player) && belongs(world, v, village)) witnesses.put(v.getUuid(), name(v));
            }
            out.add(new Reach(village, false, sufferers, witnesses));
        }
        if (deed.everywhere())
            for (ConversationStore.Village village : bridge.memory.villages())
                if (village.dimension().equals(deed.dimension()) && !near.contains(village.id())) out.add(new Reach(village, true, Map.of(), Map.of()));
        return out;
    }

    /** Whether this village knows whose deed it was: the god said the name aloud, villagers saw it fall on the player, or the prayer
     * was said at an altar inside the village. */
    private static boolean known(DivineTidings.Deed deed, Reach r) {
        if (deed.named() || !r.witnesses().isEmpty()) return true;
        if (!deed.hasAltar() || !deed.altarDimension().equals(r.village().dimension())) return false;
        long dx = deed.altarX() - r.village().x(), dz = deed.altarZ() - r.village().z();
        return dx * dx + dz * dz <= (long) ConversationStore.VILLAGE_RADIUS * ConversationStore.VILLAGE_RADIUS;
    }
    /** A far village hears the name when a near one knew it: news travel with the name. */
    private static boolean anyKnown(DivineTidings.Deed deed, List<Reach> reached) {
        for (Reach r : reached) if (!r.far() && known(deed, r)) return true;
        return false;
    }

    private boolean belongs(ServerWorld world, VillagerEntity villager, ConversationStore.Village village) {
        String home = bridge.memory.villageOf(villager.getUuid());
        if (!home.isBlank()) return home.equals(village.id());
        ConversationStore.Village here = bridge.villageAt(world, villager.getBlockPos());
        return here != null && here.id().equals(village.id());
    }
    private String name(VillagerEntity villager) {
        String known = bridge.memory.displayName(villager.getUuid());
        if (!known.isBlank()) return known;
        if (villager.hasCustomName()) return villager.getName().getString();
        return villager.getVillagerData().getProfession() == VillagerProfession.CLERIC ? "Священник" : "Житель"; // Not the vanilla «Villager».
    }

    // ---- Prices: vanilla gossip, so the trade window shows them without touching the offers. ----

    private void gossip(DivineTidings.Deed deed, ServerWorld world, Reach r, ServerPlayerEntity player, boolean known) {
        if (player == null || !known) return;
        boolean wrath = deed.kind().wrath() && deed.kind() != DivineTidings.Kind.BAN;
        boolean blessed = deed.kind() == DivineTidings.Kind.MIRACLE;
        int amount = wrath ? bridge.config.divineSurcharge : blessed ? bridge.config.divineBlessDiscount : 0;
        if (amount <= 0) return;
        for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(GOSSIP_RANGE), v -> v.isAlive() && !v.isBaby())) {
            if (!belongs(world, v, r.village())) continue;
            if (wrath) {
                int room = v.getReputation(player) - GOSSIP_FLOOR;
                if (room > 0) v.getGossip().startGossip(player.getUuid(), VillageGossipType.MINOR_NEGATIVE, Math.min(amount, room));
            } else v.getGossip().startGossip(player.getUuid(), VillageGossipType.MINOR_POSITIVE, amount);
        }
    }

    // ---- The scene in the street. ----

    private void scene(DivineTidings.Deed deed, ServerWorld world, Reach r, ServerPlayerEntity player, String blamed) {
        BlockPos focus = player != null ? player.getBlockPos() : new BlockPos(deed.x(), deed.y(), deed.z());
        List<VillagerEntity> crowd = villagers(world, r.village(), focus, SHOUT_RANGE);
        if (crowd.isEmpty()) return;
        String line = line(deed, blamed);
        if (deed.alarm()) {
            BlockPos bell = bell(world, center(world, r.village()));
            if (bell != null) ring(world, bell);
            for (VillagerEntity v : crowd) {
                if (v.getVillagerData().getProfession() == VillagerProfession.CLERIC && bell != null) {
                    v.getNavigation().startMovingTo(bell.getX() + 0.5, bell.getY(), bell.getZ() + 0.5, 0.6);
                    shout(world, v, "Помолимся, православные: Господи, помилуй нас грешных!");
                    break;
                }
            }
            for (VillagerEntity v : crowd) {
                v.getBrain().remember(MemoryModuleType.HURT_BY, world.getDamageSources().magic(), 160);
                if (player != null) v.getBrain().remember(MemoryModuleType.HURT_BY_ENTITY, player, 160);
            }
        }
        if (!line.isBlank()) {
            VillagerEntity first = crowd.get(0);
            shout(world, first, line);
            if (deed.alarm() && crowd.size() > 1 && !blamed.isBlank()) shout(world, crowd.get(1), "Покайся, " + blamed + ", пока не поздно!");
        }
        if (deed.golem() && player != null)
            for (IronGolemEntity golem : world.getEntitiesByClass(IronGolemEntity.class, player.getBoundingBox().expand(GOLEM_RANGE),
                    g -> g.isAlive() && !g.isPlayerCreated() && g.getTarget() == null)) {
                golem.getLookControl().lookAt(player, 30f, 30f);
                if (golem.squaredDistanceTo(player) > 5 * 5) golem.getNavigation().startMovingTo(player, 0.7);
            }
    }

    /** The shout in the street, by the deed and whether the village knows the sinner. */
    static String line(DivineTidings.Deed deed, String blamed) {
        boolean known = blamed != null && !blamed.isBlank();
        return switch (deed.kind()) {
            case WRATH, BOSS -> known ? "Господи, помилуй! Это всё за грехи " + blamed + "!" : "Господи, помилуй! За чьи же грехи кара?";
            case WARNING -> "Не к добру знамение, ох, не к добру…";
            case MIRACLE -> known ? "Слава Тебе, Господи! Чудо по молитве " + blamed + "!" : "Слава Тебе, Господи! Чудо!";
            case FORGIVEN -> known ? "Слава Богу, " + blamed + " покаялся!" : "Слава Богу, смилостивился Господь!";
            case BAN -> known ? "Господь изгнал " + blamed + " навеки…" : "";
            case JEST, CURSE_END -> "";
        };
    }

    private List<VillagerEntity> villagers(ServerWorld world, ConversationStore.Village village, BlockPos around, double range) {
        List<VillagerEntity> out = new ArrayList<>();
        for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, new Box(around).expand(range), v -> v.isAlive() && !v.isBaby() && !v.isAiDisabled()))
            if (belongs(world, v, village)) out.add(v);
        out.sort(Comparator.comparingDouble(v -> v.squaredDistanceTo(around.getX(), around.getY(), around.getZ())));
        return out;
    }
    private static BlockPos center(ServerWorld world, ConversationStore.Village village) {
        return new BlockPos(village.x(), world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, village.x(), village.z()), village.z());
    }
    private static BlockPos bell(ServerWorld world, BlockPos around) {
        return world.getPointOfInterestStorage().getNearestPosition(type -> type.matchesKey(PointOfInterestTypes.MEETING), around, 96,
                PointOfInterestStorage.OccupationStatus.ANY).orElse(null);
    }
    private static void ring(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BellBlock bell) bell.ring(world, pos, null);
    }

    /** A line from a villager as a subtitle in the chat of every player near (no voice: Б1 shouts are never voiced). */
    private void shout(ServerWorld world, VillagerEntity speaker, String text) {
        if (speaker == null || text == null || text.isBlank()) return;
        MobSpeechS2C packet = new MobSpeechS2C(speaker.getId(), speaker.getUuid(), name(speaker), text, false, 0, SHOUT_RANGE, MobSpeechS2C.VILLAGE, 0, "", "");
        for (ServerPlayerEntity listener : world.getPlayers())
            if (listener.squaredDistanceTo(speaker) <= SHOUT_RANGE * SHOUT_RANGE && ServerPlayNetworking.canSend(listener, MobSpeechS2C.ID))
                ServerPlayNetworking.send(listener, packet);
    }

    // ---- The bell assembly. ----

    private void assemble(ServerWorld world, ConversationStore.Village village, DivineTidings.Soul soul, long now) {
        long day = world.getTimeOfDay() % 24000, ticks = Math.floorMod(12000 - day, 24000);
        if (ticks < 1200) ticks += 24000; // Too close to sunset: the sinner gets until the next one.
        if (!bridge.memory.divineAssemble(village.id(), soul.id(), soul.name(), now + ticks * 50)) return;
        BlockPos bell = bell(world, center(world, village));
        if (bell != null) ring(world, bell);
        tell(village, soul.id(), Text.translatable("mobtalk.divine.assembly", village.name(), soul.name()));
        bridge.memory.divineAnnounced(village.id(), soul.id());
    }
    private void assemblies() {
        long now = MobClock.now();
        for (DivineTidings.AssemblyView a : bridge.memory.divineAssemblies()) {
            if (now < a.deadline()) continue;
            bridge.memory.divineExile(a.village(), a.player());
            ConversationStore.Village village = bridge.memory.village(a.village());
            if (village != null) tell(village, a.player(), Text.translatable("mobtalk.divine.exiled", village.name(), a.name()));
            BiConsumer<String, JsonObject> history = journal;
            if (history != null) {
                JsonObject data = new JsonObject();
                data.addProperty("village", a.village()); data.addProperty("player", a.player().toString()); data.addProperty("exiled", true);
                history.accept("Сход у колокола изгнал " + a.name() + (village == null ? "" : " из деревни «" + village.name() + "»"), data);
            }
        }
    }
    /** The message to the sinner wherever they are and to every player in the village. */
    private void tell(ConversationStore.Village village, UUID sinner, Text message) {
        ServerPlayerEntity culprit = bridge.server.getPlayerManager().getPlayer(sinner);
        if (culprit != null) culprit.sendMessage(message, false);
        ServerWorld world = bridge.world(village.dimension());
        if (world == null) return;
        for (ServerPlayerEntity listener : world.getPlayers()) {
            if (listener == culprit) continue;
            double dx = listener.getX() - village.x(), dz = listener.getZ() - village.z();
            if (dx * dx + dz * dz <= (double) ConversationStore.VILLAGE_RADIUS * ConversationStore.VILLAGE_RADIUS) listener.sendMessage(message, false);
        }
    }

    // ---- A villager died under a curse. ----

    void died(VillagerEntity villager) {
        if (!enabled() || !(villager.getWorld() instanceof ServerWorld world)) return;
        String village = bridge.memory.villageOf(villager.getUuid());
        if (village.isBlank()) { ConversationStore.Village here = bridge.villageAt(world, villager.getBlockPos()); village = here == null ? "" : here.id(); }
        if (village.isBlank()) return;
        String name = name(villager);
        String sinner = bridge.memory.divineMourn(villager.getUuid(), village, name, MobClock.now());
        if (sinner.isBlank() || !bridge.config.divinePanic) return;
        ConversationStore.Village home = bridge.memory.village(village);
        if (home == null) return;
        for (VillagerEntity v : villagers(world, home, villager.getBlockPos(), 24))
            if (v != villager) { shout(world, v, "Упокой, Господи, душу " + name + "… Это за грехи " + sinner + "!"); break; }
    }
}
