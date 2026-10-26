package io.github.mobtalk.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSetType;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import static net.minecraft.server.command.CommandManager.literal;

/** Soviet yard fences (tools/city2/fences.py draws them): chain-link mesh on pipe posts, welded sections with a diamond lattice and
 * the concrete PO-2 «ромбик» panel, plus wickets for the metal ones. A fence block is one metre, one block high; stacked blocks make
 * a taller fence. Each block joins its neighbours like a vanilla fence (it is in #minecraft:fences), knows whether it is the bottom,
 * middle or top of a stack ({@link #PART}) and whether it shows a post ({@link #POST}: ends, corners, beside a gate, where the fence
 * steps, and every {@link FenceLineBlock#spacing} blocks along a straight run by the world coordinate).
 *
 * <p>For the city generator: {@link #line}, {@link #gate} (or tools/city2/fences.py line/gate/finish for NBT templates). Ids:
 * {@link #FENCES}, {@link #GATES}. {@code /city fences} lays out every kind for a look. */
public final class Fences {
    private Fences() { }

    public enum Part implements StringIdentifiable {
        SINGLE, BOTTOM, MIDDLE, TOP;
        @Override public String asString() { return name().toLowerCase(); }
    }

    public static final EnumProperty<Part> PART = EnumProperty.of("part", Part.class);
    public static final BooleanProperty POST = BooleanProperty.of("post");

    /** Fence ids: chain-link «рабица» (galvanized, green), welded diamond lattice (green, blue, yellow, red), concrete PO-2. */
    public static final List<String> FENCES = List.of("chainlink_fence_galv", "chainlink_fence_green", "lattice_fence_green",
            "lattice_fence_blue", "lattice_fence_yellow", "lattice_fence_red", "concrete_fence");
    /** Gate ids: a two-block leaf hung in the fence line; two with opposite hinges are a double gate that opens together. */
    public static final List<String> GATES = List.of("chainlink_gate_galv", "chainlink_gate_green", "lattice_gate_green",
            "lattice_gate_blue", "lattice_gate_yellow", "lattice_gate_red");

    public static final Map<String, Block> BLOCKS = new LinkedHashMap<>();
    public static final List<Block> CUTOUT = new ArrayList<>();

    /** Gates open by hand, with the iron gate's sounds. */
    private static final BlockSetType GATE_SET = new BlockSetType("mobtalk_fence_gate", true, true, false, BlockSetType.ActivationRule.EVERYTHING,
            BlockSoundGroup.METAL, SoundEvents.BLOCK_IRON_DOOR_CLOSE, SoundEvents.BLOCK_IRON_DOOR_OPEN, SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE,
            SoundEvents.BLOCK_IRON_TRAPDOOR_OPEN, SoundEvents.BLOCK_METAL_PRESSURE_PLATE_CLICK_OFF, SoundEvents.BLOCK_METAL_PRESSURE_PLATE_CLICK_ON,
            SoundEvents.BLOCK_STONE_BUTTON_CLICK_OFF, SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON);

    private static boolean registered;

    public static void register() {
        if (registered) return;
        registered = true;
        List<Item> items = new ArrayList<>();
        for (String id : FENCES) {
            boolean concrete = id.startsWith("concrete");
            AbstractBlock.Settings settings = AbstractBlock.Settings.create().mapColor(color(id)).nonOpaque()
                    .strength(concrete ? 3f : 2f, 6f).requiresTool().sounds(concrete ? BlockSoundGroup.STONE : BlockSoundGroup.METAL);
            Block block = register(id, new FenceLineBlock(settings, concrete ? 4 : 3), items);
            if (!concrete) CUTOUT.add(block);
        }
        for (String id : GATES) {
            AbstractBlock.Settings settings = AbstractBlock.Settings.create().mapColor(color(id)).nonOpaque().strength(2f, 6f)
                    .requiresTool().sounds(BlockSoundGroup.METAL);
            CUTOUT.add(register(id, new GateBlock(GATE_SET, settings), items));
        }
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FUNCTIONAL).register(entries -> items.forEach(entries::add));
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(literal("city")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("fences").executes(c -> showcase(c.getSource())))));
    }

    private static Block register(String id, Block block, List<Item> items) {
        Identifier key = Identifier.of("mobtalk", id);
        BLOCKS.put(id, Registry.register(Registries.BLOCK, key, block));
        items.add(Registry.register(Registries.ITEM, key, new BlockItem(block, new Item.Settings())));
        return block;
    }

    private static MapColor color(String id) {
        if (id.endsWith("green")) return MapColor.GREEN;
        if (id.endsWith("blue")) return MapColor.LIGHT_BLUE;
        if (id.endsWith("yellow")) return MapColor.YELLOW;
        if (id.endsWith("red")) return MapColor.RED;
        if (id.startsWith("concrete")) return MapColor.STONE_GRAY;
        return MapColor.IRON_GRAY;
    }

    // ---------------------------------------------------------------- API for the city generator

    /** A straight (axis-aligned) run of fence {@code fenceId} from {@code from} to {@code to} inclusive, {@code height} blocks high,
     * standing on from's row (to's y is ignored). Cells holding a gate are left alone; joins, parts and posts are worked out. */
    public static void line(World world, BlockPos from, BlockPos to, String fenceId, int height) {
        Block block = BLOCKS.get(fenceId);
        if (!(block instanceof FenceLineBlock)) throw new IllegalArgumentException("not a fence: " + fenceId);
        if (from.getX() != to.getX() && from.getZ() != to.getZ()) throw new IllegalArgumentException("fence runs are straight");
        Set<BlockPos> placed = new LinkedHashSet<>();
        for (int x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()); x++)
            for (int z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()); z++) {
                BlockPos foot = new BlockPos(x, from.getY(), z);
                if (world.getBlockState(foot).getBlock() instanceof GateBlock) continue;
                for (int h = 0; h < height; h++) {
                    world.setBlockState(foot.up(h), block.getDefaultState(), Block.NOTIFY_LISTENERS);
                    placed.add(foot.up(h));
                }
            }
        refresh(world, placed);
    }

    /** A wicket {@code gateId} (with {@code twoLeaves} a double gate, its second leaf one step to the right seen along {@code facing})
     * whose lower half stands at {@code pos}. facing: the side the leaves swing out to; the fence line runs across it. */
    public static void gate(World world, BlockPos pos, Direction facing, String gateId, boolean twoLeaves) {
        Block block = BLOCKS.get(gateId);
        if (!(block instanceof GateBlock)) throw new IllegalArgumentException("not a gate: " + gateId);
        Set<BlockPos> placed = new LinkedHashSet<>();
        List<BlockPos> leaves = twoLeaves ? List.of(pos, pos.offset(facing.rotateYClockwise())) : List.of(pos);
        for (int i = 0; i < leaves.size(); i++) {
            BlockState leaf = block.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.OPEN, false)
                    .with(DoorBlock.HINGE, i == 0 ? DoorHinge.LEFT : DoorHinge.RIGHT);
            // no shape updates until both halves stand: a half without its other half turns to air
            world.setBlockState(leaves.get(i), leaf.with(DoorBlock.HALF, DoubleBlockHalf.LOWER), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            world.setBlockState(leaves.get(i).up(), leaf.with(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            placed.add(leaves.get(i));
            placed.add(leaves.get(i).up());
        }
        refresh(world, placed);
    }

    /** Re-reads joins, parts and posts of the fences in and around {@code cells}. */
    public static void refresh(World world, Set<BlockPos> cells) {
        Set<BlockPos> all = new LinkedHashSet<>();
        for (BlockPos p : cells) {
            all.add(p);
            for (Direction d : Direction.values()) all.add(p.offset(d));
            for (Direction d : Direction.Type.HORIZONTAL) { all.add(p.offset(d).up()); all.add(p.offset(d).down()); }
        }
        for (int pass = 0; pass < 2; pass++)
            for (BlockPos p : all) {
                BlockState state = world.getBlockState(p);
                if (!(state.getBlock() instanceof FenceLineBlock)) continue;
                BlockState now = Block.postProcessState(state, world, p);
                if (now != state) world.setBlockState(p, now, Block.NOTIFY_LISTENERS);
            }
    }

    private static int showcase(ServerCommandSource source) {
        World world = source.getWorld();
        BlockPos o = BlockPos.ofFloored(source.getPosition());
        int row = 0;
        for (String id : FENCES) {
            BlockPos a = o.add(0, 0, 2 + row * 6);
            for (int x = -1; x <= 10; x++)
                for (int z = -1; z <= 4; z++) {
                    world.setBlockState(a.add(x, -1, z), Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_LISTENERS);
                    for (int y = 0; y < 4; y++) world.setBlockState(a.add(x, y, z), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                }
            line(world, a, a.add(9, 0, 0), id, 2);
            line(world, a.add(9, 0, 1), a.add(9, 0, 3), id, 2);
            String gate = GATES.stream().filter(g -> g.replace("_gate_", "_fence_").equals(id)).findFirst().orElse(null);
            if (gate != null) gate(world, a.add(3, 0, 0), Direction.SOUTH, gate, id.startsWith("lattice"));
            row++;
        }
        BlockPos low = o.add(0, 0, 2 + row * 6);
        for (int x = -1; x <= 10; x++)
            for (int z = -1; z <= 4; z++) {
                world.setBlockState(low.add(x, -1, z), Blocks.GRASS_BLOCK.getDefaultState(), Block.NOTIFY_LISTENERS);
                for (int y = 0; y < 4; y++) world.setBlockState(low.add(x, y, z), Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
            }
        line(world, low, low.add(4, 0, 0), "lattice_fence_green", 1);
        line(world, low.add(6, 0, 0), low.add(9, 0, 0), "chainlink_fence_galv", 1);
        line(world, low.add(9, 0, 1), low.add(9, 0, 3), "chainlink_fence_galv", 1);
        source.sendFeedback(() -> Text.literal("Заборы: " + FENCES.size() + " видов, рядами на юг от " + o.toShortString()), false);
        return 1;
    }

    // ---------------------------------------------------------------- blocks

    /** One metre of fence: joins like a vanilla fence, plus the part of a stack and a post. */
    public static class FenceLineBlock extends FenceBlock {
        /** Posts along a straight run: every so many blocks (a PO-2 panel is 4 m, a mesh or welded section about 3 m). */
        final int spacing;

        FenceLineBlock(AbstractBlock.Settings settings, int spacing) {
            super(settings);
            this.spacing = spacing;
            setDefaultState(getDefaultState().with(PART, Part.SINGLE).with(POST, true));
        }

        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            super.appendProperties(builder);
            builder.add(PART, POST);
        }

        @Override public boolean canConnect(BlockState state, boolean neighborIsFullSquare, Direction dir) {
            if (state.getBlock() instanceof GateBlock) return state.get(DoorBlock.FACING).getAxis() != dir.getAxis();
            return super.canConnect(state, neighborIsFullSquare, dir);
        }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockState state = super.getPlacementState(ctx);
            return state == null ? null : refresh(state, ctx.getWorld(), ctx.getBlockPos());
        }

        @Override protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState, WorldAccess world,
                                                                 BlockPos pos, BlockPos neighborPos) {
            BlockState next = super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
            return next.isOf(this) ? refresh(next, world, pos) : next;
        }

        private static boolean line(BlockState state) { return state.getBlock() instanceof FenceLineBlock; }

        /** Part and post from the neighbours (tools/city2/fences.py finish() does the same for templates). */
        BlockState refresh(BlockState state, WorldAccess world, BlockPos pos) {
            boolean up = line(world.getBlockState(pos.up())), down = line(world.getBlockState(pos.down()));
            Part part = up ? (down ? Part.MIDDLE : Part.BOTTOM) : (down ? Part.TOP : Part.SINGLE);
            boolean n = state.get(NORTH), s = state.get(SOUTH), e = state.get(EAST), w = state.get(WEST);
            boolean alongX = e && w && !n && !s, alongZ = n && s && !e && !w;
            boolean post = !(alongX || alongZ);
            if (!post)
                for (Direction d : alongX ? List.of(Direction.EAST, Direction.WEST) : List.of(Direction.NORTH, Direction.SOUTH)) {
                    BlockPos o = pos.offset(d);
                    if (world.getBlockState(o).getBlock() instanceof GateBlock
                            || line(world.getBlockState(o.up())) != up || line(world.getBlockState(o.down())) != down) post = true;
                }
            if (!post) post = Math.floorMod(alongX ? pos.getX() : pos.getZ(), spacing) == 0;
            return state.with(PART, part).with(POST, post);
        }
    }

    /** A gate leaf hung in the fence line (not at the cell's edge like a door): closed it stands across the middle of the cell, open
     * it swings out to the facing side about its hinge. Opens with its pair like the city's double doors. */
    public static class GateBlock extends CityBuild.PairedDoorBlock {
        private static final double[][] CLOSED = {{0, 0, 7, 16, 16, 9}};
        private static final double[][] OPEN_LEFT = {{0, 0, 0, 2, 16, 8}};
        private static final double[][] OPEN_RIGHT = {{14, 0, 0, 16, 16, 8}};

        GateBlock(BlockSetType type, AbstractBlock.Settings settings) { super(type, settings); }

        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            double[][] boxes = !state.get(OPEN) ? CLOSED : state.get(HINGE) == DoorHinge.LEFT ? OPEN_LEFT : OPEN_RIGHT;
            return CityContent.shape(boxes, state.get(FACING));
        }
    }
}
