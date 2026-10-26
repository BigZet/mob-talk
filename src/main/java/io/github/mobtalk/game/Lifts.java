package io.github.mobtalk.game;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.mobtalk.net.MobTalkNet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/** Lifts of the tall panel houses (spec §3.2 p.4): a Soviet passenger lift with a 2 x 2 shaft. On every floor two door columns
 * ({@link LiftBlocks.DoorBlock}) stand in the shaft wall on the landing side and a call button ({@link LiftBlocks.ButtonBlock})
 * hangs beside them; the winch ({@link LiftBlocks.MachineBlock}) on the roof slab over the shaft finds the floors by the doors and
 * spawns the cabin ({@link LiftCabin}), an entity that carries whoever stands in it. Models and textures: tools/city2/lift.py,
 * which also writes lifts into house templates with the same layout as {@link #place}.
 *
 * <p>For the city generator: {@code Lifts.place(world, shaftOrigin, floors, storeyHeight, facing)}. {@code /city lift <floors>
 * [storey]} builds a bare test tower with a lift where the player stands. */
public final class Lifts {
    private Lifts() { }

    public static Block DOOR, BUTTON, MACHINE, FLOOR;
    public static BlockEntityType<LiftBlocks.DoorEntity> DOOR_ENTITY;
    public static BlockEntityType<LiftBlocks.MachineEntity> MACHINE_ENTITY;
    public static EntityType<LiftCabin> CABIN;
    public static SoundEvent MOTOR, START, STOP, DOOR_OPEN, DOOR_CLOSE, CLICK, RELAY, BELL;
    /** The client opens the cabin's panel through this (set by the client's init); the server leaves it alone. */
    public static Consumer<LiftCabin> openPanel = cabin -> { };
    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        List<Item> items = new ArrayList<>();
        AbstractBlock.Settings steel = AbstractBlock.Settings.create().mapColor(MapColor.BROWN).strength(30f, 1200f).requiresTool()
                .sounds(BlockSoundGroup.METAL).nonOpaque().pistonBehavior(PistonBehavior.BLOCK);
        DOOR = block("lift_door", new LiftBlocks.DoorBlock(steel), items);
        BUTTON = block("lift_button", new LiftBlocks.ButtonBlock(AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY).strength(3f)
                .sounds(BlockSoundGroup.METAL).nonOpaque().noCollision().pistonBehavior(PistonBehavior.DESTROY)
                .luminance(s -> s.get(LiftBlocks.ButtonBlock.LIT) ? 4 : 0)), items);
        MACHINE = block("lift_machine", new LiftBlocks.MachineBlock(AbstractBlock.Settings.create().mapColor(MapColor.GREEN).strength(30f, 1200f)
                .requiresTool().sounds(BlockSoundGroup.METAL).nonOpaque().pistonBehavior(PistonBehavior.BLOCK)), items);
        FLOOR = Registry.register(Registries.BLOCK, Identifier.of("mobtalk", "lift_floor"), new LiftBlocks.FloorBlock(AbstractBlock.Settings.create()
                .strength(-1f, 3600000f).dropsNothing().nonOpaque().noBlockBreakParticles().pistonBehavior(PistonBehavior.BLOCK)
                .allowsSpawning((s, w, p, t) -> false).solidBlock((s, w, p) -> false).suffocates((s, w, p) -> false)));
        DOOR_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of("mobtalk", "lift_door"),
                BlockEntityType.Builder.create(LiftBlocks.DoorEntity::new, DOOR).build());
        MACHINE_ENTITY = Registry.register(Registries.BLOCK_ENTITY_TYPE, Identifier.of("mobtalk", "lift_machine"),
                BlockEntityType.Builder.create(LiftBlocks.MachineEntity::new, MACHINE).build());
        CABIN = Registry.register(Registries.ENTITY_TYPE, Identifier.of("mobtalk", "lift_cabin"),
                EntityType.Builder.<LiftCabin>create(LiftCabin::new, SpawnGroup.MISC).dimensions(2f, 3f).maxTrackingRange(8)
                        .trackingTickInterval(1).makeFireImmune().disableSummon().build());
        MOTOR = sound("lift_motor");
        START = sound("lift_start");
        STOP = sound("lift_stop");
        DOOR_OPEN = sound("lift_door_open");
        DOOR_CLOSE = sound("lift_door_close");
        CLICK = sound("lift_button");
        RELAY = sound("lift_relay");
        BELL = sound("lift_bell");
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS).register(entries -> items.forEach(entries::add));
        PayloadTypeRegistry.playC2S().register(PressC2S.ID, PressC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PressC2S.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player.getWorld().getEntityById(payload.cabin()) instanceof LiftCabin cabin && cabin.contains(player))
                cabin.press(payload.button(), player);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(literal("city")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("lift").then(argument("floors", IntegerArgumentType.integer(2, 17))
                        .executes(c -> testTower(c.getSource(), IntegerArgumentType.getInteger(c, "floors"), 4, LiftBlocks.Style.SOVIET))
                        .then(argument("storey", IntegerArgumentType.integer(3, 6))
                                .executes(c -> testTower(c.getSource(), IntegerArgumentType.getInteger(c, "floors"),
                                        IntegerArgumentType.getInteger(c, "storey"), LiftBlocks.Style.SOVIET))
                                .then(argument("style", com.mojang.brigadier.arguments.StringArgumentType.word())
                                        .suggests((c, b) -> net.minecraft.command.CommandSource.suggestMatching(
                                                java.util.Arrays.stream(LiftBlocks.Style.values()).map(LiftBlocks.Style::asString), b))
                                        .executes(c -> testTower(c.getSource(), IntegerArgumentType.getInteger(c, "floors"),
                                                IntegerArgumentType.getInteger(c, "storey"),
                                                style(com.mojang.brigadier.arguments.StringArgumentType.getString(c, "style"))))))))));
    }

    private static LiftBlocks.Style style(String name) {
        for (LiftBlocks.Style s : LiftBlocks.Style.values()) if (s.asString().equals(name)) return s;
        return LiftBlocks.Style.SOVIET;
    }

    private static Block block(String id, Block block, List<Item> items) {
        Identifier key = Identifier.of("mobtalk", id);
        Registry.register(Registries.BLOCK, key, block);
        items.add(Registry.register(Registries.ITEM, key, new BlockItem(block, new Item.Settings())));
        return block;
    }

    private static SoundEvent sound(String id) {
        Identifier key = Identifier.of("mobtalk", id);
        return Registry.register(Registries.SOUND_EVENT, key, SoundEvent.of(key));
    }

    /** The panel's buttons as the client sends them: a floor (0 = the lowest), or one of these. */
    public static final int STOP_BUTTON = -1, BELL_BUTTON = -2;

    public record PressC2S(int cabin, int button) implements CustomPayload {
        public static final Id<PressC2S> ID = MobTalkNet.payloadId("lift_press");
        public static final PacketCodec<PacketByteBuf, PressC2S> CODEC = MobTalkNet.codec(
                (p, buf) -> { buf.writeVarInt(p.cabin); buf.writeVarInt(p.button); }, buf -> new PressC2S(buf.readVarInt(), buf.readVarInt()));
        @Override public Id<PressC2S> getId() { return ID; }
    }

    // ---------------------------------------------------------------- the layout (the same as tools/city2/lift.py place)

    /** Puts a lift into a house. {@code shaftOrigin}: the shaft's 2 x 2 corner with the smallest x and z, in the slab row of floor 1
     * (floors count from street level + 1); {@code storeyHeight}: floor to floor (4 in the new houses: slab + 3 rows of room);
     * {@code facing}: where the doors look, to the landing. The shaft's walls are the house's: here the shaft is cleared, every floor
     * gets two door columns in the wall row on the landing side (rows slab + 1 and slab + 2) and the call button on the landing to
     * the right of the doors seen from the landing (row slab + 2; it hangs on the shaft wall's corner cell), and the winch stands on
     * the roof slab over the shaft (row {@code origin.y + floors * storeyHeight}). The cabin comes by itself once the winch ticks. */
    public static void place(ServerWorld world, BlockPos shaftOrigin, int floors, int storeyHeight, Direction facing) {
        place(world, shaftOrigin, floors, storeyHeight, facing, LiftBlocks.Style.SOVIET);
    }

    /** The same in a look of its own ({@link LiftBlocks.Style#pick} gives a realistic mix by a house's seed). */
    public static void place(ServerWorld world, BlockPos shaftOrigin, int floors, int storeyHeight, Direction facing, LiftBlocks.Style style) {
        Layout l = Layout.of(shaftOrigin, facing);
        for (int k = 0; k < floors; k++) {
            int s = shaftOrigin.getY() + k * storeyHeight;
            for (BlockPos c : l.cells)
                for (int y = s; y < s + storeyHeight; y++) world.setBlockState(c.withY(y), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            for (int i = 0; i < 2; i++) {
                BlockPos col = i == 0 ? l.left : l.right;
                LiftBlocks.Side side = i == 0 ? LiftBlocks.Side.LEFT : LiftBlocks.Side.RIGHT;
                BlockState door = DOOR.getDefaultState().with(LiftBlocks.DoorBlock.FACING, facing).with(LiftBlocks.DoorBlock.SIDE, side)
                        .with(LiftBlocks.STYLE, style);
                world.setBlockState(col.withY(s + 1), door.with(LiftBlocks.DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.NOTIFY_LISTENERS);
                world.setBlockState(col.withY(s + 2), door.with(LiftBlocks.DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_LISTENERS);
            }
            world.setBlockState(l.button.withY(s + 2), BUTTON.getDefaultState().with(LiftBlocks.ButtonBlock.FACING, facing).with(LiftBlocks.STYLE, style),
                    Block.NOTIFY_LISTENERS);
        }
        world.setBlockState(shaftOrigin.up(floors * storeyHeight), MACHINE.getDefaultState(), Block.NOTIFY_LISTENERS);
    }

    /** Where things are around a shaft: its four cells, the door columns (left and right seen from inside the shaft), the button's
     * column. Any y; callers set it. */
    public record Layout(List<BlockPos> cells, BlockPos left, BlockPos right, BlockPos button, Direction facing) {
        static Layout of(BlockPos origin, Direction facing) {
            Direction p = facing.rotateYClockwise();   // to the right, seen from inside the shaft looking out
            List<BlockPos> cells = List.of(origin, origin.east(), origin.south(), origin.south().east());
            List<BlockPos> front = new ArrayList<>(cells.stream().filter(c -> !cells.contains(c.offset(facing))).toList());
            front.sort(java.util.Comparator.comparingInt(c -> c.getX() * p.getOffsetX() + c.getZ() * p.getOffsetZ()));
            BlockPos left = front.get(0).offset(facing), right = front.get(1).offset(facing);
            return new Layout(cells, left, right, left.offset(p.getOpposite()).offset(facing), facing);
        }

        /** From one door column (lower or upper half, either side); the origin's y is the door's. */
        static Layout ofDoor(BlockPos door, BlockState state) {
            Direction f = state.get(LiftBlocks.DoorBlock.FACING), p = f.rotateYClockwise();
            BlockPos left = state.get(LiftBlocks.DoorBlock.SIDE) == LiftBlocks.Side.LEFT ? door : door.offset(p.getOpposite());
            BlockPos a = left.offset(f.getOpposite()), b = a.offset(f.getOpposite()).offset(p);
            return of(new BlockPos(Math.min(a.getX(), b.getX()), door.getY(), Math.min(a.getZ(), b.getZ())), f);
        }

        public BlockPos origin() { return cells.get(0); }
    }

    /** A bare tower to try a lift on: the shaft's concrete walls, a landing with a rail on every floor, the winch's room on the roof.
     * The shaft's corner is two blocks in front of the player (the doors look at the player). */
    private static int testTower(ServerCommandSource source, int floors, int storey, LiftBlocks.Style style) {
        ServerWorld world = source.getWorld();
        Direction facing = source.getPlayer() != null ? source.getPlayer().getHorizontalFacing().getOpposite() : Direction.SOUTH;
        BlockPos feet = BlockPos.ofFloored(source.getPosition());
        BlockPos base = feet.offset(facing.getOpposite(), 4).down();   // the slab row of floor 1 is the ground row under the player
        Direction p = facing.rotateYClockwise();
        BlockPos origin = base;
        for (BlockPos c : List.of(base, base.offset(p), base.offset(facing.getOpposite()), base.offset(p).offset(facing.getOpposite())))
            origin = new BlockPos(Math.min(origin.getX(), c.getX()), base.getY(), Math.min(origin.getZ(), c.getZ()));
        BlockState wall = Registries.BLOCK.get(Identifier.of("mobtalk", "floor_concrete")).getDefaultState();
        if (wall.isAir()) wall = Blocks.SMOOTH_STONE.getDefaultState();
        BlockState slab = Registries.BLOCK.get(Identifier.of("mobtalk", "floor_terrazzo")).getDefaultState();
        if (slab.isAir()) slab = Blocks.POLISHED_ANDESITE.getDefaultState();
        int top = origin.getY() + floors * storey;
        for (int y = origin.getY() - 1; y <= top + 3; y++)
            for (int dx = -1; dx <= 2; dx++)
                for (int dz = -1; dz <= 2; dz++) {
                    BlockPos at = origin.add(dx, 0, dz).withY(y);
                    boolean ring = dx == -1 || dx == 2 || dz == -1 || dz == 2;
                    world.setBlockState(at, ring || y == origin.getY() - 1 || y >= top ? wall : Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
        Layout l = Layout.of(origin, facing);
        for (int k = 0; k < floors; k++) {   // the landings: 4 wide, 3 deep in front of the doors, with a rail
            int s = origin.getY() + k * storey;
            for (int a = -1; a <= 2; a++)
                for (int d = 1; d <= 3; d++) {
                    BlockPos front = l.left.offset(p, a).offset(facing, d).withY(s);
                    world.setBlockState(front, slab, Block.NOTIFY_LISTENERS);
                    for (int y = s + 1; y < s + storey; y++) world.setBlockState(front.withY(y), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                    if (d == 3 && k > 0) world.setBlockState(front.up(), Blocks.IRON_BARS.getDefaultState(), Block.NOTIFY_ALL);
                }
        }
        BlockPos ladder = l.left.offset(p, 3).offset(facing, 2);   // a ladder up the landings' side, for the way back down
        for (int y = origin.getY() + 1; y < top; y++) {
            world.setBlockState(ladder.offset(p).withY(y), wall, Block.NOTIFY_LISTENERS);
            world.setBlockState(ladder.withY(y), Blocks.LADDER.getDefaultState().with(net.minecraft.block.LadderBlock.FACING, p.getOpposite()), Block.NOTIFY_LISTENERS);
        }
        place(world, origin, floors, storey, facing, style);
        BlockPos o = origin;
        source.sendFeedback(() -> Text.literal("Лифт: " + floors + " этажей по " + storey + ", шахта " + o.toShortString() + ", двери на " + facing.asString()), false);
        return 1;
    }
}
