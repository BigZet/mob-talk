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
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
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

	// Тюрьма для попугая-зрителя: время мира, когда он выйдет (0 — не узник, Jail.INDEFINITE — бессрочно), кто посадил.
	private long releaseAt, jailedAt;
	@Nullable private UUID jailer;
	private String jailerName = "", prisonerName = "";
	/** Сколько тиков подряд бессрочный узник сидит без стримера в игре. */
	private int offlineTicks;

	public ParrotCageBlockEntity(BlockPos pos, BlockState state) {
		super(ParrotCages.CAGE_BLOCK_ENTITY, pos, state);
	}

	public Optional<UUID> occupant() { return Optional.ofNullable(occupant); }
	public boolean jailed() { return occupant != null && releaseAt != 0; }
	public long releaseAt() { return releaseAt; }

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
				if (jailed() && !mayFree(player)) {
					refuse(world, player);
					return;
				}
				release(world, player, "hands");
				player.sendMessage(Text.translatable("parrotcages.message.released", bird.getDisplayName()), true);
			} else {
				Text name = bird == null ? Text.translatable("parrotcages.message.unknown") : bird.getDisplayName();
				player.sendMessage(jailed() ? Text.translatable("parrotcages.message.jailed", name, Jail.clock(releaseAt, world.getTime()))
						: Text.translatable("parrotcages.message.occupied", name), true);
			}
			return;
		}
		ItemStack bait = player.getMainHandStack().isIn(ItemTags.PARROT_FOOD) ? player.getMainHandStack()
				: player.getOffHandStack().isIn(ItemTags.PARROT_FOOD) ? player.getOffHandStack() : ItemStack.EMPTY;
		if (bait.isEmpty()) {
			player.sendMessage(Text.translatable("parrotcages.message.need_bait"), true);
			return;
		}
		// Попугаев-зрителей сажают только стример и администратор; ручными у них такие птицы быть не обязаны.
		boolean warden = player instanceof ServerPlayerEntity sp && Roles.warden(sp);
		boolean[] locked = new boolean[1];
		ParrotEntity bird = world.getEntitiesByClass(ParrotEntity.class, player.getBoundingBox().expand(CAPTURE_RADIUS),
						p -> {
							if (!p.isAlive() || CageLinks.isCaged(p) || p.hasVehicle() || p.isLeashed()) return false;
							if (Roles.isViewerParrot(p)) {
								if (!warden) locked[0] = true;
								return warden;
							}
							return !p.isTamed() || p.isOwner(player);
						})
				.stream().min(Comparator.comparingDouble(p -> p.squaredDistanceTo(player))).orElse(null);
		if (bird == null) {
			if (locked[0]) refuse(world, player);
			else player.sendMessage(Text.translatable("parrotcages.message.no_parrot", (int) CAPTURE_RADIUS), true);
			return;
		}
		bait.decrementUnlessCreative(1, player);
		capture(world, bird);
		if (Roles.isViewerParrot(bird)) {
			jail(world, bird, JailSettings.get().defaultMinutes, player.getUuid(), player.getGameProfile().getName(),
					player instanceof ServerPlayerEntity sp ? sp : null);
			return;
		}
		Vec3d seat = seat();
		world.spawnParticles(ParticleTypes.HEART, seat.x, seat.y + 0.6, seat.z, 5, 0.25, 0.2, 0.25, 0.02);
		player.sendMessage(Text.translatable("parrotcages.message.caged", bird.getDisplayName()), true);
	}

	/** Чужого узника выпускают только стример, администратор и тот, кто посадил. */
	boolean mayFree(PlayerEntity player) {
		return player.getUuid().equals(jailer) || player instanceof ServerPlayerEntity sp && Roles.warden(sp);
	}

	void refuse(ServerWorld world, PlayerEntity player) {
		player.sendMessage(Text.translatable("parrotcages.message.streamer_lock"), true);
		JailEffects.refused(world, seat());
	}

	// --- Тюрьма ----------------------------------------------------------------

	/** Сажает уже пойманную птицу-зрителя на срок; 0 минут — бессрочно. */
	void jail(ServerWorld world, Entity bird, int minutes, @Nullable UUID by, String byName, @Nullable ServerPlayerEntity tell) {
		long now = world.getTime();
		jailedAt = now;
		releaseAt = minutes <= 0 ? Jail.INDEFINITE : now + (long) minutes * Jail.TICKS_PER_MINUTE;
		jailer = by;
		jailerName = byName == null ? "" : byName;
		prisonerName = bird.getDisplayName().getString();
		offlineTicks = 0;
		bird.addCommandTag(Jail.JAILED_TAG);
		markDirty();
		remember(world);
		JailEffects.jailed(world, seat());
		Jail.tellJailer(tell, bird.getDisplayName(), bird.getUuid(), releaseAt, now);
		Jail.emit("jailed", world, getPos(), bird.getUuid(), prisonerName, jailer, jailerName, releaseAt, now, "");
	}

	/** +N минут к сроку; 0 — сделать бессрочным. Бессрочный узник с +N получает срок от текущего момента. */
	void extend(ServerWorld world, int minutes) {
		if (!jailed()) return;
		long now = world.getTime();
		if (minutes <= 0) releaseAt = Jail.INDEFINITE;
		else releaseAt = (releaseAt == Jail.INDEFINITE ? now : Math.max(now, releaseAt)) + (long) minutes * Jail.TICKS_PER_MINUTE;
		offlineTicks = 0;
		markDirty();
		remember(world);
		Jail.emit("extended", world, getPos(), occupant, prisonerName, jailer, jailerName, releaseAt, now, "");
	}

	/** Выпускает узника досрочно или по сроку, с эффектом по причине. */
	boolean free(ServerWorld world, String reason, @Nullable PlayerEntity by) {
		if (!jailed()) return false;
		Entity bird = world.getEntity(occupant);
		if (bird == null) return false;
		Vec3d at = seat();
		release(world, by, reason);
		if ("amnesty".equals(reason)) JailEffects.amnesty(world, at); else JailEffects.freed(world, at);
		return true;
	}

	private void remember(ServerWorld world) {
		if (occupant != null) Jail.remember(new Jail.Prisoner(occupant, prisonerName, GlobalPos.create(world.getRegistryKey(), getPos().toImmutable()),
				releaseAt, jailedAt, jailer, jailerName));
	}

	private void clearJail(ServerWorld world, @Nullable Entity bird, String reason) {
		if (releaseAt == 0) return;
		UUID prisoner = occupant;
		long until = releaseAt;
		releaseAt = 0;
		if (bird != null) bird.removeCommandTag(Jail.JAILED_TAG);
		if (prisoner != null) {
			Jail.forget(prisoner);
			Jail.emit("released", world, getPos(), prisoner, prisonerName, jailer, jailerName, until, world.getTime(), reason);
		}
		jailedAt = 0; jailer = null; jailerName = ""; prisonerName = ""; offlineTicks = 0;
		markDirty();
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
	public void release(ServerWorld world, @Nullable PlayerEntity player) { release(world, player, player == null ? "broken" : "hands"); }

	void release(ServerWorld world, @Nullable PlayerEntity player, String reason) {
		if (occupant == null) return;
		Entity bird = world.getEntity(occupant);
		clearJail(world, bird, reason);
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
			if (getWorld() instanceof ServerWorld world) clearJail(world, null, "gone");
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
		if (cage.releaseAt != 0) cage.tickJail(server, bird);
	}

	/** Срок тикает по времени мира, поэтому кончается и при загрузке чанка после долгого отсутствия. */
	private void tickJail(ServerWorld world, Entity bird) {
		if (!bird.getCommandTags().contains(Jail.JAILED_TAG)) bird.addCommandTag(Jail.JAILED_TAG);
		remember(world);
		if (releaseAt == Jail.INDEFINITE) {
			offlineTicks = Roles.streamerOnline(world.getServer()) ? 0 : offlineTicks + CHECK_INTERVAL;
			if (offlineTicks >= JailSettings.get().offlineReleaseMinutes * Jail.TICKS_PER_MINUTE) free(world, "term", null);
		} else if (world.getTime() >= releaseAt) {
			free(world, "term", null);
		}
	}

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.writeNbt(nbt, registries);
		if (occupant != null) nbt.putUuid("Occupant", occupant);
		if (releaseAt != 0) {
			NbtCompound jail = new NbtCompound();
			jail.putLong("ReleaseAt", releaseAt);
			jail.putLong("JailedAt", jailedAt);
			if (jailer != null) jail.putUuid("Jailer", jailer);
			jail.putString("JailerName", jailerName);
			jail.putString("Name", prisonerName);
			jail.putInt("OfflineTicks", offlineTicks);
			nbt.put("Jail", jail);
		}
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		super.readNbt(nbt, registries);
		occupant = nbt.containsUuid("Occupant") ? nbt.getUuid("Occupant") : null;
		NbtCompound jail = nbt.getCompound("Jail");
		releaseAt = occupant == null ? 0 : jail.getLong("ReleaseAt");
		jailedAt = jail.getLong("JailedAt");
		jailer = jail.containsUuid("Jailer") ? jail.getUuid("Jailer") : null;
		jailerName = jail.getString("JailerName");
		prisonerName = jail.getString("Name");
		offlineTicks = jail.getInt("OfflineTicks");
	}
}
