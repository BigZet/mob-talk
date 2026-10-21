package io.github.mobtalk.client.game;

import io.github.mobtalk.core.*;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FireworkExplosionComponent;
import net.minecraft.component.type.FireworksComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ai.brain.BlockPosLookTarget;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.ai.brain.task.LookTargetUtil;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.poi.PointOfInterestStorage;
import net.minecraft.world.poi.PointOfInterestTypes;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.util.*;

/** Свадьба у колокола (трек L) на встроенном сервере: собирает пару, ведущего, свидетелей и гостей у колокола, ведёт их к местам и
 * держит там лицом к говорящему, одевает в венки и фату, ставит цветочную арку из ванильных витрин предметов (без блоков), делает
 * обмен кольцами, брак со звоном колокола, сердечками и салютом, праздник с прыжками и подарками. Порядок службы решает
 * {@link Wedding.Ceremony}, реплики озвучивает клиент. Всё, что свадьба дала на время, помечено и убирается после неё, а после вылета
 * игры — при следующей проверке. Только серверный поток, кроме чтения {@link #scene()}. */
public final class WeddingController {
    static final String TAG = "mobtalk_wedding";
    private static final long CHECK = 5, CLEAN_PERIOD = 200, DAY = 24_000;
    /** Дальше стольких блоков от места пары игрок «ушёл со свадьбы». */
    private static final double LEAVE_RANGE = 40;
    private final MobGameBridge bridge;
    private final Random random = new Random();
    private volatile Scene scene;

    WeddingController(MobGameBridge bridge) { this.bridge = bridge; }
    private boolean enabled() { return bridge.config.enabled && bridge.config.socialLayer && bridge.config.weddingCeremony; }

    /** Одна свадьба в мире. Поля задаются при начале и дальше не меняются; меняется только служба (она синхронизирована) и
     * служебные поля, которые трогает лишь серверный поток. */
    public static final class Scene {
        public final Wedding.Ceremony ceremony;
        public final WeddingScript.Input input;
        /** Игрок-жених (невеста); null — женятся два жителя. */
        public final UUID player;
        final ServerWorld world;
        final Vec3d anchor, forward, right, couple, playerSpot;
        final boolean quiet;
        final Map<UUID, Vec3d> seats;
        final List<UUID> displays = new ArrayList<>();
        final Set<UUID> dressed = new HashSet<>();
        long feastUntil = -1;
        final List<Long> bells = new ArrayList<>();
        boolean rings, hurt, servicing;
        /** Наряд надет и арка стоит: до этого клиенту (и самотесту) смотреть рано. */
        public volatile boolean ready;
        String ringItem = "";
        Scene(Wedding.Ceremony ceremony, WeddingScript.Input input, UUID player, ServerWorld world, Vec3d anchor, Vec3d forward, Vec3d right,
              Vec3d couple, Vec3d playerSpot, boolean quiet, Map<UUID, Vec3d> seats) {
            this.ceremony = ceremony; this.input = input; this.player = player; this.world = world; this.anchor = anchor; this.forward = forward;
            this.right = right; this.couple = couple; this.playerSpot = playerSpot; this.quiet = quiet; this.seats = seats;
        }
        public UUID spouse() { return ceremony.cast().spouse().id(); }
        public boolean playerWedding() { return player != null; }
        /** Для самотеста: где стоит пара, место игрока, куда смотрят гости, сколько витрин в арке. */
        public Vec3d couple() { return couple; }
        public Vec3d playerSpot() { return playerSpot; }
        public Vec3d forward() { return forward; }
        public int archSize() { return displays.size(); }
        /** Для самотеста: площадь у колокола и места участников. */
        public Vec3d anchor() { return anchor; }
        public Collection<Vec3d> seatSpots() { return Collections.unmodifiableCollection(seats.values()); }
    }
    /** Идущая свадьба или null; можно читать из клиентского потока. */
    public Scene scene() { return scene; }
    public boolean active() { return scene != null; }
    /** Участвует ли житель в идущей свадьбе (его не зовут болтать и не уводят по делам). */
    public boolean involved(UUID id) { Scene s = scene; return s != null && s.ceremony.cast().find(id) != null; }
    /** Тексты для клиента: один раз за свадьбу. */
    public WeddingScript.Input takeScriptRequest() { Scene s = scene; return s != null && s.ceremony.takeScriptRequest() ? s.input : null; }

    // ---- Начало. ----

    /** Игрок позвал невесту (жениха) на свадьбу, и её ответ доставлен. Строка для чата: почему нельзя, или "" — свадьба началась
     * (или звали не ту, тогда тоже ""). */
    String start(VillagerEntity spouse, ServerPlayerEntity player) {
        if (!enabled() || spouse == null || player == null || !spouse.isAlive()) return "";
        if (!bridge.memory.wedding().engaged(spouse.getUuid(), player.getUuid())) return "";
        if (scene != null) return "Свадьба уже идёт";
        if (!(spouse.getWorld() instanceof ServerWorld world) || player.getWorld() != world) return "";
        if (!daytime(world)) return "Свадьбы играют днём: позовите на свадьбу утром";
        if (world.hasRaidAt(spouse.getBlockPos())) return "Не до свадьбы: на деревню напали";
        Venue venue = venue(world, spouse);
        boolean quiet = venue == null;
        Vec3d toPlayer = flat(player.getPos().subtract(quiet ? spouse.getPos() : venue.plaza()));
        Vec3d forward = toPlayer.lengthSquared() < 0.25 ? flat(Vec3d.fromPolar(0, player.getYaw()).multiply(-1)) : toPlayer.normalize();
        if (forward.lengthSquared() < 0.25) forward = new Vec3d(0, 0, 1);
        forward = forward.normalize();
        // Тихая свадьба без колокола: «колокол» — за спиной у пары, пара стоит, где стоит невеста.
        Vec3d anchor = quiet ? spouse.getPos().subtract(forward.multiply(Wedding.COUPLE_FORWARD)) : venue.plaza();
        double range = quiet ? 16 : bridge.config.weddingRange;
        long now = System.currentTimeMillis();
        List<VillagerEntity> around = villagers(world, anchor, range);
        if (!around.contains(spouse)) around.add(spouse);
        List<WeddingLedger.Resident> residents = around.stream().map(v -> new WeddingLedger.Resident(v.getUuid(), name(v), profession(v), !v.isBaby())).toList();
        List<Wedding.Person> people = bridge.memory.wedding().people(residents, spouse.getUuid(), null, player.getUuid(), now);
        Wedding.Person bride = people.stream().filter(p -> p.id().equals(spouse.getUuid())).findFirst().orElse(null);
        if (bride == null) return "";
        UUID[] asked = bridge.memory.wedding().witnesses(spouse.getUuid(), player.getUuid());
        // One village for both: the name in the script and the player's standing there (an exile gets only kin as guests).
        ConversationStore.Village village = village(world, spouse, anchor);
        VillageStanding.View standing = village == null ? VillageStanding.View.NONE : bridge.memory.social().standing(village.id(), player.getUuid(), now);
        Wedding.Cast cast = Wedding.cast(bride, null, people, asked[0], asked[1], standing.banished(), bridge.config.weddingGuests);
        Wedding.Names names = new Wedding.Names(MobGameBridge.playerName(player), Gender.ofPlayer(bridge.config.playerGender), village == null ? "" : village.name());
        MobCard card = bridge.memory.card(spouse.getUuid(), player.getUuid());
        WeddingScript.Input input = new WeddingScript.Input(cast, names, VillageStanding.describe(standing), card == null ? "" : card.memory(), bridge.config.weddingWishes);
        Vec3d couple = anchor.add(forward.multiply(Wedding.COUPLE_FORWARD));
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        boolean playerRight = player.getPos().subtract(couple).dotProduct(right) >= 0;
        Set<BlockPos> walkable = quiet ? walkable(world, standingNear(world, spouse.getBlockPos()), REACH) : venue.walkable();
        Scene s = begin(world, cast, names, input, player.getUuid(), anchor, forward, quiet, playerRight, names.playerGender(), walkable, now);
        Vec3d spot = s.playerSpot;
        for (ServerPlayerEntity listener : world.getPlayers())
            if (listener.squaredDistanceTo(anchor) <= 96 * 96) listener.sendMessage(Text.literal("§d[Mob Talk] В деревне свадьба! " + bride.name() + " и " + names.player()
                    + (quiet ? " — женятся прямо здесь." : " — все к колоколу.")), false);
        player.sendMessage(Text.literal("§7[Mob Talk] Встаньте на отмеченное место рядом с " + bride.name() + (spot == null ? "" : " (искры у колокола)")
                + ". Когда ведущий спросит вас, удерживайте клавишу речи и скажите клятву."), false);
        return "";
    }

    /** Свадьба двух жителей сценой у колокола: пара уже выбрана (и ещё не женаты). true — сцена началась. */
    boolean startVillage(ServerWorld world, UUID first, UUID second, ServerPlayerEntity near) {
        if (!enabled() || scene != null || !(world.getEntity(first) instanceof VillagerEntity a) || !(world.getEntity(second) instanceof VillagerEntity b)) return false;
        if (!daytime(world) || world.hasRaidAt(a.getBlockPos())) return false;
        if (bridge.memory.social().household(first).spouse() != null || bridge.memory.social().household(second).spouse() != null) return false; // Уже в браке.
        Venue venue = venue(world, a);
        if (venue == null) return false;
        Vec3d anchor = venue.plaza();
        Vec3d forward = flat(near.getPos().subtract(anchor));
        forward = forward.lengthSquared() < 0.25 ? new Vec3d(0, 0, 1) : forward.normalize();
        long now = System.currentTimeMillis();
        List<VillagerEntity> around = villagers(world, anchor, bridge.config.weddingRange);
        for (VillagerEntity v : List.of(a, b)) if (!around.contains(v)) around.add(v);
        List<WeddingLedger.Resident> residents = around.stream().map(v -> new WeddingLedger.Resident(v.getUuid(), name(v), profession(v), !v.isBaby())).toList();
        List<Wedding.Person> people = bridge.memory.wedding().people(residents, b.getUuid(), a.getUuid(), null, now);
        Wedding.Person pa = people.stream().filter(p -> p.id().equals(first)).findFirst().orElse(null), pb = people.stream().filter(p -> p.id().equals(second)).findFirst().orElse(null);
        if (pa == null || pb == null) return false;
        Wedding.Cast cast = Wedding.cast(pb, pa, people, null, null, false, bridge.config.weddingGuests);
        ConversationStore.Village village = village(world, a, anchor);
        Wedding.Names names = new Wedding.Names("", null, village == null ? "" : village.name());
        WeddingScript.Input input = new WeddingScript.Input(cast, names, "", "", bridge.config.weddingWishes);
        begin(world, cast, names, input, null, anchor, forward, false, true, pa.gender(), venue.walkable(), now);
        for (ServerPlayerEntity listener : world.getPlayers())
            if (listener.squaredDistanceTo(anchor) <= 96 * 96) listener.sendMessage(Text.literal("§d[Mob Talk] В деревне свадьба: " + pa.name() + " и " + pb.name() + " — у колокола."), false);
        return true;
    }

    /** Только самотест: свадьба двух жителей сценой без броска кубика. */
    public boolean startVillageForTest(ServerWorld world, UUID first, UUID second, ServerPlayerEntity near) {
        return startVillage(world, first, second, near);
    }

    /** {@code walkable}: клетки, куда от площади можно дойти пешком; места ставятся только на них (не на крышах и не в закрытых
     * домах). Пусто — по-старому, ближайшая по высоте клетка у задуманной точки. */
    private Scene begin(ServerWorld world, Wedding.Cast cast, Wedding.Names names, WeddingScript.Input input, UUID player, Vec3d anchor, Vec3d forward,
                        boolean quiet, boolean playerRight, Gender playerGender, Set<BlockPos> walkable, long now) {
        Vec3d right = new Vec3d(-forward.z, 0, forward.x);
        Map<UUID, Vec3d> seats = new HashMap<>();
        Set<BlockPos> good = new HashSet<>(), used = new HashSet<>();
        for (BlockPos p : walkable) if (sky(world, p) && solidUnder(world, p)) good.add(p);
        for (Wedding.Seat seat : Wedding.seats(cast, playerRight, playerGender)) {
            Vec3d ideal = anchor.add(forward.multiply(seat.forward())).add(right.multiply(seat.right()));
            Vec3d spot = walkable.isEmpty() ? ground(world, ideal, anchor.y) : nearest(walkable, good, used, ideal);
            if (spot != null) seats.put(seat.id(), spot);
        }
        Vec3d couple = anchor.add(forward.multiply(Wedding.COUPLE_FORWARD));
        Wedding.Seat mine = Wedding.playerSeat(playerRight);
        Vec3d mineIdeal = anchor.add(forward.multiply(mine.forward())).add(right.multiply(mine.right()));
        Vec3d playerSpot = player == null ? null : walkable.isEmpty() ? ground(world, mineIdeal, anchor.y) : nearest(walkable, good, used, mineIdeal);
        Wedding.Ceremony ceremony = new Wedding.Ceremony(cast, names, bridge.config.weddingGatherSeconds * 1000L, bridge.config.weddingVowWaitSeconds * 1000L,
                bridge.config.weddingWishes, now);
        Scene s = new Scene(ceremony, input, player, world, anchor, forward, right, couple, playerSpot, quiet, seats);
        scene = s;
        dress(s, world);
        arch(s, world);
        s.ready = true;
        s.bells.add(world.getTime() + 2);
        return s;
    }

    // ---- Каждый тик. ----

    void tick(ServerWorld world) {
        Scene s = scene;
        if (s == null) { if (world.getTime() % CLEAN_PERIOD == 113) clean(world); return; }
        if (s.world != world) return;
        long t = world.getTime(), now = System.currentTimeMillis();
        for (Iterator<Long> it = s.bells.iterator(); it.hasNext(); ) {
            long at = it.next();
            if (t >= at) { it.remove(); world.playSound(null, BlockPos.ofFloored(s.anchor), SoundEvents.BLOCK_BELL_USE, SoundCategory.BLOCKS, 2.0f, 1.0f); }
        }
        if (s.feastUntil > t) feast(s, world, t);
        if (t % 40 == 9 && !s.ceremony.over()) dress(s, world); // Мозг жителя мог забрать цветы из руки: вернуть.
        if (t % CHECK != 0) return;
        Wedding.Ceremony c = s.ceremony;
        String trouble = trouble(s, world);
        if (trouble != null) { finish(s, world, trouble); return; }
        if (c.phase() == Wedding.Ceremony.Phase.GATHER) {
            if (s.playerSpot != null && t % 10 == 0) world.spawnParticles(ParticleTypes.END_ROD, s.playerSpot.x, s.playerSpot.y + 0.2, s.playerSpot.z, 4, 0.25, 0.05, 0.25, 0.01);
            if (t % 20 == 0) world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, s.couple.x, s.couple.y + 1.5, s.couple.z, 3, 1.5, 0.5, 1.5, 0.02);
            if (c.gatherDone(seated(s, world), now)) serviceStarts(s, world);
        }
        c.tick(now);
        Wedding.Act act = c.pendingAct(now);
        if (act != null) perform(s, world, act, now);
        if (c.over()) finish(s, world, null);
    }

    /** Почему свадьбу пора прервать, или null. После брака свадьбу уже ничто не отменяет — только заканчивает. */
    private String trouble(Scene s, ServerWorld world) {
        Wedding.Cast cast = s.ceremony.cast();
        if (!(world.getEntity(cast.spouse().id()) instanceof VillagerEntity spouse) || !spouse.isAlive()) return cast.spouse().name() + " больше нет рядом";
        if (cast.partner() != null && (!(world.getEntity(cast.partner().id()) instanceof VillagerEntity partner) || !partner.isAlive())) return cast.partner().name() + " больше нет рядом";
        if (s.ceremony.married()) return null;
        if (s.player != null) {
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(s.player);
            if (player == null || player.getWorld() != world || !player.isAlive()) return "игрок ушёл со свадьбы";
            if (player.squaredDistanceTo(s.couple) > LEAVE_RANGE * LEAVE_RANGE) return "игрок ушёл от колокола";
        }
        if (s.hurt) return "игрок ударил гостя — какая уж тут свадьба";
        if (world.hasRaidAt(BlockPos.ofFloored(s.anchor))) return "на деревню напали";
        if (s.ceremony.phase() == Wedding.Ceremony.Phase.GATHER && world.getTimeOfDay() % DAY >= 13_000) return "наступила ночь";
        return null;
    }

    /** Все ли на местах: пара, ведущий и свидетели в метре от своих мест, игрок — у своего (в паре шагов), гости — большинство. */
    private boolean seated(Scene s, ServerWorld world) {
        Wedding.Cast cast = s.ceremony.cast();
        int guests = 0, guestsSeated = 0;
        for (Map.Entry<UUID, Vec3d> seat : s.seats.entrySet()) {
            Entity entity = world.getEntity(seat.getKey());
            boolean there = entity != null && entity.squaredDistanceTo(seat.getValue()) <= 1.5 * 1.5;
            if (cast.role(seat.getKey()) == Wedding.Role.GUEST) { guests++; if (there) guestsSeated++; }
            else if (!there) return false;
        }
        if (guests > 0 && guestsSeated * 3 < guests * 2) return false;
        if (s.player != null && s.playerSpot != null) {
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(s.player);
            return player != null && player.squaredDistanceTo(s.playerSpot) <= 3 * 3;
        }
        return true;
    }

    /** Служба началась: опоздавших пару, ведущего и свидетелей ставят на места (чтобы служба не шла в пустоту). */
    private void serviceStarts(Scene s, ServerWorld world) {
        s.servicing = true;
        Wedding.Cast cast = s.ceremony.cast();
        for (Map.Entry<UUID, Vec3d> seat : s.seats.entrySet()) {
            if (cast.role(seat.getKey()) == Wedding.Role.GUEST) continue;
            if (world.getEntity(seat.getKey()) instanceof VillagerEntity v && v.squaredDistanceTo(seat.getValue()) > 3 * 3) {
                v.getNavigation().stop();
                v.refreshPositionAndAngles(seat.getValue().x, seat.getValue().y, seat.getValue().z, v.getYaw(), 0);
                world.spawnParticles(ParticleTypes.CHERRY_LEAVES, v.getX(), v.getY() + 1, v.getZ(), 8, 0.4, 0.6, 0.4, 0.01);
            }
        }
        s.bells.add(world.getTime() + 1); s.bells.add(world.getTime() + 14);
    }

    private void perform(Scene s, ServerWorld world, Wedding.Act act, long now) {
        Wedding.Cast cast = s.ceremony.cast();
        switch (act) {
            case RINGS -> {
                boolean exchanged = false;
                ServerPlayerEntity player = s.player == null ? null : world.getServer().getPlayerManager().getPlayer(s.player);
                if (player != null) for (Item ring : List.of(WeddingItems.WEDDING_RING, WeddingItems.ENGAGEMENT_RING, Items.DIAMOND)) {
                    int slot = slotOf(player, ring);
                    if (slot < 0) continue;
                    player.getInventory().removeStack(slot, 1);
                    s.ringItem = Registries.ITEM.getId(ring).toString();
                    exchanged = true;
                    break;
                }
                s.rings = exchanged;
                if (exchanged) {
                    world.spawnParticles(ParticleTypes.END_ROD, s.couple.x, s.couple.y + 1.2, s.couple.z, 20, 0.6, 0.4, 0.6, 0.02);
                    world.playSound(null, BlockPos.ofFloored(s.couple), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.NEUTRAL, 1.5f, 1.2f);
                }
                s.ceremony.actDone(act, exchanged, now);
            }
            case WED -> {
                ServerPlayerEntity player = s.player == null ? null : world.getServer().getPlayerManager().getPlayer(s.player);
                if (s.player != null) bridge.memory.wedding().wed(cast, s.player, s.input.names().player(), s.input.names().playerGender(), s.rings, s.ringItem, now);
                else {
                    bridge.memory.wedding().villageWed(cast, world.getPlayers().isEmpty() ? null : world.getPlayers().getFirst().getUuid(), now);
                }
                long t = world.getTime();
                s.bells.add(t + 1); s.bells.add(t + 12); s.bells.add(t + 24);
                world.spawnParticles(ParticleTypes.HEART, s.couple.x, s.couple.y + 2.0, s.couple.z, 14, 0.7, 0.4, 0.7, 0.05);
                world.spawnParticles(ParticleTypes.CHERRY_LEAVES, s.couple.x, s.couple.y + 4.0, s.couple.z, 60, 3.0, 1.0, 3.0, 0.01);
                for (int i = 0; i < 3; i++) firework(s, world);
                chorus(s, world);
                if (player != null) keepsake(player, s.input.names().playerGender());
                s.ceremony.actDone(act, true, now);
            }
            case FEAST -> {
                s.feastUntil = world.getTime() + Wedding.FEAST_MILLIS / 50;
                ServerPlayerEntity player = s.player == null ? null : world.getServer().getPlayerManager().getPlayer(s.player);
                if (player != null) gifts(s, world, player);
                s.ceremony.actDone(act, true, now);
            }
        }
    }

    /** Кто не говорил пожелание вслух, кричит «Горько!» в чат (не больше пятерых), а без пожеланий — все. */
    private void chorus(Scene s, ServerWorld world) {
        Wedding.Cast cast = s.ceremony.cast();
        Set<UUID> wishers = new HashSet<>();
        for (Wedding.Person p : cast.wishers(bridge.config.weddingWishes)) wishers.add(p.id());
        List<String> shouts = List.of("Горько!", "Горько! Горько!", "Горько-о!", "Совет да любовь!", "Ура молодым!");
        int i = 0;
        for (Wedding.Person guest : cast.guests()) {
            if (wishers.contains(guest.id()) || i >= 5) continue;
            for (ServerPlayerEntity listener : world.getPlayers())
                if (listener.squaredDistanceTo(s.couple) <= 64 * 64) listener.sendMessage(Text.literal("§7" + guest.name() + ": " + shouts.get(i % shouts.size())), false);
            i++;
        }
    }

    /** Праздник: гости подпрыгивают, над деревней салют, сыплются лепестки. */
    private void feast(Scene s, ServerWorld world, long t) {
        if (t % 40 == 0) firework(s, world);
        if (t % 10 == 0) world.spawnParticles(ParticleTypes.CHERRY_LEAVES, s.couple.x, s.couple.y + 4.0, s.couple.z, 12, 4.0, 1.0, 4.0, 0.01);
    }
    /** Ракета взлетает в 6–9 блоках сбоку от гостей и рвётся высоко: осколки до людей не долетают. */
    private void firework(Scene s, ServerWorld world) {
        double side = (random.nextBoolean() ? 1 : -1) * (6.5 + random.nextDouble() * 2.5), ahead = 1 + random.nextDouble() * 5;
        Vec3d at = s.couple.add(s.right.multiply(side)).add(s.forward.multiply(ahead));
        int[][] palettes = {{0xFFFFFF, 0xF38BAA}, {0xFFD700, 0xFFFFFF}, {0xE0303A, 0xF38BAA}, {0xF38BAA, 0xFFD700}};
        int[] palette = palettes[random.nextInt(palettes.length)];
        FireworkExplosionComponent.Type shape = random.nextInt(3) == 0 ? FireworkExplosionComponent.Type.STAR : FireworkExplosionComponent.Type.LARGE_BALL;
        ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        rocket.set(DataComponentTypes.FIREWORKS, new FireworksComponent(2, List.of(new FireworkExplosionComponent(shape, IntList.of(palette), IntList.of(0xFFFFFF), true, true))));
        world.spawnEntity(new FireworkRocketEntity(world, at.x, at.y + 0.5, at.z, rocket));
    }

    /** Свидетели и пара дарят игроку подарок «героя деревни» по своей профессии; без профессии — торт. */
    private void gifts(Scene s, ServerWorld world, ServerPlayerEntity player) {
        Wedding.Cast cast = s.ceremony.cast();
        for (Wedding.Person giver : Arrays.asList(cast.witnessMan(), cast.witnessWoman(), cast.spouse())) {
            if (giver == null || !(world.getEntity(giver.id()) instanceof VillagerEntity villager)) continue;
            List<ItemStack> gifts = new ArrayList<>();
            String path = Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()).getPath();
            if (!"none".equals(path) && !"nitwit".equals(path)) {
                RegistryKey<LootTable> key = RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.ofVanilla("gameplay/hero_of_the_village/" + path + "_gift"));
                try {
                    LootTable table = world.getServer().getReloadableRegistries().getLootTable(key);
                    LootContextParameterSet parameters = new LootContextParameterSet.Builder(world).add(LootContextParameters.ORIGIN, villager.getPos())
                            .add(LootContextParameters.THIS_ENTITY, villager).build(LootContextTypes.GIFT);
                    gifts.addAll(table.generateLoot(parameters));
                } catch (RuntimeException missing) { /* Таблицы нет: ниже торт. */ }
            }
            if (gifts.isEmpty()) gifts.add(new ItemStack(Items.CAKE));
            for (ItemStack gift : gifts) LookTargetUtil.give(villager, gift, player.getPos());
        }
    }

    /** На память игроку: фата или венец — на голову, если она свободна, иначе в инвентарь. */
    private static void keepsake(ServerPlayerEntity player, Gender gender) {
        ItemStack attire = new ItemStack(gender == Gender.FEMALE ? WeddingItems.BRIDAL_VEIL : WeddingItems.WEDDING_CROWN);
        if (player.getEquippedStack(EquipmentSlot.HEAD).isEmpty()) player.equipStack(EquipmentSlot.HEAD, attire);
        else if (!player.getInventory().insertStack(attire)) player.dropItem(attire, false);
    }
    private static int slotOf(ServerPlayerEntity player, Item item) {
        var inventory = player.getInventory();
        if (inventory.getMainHandStack().isOf(item)) return inventory.selectedSlot;
        for (int i = 0; i < inventory.size(); i++) if (inventory.getStack(i).isOf(item)) return i;
        return -1;
    }

    // ---- Конец. ----

    /** Свадьба кончилась: сыграна, сорвана, отложена ({@code why}) или жених сбежал. Всё временное убирается. */
    private void finish(Scene s, ServerWorld world, String why) {
        if (scene != s) return;
        scene = null;
        Wedding.Ceremony c = s.ceremony;
        long now = System.currentTimeMillis();
        boolean married = c.married();
        if (why != null && !married) {
            c.cancel();
            if (s.player != null) bridge.memory.wedding().postponed(c.cast().spouse().id(), s.player, why, now);
            tell(s, world, "§e[Mob Talk] Свадьба отложена: " + why + "." + (s.player != null ? " Помолвка остаётся — позовите на свадьбу снова." : ""));
        } else if (c.jilted() && s.player != null) {
            bridge.memory.wedding().jilted(c.cast(), s.player, s.input.names().player(), now);
            tell(s, world, "§c[Mob Talk] Свадьбы не будет: " + s.input.names().player() + " отказался прямо у колокола.");
        } else if (married) {
            tell(s, world, "§d[Mob Talk] " + (s.player != null ? s.input.names().player() + " и " + c.cast().spouse().name() : c.cast().partner().name() + " и " + c.cast().spouse().name())
                    + " — теперь супруги!");
        }
        undress(s, world, married);
        for (UUID id : s.displays) if (world.getEntity(id) instanceof DisplayEntity display) display.discard();
        s.displays.clear();
        for (Wedding.Person p : c.cast().everyone()) if (world.getEntity(p.id()) instanceof VillagerEntity v) {
            v.getBrain().forget(MemoryModuleType.LOOK_TARGET);
            v.getBrain().forget(MemoryModuleType.WALK_TARGET);
        }
    }
    private static void tell(Scene s, ServerWorld world, String text) {
        for (ServerPlayerEntity listener : world.getPlayers()) if (listener.squaredDistanceTo(s.anchor) <= 96 * 96) listener.sendMessage(Text.literal(text), false);
    }

    /** Свадьбу прервали снаружи (мир выгружается). */
    void clearRuntime() {
        Scene s = scene;
        if (s != null && s.world.getServer().isOnThread()) finish(s, s.world, "мир закрывается");
        scene = null;
    }
    /** Игрок ударил участника идущей свадьбы. */
    void hurt(MobEntity mob, ServerPlayerEntity player) {
        Scene s = scene;
        if (s != null && s.player != null && s.player.equals(player.getUuid()) && s.ceremony.cast().find(mob.getUuid()) != null) s.hurt = true;
    }

    // ---- Наряд, цветы, арка. ----

    /** Пара и свидетели получают венки, фату или венец (только если голова свободна) и цветы в руку (если рука пуста); ведущий-священник —
     * книгу. Всё помечено: снимется после службы или срока. */
    private void dress(Scene s, ServerWorld world) {
        Wedding.Cast cast = s.ceremony.cast();
        for (Wedding.Person p : cast.everyone()) {
            if (!(world.getEntity(p.id()) instanceof VillagerEntity v)) continue;
            Wedding.Role role = cast.role(p.id());
            Item head = switch (role) {
                case SPOUSE, PARTNER -> p.gender() == Gender.FEMALE ? WeddingItems.BRIDAL_VEIL : WeddingItems.WEDDING_CROWN;
                case WITNESS_WOMAN -> WeddingItems.FLOWER_WREATH;
                case WITNESS_MAN -> WeddingItems.WEDDING_CROWN;
                default -> null;
            };
            Item hand = switch (role) {
                case SPOUSE, PARTNER -> p.gender() == Gender.FEMALE ? Items.PEONY : Items.POPPY;
                case WITNESS_WOMAN -> Items.ALLIUM;
                case WITNESS_MAN -> Items.CORNFLOWER;
                case OFFICIANT -> p.cleric() ? Items.BOOK : null;
                default -> null;
            };
            if (head != null && v.getEquippedStack(EquipmentSlot.HEAD).isEmpty()) { v.equipStack(EquipmentSlot.HEAD, WeddingItems.marked(head, Long.MAX_VALUE)); s.dressed.add(p.id()); }
            // В руке у жителя бывает только временное (товар, который он показывает): цветы его заменяют, после службы рука пустеет.
            if (hand != null && WeddingItems.markedUntil(v.getMainHandStack()) < 0) v.equipStack(EquipmentSlot.MAINHAND, WeddingItems.marked(hand, 0));
        }
    }
    /** После службы цветы из рук убираются; наряд пары и свидетелей остаётся на {@link MobTalkConfig#weddingAttireDays} дней после
     * сыгранной свадьбы, а у несостоявшейся снимается сразу. */
    private void undress(Scene s, ServerWorld world, boolean married) {
        long until = married ? world.getTime() + (long) bridge.config.weddingAttireDays * DAY : 0;
        for (Wedding.Person p : s.ceremony.cast().everyone()) {
            if (!(world.getEntity(p.id()) instanceof VillagerEntity v)) continue;
            if (WeddingItems.markedUntil(v.getMainHandStack()) >= 0) v.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            ItemStack head = v.getEquippedStack(EquipmentSlot.HEAD);
            if (WeddingItems.markedUntil(head) >= 0) {
                if (until <= world.getTime()) v.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
                else v.equipStack(EquipmentSlot.HEAD, WeddingItems.marked(head.getItem(), until));
            }
        }
    }
    /** Цветочная арка над парой: полукруг из витрин с цветами. Без блоков: мир не меняется. */
    private void arch(Scene s, ServerWorld world) {
        List<Item> flowers = List.of(Items.POPPY, Items.OXEYE_DAISY, Items.PINK_TULIP, Items.LILAC, Items.AZURE_BLUET, Items.PEONY, Items.ROSE_BUSH,
                Items.ALLIUM, Items.LILY_OF_THE_VALLEY, Items.CORNFLOWER, Items.WHITE_TULIP, Items.POPPY, Items.PINK_TULIP);
        int count = flowers.size();
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * i / (count - 1);
            Vec3d at = s.couple.add(s.right.multiply(Math.cos(angle) * 2.2)).add(0, 0.3 + Math.sin(angle) * 2.6, 0).subtract(s.forward.multiply(0.3));
            DisplayEntity.ItemDisplayEntity display = new DisplayEntity.ItemDisplayEntity(EntityType.ITEM_DISPLAY, world);
            display.setItemStack(new ItemStack(flowers.get(i)));
            display.setBillboardMode(DisplayEntity.BillboardMode.CENTER);
            display.setTransformation(new AffineTransformation(new Vector3f(), new Quaternionf(), new Vector3f(0.8f, 0.8f, 0.8f), new Quaternionf()));
            display.setPosition(at.x, at.y, at.z);
            display.addCommandTag(TAG);
            if (world.spawnEntity(display)) s.displays.add(display.getUuid());
        }
    }

    /** Без свадьбы: снять просроченный наряд и забытые цветы с жителей возле игроков, убрать арку, оставшуюся после вылета игры. */
    private void clean(ServerWorld world) {
        long t = world.getTime();
        for (ServerPlayerEntity player : world.getPlayers()) {
            Box box = player.getBoundingBox().expand(96);
            for (VillagerEntity v : world.getEntitiesByClass(VillagerEntity.class, box, Entity::isAlive)) {
                if (WeddingItems.markedUntil(v.getMainHandStack()) >= 0) v.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                long until = WeddingItems.markedUntil(v.getEquippedStack(EquipmentSlot.HEAD));
                if (until >= 0 && until <= t) v.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY);
                if (until == Long.MAX_VALUE) v.equipStack(EquipmentSlot.HEAD, ItemStack.EMPTY); // Свадьба, прерванная вылетом игры.
            }
            for (DisplayEntity.ItemDisplayEntity display : world.getEntitiesByClass(DisplayEntity.ItemDisplayEntity.class, box, d -> d.getCommandTags().contains(TAG)))
                display.discard();
        }
    }
    /** Сущность загрузилась: арка от прерванной свадьбы исчезает сразу. */
    void loaded(Entity entity) {
        if (scene == null && entity instanceof DisplayEntity.ItemDisplayEntity display && display.getCommandTags().contains(TAG)) display.discard();
    }

    // ---- Движение участников. ----

    /** После мозга: участник идёт к своему месту, на месте стоит смирно и смотрит на говорящего (ведущий — на пару). true — движением
     * жителя распоряжается свадьба. */
    boolean afterBrain(MobEntity mob) {
        Scene s = scene;
        if (s == null || !(mob instanceof VillagerEntity villager)) return false;
        Vec3d seat = s.seats.get(mob.getUuid());
        if (seat == null) return false;
        boolean gathering = s.ceremony.phase() == Wedding.Ceremony.Phase.GATHER;
        if (gathering && mob.squaredDistanceTo(seat) > 0.7 * 0.7) {
            if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET)) mob.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(seat, 0.6F, 0), 20);
            else mob.getNavigation().startMovingTo(seat.x, seat.y, seat.z, 0.6);
            return true;
        }
        if (MobActionController.registered(mob, MemoryModuleType.WALK_TARGET)) mob.getBrain().forget(MemoryModuleType.WALK_TARGET);
        if (MobActionController.registered(mob, MemoryModuleType.PATH)) mob.getBrain().forget(MemoryModuleType.PATH);
        mob.getNavigation().stop();
        if (MobActionController.registered(mob, MemoryModuleType.LOOK_TARGET)) {
            Entity speaker = focus(s, mob);
            if (speaker != null) mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(speaker, true), 10);
            else mob.getBrain().remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(lookPoint(s, mob)), 10);
        }
        if (s.feastUntil > mob.getWorld().getTime() && villager.isOnGround() && random.nextInt(25) == 0) villager.getJumpControl().setActive();
        return true;
    }
    /** На кого смотрит участник: на того, кто сейчас говорит; ведущий и пара — друг на друга. */
    private Entity focus(Scene s, MobEntity mob) {
        Wedding.Beat beat = s.ceremony.current();
        if (beat == null || !beat.line() || beat.speaker() == null || beat.speaker().equals(mob.getUuid())) return null;
        return mob.getWorld() instanceof ServerWorld world ? world.getEntity(beat.speaker()) : null;
    }
    private Vec3d lookPoint(Scene s, MobEntity mob) {
        Wedding.Role role = s.ceremony.cast().role(mob.getUuid());
        if (role == Wedding.Role.OFFICIANT) return s.couple.add(0, 1.6, 0);
        if (role == Wedding.Role.SPOUSE || role == Wedding.Role.PARTNER) return s.anchor.add(s.forward.multiply(Wedding.OFFICIANT_FORWARD)).add(0, 1.6, 0);
        return s.couple.add(0, 1.6, 0);
    }

    void unload(MobEntity mob) { /* Пара пропала — это поймает проверка свадьбы; гость просто выпадает. */ }

    // ---- Где колокол и кто вокруг. ----

    private static boolean daytime(ServerWorld world) { return world.getTimeOfDay() % DAY < 12_000; }
    /** Деревня свадьбы: своя деревня жителя, иначе та, в чьей постройке стоит колокол, иначе ближайшая известная. */
    private ConversationStore.Village village(ServerWorld world, VillagerEntity villager, Vec3d anchor) {
        ConversationStore.Village own = bridge.memory.village(bridge.memory.villageOf(villager.getUuid()));
        return own != null ? own : bridge.villageAt(world, BlockPos.ofFloored(anchor));
    }

    /** Где играть свадьбу: колокол, точка площади (земля у подножия колокола или открытая земля рядом), простор — сколько клеток
     * площади под открытым небом, — и клетки, куда от площади можно дойти пешком: только на них встают пара и гости. */
    public record Venue(BlockPos bell, Vec3d plaza, int openness, Set<BlockPos> walkable) { }
    /** Как далеко от точки площади ищутся места: ряды гостей уходят от неё на 10 блоков. */
    private static final int REACH = 11;
    private static final int[][] EIGHT = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};

    /** Колокол свадьбы этого жителя и место у его подножия. В деревнях CTOV колокол висит под аркой, на башне, на стене дома: из
     * колоколов в 96 блоках (своей деревни-постройки, если она известна) берётся тот, что житель помнит, если вокруг него не сильно
     * теснее, чем у самого просторного, иначе самый просторный. null — колокола нет. В ванильной деревне это её колокол на площади. */
    public Venue venue(ServerWorld world, VillagerEntity villager) {
        BlockPos remembered = rememberedBell(world, villager);
        List<BlockPos> bells = new ArrayList<>();
        if (remembered != null) bells.add(remembered);
        world.getPointOfInterestStorage().getInCircle(type -> type.matchesKey(PointOfInterestTypes.MEETING), villager.getBlockPos(), 96,
                        PointOfInterestStorage.OccupationStatus.ANY)
                .map(poi -> poi.getPos()).sorted(Comparator.comparingDouble(pos -> pos.getSquaredDistance(villager.getBlockPos()))).limit(8)
                .forEach(pos -> { if (!bells.contains(pos)) bells.add(pos); });
        ConversationStore.Site site = bridge.config.villageSites ? VillageSites.of(world, villager) : null;
        if (site != null && bells.stream().anyMatch(pos -> site.holds(pos.getX(), pos.getZ()))) bells.removeIf(pos -> !site.holds(pos.getX(), pos.getZ()));
        Venue best = null, own = null;
        for (BlockPos bell : bells) {
            if (!loaded(world, bell, 10)) continue; // Не грузить чанки ради свадьбы.
            Venue venue = square(world, bell);
            if (venue == null) continue;
            if (bell.equals(remembered)) own = venue;
            if (best == null || venue.openness() > best.openness()) best = venue;
        }
        return own != null && own.openness() * 10 >= best.openness() * 6 ? own : best;
    }

    /** Площадь у колокола: земля у его подножия, а если там тесно (колокол на башне, в доме, на обелиске) — открытая земля в 3–9
     * блоках от него; подножие выигрывает, если не сильно теснее. null — встать негде. Ванильный колокол стоит на площади: точкой
     * остаётся он сам, как раньше. */
    static Venue square(ServerWorld world, BlockPos bell) {
        Vec3d foot = plaza(world, bell);
        BlockPos footStart = standingNear(world, BlockPos.ofFloored(foot));
        List<BlockPos> starts = new ArrayList<>();
        if (footStart != null) starts.add(footStart);
        for (int r : new int[]{3, 6, 9})
            for (int[] d : EIGHT) {
                int x = bell.getX() + d[0] * r, z = bell.getZ() + d[1] * r;
                BlockPos top = new BlockPos(x, world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z), z);
                if (Math.abs(top.getY() - (int) Math.floor(foot.y)) <= 8 && standable(world, top) && solidUnder(world, top)) starts.add(top);
            }
        Venue atFoot = null, best = null;
        Set<BlockPos> explored = new HashSet<>();
        for (BlockPos start : starts) {
            if (explored.contains(start)) continue; // Та же площадь, что уже обойдена.
            Set<BlockPos> walkable = walkable(world, start, REACH);
            explored.addAll(walkable);
            int open = (int) walkable.stream().filter(p -> sky(world, p)).count();
            boolean isFoot = start == footStart;
            Venue venue = new Venue(bell, isFoot ? foot : Vec3d.ofBottomCenter(start), open, Collections.unmodifiableSet(walkable));
            if (isFoot) atFoot = venue;
            if (best == null || open > best.openness()) best = venue;
        }
        if (best == null) return null;
        return atFoot != null && atFoot.openness() * 10 >= best.openness() * 7 ? atFoot : best;
    }

    /** Клетки, куда от {@code start} можно дойти пешком (шаг вверх или вниз не больше блока, над головой свободно), не дальше
     * {@code reach} блоков от неё и только в загруженных чанках. Крыша и закрытый дом от площади так не достижимы. */
    static Set<BlockPos> walkable(ServerWorld world, BlockPos start, int reach) {
        Set<BlockPos> out = new HashSet<>();
        if (start == null || world.getChunkManager().getWorldChunk(start.getX() >> 4, start.getZ() >> 4) == null || !standable(world, start)) return out;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        out.add(start); queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            for (Direction side : Direction.Type.HORIZONTAL) {
                BlockPos next = at.offset(side);
                if (Math.abs(next.getX() - start.getX()) > reach || Math.abs(next.getZ() - start.getZ()) > reach
                        || world.getChunkManager().getWorldChunk(next.getX() >> 4, next.getZ() >> 4) == null) continue;
                BlockPos step = standable(world, next) ? next
                        : clear(world, at.up(2)) && standable(world, next.up()) ? next.up()
                        : clear(world, next.up()) && standable(world, next.down()) ? next.down() : null;
                if (step != null && out.add(step)) queue.add(step);
            }
        }
        return out;
    }

    /** Место участника: клетка площади, ближайшая к задуманной точке (на земле под небом лучше), не занятая другим. */
    private static Vec3d nearest(Set<BlockPos> walkable, Set<BlockPos> good, Set<BlockPos> used, Vec3d ideal) {
        BlockPos best = null; double bestScore = Double.MAX_VALUE;
        for (BlockPos p : walkable) {
            if (used.contains(p)) continue;
            double dx = p.getX() + 0.5 - ideal.x, dz = p.getZ() + 0.5 - ideal.z, dy = p.getY() - ideal.y;
            double score = dx * dx + dz * dz + dy * dy * 0.5 + (good.contains(p) ? 0 : 9);
            if (score < bestScore) { best = p; bestScore = score; }
        }
        if (best == null) return null;
        used.add(best);
        return Vec3d.ofBottomCenter(best);
    }

    /** Клетка, где можно стоять, в самой точке или рядом (колокол на площади сам занимает свою клетку); null — негде. */
    private static BlockPos standingNear(ServerWorld world, BlockPos cell) {
        if (standable(world, cell)) return cell;
        for (int dy : new int[]{0, -1, 1})
            for (int[] d : EIGHT) {
                BlockPos near = cell.add(d[0], dy, d[1]);
                if (standable(world, near)) return near;
            }
        return null;
    }
    private static boolean clear(ServerWorld world, BlockPos pos) { return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty(); }
    /** Над клеткой открытое небо (листва не в счёт). */
    private static boolean sky(ServerWorld world, BlockPos feet) { return feet.getY() >= world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, feet.getX(), feet.getZ()); }
    /** Под полом клетки ещё блок: это земля, а не настил крыши или помост. */
    public static boolean solidUnder(ServerWorld world, BlockPos feet) { return !clear(world, feet.down()) && !clear(world, feet.down(2)); }

    /** Колокол, который житель помнит местом сбора (в этом мире и в 96 блоках), или null. Для самотеста и выбора места. */
    public static BlockPos rememberedBell(ServerWorld world, VillagerEntity villager) {
        if (!MobActionController.registered(villager, MemoryModuleType.MEETING_POINT)) return null;
        Optional<GlobalPos> remembered = villager.getBrain().getOptionalRegisteredMemory(MemoryModuleType.MEETING_POINT);
        return remembered.isPresent() && remembered.get().dimension() == world.getRegistryKey() && remembered.get().pos().isWithinDistance(villager.getBlockPos(), 96)
                ? remembered.get().pos() : null;
    }

    /** Земля у подножия колокола: его собственная клетка, если он стоит на полу (ванильная площадь), иначе первая клетка с полом
     * прямо под ним (колокол под аркой, на стене, на звоннице) не глубже 8 блоков; иначе клетка колокола. */
    static Vec3d plaza(ServerWorld world, BlockPos bell) {
        for (int dy = 0; dy >= -8; dy--) {
            BlockPos feet = bell.up(dy), below = feet.down();
            if (world.getBlockState(below).getCollisionShape(world, below).isEmpty() || !world.getFluidState(feet).isEmpty()) continue;
            if (dy == 0 || world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()) return Vec3d.ofBottomCenter(feet);
        }
        return Vec3d.ofBottomCenter(bell);
    }

    /** Все чанки квадрата {@code reach} вокруг точки загружены целиком: читать блоки можно, не дожидаясь загрузки. */
    private static boolean loaded(ServerWorld world, BlockPos center, int reach) {
        for (int cx = center.getX() - reach >> 4; cx <= center.getX() + reach >> 4; cx++)
            for (int cz = center.getZ() - reach >> 4; cz <= center.getZ() + reach >> 4; cz++)
                if (world.getChunkManager().getWorldChunk(cx, cz) == null) return false;
        return true;
    }
    private List<VillagerEntity> villagers(ServerWorld world, Vec3d center, double range) {
        return new ArrayList<>(world.getEntitiesByClass(VillagerEntity.class, Box.of(center, range * 2, 48, range * 2),
                v -> v.isAlive() && !v.isRemoved() && !v.isAiDisabled() && !v.isBaby() && v.squaredDistanceTo(center) <= range * range));
    }
    /** Где стоять, когда площадь не обойти (свадьба без загруженной площади): клетка с твёрдым полом и свободным местом для тела,
     * ближайшая по высоте к площади (сначала её уровень, потом на блок ниже и выше), в точке или рядом с ней. */
    static Vec3d ground(ServerWorld world, Vec3d point, double baseY) {
        int[][] around = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int dy : new int[]{0, -1, 1, -2, 2, -3, 3, -4, -5})
            for (int[] d : around) {
                int x = (int) Math.floor(point.x) + d[0], z = (int) Math.floor(point.z) + d[1];
                BlockPos feet = new BlockPos(x, (int) Math.floor(baseY) + dy, z);
                if (standable(world, feet)) return new Vec3d(x + 0.5, feet.getY(), z + 0.5);
            }
        return null;
    }
    /** Твёрдый пол, не в жидкости, ноги и голова свободны. */
    private static boolean standable(ServerWorld world, BlockPos feet) {
        BlockState below = world.getBlockState(feet.down());
        if (below.getCollisionShape(world, feet.down()).isEmpty() || !world.getFluidState(feet).isEmpty()) return false;
        return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty() && world.getBlockState(feet.up()).getCollisionShape(world, feet.up()).isEmpty();
    }
    private static Vec3d flat(Vec3d v) { return new Vec3d(v.x, 0, v.z); }
    private String name(VillagerEntity v) { String given = bridge.memory.displayName(v.getUuid()); return given.isBlank() ? v.getName().getString() : given; }
    private static String profession(VillagerEntity v) { return Registries.VILLAGER_PROFESSION.getId(v.getVillagerData().getProfession()).toString(); }
}
