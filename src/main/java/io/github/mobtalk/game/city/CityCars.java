package io.github.mobtalk.game.city;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.MapCodec;
import io.github.mobtalk.server.ServerConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.ServerWorldAccess;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/** The city's parked cars (spec §7, docs/cars.md): the car entity's registration, the API the city generator parks cars with,
 * the invisible {@code parking_spot} marker for templates and the {@code /cars} commands for testing.
 *
 * <p>For the city generator: {@link #park(ServerWorldAccess, BlockPos, Direction, Random, Place)} parks a car fitting the place
 * (a yard, a street, a bus stop, a police station…) on a spot, or leaves it empty by {@code cityCarsPercent}; it works during
 * world generation (a feature's {@code StructureWorldAccess}) and in a live world. {@link #spawn} puts a given car in a given
 * scheme. A template from another thread marks its spots with {@code mobtalk:parking_spot} (facing = where the car's nose
 * points): when the chunk loads the marker turns into a car or into air. */
public final class CityCars {
    private CityCars() { }

    public static Block PARKING_SPOT;
    private static boolean registered;
    private static final Map<ServerWorld, Deque<ChunkPos>> PENDING = new WeakHashMap<>();

    /** Where a car is parked: the mix of cars and paints differs. */
    public enum Place {
        /** A yard between panel houses: Zhiguli, Moskvich, Niva, a «четырка», now and then a Volga, a Zaporozhets, a UAZ. */
        YARD,
        /** Along a street or by a shop: the same with more Volgas and vans. */
        STREET,
        /** A bus stop or the bus station: a PAZ. */
        BUS,
        /** By the police station: police Zhiguli and the patrol UAZ. */
        POLICE,
        /** Soviet times: the militia's yellow Zhiguli. */
        MILITSIYA,
        /** A taxi rank: Volga taxis. */
        TAXI,
        /** The hospital: the UAZ ambulance. */
        AMBULANCE
    }

    /** Weighted car types per place, then weighted schemes per type (liveries never come up as a private car's paint). */
    private static final Map<Place, Object[]> MIX = Map.of(
            Place.YARD, new Object[] {"vaz2107", 30, "vaz2114", 16, "moskvich412", 12, "niva", 10, "gaz24", 7, "zaz968", 8, "uaz452", 3},
            Place.STREET, new Object[] {"vaz2107", 24, "vaz2114", 18, "moskvich412", 10, "niva", 8, "gaz24", 12, "zaz968", 5, "uaz452", 6},
            Place.BUS, new Object[] {"paz3205", 1},
            Place.POLICE, new Object[] {"vaz2107", 6, "uaz452", 4},
            Place.MILITSIYA, new Object[] {"vaz2107", 1},
            Place.TAXI, new Object[] {"gaz24", 1},
            Place.AMBULANCE, new Object[] {"uaz452", 1});

    public static void register() {
        if (registered) return;
        registered = true;
        CarEntity.TYPE = Registry.register(Registries.ENTITY_TYPE, Identifier.of("mobtalk", "car"),
                EntityType.Builder.<CarEntity>create(CarEntity::new, SpawnGroup.MISC).dimensions(1.8f, 1.5f).maxTrackingRange(10)
                        .trackingTickInterval(2).makeFireImmune().build());
        PARKING_SPOT = Registry.register(Registries.BLOCK, Identifier.of("mobtalk", "parking_spot"), new SpotBlock(AbstractBlock.Settings.create()
                .strength(0.1f).noCollision().nonOpaque().dropsNothing().replaceable().pistonBehavior(PistonBehavior.DESTROY)));
        Item item = Registry.register(Registries.ITEM, Identifier.of("mobtalk", "parking_spot"), new BlockItem(PARKING_SPOT, new Item.Settings()));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS).register(entries -> entries.add(item));
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (hasSpots(chunk)) PENDING.computeIfAbsent(world, w -> new ArrayDeque<>()).add(chunk.getPos());
        });
        ServerTickEvents.END_WORLD_TICK.register(CityCars::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(literal("cars")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("spawn").then(argument("type", StringArgumentType.word()).suggests((c, b) -> CommandSource.suggestMatching(
                                CarTypes.ALL.stream().map(CarTypes.Type::id), b))
                        .executes(c -> spawnCommand(c, null))
                        .then(argument("scheme", StringArgumentType.word()).suggests((c, b) -> {
                            CarTypes.Type t = CarTypes.get(StringArgumentType.getString(c, "type"));
                            return CommandSource.suggestMatching(t == null ? List.of() : t.schemes(), b);
                        }).executes(c -> spawnCommand(c, StringArgumentType.getString(c, "scheme"))))))
                .then(literal("row").executes(CityCars::rowCommand))
                .then(literal("park").then(argument("place", StringArgumentType.word()).suggests((c, b) -> CommandSource.suggestMatching(
                        java.util.Arrays.stream(Place.values()).map(p -> p.name().toLowerCase()), b)).executes(CityCars::parkCommand)))
                .then(literal("lights").then(argument("what", StringArgumentType.word()).suggests((c, b) -> CommandSource.suggestMatching(
                        List.of("on", "off", "beacon", "nobeacon", "brake", "hazard", "doors", "close"), b)).executes(CityCars::lightsCommand)))
                .then(literal("drive").then(argument("pedal", FloatArgumentType.floatArg(-1, 1)).then(argument("wheel", FloatArgumentType.floatArg(-1, 1))
                        .executes(c -> nearest(c, car -> car.drive(FloatArgumentType.getFloat(c, "pedal"), FloatArgumentType.getFloat(c, "wheel")))))))
                .then(literal("steer").then(argument("wheel", FloatArgumentType.floatArg(-1, 1))
                        .executes(c -> nearest(c, car -> { car.drive(0, FloatArgumentType.getFloat(c, "wheel")); }))))
                .then(literal("stop").executes(c -> nearest(c, CarEntity::stop)))
                .then(literal("clear").executes(CityCars::clearCommand))));
    }

    // ------------------------------------------------------------ the API

    /** Parks a car fitting {@code place} on the spot (the block the car's centre stands on, its nose toward {@code facing}),
     * or nothing by the {@code cityCarsPercent} chance. Returns the car or null. */
    public static CarEntity park(ServerWorldAccess world, BlockPos spot, Direction facing, Random random, Place place) {
        if (random.nextInt(100) >= ServerConfig.get().cityCarsPercent) return null;
        return parkAlways(world, spot, facing, random, place);
    }

    /** As {@link #park} but always parks. */
    public static CarEntity parkAlways(ServerWorldAccess world, BlockPos spot, Direction facing, Random random, Place place) {
        String type = pick(MIX.get(place), random);
        CarTypes.Type t = CarTypes.get(type);
        if (t == null) return null;
        String scheme = scheme(t, random, place);
        // a little play in how people park: up to a quarter block off the line and three degrees askew
        float yaw = facing.asRotation() + (place == Place.BUS ? 0 : (random.nextFloat() - 0.5f) * 6);
        double side = place == Place.BUS ? 0 : (random.nextDouble() - 0.5) * 0.3;
        Vec3d off = new Vec3d(facing.rotateYClockwise().getOffsetX(), 0, facing.rotateYClockwise().getOffsetZ()).multiply(side);
        return spawn(world, spot.getX() + 0.5 + off.x, spot.getY(), spot.getZ() + 0.5 + off.z, yaw, type, scheme);
    }

    /** A given car (see {@link CarTypes}) in a given scheme (null: a random factory colour) at a point, facing yaw (0 = south). */
    public static CarEntity spawn(ServerWorldAccess world, double x, double y, double z, float yaw, String type, String scheme) {
        CarTypes.Type t = CarTypes.get(type);
        if (t == null) return null;
        if (scheme == null) scheme = scheme(t, world.getRandom(), Place.YARD);
        CarEntity car = CarEntity.create(world.toServerWorld(), t.id(), scheme, x, y, z, yaw);
        world.spawnEntity(car);
        return car;
    }

    /** The schemes a car of the type may wear at the place: a livery for a service place, a factory colour otherwise. */
    public static String scheme(CarTypes.Type t, Random random, Place place) {
        String livery = switch (place) {
            case POLICE -> "police"; case MILITSIYA -> "militsiya"; case TAXI -> "taxi"; case AMBULANCE -> "ambulance"; default -> null;
        };
        if (livery != null && t.schemes().contains(livery)) return livery;
        List<String> plain = new ArrayList<>();
        for (String s : t.schemes()) if (!LIVERIES.contains(s)) plain.add(s);
        return plain.isEmpty() ? t.schemes().get(0) : plain.get(random.nextInt(plain.size()));
    }

    public static final List<String> LIVERIES = List.of("police", "militsiya", "taxi", "ambulance");

    private static String pick(Object[] mix, Random random) {
        int total = 0;
        for (int i = 1; i < mix.length; i += 2) if (CarTypes.get((String) mix[i - 1]) != null) total += (Integer) mix[i];
        int roll = random.nextInt(Math.max(1, total));
        for (int i = 1; i < mix.length; i += 2) {
            if (CarTypes.get((String) mix[i - 1]) == null) continue;
            roll -= (Integer) mix[i];
            if (roll < 0) return (String) mix[i - 1];
        }
        return CarTypes.ALL.get(0).id();
    }

    // ------------------------------------------------------------ the marker block

    /** The template marker: invisible, no collision, facing = the car's nose. Turned into a car (by {@link Place#YARD} mix unless the
     * block below is a police or bus block — the city decides that by calling {@link #park} itself) when its chunk loads. */
    public static final class SpotBlock extends HorizontalFacingBlock {
        public static final MapCodec<SpotBlock> CODEC = createCodec(SpotBlock::new);
        public SpotBlock(Settings settings) { super(settings); setDefaultState(getDefaultState().with(FACING, Direction.NORTH)); }
        @Override protected MapCodec<? extends HorizontalFacingBlock> getCodec() { return CODEC; }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING); }
        @Override public BlockState getPlacementState(ItemPlacementContext ctx) { return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing()); }
        @Override protected BlockRenderType getRenderType(BlockState state) { return BlockRenderType.INVISIBLE; }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return VoxelShapes.cuboid(0.25, 0, 0.25, 0.75, 0.1, 0.75);
        }
    }

    private static boolean hasSpots(WorldChunk chunk) {
        for (ChunkSection section : chunk.getSectionArray()) {
            if (!section.isEmpty() && section.hasAny(s -> s.isOf(PARKING_SPOT))) return true;
        }
        return false;
    }

    /** Two chunks a tick: their markers become cars or air. */
    private static void tick(ServerWorld world) {
        Deque<ChunkPos> queue = PENDING.get(world);
        if (queue == null) return;
        for (int n = 0; n < 2 && !queue.isEmpty(); n++) {
            ChunkPos pos = queue.poll();
            WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
            if (chunk == null) continue;
            ChunkSection[] sections = chunk.getSectionArray();
            for (int i = 0; i < sections.length; i++) {
                ChunkSection section = sections[i];
                if (section.isEmpty() || !section.hasAny(s -> s.isOf(PARKING_SPOT))) continue;
                int baseY = chunk.sectionIndexToCoord(i) << 4;
                for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (!state.isOf(PARKING_SPOT)) continue;
                    BlockPos at = new BlockPos(pos.getStartX() + x, baseY + y, pos.getStartZ() + z);
                    world.setBlockState(at, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                    park(world, at, state.get(SpotBlock.FACING), Random.create(at.asLong() ^ world.getSeed()), Place.YARD);
                }
            }
        }
    }

    // ------------------------------------------------------------ commands

    private static int spawnCommand(CommandContext<ServerCommandSource> c, String scheme) {
        ServerPlayerEntity player = c.getSource().getPlayer();
        if (player == null) return 0;
        String type = StringArgumentType.getString(c, "type");
        if (CarTypes.get(type) == null) { c.getSource().sendError(Text.literal("Нет такой машины: " + type)); return 0; }
        float yaw = Math.round(player.getYaw() / 90f) * 90f;
        Vec3d at = player.getPos().add(Vec3d.fromPolar(0, player.getYaw()).multiply(4));
        CarEntity car = spawn(player.getServerWorld(), at.x, Math.floor(player.getY()), at.z, yaw, type, scheme);
        c.getSource().sendFeedback(() -> Text.literal(car.getName().getString() + " — " + car.scheme()), false);
        return 1;
    }

    /** Every type in a row three blocks apart, noses toward the player, in its first factory colour. */
    private static int rowCommand(CommandContext<ServerCommandSource> c) {
        ServerPlayerEntity player = c.getSource().getPlayer();
        if (player == null) return 0;
        Direction facing = player.getHorizontalFacing();
        Direction along = facing.rotateYClockwise();
        BlockPos base = player.getBlockPos().offset(facing, 6);
        int i = 0;
        for (CarTypes.Type t : CarTypes.ALL) {
            BlockPos at = base.offset(along, (i - CarTypes.ALL.size() / 2) * 4);
            String scheme = scheme(t, Random.create(t.id().hashCode()), Place.YARD);
            spawn(player.getServerWorld(), at.getX() + 0.5, at.getY(), at.getZ() + 0.5, facing.getOpposite().asRotation(), t.id(), scheme);
            i++;
        }
        return i;
    }

    private static int parkCommand(CommandContext<ServerCommandSource> c) {
        ServerPlayerEntity player = c.getSource().getPlayer();
        if (player == null) return 0;
        Place place;
        try { place = Place.valueOf(StringArgumentType.getString(c, "place").toUpperCase()); }
        catch (IllegalArgumentException e) { c.getSource().sendError(Text.literal("Места: yard, street, bus, police, militsiya, taxi, ambulance")); return 0; }
        CarEntity car = parkAlways(player.getServerWorld(), player.getBlockPos().offset(player.getHorizontalFacing(), 4), player.getHorizontalFacing(),
                player.getRandom(), place);
        return car == null ? 0 : 1;
    }

    private static int lightsCommand(CommandContext<ServerCommandSource> c) {
        String what = StringArgumentType.getString(c, "what");
        return nearest(c, car -> {
            switch (what) {
                case "on" -> car.setHeadlights(true);
                case "off" -> { car.setHeadlights(false); car.setBeacon(false); car.setLight(CarEntity.BRAKE, false); car.setLight(CarEntity.HAZARD, false); }
                case "beacon" -> car.setBeacon(true);
                case "nobeacon" -> car.setBeacon(false);
                case "brake" -> car.setLight(CarEntity.BRAKE, true);
                case "hazard" -> car.setLight(CarEntity.HAZARD, true);
                case "doors" -> { for (int i = 0; i < car.type().doors(); i++) car.openDoor(i, 20 * 60 * 10); }
                case "close" -> { for (int i = 0; i < car.type().doors(); i++) car.openDoor(i, 0); }
                default -> { }
            }
        });
    }

    /** Runs on every car within 12 blocks of the command's source. */
    private static int nearest(CommandContext<ServerCommandSource> c, java.util.function.Consumer<CarEntity> action) {
        ServerWorld world = c.getSource().getWorld();
        Vec3d at = c.getSource().getPosition();
        List<CarEntity> cars = world.getEntitiesByClass(CarEntity.class, new net.minecraft.util.math.Box(at, at).expand(12), e -> true);
        cars.forEach(action);
        return cars.size();
    }

    private static int clearCommand(CommandContext<ServerCommandSource> c) {
        ServerWorld world = c.getSource().getWorld();
        Vec3d at = c.getSource().getPosition();
        List<CarEntity> cars = world.getEntitiesByClass(CarEntity.class, new net.minecraft.util.math.Box(at, at).expand(64), e -> true);
        cars.forEach(CarEntity::discard);
        return cars.size();
    }

    static int clamp(int v) { return MathHelper.clamp(v, 0, 100); }
}
