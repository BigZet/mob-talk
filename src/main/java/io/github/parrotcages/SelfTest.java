package io.github.parrotcages;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Прогон в настоящей игре только для разработчика: {@code -Dparrotcages.selftest=<папка мира>}.
 * Загружает мир, строит площадку на y=200, ставит клетки трёх размеров настоящей установкой
 * предмета, сажает в них попугаев Viewer Parrots и ванильного попугая, проверяет команды
 * перемещения, освобождение и разрушение клетки, делает скриншоты и закрывает игру.
 * Каждый шаг пишется в лог как {@code [selftest]}.
 */
final class SelfTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("parrotcages-selftest");
	private static final int SY = 200;

	private record Step(String name, int timeoutSeconds, Runnable action, BooleanSupplier done) { }

	private final MinecraftClient client;
	private final String world;
	private final List<Step> steps = new ArrayList<>();
	private final List<String> output = new CopyOnWriteArrayList<>();
	private int index = -1, ticks, deadline;
	private boolean started, failed;
	private int sx, sz;
	private BlockPos small, medium, large, vanillaCage;
	private UUID vanilla;

	private SelfTest(MinecraftClient client, String world) { this.client = client; this.world = world; }

	static void install() {
		String world = System.getProperty("parrotcages.selftest");
		if (world == null || world.isBlank()) return;
		SelfTest test = new SelfTest(MinecraftClient.getInstance(), world);
		ClientTickEvents.END_CLIENT_TICK.register(ignored -> test.tick());
		LOGGER.info("[selftest] armed for world {}", world);
	}

	private void log(String text) { LOGGER.info("[selftest] {}", text); }
	private void check(boolean ok, String what) {
		if (ok) log("OK: " + what);
		else { failed = true; LOGGER.error("[selftest] FAIL: {}", what); }
	}

	private void tick() {
		ticks++;
		if (!started) {
			if (client.currentScreen instanceof TitleScreen && ticks > 40) {
				started = true;
				log("loading world " + world);
				client.createIntegratedServerLoader().start(world, () -> check(false, "world load cancelled"));
				plan();
				index = 0;
				deadline = ticks + steps.get(0).timeoutSeconds * 20;
			}
			return;
		}
		if (index < 0 || index >= steps.size()) return;
		Step step = steps.get(index);
		if (step.done.getAsBoolean()) {
			log("… " + step.name);
		} else if (ticks < deadline) {
			return;
		} else {
			check(false, "timeout: " + step.name);
		}
		index++;
		if (index >= steps.size()) return;
		Step next = steps.get(index);
		deadline = ticks + next.timeoutSeconds * 20;
		try { next.action.run(); } catch (RuntimeException e) { check(false, next.name + ": " + e); }
	}

	// --- Помощники -------------------------------------------------------------

	private void step(String name, int timeout, Runnable action, BooleanSupplier done) { steps.add(new Step(name, timeout, action, done)); }
	/** Действие выполняется на потоке сервера: мир сервера нельзя читать с потока отрисовки. */
	private void pause(String name, int seconds, Runnable action) {
		pauseClient(name, seconds, () -> server().submitAndJoin(action));
	}
	private void pauseClient(String name, int seconds, Runnable action) {
		int[] until = new int[1];
		step(name, seconds + 5, () -> { until[0] = ticks + seconds * 20; action.run(); }, () -> ticks >= until[0]);
	}

	private MinecraftServer server() { return client.getServer(); }
	private ServerPlayerEntity player() { return server().getPlayerManager().getPlayer(client.player.getUuid()); }
	private ServerWorld serverWorld() { return (ServerWorld) player().getWorld(); }
	private void onServer(Runnable task) { server().execute(task); }

	private void command(String line) {
		CommandOutput sink = new CommandOutput() {
			@Override public void sendMessage(Text message) { output.add(message.getString()); log("  > " + message.getString()); }
			@Override public boolean shouldReceiveFeedback() { return true; }
			@Override public boolean shouldTrackOutput() { return true; }
			@Override public boolean shouldBroadcastConsoleToOps() { return false; }
		};
		onServer(() -> server().getCommandManager().executeWithPrefix(
				player().getCommandSource().withOutput(sink).withLevel(4), line));
	}

	private boolean saw(String... fragments) {
		for (String line : output) for (String fragment : fragments) if (line.toLowerCase().contains(fragment)) return true;
		return false;
	}

	private Entity viewerParrot(String viewerId) {
		if (server() == null || client.player == null) return null;
		Entity found = null;
		for (Entity entity : serverWorld().iterateEntities()) {
			if (!(entity instanceof ParrotEntity) || entity.getType() == EntityType.PARROT) continue;
			NbtCompound nbt = entity.writeNbt(new NbtCompound());
			if (viewerId.equals(nbt.getCompound("viewerparrots").getString("viewerId"))) found = entity;
		}
		return found;
	}

	private ParrotCageBlockEntity cage(BlockPos origin) {
		return serverWorld().getBlockEntity(origin) instanceof ParrotCageBlockEntity cage ? cage : null;
	}

	private boolean holds(BlockPos origin, Entity bird) {
		ParrotCageBlockEntity cage = cage(origin);
		if (bird != null && cage != null) log("  bird at " + bird.getBlockPos().toShortString() + ", cage interior " + cage.interior()
				+ ", occupant " + cage.occupant().map(UUID::toString).orElse("-") + ", tagged " + CageLinks.isCaged(bird));
		return bird != null && cage != null && cage.occupant().filter(bird.getUuid()::equals).isPresent()
				&& CageLinks.isCaged(bird) && cage.interior().expand(0.3).contains(bird.getBoundingBox().getCenter());
	}

	private void use(BlockPos target, Direction face, ItemStack inHand) {
		ServerPlayerEntity p = player();
		p.setStackInHand(Hand.MAIN_HAND, inHand);
		p.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
		BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(target).add(Vec3d.of(face.getVector()).multiply(0.5)), face, target, false);
		p.interactionManager.interactBlock(p, serverWorld(), p.getMainHandStack(), Hand.MAIN_HAND, hit);
	}

	private void placeCage(Block cage, BlockPos at) {
		onServer(() -> use(at.down(), Direction.UP, new ItemStack(cage.asItem())));
	}

	/** Приманка: птица прямо у ног игрока, у него семена, ПКМ по клетке. */
	private void lure(Entity bird, BlockPos cage) {
		onServer(() -> {
			ServerPlayerEntity p = player();
			bird.teleport(serverWorld(), p.getX(), p.getY(), p.getZ(), java.util.Set.of(), 0, 0);
			use(cage, Direction.NORTH, new ItemStack(Items.WHEAT_SEEDS, 4));
		});
	}

	private void releaseByHand(BlockPos cage) {
		onServer(() -> {
			player().setSneaking(true);
			use(cage, Direction.NORTH, ItemStack.EMPTY);
			player().setSneaking(false);
		});
	}

	private void screenshot(String file) {
		ScreenshotRecorder.saveScreenshot(client.runDirectory, file, client.getFramebuffer(), text -> log("screenshot: " + text.getString()));
	}

	private void look(double x, double y, double z, float yaw, float pitch) {
		command("tp @s " + x + " " + y + " " + z + " " + yaw + " " + pitch);
	}

	// --- Сценарий ---------------------------------------------------------------

	private void plan() {
		step("world joined", 120, () -> { }, () -> client.player != null && client.world != null && server() != null && client.player.age > 60);
		pause("scene built", 4, () -> {
			sx = client.player.getBlockPos().getX();
			sz = client.player.getBlockPos().getZ();
			String name = client.player.getName().getString();
			small = new BlockPos(sx - 6, SY, sz + 4);
			medium = new BlockPos(sx - 3, SY, sz + 4);
			large = new BlockPos(sx + 1, SY, sz + 4);
			vanillaCage = new BlockPos(sx + 6, SY, sz + 4);
			command("gamemode survival " + name);
			command("time set noon");
			command("gamerule doDaylightCycle false");
			command("weather clear");
			command("fill " + (sx - 30) + " " + (SY - 1) + " " + (sz - 12) + " " + (sx + 30) + " " + (SY - 1) + " " + (sz + 12) + " minecraft:smooth_stone");
			command("fill " + (sx - 30) + " " + SY + " " + (sz - 12) + " " + (sx + 30) + " " + (SY + 6) + " " + (sz + 12) + " minecraft:air");
			command("tp " + name + " " + sx + " " + SY + " " + (sz - 2));
			command("effect give " + name + " minecraft:resistance 900 255 true");
			command("effect give " + name + " minecraft:saturation 900 255 true");
			command("vp setstreamer " + name);
			command("vp create cage_small Малыш");
			command("vp create cage_medium Середняк");
			command("vp create cage_large Великан");
		});
		step("three viewer parrots exist", 30, () -> { },
				() -> server().submit(() -> viewerParrot("cage_small") != null && viewerParrot("cage_medium") != null
						&& viewerParrot("cage_large") != null).join());
		pause("cages placed", 2, () -> {
			placeCage(ParrotCages.SMALL_CAGE, small);
			placeCage(ParrotCages.MEDIUM_CAGE, medium);
			placeCage(ParrotCages.LARGE_CAGE, large.add(1, 0, 1)); // большая клетка встаёт вокруг клика: он в середине нижнего слоя
			placeCage(ParrotCages.SMALL_CAGE, vanillaCage);
		});
		pause("cage structure checked", 1, () -> {
			check(serverWorld().getBlockState(small.up()).isOf(ParrotCages.SMALL_CAGE) && cage(small) != null, "small cage is 1x2x1 with its corner block entity");
			check(serverWorld().getBlockState(medium.add(1, 1, 1)).isOf(ParrotCages.MEDIUM_CAGE) && cage(medium) != null, "medium cage is 2x2x2");
			check(serverWorld().getBlockState(large.add(2, 2, 2)).isOf(ParrotCages.LARGE_CAGE) && cage(large) != null, "large cage is 3x3x3");
		});

		// Без приманки клетка никого не берёт.
		pause("no bait, no capture", 1, () -> onServer(() -> use(small, Direction.NORTH, ItemStack.EMPTY)));
		pause("checked empty", 1, () -> check(cage(small).occupant().isEmpty(), "empty hand does not lure a parrot"));

		// Маленькая клетка: !come отклоняется, птица остаётся внутри.
		pause("small: lure", 2, () -> lure(viewerParrot("cage_small"), small));
		pause("small: come while caged", 6, () -> {
			check(holds(small, viewerParrot("cage_small")), "viewer parrot sits in the small cage");
			check(player().getMainHandStack().getCount() == 3, "one seed spent as bait");
			output.clear();
			command("vp come cage_small");
			command("tp @s " + (sx + 12) + " " + SY + " " + (sz - 6));
		});
		pause("small: checked", 1, () -> {
			check(saw("клетке", "in a cage"), "!come is rejected with the caged message");
			check(holds(small, viewerParrot("cage_small")), "small cage still holds the parrot after !come");
		});

		// Средняя клетка: птица уже следует за стримером, клетка её держит, после выпуска следование продолжается.
		pause("medium: follow, then lure", 2, () -> {
			command("tp @s " + sx + " " + SY + " " + (sz - 2));
			command("vp follow cage_medium");
		});
		pause("medium: lured", 2, () -> lure(viewerParrot("cage_medium"), medium));
		pause("medium: streamer walks away", 8, () -> {
			check(holds(medium, viewerParrot("cage_medium")), "following viewer parrot sits in the medium cage");
			command("tp @s " + (sx + 25) + " " + SY + " " + (sz - 8));
		});
		pause("medium: checked", 1, () -> check(holds(medium, viewerParrot("cage_medium")), "follow does not pull the parrot out of the cage"));

		// Большая клетка: танец работает внутри, freefollow и arm отклоняются.
		pause("large: lure", 2, () -> {
			command("tp @s " + sx + " " + SY + " " + (sz - 2));
		});
		pause("large: lured", 2, () -> lure(viewerParrot("cage_large"), large));
		pause("large: commands", 5, () -> {
			check(holds(large, viewerParrot("cage_large")), "viewer parrot sits in the large cage");
			output.clear();
			command("vp dance cage_large");
			command("vp freefollow cage_large");
			command("vp arm cage_large");
		});
		pause("large: checked", 1, () -> {
			long rejected = output.stream().filter(l -> l.contains("клетке") || l.contains("in a cage")).count();
			check(rejected >= 2, "!freefollow and !arm are rejected (" + rejected + " caged replies)");
			check(holds(large, viewerParrot("cage_large")), "large cage still holds the parrot");
		});

		// Ванильный ручной попугай.
		pause("vanilla: spawn tamed parrot", 2, () -> onServer(() -> {
			ParrotEntity bird = EntityType.PARROT.create(serverWorld());
			bird.refreshPositionAndAngles(player().getX() + 2, player().getY(), player().getZ(), 0, 0);
			bird.setOwner(player());
			bird.setPersistent();
			serverWorld().spawnEntity(bird);
			vanilla = bird.getUuid();
		}));
		pause("vanilla: lured", 3, () -> lure(serverWorld().getEntity(vanilla), vanillaCage));
		pause("vanilla: checked", 1, () -> {
			Entity bird = serverWorld().getEntity(vanilla);
			check(holds(vanillaCage, bird), "tamed vanilla parrot sits in a cage");
			check(bird instanceof ParrotEntity p && p.isSitting(), "tamed vanilla parrot sits down in the cage");
		});

		// Скриншоты.
		pauseClient("camera", 3, () -> {
			client.options.hudHidden = true;
			look(sx + 1.5, SY + 2.2, sz - 4.5, -5f, 18f);
		});
		pauseClient("screenshot overview", 1, () -> screenshot("parrotcages-overview.png"));
		pause("camera small", 2, () -> look(sx - 4.3, SY + 1.0, sz + 1.6, 25f, 10f));
		pauseClient("screenshot small+medium", 1, () -> screenshot("parrotcages-small-medium.png"));
		pause("camera large", 2, () -> look(sx + 2.5, SY + 2.6, sz + 0.5, 0f, 25f));
		pauseClient("screenshot large", 1, () -> screenshot("parrotcages-large.png"));
		pauseClient("camera back", 1, () -> { client.options.hudHidden = false; look(sx, SY, sz - 2, 0f, 0f); });

		// Выпуск: средняя птица снова следует за стримером.
		pause("medium: release", 2, () -> releaseByHand(medium));
		pause("medium: streamer walks away again", 10, () -> {
			Entity bird = viewerParrot("cage_medium");
			check(bird != null && !CageLinks.isCaged(bird) && cage(medium).occupant().isEmpty(), "sneak + empty hand releases the parrot");
			command("tp @s " + (sx + 14) + " " + SY + " " + (sz - 8));
		});
		pause("medium: followed", 1, () -> {
			Entity bird = viewerParrot("cage_medium");
			double distance = bird == null ? -1 : Math.sqrt(bird.squaredDistanceTo(player()));
			check(bird != null && distance < 8, "released parrot follows the streamer again (" + String.format("%.1f", distance) + " blocks)");
		});

		// Выпуск маленькой: !come снова работает.
		pause("small: release", 2, () -> { command("tp @s " + sx + " " + SY + " " + (sz - 2)); releaseByHand(small); });
		pause("small: come", 8, () -> {
			output.clear();
			command("tp @s " + (sx - 14) + " " + SY + " " + (sz - 8));
			command("vp come cage_small");
		});
		pause("small: checked", 1, () -> {
			Entity bird = viewerParrot("cage_small");
			double distance = bird == null ? -1 : Math.sqrt(bird.squaredDistanceTo(player()));
			check(!saw("клетке", "in a cage"), "!come is accepted after release");
			check(bird != null && distance < 6, "released parrot came to the streamer (" + String.format("%.1f", distance) + " blocks)");
		});

		// Сломанная большая клетка выпускает птицу и исчезает целиком, выпадает один предмет.
		pause("large: break", 2, () -> onServer(() -> {
			player().setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
			player().interactionManager.tryBreakBlock(large.add(1, 0, 0));
		}));
		pause("large: checked", 1, () -> {
			Entity bird = viewerParrot("cage_large");
			check(bird != null && !CageLinks.isCaged(bird), "breaking the cage releases the parrot");
			boolean gone = true;
			for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++)
				if (serverWorld().getBlockState(large.add(x, y, z)).isOf(ParrotCages.LARGE_CAGE)) gone = false;
			check(gone, "the whole large cage is removed");
			long drops = serverWorld().getEntitiesByType(EntityType.ITEM, new net.minecraft.util.math.Box(large).expand(4),
					item -> item.getStack().isOf(ParrotCages.LARGE_CAGE.asItem())).stream().mapToLong(i -> i.getStack().getCount()).sum();
			check(drops == 1, "exactly one large cage item dropped (" + drops + ")");
		});

		pause("cleanup", 3, () -> {
			command("vp remove cage_small");
			command("vp remove cage_medium");
			command("vp remove cage_large");
		});
		pauseClient("finish", 3, () -> {
			log(failed ? "FINISHED WITH FAILURES" : "ALL CHECKS PASSED");
			client.scheduleStop();
		});
	}
}
