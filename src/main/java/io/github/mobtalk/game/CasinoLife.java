package io.github.mobtalk.game;

import io.github.mobtalk.server.ServerConfig;
import io.github.mobtalk.core.MobTalkConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.BlockPosLookTarget;
import net.minecraft.entity.ai.brain.EntityLookTarget;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.ai.brain.WalkTarget;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import java.util.*;

/** What goes on in a casino (docs/casino.md), all on the server thread:
 * <ul>
 * <li>Bouncers: villagers of the bouncer profession, big and tough (health, hidden armour, no knockback). Each keeps to its velvet rope;
 * it beats monsters that come near and a troublemaker: a player who hit a villager or a golem near the casino, hit the bouncer, or broke
 * a piece of the casino (two minutes). A blow at arm's length, a heavy knockback. They never panic.</li>
 * <li>Visitors: in the afternoon villagers from around come in, sit on a chair or a stool by a game table or a slot machine for a couple
 * of minutes (on an invisible seat), now and then the table clicks and rattles, and they leave.</li>
 * <li>The tables: a chair or stool seats the player too; the table for 21 opens the game of 21 with the villager at it, or calls the
 * nearest villager to deal; the slot machine spins for fun.</li>
 * </ul> */
public final class CasinoLife {
    private CasinoLife() { }
    static final String SEAT_TAG = "mobtalk_casino_seat";
    static final Identifier BOUNCER_HEALTH = Identifier.of("mobtalk", "bouncer_health"), BOUNCER_ARMOR = Identifier.of("mobtalk", "bouncer_armor"),
            BOUNCER_STEADY = Identifier.of("mobtalk", "bouncer_steady");
    static final double REACH = 2.5, GUARD_RADIUS = 14, TROUBLE_RADIUS = 24;
    static final long BLOW_TICKS = 24, TROUBLE_TICKS = 2400;
    /** Visits: from the afternoon to dusk; how long one lasts; how far a visitor comes from. */
    static final long VISIT_FROM = 5000, VISIT_TO = 12300, VISIT_MIN = 1200, VISIT_MAX = 3600, WALK_LIMIT = 900;
    static final double VISITOR_RANGE = 40;

    private static final Map<UUID, Long> trouble = new HashMap<>();
    private static final Map<UUID, Long> lastBlow = new HashMap<>();
    private static final Set<UUID> warned = new HashSet<>();
    private static final Map<UUID, Visit> visits = new HashMap<>();
    private static final List<Spin> spins = new ArrayList<>();
    private static final Random random = new Random();
    private static boolean registered;

    /** A villager's trip to a game: walking to the seat, then seated until {@code until}; a dealer walks to the table for a player. */
    private static final class Visit {
        final UUID villager;
        final BlockPos table, seat;
        final UUID player; // set for the dealer called to the table for 21
        final RegistryKey<World> dimension;
        long started, until;
        UUID seatEntity;
        Visit(ServerWorld world, UUID villager, BlockPos table, BlockPos seat, UUID player, long now) {
            this.dimension = world.getRegistryKey();
            this.villager = villager; this.table = table; this.seat = seat; this.player = player; this.started = now;
        }
    }
    private record Spin(UUID player, BlockPos pos, long start) { }

    public static void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_WORLD_TICK.register(CasinoLife::tick);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity sp && (entity instanceof VillagerEntity || entity instanceof IronGolemEntity))
                troublemaker(sp, entity.getBlockPos());
            return ActionResult.PASS;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
            Identifier id = Registries.BLOCK.getId(state.getBlock());
            if (!world.isClient && player instanceof ServerPlayerEntity sp && id.getNamespace().equals("mobtalk") && id.getPath().startsWith("casino_"))
                troublemaker(sp, pos);
        });
        ServerTickEvents.END_SERVER_TICK.register(CasinoLife::spins);
    }

    private static MobTalkConfig config() { return ServerConfig.get(); }

    /** Bouncers never take to vanilla's panic: they stand their ground (the mixin on PanicTask asks). */
    public static boolean standsGround(VillagerEntity villager) { return CasinoContent.bouncer(villager) && config().casinoBouncers; }

    // ---- Trouble ----

    /** A player hit a villager or a golem, or broke a piece of the casino: if a bouncer keeps watch near there, the player has to go. */
    static void troublemaker(ServerPlayerEntity player, BlockPos where) {
        if (player.isCreative() || player.isSpectator() || !config().casinoBouncers) return;
        ServerWorld world = player.getServerWorld();
        List<VillagerEntity> bouncers = world.getEntitiesByClass(VillagerEntity.class, new Box(where).expand(TROUBLE_RADIUS), CasinoContent::bouncer);
        if (bouncers.isEmpty()) return;
        boolean fresh = trouble.put(player.getUuid(), world.getTime() + TROUBLE_TICKS) == null || !warned.contains(player.getUuid());
        if (fresh) {
            warned.add(player.getUuid());
            VillagerEntity nearest = bouncers.stream().min(Comparator.comparingDouble(b -> b.squaredDistanceTo(player))).get();
            player.sendMessage(Text.translatable("mobtalk.casino.bouncer.out", name(nearest)).formatted(Formatting.RED), false);
        }
    }

    private static boolean wanted(ServerPlayerEntity player, long now) {
        Long until = trouble.get(player.getUuid());
        if (until == null) return false;
        if (until < now) { trouble.remove(player.getUuid()); warned.remove(player.getUuid()); return false; }
        return !player.isCreative() && !player.isSpectator() && player.isAlive();
    }

    // ---- The tick ----

    private static void tick(ServerWorld world) {
        if (world.getPlayers().isEmpty()) return;
        long now = world.getTime();
        MobTalkConfig config = config();
        if (now % 5 == 0 && config.casinoBouncers) bouncers(world, now, config);
        if (now % 10 == 3) visits(world, now);
        if (now % 100 == 37 && config.casinoVisitors && config.casinoPlayersPerTable > 0) invite(world, now, config);
        if (now % 200 == 71) sweepSeats(world);
    }

    /** The bouncers near the players: keep their stats, fight, or go back to their rope. */
    private static void bouncers(ServerWorld world, long now, MobTalkConfig config) {
        Set<VillagerEntity> seen = new HashSet<>();
        for (ServerPlayerEntity player : world.getPlayers())
            seen.addAll(world.getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(96), CasinoContent::bouncer));
        for (VillagerEntity bouncer : seen) {
            if (!bouncer.isAlive() || bouncer.isBaby()) continue;
            toughen(bouncer, config);
            BlockPos home = home(world, bouncer);
            LivingEntity foe = foe(world, bouncer, home, now);
            if (foe != null) fight(world, bouncer, foe, now, config);
            else if (home != null && bouncer.getBlockPos().getSquaredDistance(home) > 3 * 3) walk(bouncer, home, 0.6f, 1);
            else if (home != null && now % 40 == 0) {
                PlayerEntity near = world.getClosestPlayer(bouncer, 8);
                if (near != null) look(bouncer, near);
            }
        }
    }

    private static void toughen(VillagerEntity bouncer, MobTalkConfig config) {
        EntityAttributeInstance health = bouncer.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (health != null) {
            double extra = Math.max(0, config.bouncerHealth - health.getBaseValue());
            EntityAttributeModifier existing = health.getModifier(BOUNCER_HEALTH);
            if (existing == null || existing.value() != extra) {
                health.removeModifier(BOUNCER_HEALTH);
                if (extra > 0) health.addPersistentModifier(new EntityAttributeModifier(BOUNCER_HEALTH, extra, EntityAttributeModifier.Operation.ADD_VALUE));
                if (existing == null) bouncer.setHealth(bouncer.getMaxHealth());
                else if (bouncer.getHealth() > bouncer.getMaxHealth()) bouncer.setHealth(bouncer.getMaxHealth());
            }
        }
        modifier(bouncer, EntityAttributes.GENERIC_ARMOR, BOUNCER_ARMOR, 8);
        modifier(bouncer, EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, BOUNCER_STEADY, 0.8);
        if (bouncer.getHealth() < bouncer.getMaxHealth() && bouncer.age % 40 == 0) bouncer.heal(1);
    }

    private static void modifier(VillagerEntity v, RegistryEntry<EntityAttribute> attribute, Identifier id, double value) {
        EntityAttributeInstance instance = v.getAttributeInstance(attribute);
        if (instance != null && instance.getModifier(id) == null)
            instance.addPersistentModifier(new EntityAttributeModifier(id, value, EntityAttributeModifier.Operation.ADD_VALUE));
    }

    /** The bouncer's rope (its job site), else the nearest rope, else where it stands. */
    static BlockPos home(ServerWorld world, VillagerEntity bouncer) {
        if (MobActionController.registered(bouncer, MemoryModuleType.JOB_SITE)) {
            Optional<GlobalPos> site = bouncer.getBrain().getOptionalRegisteredMemory(MemoryModuleType.JOB_SITE);
            if (site.isPresent() && site.get().dimension() == world.getRegistryKey()) return site.get().pos();
        }
        return world.getPointOfInterestStorage().getInSquare(e -> e.matchesKey(CasinoContent.ROPE_POI), bouncer.getBlockPos(), 24,
                PointOfInterestStorage.OccupationStatus.ANY).map(PointOfInterest::getPos)
                .min(Comparator.comparingDouble(p -> p.getSquaredDistance(bouncer.getBlockPos()))).orElse(null);
    }

    /** Whom to beat: a player in trouble near the casino, whoever just hit the bouncer, a monster near its rope. */
    private static LivingEntity foe(ServerWorld world, VillagerEntity bouncer, BlockPos home, long now) {
        BlockPos centre = home != null ? home : bouncer.getBlockPos();
        LivingEntity attacker = bouncer.getAttacker();
        if (attacker != null && attacker.isAlive() && attacker.squaredDistanceTo(Vec3d.ofCenter(centre)) < TROUBLE_RADIUS * TROUBLE_RADIUS
                && !(attacker instanceof VillagerEntity) && !(attacker instanceof IronGolemEntity)) {
            if (attacker instanceof ServerPlayerEntity player) {
                if (!player.isCreative() && !player.isSpectator()) { troublemaker(player, bouncer.getBlockPos()); return player; }
            } else return attacker;
        }
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ServerPlayerEntity player : world.getPlayers()) {
            if (!wanted(player, now)) continue;
            double d = player.squaredDistanceTo(Vec3d.ofCenter(centre));
            if (d < TROUBLE_RADIUS * TROUBLE_RADIUS && d < bestDistance) { best = player; bestDistance = d; }
        }
        if (best != null) return best;
        for (HostileEntity monster : world.getEntitiesByClass(HostileEntity.class, new Box(centre).expand(GUARD_RADIUS), e -> e.isAlive() && bouncer.canSee(e))) {
            double d = monster.squaredDistanceTo(bouncer);
            if (d < bestDistance) { best = monster; bestDistance = d; }
        }
        return best;
    }

    private static void fight(ServerWorld world, VillagerEntity bouncer, LivingEntity foe, long now, MobTalkConfig config) {
        look(bouncer, foe);
        double d = bouncer.squaredDistanceTo(foe);
        if (d > REACH * REACH) { walk(bouncer, foe.getBlockPos(), 0.75f, 0); return; }
        Long last = lastBlow.get(bouncer.getUuid());
        if (last != null && now - last < BLOW_TICKS) return;
        lastBlow.put(bouncer.getUuid(), now);
        bouncer.swingHand(Hand.MAIN_HAND, true);
        if (foe.damage(world.getDamageSources().mobAttack(bouncer), config.bouncerDamage)) {
            foe.takeKnockback(foe instanceof PlayerEntity ? 1.6 : 1.0, bouncer.getX() - foe.getX(), bouncer.getZ() - foe.getZ());
            if (foe instanceof ServerPlayerEntity sp) sp.velocityModified = true;
            world.playSound(null, foe.getBlockPos(), SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.NEUTRAL, 1f, 0.8f);
        }
    }

    // ---- Visitors ----

    /** Now and then, in the afternoon, a villager from around comes to a free seat at a game near a player. */
    private static void invite(ServerWorld world, long now, MobTalkConfig config) {
        long day = world.getTimeOfDay() % 24000;
        if (day < VISIT_FROM || day > VISIT_TO || world.isRaining() && random.nextBoolean()) return;
        Set<BlockPos> tables = new HashSet<>();
        for (ServerPlayerEntity player : world.getPlayers())
            world.getPointOfInterestStorage().getInSquare(e -> e.matchesKey(CasinoContent.GAME_POI), player.getBlockPos(), 64,
                    PointOfInterestStorage.OccupationStatus.ANY).forEach(p -> tables.add(p.getPos()));
        for (BlockPos table : tables) {
            if (random.nextFloat() > 0.35f) continue;
            long playing = visits.values().stream().filter(v -> v.table.equals(table)).count();
            if (playing >= config.casinoPlayersPerTable) continue;
            BlockPos seat = freeSeat(world, table);
            if (seat == null) continue;
            VillagerEntity visitor = visitor(world, table, tables);
            if (visitor == null) continue;
            visits.put(visitor.getUuid(), new Visit(world, visitor.getUuid(), table, seat, null, now));
        }
    }

    /** A chair or stool by the table (or in front of the slot machine) that nobody sits on or is heading to. */
    static BlockPos freeSeat(ServerWorld world, BlockPos table) {
        List<BlockPos> seats = new ArrayList<>();
        for (BlockPos at : BlockPos.iterate(table.add(-3, -1, -3), table.add(3, 1, 3))) {
            BlockState s = world.getBlockState(at);
            if (s.isOf(CasinoContent.CHAIR) || s.isOf(CasinoContent.BAR_STOOL)) seats.add(at.toImmutable());
        }
        seats.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(table)));
        for (BlockPos seat : seats) {
            boolean taken = visits.values().stream().anyMatch(v -> v.seat.equals(seat));
            if (!taken && world.getEntitiesByClass(ArmorStandEntity.class, new Box(seat), e -> e.getCommandTags().contains(SEAT_TAG)).isEmpty())
                return seat;
        }
        return null;
    }

    /** A grown villager nearby with nothing better to do: not a bouncer or a soldier, awake, on foot, not already busy here. */
    private static VillagerEntity visitor(ServerWorld world, BlockPos table, Set<BlockPos> tables) {
        List<VillagerEntity> all = world.getEntitiesByClass(VillagerEntity.class, new Box(table).expand(VISITOR_RANGE, 12, VISITOR_RANGE),
                v -> v.isAlive() && !v.isBaby() && !v.isSleeping() && !v.hasVehicle() && !CasinoContent.bouncer(v) && !DefenseContent.defender(v)
                        && !DefenseContent.traveller(v) && !visits.containsKey(v.getUuid()) && v.getAttacker() == null
                        && ownCasino(v, table, tables));
        if (all.isEmpty()) return null;
        // One of the few nearest: a villager from across the village would wander in the street looking for the door.
        all.sort(Comparator.comparingDouble(v -> v.getBlockPos().getSquaredDistance(table)));
        return all.get(random.nextInt(Math.min(3, all.size())));
    }

    /** Whether the table belongs to the casino nearest the villager: one casino's tables lie within a dozen blocks of each other. */
    private static boolean ownCasino(VillagerEntity villager, BlockPos table, Set<BlockPos> tables) {
        BlockPos nearest = tables.stream().min(Comparator.comparingDouble(t -> t.getSquaredDistance(villager.getBlockPos()))).orElse(table);
        return nearest.getSquaredDistance(table) <= 12 * 12;
    }

    private static void visits(ServerWorld world, long now) {
        Iterator<Visit> it = visits.values().iterator();
        while (it.hasNext()) {
            Visit visit = it.next();
            if (visit.dimension != world.getRegistryKey()) continue;
            Entity entity = world.getEntity(visit.villager);
            if (!(entity instanceof VillagerEntity villager)) { unseat(world, visit); it.remove(); continue; }
            if (!villager.isAlive() || villager.isSleeping() || villager.getAttacker() != null && villager.getAttacker().isAlive()
                    || visit.until > 0 && now > visit.until || visit.until == 0 && now - visit.started > WALK_LIMIT
                    || world.getBlockState(visit.table).isAir()) {
                leave(world, villager, visit);
                it.remove();
                continue;
            }
            if (visit.player != null) { dealer(world, villager, visit, now); continue; }
            if (visit.until == 0) {
                if (villager.getBlockPos().getSquaredDistance(visit.seat) > 2.0 * 2.0) { walk(villager, visit.seat, 0.55f, 0); continue; }
                ArmorStandEntity seat = seat(world, visit.seat, visit.table);
                if (seat == null || !villager.startRiding(seat, true)) { leave(world, villager, visit); it.remove(); continue; }
                visit.seatEntity = seat.getUuid();
                visit.until = now + VISIT_MIN + random.nextInt((int) (VISIT_MAX - VISIT_MIN));
            }
            villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
            villager.getBrain().remember(MemoryModuleType.LOOK_TARGET, new BlockPosLookTarget(visit.table.up()));
            if (random.nextFloat() < 0.08f) play(world, villager, visit.table);
        }
    }

    /** The sound of the game the visitor plays, and a cheer now and then. */
    private static void play(ServerWorld world, VillagerEntity villager, BlockPos table) {
        BlockState state = world.getBlockState(table);
        Vec3d at = Vec3d.ofCenter(table).add(0, 0.5, 0);
        if (state.isOf(CasinoContent.SLOT)) {
            world.playSound(null, table, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 0.6f, 1.2f);
            world.playSound(null, table, SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.BLOCKS, 0.35f, 0.8f + random.nextFloat() * 0.8f);
        } else if (state.isOf(CasinoContent.ROULETTE)) {
            world.playSound(null, table, SoundEvents.BLOCK_BAMBOO_WOOD_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 0.5f, 1.6f);
        } else {
            world.playSound(null, table, SoundEvents.ITEM_BOOK_PAGE_TURN, SoundCategory.BLOCKS, 0.6f, 1.3f);
        }
        if (random.nextFloat() < 0.2f) {
            world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, villager.getX(), villager.getEyeY() + 0.3, villager.getZ(), 6, 0.3, 0.2, 0.3, 0);
            world.playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_VILLAGER_CELEBRATE, SoundCategory.NEUTRAL, 0.8f, 1f);
        } else if (random.nextFloat() < 0.25f) {
            world.playSound(null, villager.getBlockPos(), SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 0.6f, 1f);
        }
        world.spawnParticles(ParticleTypes.WAX_ON, at.x, at.y, at.z, 2, 0.3, 0.1, 0.3, 0);
    }

    private static void leave(ServerWorld world, VillagerEntity villager, Visit visit) {
        if (villager.hasVehicle()) villager.stopRiding();
        unseat(world, visit);
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        villager.getBrain().forget(MemoryModuleType.LOOK_TARGET);
    }

    private static void unseat(ServerWorld world, Visit visit) {
        if (visit.seatEntity == null) return;
        Entity seat = world.getEntity(visit.seatEntity);
        if (seat != null) seat.discard();
    }

    // ---- Seats ----

    /** An invisible marker on the chair, turned to face the table, for a villager or a player to ride. */
    static ArmorStandEntity seat(ServerWorld world, BlockPos chair, BlockPos facingTo) {
        ArmorStandEntity stand = EntityType.ARMOR_STAND.create(world);
        if (stand == null) return null;
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean("Marker", true);
        nbt.putBoolean("Invisible", true);
        nbt.putBoolean("NoGravity", true);
        nbt.putBoolean("Invulnerable", true);
        stand.readCustomDataFromNbt(nbt);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setSilent(true);
        stand.addCommandTag(SEAT_TAG);
        BlockState state = world.getBlockState(chair);
        double height = state.isOf(CasinoContent.BAR_STOOL) ? 0.5 : 0.3;
        float yaw;
        if (facingTo != null) {
            Vec3d d = Vec3d.ofCenter(facingTo).subtract(Vec3d.ofCenter(chair));
            yaw = (float) (Math.toDegrees(Math.atan2(-d.x, d.z)));
        } else yaw = state.contains(HorizontalFacingBlock.FACING) ? state.get(HorizontalFacingBlock.FACING).getOpposite().asRotation() : 0;
        stand.refreshPositionAndAngles(chair.getX() + 0.5, chair.getY() + height - 0.35, chair.getZ() + 0.5, yaw, 0);
        stand.setHeadYaw(yaw);
        stand.setBodyYaw(yaw);
        world.spawnEntity(stand);
        return stand;
    }

    /** Seats nobody rides any more go away. */
    private static void sweepSeats(ServerWorld world) {
        for (ServerPlayerEntity player : world.getPlayers())
            for (ArmorStandEntity stand : world.getEntitiesByClass(ArmorStandEntity.class, player.getBoundingBox().expand(96),
                    e -> e.getCommandTags().contains(SEAT_TAG) && !e.hasPassengers())) {
                boolean held = visits.values().stream().anyMatch(v -> stand.getUuid().equals(v.seatEntity));
                if (!held) stand.discard();
            }
    }

    // ---- Using the furniture ----

    static void use(ServerPlayerEntity player, BlockPos pos, BlockState state, CasinoBlocks.Use use) {
        ServerWorld world = player.getServerWorld();
        switch (use) {
            case SEAT -> {
                if (player.hasVehicle() || player.isSneaking()) return;
                if (!world.getEntitiesByClass(ArmorStandEntity.class, new Box(pos), e -> e.getCommandTags().contains(SEAT_TAG)).isEmpty()) return;
                ArmorStandEntity seat = seat(world, pos, nearestTable(world, pos));
                if (seat != null) player.startRiding(seat, true);
            }
            case BLACKJACK -> twentyOne(world, player, pos, state);
            case ROULETTE -> player.sendMessage(Text.translatable("mobtalk.casino.closed.roulette").formatted(Formatting.GOLD), true);
            case POKER -> player.sendMessage(Text.translatable("mobtalk.casino.closed.poker").formatted(Formatting.GOLD), true);
            default -> { }
        }
    }

    private static BlockPos nearestTable(ServerWorld world, BlockPos seat) {
        return world.getPointOfInterestStorage().getInSquare(e -> e.matchesKey(CasinoContent.GAME_POI), seat, 3,
                PointOfInterestStorage.OccupationStatus.ANY).map(PointOfInterest::getPos)
                .min(Comparator.comparingDouble(p -> p.getSquaredDistance(seat))).orElse(null);
    }

    /** The dealer's place: behind the middle of the table's straight side. */
    static BlockPos dealerSpot(BlockPos table, BlockState state) {
        Direction facing = state.get(HorizontalFacingBlock.FACING);
        return CasinoBlocks.cell(table, facing, 0, 1);
    }

    /** The table for 21: the villager at it deals (the game of 21 opens through its own command); if nobody is there, the nearest
     * villager is called over and the game opens when it gets there. */
    private static void twentyOne(ServerWorld world, ServerPlayerEntity player, BlockPos table, BlockState state) {
        if (!cardsAvailable(world.getServer())) { player.sendMessage(Text.translatable("mobtalk.casino.cards.missing").formatted(Formatting.GOLD), true); return; }
        BlockPos spot = dealerSpot(table, state);
        List<VillagerEntity> at = world.getEntitiesByClass(VillagerEntity.class, new Box(table).expand(3.5, 2, 3.5),
                v -> v.isAlive() && !v.isBaby() && !CasinoContent.bouncer(v));
        if (!at.isEmpty()) {
            VillagerEntity dealer = at.stream().min(Comparator.comparingDouble(v -> v.getBlockPos().getSquaredDistance(spot))).get();
            openCards(world.getServer(), player, dealer);
            return;
        }
        for (Visit v : visits.values()) if (v.table.equals(table) && player.getUuid().equals(v.player)) return;
        VillagerEntity dealer = world.getEntitiesByClass(VillagerEntity.class, new Box(table).expand(32, 8, 32),
                        v -> v.isAlive() && !v.isBaby() && !v.isSleeping() && !CasinoContent.bouncer(v) && !DefenseContent.defender(v))
                .stream().min(Comparator.comparingDouble(v -> v.squaredDistanceTo(Vec3d.ofCenter(table)))).orElse(null);
        if (dealer == null) { player.sendMessage(Text.translatable("mobtalk.casino.dealer.none").formatted(Formatting.GOLD), true); return; }
        Visit old = visits.remove(dealer.getUuid());
        if (old != null) leave(world, dealer, old);
        visits.put(dealer.getUuid(), new Visit(world, dealer.getUuid(), table, spot, player.getUuid(), world.getTime()));
        player.sendMessage(Text.translatable("mobtalk.casino.dealer.coming", name(dealer)).formatted(Formatting.GOLD), true);
    }

    private static void dealer(ServerWorld world, VillagerEntity villager, Visit visit, long now) {
        ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(visit.player);
        if (player == null || player.squaredDistanceTo(Vec3d.ofCenter(visit.table)) > 8 * 8) { visit.until = now - 1; return; }
        if (villager.getBlockPos().getSquaredDistance(visit.seat) > 1.6 * 1.6) { walk(villager, visit.seat, 0.6f, 0); return; }
        villager.getBrain().forget(MemoryModuleType.WALK_TARGET);
        visit.until = now - 1; // the game of 21 takes the villager from here
        openCards(world.getServer(), player, villager);
    }

    /** Whether the game of 21 is in this build: its command {@code /mobtalk cards open <villager>}. */
    static boolean cardsAvailable(MinecraftServer server) {
        var root = server.getCommandManager().getDispatcher().getRoot().getChild("mobtalk");
        return root != null && root.getChild("cards") != null;
    }

    private static void openCards(MinecraftServer server, ServerPlayerEntity player, VillagerEntity dealer) {
        server.getCommandManager().executeWithPrefix(player.getCommandSource(), "mobtalk cards open " + dealer.getUuidAsString());
    }

    // ---- The slot machine ----

    private static final String[] SYMBOLS = { "7", "♥", "★", "♦", "♣" };
    private static final Formatting[] COLORS = { Formatting.RED, Formatting.LIGHT_PURPLE, Formatting.YELLOW, Formatting.AQUA, Formatting.GREEN };

    /** A spin for fun: the reels run for a second and a half on the action bar, with clicks and a bell. */
    static void spin(ServerPlayerEntity player, BlockPos pos) {
        for (Spin s : spins) if (s.player().equals(player.getUuid())) return;
        spins.add(new Spin(player.getUuid(), pos, player.getServerWorld().getTime()));
        player.getServerWorld().playSound(null, pos, SoundEvents.BLOCK_LEVER_CLICK, SoundCategory.BLOCKS, 1f, 0.9f);
    }

    private static void spins(MinecraftServer server) {
        if (spins.isEmpty()) return;
        Iterator<Spin> it = spins.iterator();
        while (it.hasNext()) {
            Spin s = it.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(s.player());
            if (player == null) { it.remove(); continue; }
            ServerWorld world = player.getServerWorld();
            long t = world.getTime() - s.start();
            if (t < 0 || t > 40) { it.remove(); continue; }
            if (t % 3 == 0 && t < 30) {
                player.sendMessage(reels(random.nextInt(5), random.nextInt(5), random.nextInt(5)), true);
                world.playSound(null, s.pos(), SoundEvents.BLOCK_NOTE_BLOCK_HAT.value(), SoundCategory.BLOCKS, 0.4f, 1.4f + random.nextFloat() * 0.4f);
            }
            if (t == 30) {
                int a = random.nextInt(5), b = random.nextInt(4) == 0 ? a : random.nextInt(5), c = random.nextInt(4) == 0 ? b : random.nextInt(5);
                player.sendMessage(reels(a, b, c), true);
                boolean jackpot = a == b && b == c;
                player.sendMessage(Text.translatable(jackpot ? "mobtalk.casino.spin.jackpot" : "mobtalk.casino.spin.lose")
                        .formatted(jackpot ? Formatting.GOLD : Formatting.GRAY), false);
                if (jackpot) {
                    world.playSound(null, s.pos(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.BLOCKS, 1f, 1.2f);
                    world.spawnParticles(ParticleTypes.WAX_OFF, s.pos().getX() + 0.5, s.pos().getY() + 2.2, s.pos().getZ() + 0.5, 30, 0.4, 0.3, 0.4, 0.5);
                } else world.playSound(null, s.pos(), SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), SoundCategory.BLOCKS, 0.6f, 0.7f);
            }
        }
    }

    private static Text reels(int a, int b, int c) {
        return Text.translatable("mobtalk.casino.spin.result", sym(a), sym(b), sym(c)).formatted(Formatting.WHITE);
    }
    private static MutableText sym(int i) { return Text.literal(SYMBOLS[i]).formatted(COLORS[i], Formatting.BOLD); }

    // ---- Helpers ----

    private static void walk(VillagerEntity villager, BlockPos to, float speed, int range) {
        if (!MobActionController.registered(villager, MemoryModuleType.WALK_TARGET)) return;
        villager.getBrain().remember(MemoryModuleType.WALK_TARGET, new WalkTarget(to, speed, range), 40);
    }

    private static void look(VillagerEntity villager, Entity at) {
        if (MobActionController.registered(villager, MemoryModuleType.LOOK_TARGET))
            villager.getBrain().remember(MemoryModuleType.LOOK_TARGET, new EntityLookTarget(at, true), 40);
    }

    static String name(VillagerEntity villager) {
        MobGameBridge bridge = MobGameBridge.forEntity(villager);
        String name = bridge == null ? null : bridge.memory.displayName(villager.getUuid());
        return name == null || name.isBlank() ? villager.getName().getString() : name;
    }

    /** For a world that closes: forget the runtime state (seats in the world go with their sweep). */
    public static void clear() { trouble.clear(); lastBlow.clear(); warned.clear(); visits.clear(); spins.clear(); }
}
