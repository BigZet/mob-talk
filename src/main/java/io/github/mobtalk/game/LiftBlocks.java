package io.github.mobtalk.game;

import com.mojang.serialization.MapCodec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/** The lift's blocks (see {@link Lifts}): shaft doors, the landing's call button, the winch on the roof, the cabin's floor. */
public final class LiftBlocks {
    private LiftBlocks() { }

    /** A door column's side, seen from inside the shaft looking out to the landing. */
    public enum Side implements StringIdentifiable {
        LEFT, RIGHT;
        @Override public String asString() { return this == LEFT ? "left" : "right"; }
    }

    /** Door leaves open and shut in this many ticks (the cabin's leaves too). */
    public static final int DOOR_TICKS = 16;

    /** A landing door column, two blocks high: the steel portal, a fixed panel and, at the doorway's middle, a leaf that slides
     * behind the panel (drawn by the lower half's block entity). The pair parts in the middle. Opened only by the cabin. */
    public static class DoorBlock extends BlockWithEntity {
        public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
        public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;
        public static final BooleanProperty OPEN = Properties.OPEN;
        public static final EnumProperty<Side> SIDE = EnumProperty.of("side", Side.class);
        private static final Map<Direction, VoxelShape[]> OPEN_SHAPES = new EnumMap<>(Direction.class);
        static {
            // model space, left column: the jamb, panel and wall at x 0..8; the doorway is the full 2 blocks high (a villager is 1.95)
            for (Direction d : Direction.Type.HORIZONTAL) {
                VoxelShape[] s = new VoxelShape[4];
                s[0] = CityContent.shape(new double[][] {{0, 0, 0, 8, 16, 16}}, d);
                s[1] = CityContent.shape(new double[][] {{0, 0, 0, 8, 16, 16}}, d);
                s[2] = CityContent.shape(new double[][] {{8, 0, 0, 16, 16, 16}}, d);
                s[3] = CityContent.shape(new double[][] {{8, 0, 0, 16, 16, 16}}, d);
                OPEN_SHAPES.put(d, s);
            }
        }

        public DoorBlock(AbstractBlock.Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(HALF, DoubleBlockHalf.LOWER)
                    .with(OPEN, false).with(SIDE, Side.LEFT));
        }

        @Override protected MapCodec<? extends BlockWithEntity> getCodec() { return createCodec(DoorBlock::new); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, HALF, OPEN, SIDE); }
        @Override protected BlockRenderType getRenderType(BlockState state) { return BlockRenderType.MODEL; }

        @Override public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
            return state.get(HALF) == DoubleBlockHalf.LOWER ? new DoorEntity(pos, state) : null;
        }

        @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
            return world.isClient && state.get(HALF) == DoubleBlockHalf.LOWER ? validateTicker(type, Lifts.DOOR_ENTITY, (w, p, s, be) -> be.clientTick(s)) : null;
        }

        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            if (!state.get(OPEN)) return VoxelShapes.fullCube();
            int i = (state.get(SIDE) == Side.LEFT ? 0 : 2) + (state.get(HALF) == DoubleBlockHalf.UPPER ? 1 : 0);
            return OPEN_SHAPES.get(state.get(FACING))[i];
        }

        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) { return state.with(FACING, rotation.rotate(state.get(FACING))); }
        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) {
            // a mirrored shaft swaps its columns, so each keeps its leaf at the doorway's middle
            BlockState turned = state.rotate(mirror.getRotation(state.get(FACING)));
            return mirror == BlockMirror.NONE ? state : turned.with(SIDE, state.get(SIDE) == Side.LEFT ? Side.RIGHT : Side.LEFT);
        }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockPos pos = ctx.getBlockPos();
            if (pos.getY() >= ctx.getWorld().getTopY() - 1 || !ctx.getWorld().getBlockState(pos.up()).canReplace(ctx)) return null;
            return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
        }

        @Override public void onPlaced(World world, BlockPos pos, BlockState state, net.minecraft.entity.LivingEntity placer, net.minecraft.item.ItemStack stack) {
            world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
        }

        @Override protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor,
                                                                 net.minecraft.world.WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            DoubleBlockHalf half = state.get(HALF);
            if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP))
                return neighbor.isOf(this) && neighbor.get(HALF) != half ? state.with(OPEN, neighbor.get(OPEN))
                        : net.minecraft.block.Blocks.AIR.getDefaultState();
            return state;
        }

        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            // knocking on a lift's doors: the call, as in every stairwell
            BlockPos lower = state.get(HALF) == DoubleBlockHalf.LOWER ? pos : pos.down();
            return ButtonBlock.call(world, lower, world.getBlockState(lower), player) ? ActionResult.success(world.isClient) : ActionResult.PASS;
        }
    }

    /** The lower half of a door column: how far its leaf has slid (client side, for drawing). */
    public static class DoorEntity extends BlockEntity {
        public float progress, prevProgress;

        public DoorEntity(BlockPos pos, BlockState state) { super(Lifts.DOOR_ENTITY, pos, state); }

        void clientTick(BlockState state) {
            prevProgress = progress;
            float target = state.get(DoorBlock.OPEN) ? 1f : 0f;
            float step = 1f / DOOR_TICKS;
            progress = progress < target ? Math.min(target, progress + step) : Math.max(target, progress - step);
        }
    }

    /** The call button on the landing: a grey box with a black button in a ring that lights red until the cabin comes. */
    public static class ButtonBlock extends Block {
        public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
        public static final BooleanProperty LIT = Properties.LIT;
        private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);
        static {
            for (Direction d : Direction.Type.HORIZONTAL) SHAPES.put(d, CityContent.shape(new double[][] {{5.5, 0.5, 14, 10.5, 6.5, 16}}, d));
        }

        public ButtonBlock(AbstractBlock.Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(LIT, false));
        }

        @Override protected MapCodec<? extends Block> getCodec() { return createCodec(ButtonBlock::new); }
        @Override protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(FACING, LIT); }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return SHAPES.get(state.get(FACING)); }
        @Override protected BlockState rotate(BlockState state, BlockRotation rotation) { return state.with(FACING, rotation.rotate(state.get(FACING))); }
        @Override protected BlockState mirror(BlockState state, BlockMirror mirror) { return state.rotate(mirror.getRotation(state.get(FACING))); }

        @Override public BlockState getPlacementState(ItemPlacementContext ctx) {
            Direction side = ctx.getSide();
            return side.getAxis().isHorizontal() ? getDefaultState().with(FACING, side) : null;
        }

        @Override protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
            return !world.getBlockState(pos.offset(state.get(FACING).getOpposite())).isAir();
        }

        @Override protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            world.playSound(player, pos, Lifts.CLICK, SoundCategory.BLOCKS, 0.8f, 1f);
            BlockPos door = findDoor(world, pos, state.get(FACING));
            if (door == null) return ActionResult.success(world.isClient);
            call(world, door, world.getBlockState(door), player);
            return ActionResult.success(world.isClient);
        }

        /** The door column next to a button: normally the left one, a step back and to the side, a row down. */
        static BlockPos findDoor(BlockView world, BlockPos button, Direction facing) {
            BlockPos guess = button.offset(facing.getOpposite()).offset(facing.rotateYClockwise()).down();
            if (isLowerDoor(world.getBlockState(guess))) return guess;
            for (BlockPos p : BlockPos.iterate(button.add(-2, -2, -2), button.add(2, 0, 2)))
                if (isLowerDoor(world.getBlockState(p))) return p.toImmutable();
            return null;
        }

        static boolean isLowerDoor(BlockState s) { return s.isOf(Lifts.DOOR) && s.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER; }

        /** Calls the cabin to the floor of this lower door column. False when there is no cabin (yet). */
        static boolean call(World world, BlockPos door, BlockState state, PlayerEntity player) {
            if (world.isClient || !isLowerDoor(state)) return !world.isClient ? false : isLowerDoor(state);
            LiftCabin cabin = LiftCabin.find(world, Lifts.Layout.ofDoor(door, state));
            if (cabin == null) {
                if (player != null) player.sendMessage(Text.translatable("mobtalk.lift.busy"), true);
                return false;
            }
            cabin.call(door.getY() - 1);
            return true;
        }

        /** Lights (or puts out) the button of the floor whose lower door column this is. */
        static void light(World world, Lifts.Layout layout, int slabY, boolean lit) {
            BlockPos b = layout.button().withY(slabY + 2);
            BlockState s = world.getBlockState(b);
            if (s.isOf(Lifts.BUTTON) && s.get(LIT) != lit) world.setBlockState(b, s.with(LIT, lit), Block.NOTIFY_ALL);
        }
    }

    /** The winch over the shaft, on the roof slab. Finds the floors by the doors under it and keeps one cabin in the shaft. */
    public static class MachineBlock extends BlockWithEntity {
        public MachineBlock(AbstractBlock.Settings settings) { super(settings); }
        @Override protected MapCodec<? extends BlockWithEntity> getCodec() { return createCodec(MachineBlock::new); }
        @Override protected BlockRenderType getRenderType(BlockState state) { return BlockRenderType.MODEL; }
        @Override public BlockEntity createBlockEntity(BlockPos pos, BlockState state) { return new MachineEntity(pos, state); }

        @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
            return world.isClient ? null : validateTicker(type, Lifts.MACHINE_ENTITY, (w, p, s, be) -> be.serverTick((ServerWorld) w));
        }
    }

    public static class MachineEntity extends BlockEntity {
        private UUID cabin;
        private int ticks;

        public MachineEntity(BlockPos pos, BlockState state) { super(Lifts.MACHINE_ENTITY, pos, state); }

        void serverTick(ServerWorld world) {
            if (++ticks % 40 != 5) return;
            if (cabin != null && world.getEntity(cabin) instanceof LiftCabin c && !c.isRemoved()) return;
            Shaft shaft = scan(world, pos);
            if (shaft == null) return;
            LiftCabin found = LiftCabin.find(world, shaft.layout);
            if (found != null) {
                cabin = found.getUuid();
                markDirty();
                return;
            }
            if (ticks < 80) return;   // entities of a freshly loaded chunk come a little after its blocks
            for (BlockPos c : shaft.layout.cells())
                if (!world.shouldTickEntity(c.withY(pos.getY()))) return;
            LiftCabin made = new LiftCabin(Lifts.CABIN, world);
            made.setup(pos, shaft.layout, shaft.floors);
            world.spawnEntity(made);
            cabin = made.getUuid();
            markDirty();
        }

        @Override protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
            super.writeNbt(nbt, registries);
            if (cabin != null) nbt.putUuid("cabin", cabin);
        }

        @Override protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
            super.readNbt(nbt, registries);
            cabin = nbt.containsUuid("cabin") ? nbt.getUuid("cabin") : null;
        }
    }

    /** A shaft found under a winch: its layout and the slab row of every floor, lowest first. */
    public record Shaft(Lifts.Layout layout, List<Integer> floors) { }

    /** Looks down from a winch for the lift's doors: the first lower door column whose shaft lies under the winch, then every
     * floor with a door in that column. Null when there are fewer than two floors. */
    public static Shaft scan(World world, BlockPos machine) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = machine.getY() - 1; y > Math.max(world.getBottomY(), machine.getY() - 160); y--)
            for (int dx = -3; dx <= 3; dx++)
                for (int dz = -3; dz <= 3; dz++) {
                    m.set(machine.getX() + dx, y, machine.getZ() + dz);
                    BlockState s = world.getBlockState(m);
                    if (!ButtonBlock.isLowerDoor(s)) continue;
                    Lifts.Layout l = Lifts.Layout.ofDoor(m.toImmutable(), s);
                    boolean under = l.cells().stream().anyMatch(c -> c.getX() == machine.getX() && c.getZ() == machine.getZ());
                    if (!under) continue;
                    List<Integer> floors = new ArrayList<>();
                    for (int fy = world.getBottomY(); fy < machine.getY(); fy++) {
                        BlockState d = world.getBlockState(l.left().withY(fy));
                        if (ButtonBlock.isLowerDoor(d) && d.get(DoorBlock.FACING) == l.facing()) floors.add(fy - 1);
                    }
                    return floors.size() >= 2 ? new Shaft(l, floors) : null;
                }
        return null;
    }

    /** The cabin's floor while it stands at a landing: solid to stand on, drawn by nobody (the cabin draws its own floor). */
    public static class FloorBlock extends Block {
        public FloorBlock(AbstractBlock.Settings settings) { super(settings); }
        @Override protected MapCodec<? extends Block> getCodec() { return createCodec(FloorBlock::new); }
        @Override protected BlockRenderType getRenderType(BlockState state) { return BlockRenderType.INVISIBLE; }
        @Override protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return VoxelShapes.empty(); }
        @Override protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return VoxelShapes.fullCube(); }
        @Override protected VoxelShape getCameraCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) { return VoxelShapes.empty(); }
        @Override protected float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) { return 1f; }
        @Override protected boolean isTransparent(BlockState state, BlockView world, BlockPos pos) { return true; }
    }

    static Box cellsBox(Lifts.Layout l, double y0, double y1) {
        BlockPos o = l.origin();
        return new Box(o.getX(), y0, o.getZ(), o.getX() + 2, y1, o.getZ() + 2);
    }
}
