package io.github.parrotcages;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/** Угловая часть клетки: помнит пленника и не даёт ему оказаться снаружи. */
public final class ParrotCageBlockEntity extends BlockEntity {
	/** На каком расстоянии от игрока клетка ищет попугая. */
	public static final double CAPTURE_RADIUS = 8;
	/** Сколько тиков пленник может отсутствовать в мире, пока клетка его ждёт (смерть, пересоздание). */
	static final int FORGET_MISSING_TICKS = 30 * 20;
	private static final int CHECK_INTERVAL = 5;

	@Nullable private UUID occupant;
	private int missingTicks;

	public ParrotCageBlockEntity(BlockPos pos, BlockState state) {
		super(ParrotCages.CAGE_BLOCK_ENTITY, pos, state);
	}

	public Optional<UUID> occupant() { return Optional.ofNullable(occupant); }

	private ParrotCageBlock cage() { return (ParrotCageBlock) getCachedState().getBlock(); }

	/** Квадрат, вписанный в круглую клетку до начала купола: здесь пленник точно внутри. */
	public Box interior() {
		CageForm form = cage().form();
		double half = form.radius() * 0.7;
		BlockPos o = getPos();
		return new Box(o.getX() + form.centerX() - half, o.getY() + form.floor(), o.getZ() + form.centerZ() - half,
				o.getX() + form.centerX() + half, o.getY() + form.wallTop(), o.getZ() + form.centerZ() + half);
	}

	/** Центр птицы внутри формы клетки (с небольшим допуском на рывок о прутья). */
	private boolean contains(Entity bird) {
		Vec3d c = bird.getBoundingBox().getCenter();
		return cage().form().inside(c.x - getPos().getX(), c.y - getPos().getY(), c.z - getPos().getZ(), -0.2);
	}

	private Vec3d seat() {
		Box box = interior();
		return new Vec3d(box.getCenter().x, box.minY + 0.01, box.getCenter().z);
	}

	public void interact(ServerWorld world, PlayerEntity player) {
		if (occupant != null) {
			Entity bird = world.getEntity(occupant);
			if (player.isSneaking()) {
				if (bird == null) {
					player.sendMessage(Text.translatable("parrotcages.message.occupant_away"), true);
					return;
				}
				release(world, player);
				player.sendMessage(Text.translatable("parrotcages.message.released", bird.getDisplayName()), true);
			} else {
				Text name = bird == null ? Text.translatable("parrotcages.message.unknown") : bird.getDisplayName();
				player.sendMessage(Text.translatable("parrotcages.message.occupied", name), true);
			}
			return;
		}
		ItemStack bait = player.getMainHandStack().isIn(ItemTags.PARROT_FOOD) ? player.getMainHandStack()
				: player.getOffHandStack().isIn(ItemTags.PARROT_FOOD) ? player.getOffHandStack() : ItemStack.EMPTY;
		if (bait.isEmpty()) {
			player.sendMessage(Text.translatable("parrotcages.message.need_bait"), true);
			return;
		}
		ParrotEntity bird = world.getEntitiesByClass(ParrotEntity.class, player.getBoundingBox().expand(CAPTURE_RADIUS),
						p -> p.isAlive() && !CageLinks.isCaged(p) && !p.hasVehicle() && !p.isLeashed()
								&& (!p.isTamed() || p.isOwner(player)))
				.stream().min(Comparator.comparingDouble(p -> p.squaredDistanceTo(player))).orElse(null);
		if (bird == null) {
			player.sendMessage(Text.translatable("parrotcages.message.no_parrot", (int) CAPTURE_RADIUS), true);
			return;
		}
		bait.decrementUnlessCreative(1, player);
		capture(world, bird);
		Vec3d seat = seat();
		world.spawnParticles(ParticleTypes.HEART, seat.x, seat.y + 0.6, seat.z, 5, 0.25, 0.2, 0.25, 0.02);
		player.sendMessage(Text.translatable("parrotcages.message.caged", bird.getDisplayName()), true);
	}

	void capture(ServerWorld world, ParrotEntity bird) {
		occupant = bird.getUuid();
		missingTicks = 0;
		CageLinks.link(bird, world, getPos());
		// Ручной ванильный попугай иначе запрыгнет хозяину на плечо и исчезнет из клетки.
		if (bird.isTamed()) bird.setSitting(true);
		returnInside(world, bird);
		markDirty();
	}

	/** Выпускает пленника рядом с игроком (или у клетки, если клетку сломали). */
	public void release(ServerWorld world, @Nullable PlayerEntity player) {
		if (occupant == null) return;
		Entity bird = world.getEntity(occupant);
		occupant = null;
		missingTicks = 0;
		markDirty();
		if (bird == null) return;
		CageLinks.unlink(bird);
		if (bird instanceof ParrotEntity parrot && parrot.isTamed()) {
			parrot.setSitting(false);
			parrot.setInSittingPose(false);
		}
		Vec3d to = player != null ? player.getPos() : Vec3d.ofBottomCenter(getPos()).add(0, cage().height(), 0);
		bird.teleport(world, to.x, to.y, to.z, java.util.Set.of(), bird.getYaw(), bird.getPitch());
	}

	/** Пленник исчез из мира навсегда (умер или пересоздан). */
	void forget(UUID uuid) {
		if (uuid.equals(occupant)) {
			occupant = null;
			missingTicks = 0;
			markDirty();
		}
	}

	private void returnInside(ServerWorld world, Entity bird) {
		Vec3d seat = seat();
		if (bird instanceof ParrotEntity parrot) parrot.getNavigation().stop();
		bird.setVelocity(Vec3d.ZERO);
		bird.fallDistance = 0;
		bird.teleport(world, seat.x, seat.y, seat.z, java.util.Set.of(), bird.getYaw(), bird.getPitch());
	}

	public static void tick(World world, BlockPos pos, BlockState state, ParrotCageBlockEntity cage) {
		if (cage.occupant == null || !(world instanceof ServerWorld server) || world.getTime() % CHECK_INTERVAL != 0) return;
		Entity bird = server.getEntity(cage.occupant);
		if (bird == null || !bird.isAlive()) {
			cage.missingTicks += CHECK_INTERVAL;
			if (cage.missingTicks >= FORGET_MISSING_TICKS) cage.forget(cage.occupant);
			return;
		}
		cage.missingTicks = 0;
		if (!CageLinks.isLinkedTo(bird, server, pos)) CageLinks.link(bird, server, pos);
		if (!cage.contains(bird)) cage.returnInside(server, bird);
	}

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.writeNbt(nbt, registries);
		if (occupant != null) nbt.putUuid("Occupant", occupant);
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.readNbt(nbt, registries);
		occupant = nbt.containsUuid("Occupant") ? nbt.getUuid("Occupant") : null;
	}
}
