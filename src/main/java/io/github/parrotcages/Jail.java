package io.github.parrotcages;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Тюрьма для попугаев-зрителей: клетка с птицей Viewer Parrots держит её срок. Срок и тюремщик живут в NBT клетки
 * ({@link ParrotCageBlockEntity}), здесь — список узников загруженных клеток и контракт для других модов без общего кода:
 * <ul>
 *   <li>тег {@link #JAILED_TAG} на птице (вместе с {@link CageLinks#CAGED_TAG});</li>
 *   <li>общий объект {@link #API} — {@code Function<Map<String,Object>,Object>}, вызывать на потоке сервера:
 *       {@code op=cage} (parrot: Entity, center: Vec3d, radius, minutes: −1 по умолчанию / 0 бессрочно, jailer: UUID, jailerName)
 *       → "" или текст отказа; {@code op=release} (parrot: UUID, reason) → Boolean; {@code op=extend} (parrot, minutes; 0 —
 *       бессрочно) → Boolean; {@code op=info} (parrot) → Map или null; {@code op=list} → List&lt;Map&gt;; {@code op=settings} → Map;
 *       {@code op=effect} (name: refused|escape_attempt, world: ServerWorld, at: Vec3d);</li>
 *   <li>общий объект {@link #LISTENERS} — {@code List<Consumer<Map<String,Object>>>}: события {@code jailed}, {@code extended},
 *       {@code released} (reason: term, hands, amnesty, escape, admin, broken, gone).</li>
 * </ul>
 */
public final class Jail {
	public static final String JAILED_TAG = "parrotcages.jailed";
	public static final String API = "parrotcages:jail";
	public static final String LISTENERS = "parrotcages:jail_listeners";
	/** Срок без конца. */
	public static final long INDEFINITE = Long.MAX_VALUE;
	public static final int TICKS_PER_MINUTE = 1200;
	/** Шаг кнопки «+5 мин». */
	public static final int EXTEND_STEP = 5;

	/** Узник загруженной клетки, как его видят список и админка. */
	public record Prisoner(UUID parrot, String name, GlobalPos cage, long releaseAt, long jailedAt, @Nullable UUID jailer, String jailerName) { }

	private static final Map<UUID, Prisoner> PRISONERS = new ConcurrentHashMap<>();
	private static final List<Consumer<Map<String, Object>>> SUBSCRIBERS = new CopyOnWriteArrayList<>();
	private static volatile MinecraftServer server;

	private Jail() { }

	static void install() {
		FabricLoader.getInstance().getObjectShare().put(API, (Function<Map<String, Object>, Object>) Jail::call);
		FabricLoader.getInstance().getObjectShare().put(LISTENERS, SUBSCRIBERS);
	}

	static void started(MinecraftServer started) { server = started; PRISONERS.clear(); }
	static void stopped() { server = null; PRISONERS.clear(); }
	@Nullable static MinecraftServer server() { return server; }

	static void remember(Prisoner prisoner) { PRISONERS.put(prisoner.parrot(), prisoner); }
	static void forget(UUID parrot) { PRISONERS.remove(parrot); }

	public static List<Prisoner> prisoners() {
		return PRISONERS.values().stream().sorted(Comparator.comparing(Prisoner::name, String.CASE_INSENSITIVE_ORDER)).toList();
	}

	static void emit(String event, ServerWorld world, BlockPos cage, UUID parrot, String name, @Nullable UUID jailer, String jailerName,
			long releaseAt, long now, String reason) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("event", event);
		data.put("parrot", parrot);
		data.put("name", name);
		data.put("viewer", true);
		if (jailer != null) data.put("jailer", jailer);
		data.put("jailerName", jailerName);
		data.put("minutes", minutesLeft(releaseAt, now));
		data.put("reason", reason);
		data.put("world", world);
		data.put("pos", cage);
		for (Consumer<Map<String, Object>> subscriber : SUBSCRIBERS) {
			try { subscriber.accept(Map.copyOf(data)); }
			catch (RuntimeException failure) { org.slf4j.LoggerFactory.getLogger("parrotcages").warn("Подписчик тюрьмы упал: {}", failure.toString()); }
		}
	}

	/** Минуты до выхода с округлением вверх; −1 — бессрочно. */
	public static int minutesLeft(long releaseAt, long now) {
		if (releaseAt == INDEFINITE) return -1;
		return (int) Math.max(0, (releaseAt - now + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE);
	}

	/** «3:12» или «бессрочно». */
	public static String clock(long releaseAt, long now) {
		if (releaseAt == INDEFINITE) return "бессрочно";
		long seconds = Math.max(0, (releaseAt - now) / 20);
		return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
	}

	/** «на 5 минут», «бессрочно». */
	static String term(int minutes) {
		if (minutes <= 0) return "бессрочно";
		int mod10 = minutes % 10, mod100 = minutes % 100;
		String word = mod10 == 1 && mod100 != 11 ? "минуту" : mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? "минуты" : "минут";
		return "на " + minutes + " " + word;
	}

	@Nullable static ParrotCageBlockEntity cageOf(UUID parrot) {
		MinecraftServer current = server;
		Prisoner prisoner = PRISONERS.get(parrot);
		if (current == null || prisoner == null) return null;
		ServerWorld world = current.getWorld(prisoner.cage().dimension());
		if (world == null || !world.isChunkLoaded(ChunkPos.toLong(prisoner.cage().pos()))) return null;
		return world.getBlockEntity(prisoner.cage().pos()) instanceof ParrotCageBlockEntity cage
				&& cage.occupant().filter(parrot::equals).isPresent() && cage.jailed() ? cage : null;
	}

	/** Строка тюремщику: срок и кнопки «+5 мин», «бессрочно», «освободить». */
	static void tellJailer(@Nullable ServerPlayerEntity jailer, Text bird, UUID parrot, long releaseAt, long now) {
		if (jailer == null) return;
		MutableText line = Text.literal("Попугай ").formatted(Formatting.GRAY).append(bird.copy().formatted(Formatting.WHITE))
				.append(Text.literal(" арестован " + term(minutesLeft(releaseAt, now)) + " ").formatted(Formatting.GRAY))
				.append(button("[+" + EXTEND_STEP + " мин]", "/parrotcages jail " + parrot + " extend " + EXTEND_STEP, "Продлить срок", Formatting.GOLD))
				.append(" ")
				.append(button("[бессрочно]", "/parrotcages jail " + parrot + " forever", "Без срока: выйдет только по вашему слову", Formatting.RED))
				.append(" ")
				.append(button("[освободить]", "/parrotcages jail " + parrot + " release", "Выпустить сейчас", Formatting.GREEN));
		jailer.sendMessage(line, false);
	}

	private static Text button(String label, String command, String hover, Formatting color) {
		return Text.literal(label).styled(style -> style.withColor(color)
				.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
	}

	// --- Контракт для других модов ---------------------------------------------

	static Object call(Map<String, Object> query) {
		String op = String.valueOf(query.get("op"));
		return switch (op) {
			case "cage" -> cageNearest(query);
			case "release" -> release(uuid(query.get("parrot")), String.valueOf(query.getOrDefault("reason", "admin")), null);
			case "extend" -> extend(uuid(query.get("parrot")), query.get("minutes") instanceof Number n ? n.intValue() : EXTEND_STEP);
			case "info" -> info(uuid(query.get("parrot")));
			case "list" -> prisoners().stream().map(Jail::describe).toList();
			case "settings" -> JailSettings.get().values();
			case "effect" -> effect(query);
			default -> null;
		};
	}

	@Nullable private static UUID uuid(Object value) {
		if (value instanceof UUID uuid) return uuid;
		try { return value == null ? null : UUID.fromString(value.toString()); } catch (IllegalArgumentException invalid) { return null; }
	}

	static boolean release(@Nullable UUID parrot, String reason, @Nullable ServerPlayerEntity by) {
		ParrotCageBlockEntity cage = parrot == null ? null : cageOf(parrot);
		if (cage == null || !(cage.getWorld() instanceof ServerWorld world)) return false;
		return cage.free(world, reason, by);
	}

	static boolean extend(@Nullable UUID parrot, int minutes) {
		ParrotCageBlockEntity cage = parrot == null ? null : cageOf(parrot);
		if (cage == null || !(cage.getWorld() instanceof ServerWorld world)) return false;
		cage.extend(world, minutes);
		return true;
	}

	@Nullable private static Map<String, Object> info(@Nullable UUID parrot) {
		Prisoner prisoner = parrot == null ? null : PRISONERS.get(parrot);
		return prisoner == null || cageOf(parrot) == null ? null : describe(prisoner);
	}

	private static Map<String, Object> describe(Prisoner prisoner) {
		MinecraftServer current = server;
		ServerWorld world = current == null ? null : current.getWorld(prisoner.cage().dimension());
		long now = world == null ? 0 : world.getTime();
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("parrot", prisoner.parrot());
		map.put("name", prisoner.name());
		map.put("releaseAt", prisoner.releaseAt() == INDEFINITE ? -1L : prisoner.releaseAt());
		map.put("jailedAt", prisoner.jailedAt());
		map.put("now", now);
		map.put("left", clock(prisoner.releaseAt(), now));
		map.put("jailerName", prisoner.jailerName());
		map.put("dimension", prisoner.cage().dimension().getValue().toString());
		map.put("pos", prisoner.cage().pos());
		return map;
	}

	private static Object effect(Map<String, Object> query) {
		if (!(query.get("world") instanceof ServerWorld world) || !(query.get("at") instanceof Vec3d at)) return false;
		switch (String.valueOf(query.get("name"))) {
			case "refused" -> JailEffects.refused(world, at);
			case "escape_attempt" -> JailEffects.escapeAttempt(world, at);
			default -> { return false; }
		}
		return true;
	}

	/** Птица перелетает в ближайшую свободную клетку в радиусе от точки (обычно — от стримера). */
	private static String cageNearest(Map<String, Object> query) {
		if (!(query.get("parrot") instanceof ParrotEntity bird) || !(bird.getWorld() instanceof ServerWorld birdWorld)) return "Попугай не найден в мире";
		if (!Roles.isViewerParrot(bird)) return "Это не попугай зрителя";
		if (CageLinks.isCaged(bird)) return "Попугай уже в клетке";
		ServerWorld world = query.get("world") instanceof ServerWorld w ? w : birdWorld;
		Vec3d center = query.get("center") instanceof Vec3d c ? c : bird.getPos();
		int radius = query.get("radius") instanceof Number n ? n.intValue() : 32;
		int minutes = query.get("minutes") instanceof Number n ? n.intValue() : -1;
		if (minutes < 0) minutes = JailSettings.get().defaultMinutes;
		UUID jailer = uuid(query.get("jailer"));
		String jailerName = String.valueOf(query.getOrDefault("jailerName", ""));
		ParrotCageBlockEntity best = null;
		double bestDistance = Double.MAX_VALUE;
		BlockPos origin = BlockPos.ofFloored(center);
		int chunks = (radius >> 4) + 1;
		for (int cx = -chunks; cx <= chunks; cx++) for (int cz = -chunks; cz <= chunks; cz++) {
			WorldChunk chunk = world.getChunkManager().getWorldChunk((origin.getX() >> 4) + cx, (origin.getZ() >> 4) + cz);
			if (chunk == null) continue;
			for (BlockEntity entity : new ArrayList<>(chunk.getBlockEntities().values())) {
				if (!(entity instanceof ParrotCageBlockEntity cage) || cage.occupant().isPresent()) continue;
				double distance = Vec3d.ofCenter(cage.getPos()).squaredDistanceTo(center);
				if (distance <= (double) radius * radius && distance < bestDistance) { best = cage; bestDistance = distance; }
			}
		}
		if (best == null) return "Рядом со стримером нет свободной клетки (ищу в " + radius + " блоках)";
		if (bird.getWorld() != world) return "Попугай в другом измерении, чем стример";
		best.capture(world, bird);
		ServerPlayerEntity online = jailer == null ? null : world.getServer().getPlayerManager().getPlayer(jailer);
		best.jail(world, bird, minutes, jailer, jailerName.isBlank() && online != null ? online.getGameProfile().getName() : jailerName, online);
		return "";
	}
}
