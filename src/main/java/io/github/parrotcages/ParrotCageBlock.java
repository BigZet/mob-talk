package io.github.parrotcages;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.pathing.NavigationType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * Клетка из {@code width × height × width} частей. Каждая часть знает своё смещение
 * от угла (cx, cy, cz); блок-сущность живёт только в угловой части (0, 0, 0).
 * Стенки — тонкие пластины по внешним граням, внутри клетки ничего не мешает полёту.
 */
public final class ParrotCageBlock extends BlockWithEntity {
	public static final IntProperty CX = IntProperty.of("cx", 0, 2);
	public static final IntProperty CY = IntProperty.of("cy", 0, 2);
	public static final IntProperty CZ = IntProperty.of("cz", 0, 2);

	private final int width, height;
	private final boolean golden;
	private final CageForm form;
	private final MapCodec<ParrotCageBlock> codec;
	private final VoxelShape[] shapes = new VoxelShape[27];

	public ParrotCageBlock(Settings settings, int width, int height, boolean golden) {
		super(settings);
		this.width = width;
		this.height = height;
		this.golden = golden;
		this.form = new CageForm(width, height);
		this.codec = createCodec(s -> new ParrotCageBlock(s, width, height, golden));
		setDefaultState(getStateManager().getDefaultState().with(CX, 0).with(CY, 0).with(CZ, 0));
		for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++)
			shapes[index(x, y, z)] = buildShape(x, y, z);
	}

	public int width() { return width; }
	public int height() { return height; }
	public boolean golden() { return golden; }

	private static int index(int x, int y, int z) { return x * 9 + y * 3 + z; }

	public CageForm form() { return form; }

	/**
	 * Столкновения по сетке 2×2×2 пикселя: твёрдо всё, что вне формы клетки. Так стенки повторяют
	 * круглые прутья и купол, а над куполом и в углах блока птица не протиснется.
	 */
	private VoxelShape buildShape(int x, int y, int z) {
		if (x >= width || y >= height || z >= width) return VoxelShapes.empty();
		VoxelShape shape = VoxelShapes.empty();
		int cells = 8;
		// В маленькой клетке птица держится ближе к середине, чтобы клюв и хвост не торчали сквозь прутья.
		double margin = width == 1 ? 0.08 : 0.03;
		double cell = 1.0 / cells;
		for (int cx = 0; cx < cells; cx++) for (int cz = 0; cz < cells; cz++) {
			int runStart = -1;
			for (int cy = 0; cy <= cells; cy++) {
				boolean solid = cy < cells && !form.inside(x + (cx + 0.5) * cell, y + (cy + 0.5) * cell, z + (cz + 0.5) * cell, margin);
				if (solid && runStart < 0) runStart = cy;
				if (!solid && runStart >= 0) {
					shape = VoxelShapes.union(shape, VoxelShapes.cuboid(cx * cell, runStart * cell, cz * cell, (cx + 1) * cell, cy * cell, (cz + 1) * cell));
					runStart = -1;
				}
			}
		}
		return shape.simplify();
	}

	@Override protected MapCodec<? extends BlockWithEntity> getCodec() { return codec; }

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) { builder.add(CX, CY, CZ); }

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return shapes[index(state.get(CX), state.get(CY), state.get(CZ))];
	}

	/** Всю клетку целиком рисует {@code CageRenderer} из угловой части: купол шире одного блока. */
	@Override protected BlockRenderType getRenderType(BlockState state) { return BlockRenderType.INVISIBLE; }
	@Override protected boolean canPathfindThrough(BlockState state, NavigationType type) { return false; }
	@Override protected boolean isTransparent(BlockState state, BlockView world, BlockPos pos) { return true; }

	public static BlockPos origin(BlockPos pos, BlockState state) {
		return pos.add(-state.get(CX), -state.get(CY), -state.get(CZ));
	}

	public static boolean isOrigin(BlockState state) {
		return state.get(CX) == 0 && state.get(CY) == 0 && state.get(CZ) == 0;
	}

	// --- Установка и разрушение всей клетки ---------------------------------

	/** Кликнутая позиция становится одной из нижних частей; угол подбирается так, чтобы всё влезло. */
	@Nullable @Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		World world = ctx.getWorld();
		BlockPos pos = ctx.getBlockPos();
		if (pos.getY() + height > world.getTopY()) return null;
		int[] order = width == 3 ? new int[] {1, 0, 2} : width == 2 ? new int[] {0, 1} : new int[] {0};
		for (int dx : order) for (int dz : order) {
			BlockPos origin = pos.add(-dx, 0, -dz);
			if (fits(world, origin, pos, ctx)) return getDefaultState().with(CX, dx).with(CY, 0).with(CZ, dz);
		}
		return null;
	}

	private boolean fits(World world, BlockPos origin, BlockPos clicked, ItemPlacementContext ctx) {
		for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < width; z++) {
			BlockPos p = origin.add(x, y, z);
			if (p.equals(clicked)) continue;
			if (!world.getBlockState(p).canReplace(ctx) || !world.getWorldBorder().contains(p)) return false;
		}
		return true;
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		if (world.isClient()) return;
		BlockPos origin = origin(pos, state);
		for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < width; z++) {
			BlockPos p = origin.add(x, y, z);
			if (!p.equals(pos)) world.setBlockState(p, getDefaultState().with(CX, x).with(CY, y).with(CZ, z), Block.NOTIFY_ALL);
		}
	}

	/** Пропала соседняя часть той же клетки — значит её разобрали, и исчезает вся клетка (как кровать). */
	@Override
	protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor,
			WorldAccess world, BlockPos pos, BlockPos neighborPos) {
		BlockPos origin = origin(pos, state);
		int x = neighborPos.getX() - origin.getX(), y = neighborPos.getY() - origin.getY(), z = neighborPos.getZ() - origin.getZ();
		boolean inside = x >= 0 && x < width && y >= 0 && y < height && z >= 0 && z < width;
		if (inside && !(neighbor.isOf(this) && origin(neighborPos, neighbor).equals(origin))) return Blocks.AIR.getDefaultState();
		return state;
	}

	/** Предмет выпадает только из угловой части; в творческом режиме части снимаются без каскада и без выпадения. */
	@Override
	public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
		if (!world.isClient() && player.isCreative()) {
			BlockPos origin = origin(pos, state);
			if (!pos.equals(origin) && world.getBlockEntity(origin) instanceof ParrotCageBlockEntity cage && world instanceof ServerWorld server) {
				cage.release(server, null);
			}
			for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < width; z++) {
				BlockPos p = origin.add(x, y, z);
				if (!p.equals(pos) && world.getBlockState(p).isOf(this))
					world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS);
			}
		}
		return super.onBreak(world, pos, state, player);
	}

	@Override
	protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock()) && isOrigin(state) && world instanceof ServerWorld server
				&& world.getBlockEntity(pos) instanceof ParrotCageBlockEntity cage) {
			cage.release(server, null);
		}
		super.onStateReplaced(state, world, pos, newState, moved);
	}

	// --- Пленник -------------------------------------------------------------

	@Override
	protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient()) return ActionResult.SUCCESS;
		BlockPos origin = origin(pos, state);
		if (!(world.getBlockEntity(origin) instanceof ParrotCageBlockEntity cage)) return ActionResult.PASS;
		cage.interact((ServerWorld) world, player);
		return ActionResult.CONSUME;
	}

	@Nullable @Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return isOrigin(state) ? new ParrotCageBlockEntity(pos, state) : null;
	}

	@Nullable @Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient() || !isOrigin(state) ? null
				: validateTicker(type, ParrotCages.CAGE_BLOCK_ENTITY, ParrotCageBlockEntity::tick);
	}
}
