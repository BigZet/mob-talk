package io.github.parrotcages;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Настройки тюрьмы для попугаев-зрителей в {@code config/parrotcages.json}. Их правят F8 (Viewer Parrots) и
 * админка Mob Talk через {@link AdminBridge}; Viewer Parrots читает голосование и вора отсюда же.
 */
public final class JailSettings {
	private static final Logger LOGGER = LoggerFactory.getLogger("parrotcages");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Срок по умолчанию в минутах; 0 — бессрочно. */
	public int defaultMinutes = 5;
	/** Бессрочного узника выпускают, если стримера нет в игре столько минут подряд. */
	public int offlineReleaseMinutes = 30;
	/** Сколько разных зрителей нужно для досрочной амнистии !свободу. */
	public int amnestyVotes = 5;
	/** За сколько секунд нужно набрать голоса. */
	public int amnestyWindowSeconds = 60;
	/** Модераторы Twitch могут сажать попугаев командой !вклетку. */
	public boolean modJailCommand = false;
	/** Шанс побега вора (характер THIEF) раз за срок, после половины срока. */
	public int thiefChancePercent = 15;

	/** Поле для формы: ключ, заголовок, подсказка, тип и границы. */
	public record Field(String key, String title, String help, String type, int min, int max) { }

	public static final List<Field> FIELDS = List.of(
			new Field("defaultMinutes", "Срок по умолчанию", "Минуты; 0 — бессрочно.", "int", 0, 60),
			new Field("offlineReleaseMinutes", "Выпуск бессрочных без стримера", "Минут без стримера в игре, после которых бессрочный узник выходит.", "int", 1, 240),
			new Field("amnestyVotes", "Голосов за амнистию", "Разных зрителей с !свободу <ник>; голос самого узника не считается.", "int", 2, 50),
			new Field("amnestyWindowSeconds", "Окно голосования", "Секунды, за которые нужно набрать голоса.", "int", 10, 600),
			new Field("modJailCommand", "!вклетку для модераторов", "Модераторы Twitch-канала могут сажать попугаев в клетку.", "bool", 0, 1),
			new Field("thiefChancePercent", "Шанс побега вора", "Проценты; одна попытка за срок, после его половины.", "int", 0, 100));

	private static volatile JailSettings current = new JailSettings();
	private static Path file;

	public static JailSettings get() { return current; }

	static void load() {
		file = FabricLoader.getInstance().getConfigDir().resolve("parrotcages.json");
		if (!Files.exists(file)) { save(current); return; }
		try {
			JailSettings read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JailSettings.class);
			if (read != null) current = read.clamped();
		} catch (IOException | RuntimeException failure) {
			LOGGER.warn("parrotcages.json не прочитан, беру значения по умолчанию ({})", failure.toString());
		}
	}

	private static void save(JailSettings settings) {
		if (file == null) return;
		try { Files.writeString(file, GSON.toJson(settings), StandardCharsets.UTF_8); }
		catch (IOException failure) { LOGGER.warn("parrotcages.json не записан ({})", failure.toString()); }
	}

	public Map<String, Object> values() {
		Map<String, Object> values = new LinkedHashMap<>();
		JsonObject json = GSON.toJsonTree(this).getAsJsonObject();
		for (Field field : FIELDS) values.put(field.key(), field.type().equals("bool") ? (Object) json.get(field.key()).getAsBoolean() : (Object) json.get(field.key()).getAsInt());
		return values;
	}

	/** Применяет правку; возвращает ошибки по ключам (пустая карта — всё применилось). */
	static synchronized Map<String, String> patch(Map<String, Object> changes) {
		Map<String, String> errors = new LinkedHashMap<>();
		JsonObject json = GSON.toJsonTree(current).getAsJsonObject();
		changes.forEach((key, value) -> {
			Field field = FIELDS.stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
			if (field == null) { errors.put(key, "нет такой настройки"); return; }
			if (field.type().equals("bool")) {
				if (value instanceof Boolean flag) json.addProperty(key, flag);
				else errors.put(key, "нужно да/нет");
				return;
			}
			if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) { errors.put(key, "нужно целое число"); return; }
			int v = number.intValue();
			if (v < field.min() || v > field.max()) { errors.put(key, "от " + field.min() + " до " + field.max()); return; }
			json.addProperty(key, v);
		});
		JailSettings next = GSON.fromJson(json, JailSettings.class).clamped();
		current = next;
		save(next);
		return errors;
	}

	private JailSettings clamped() {
		JsonObject json = GSON.toJsonTree(this).getAsJsonObject();
		for (Field field : FIELDS) if (!field.type().equals("bool"))
			json.addProperty(field.key(), Math.max(field.min(), Math.min(field.max(), json.get(field.key()).getAsInt())));
		return GSON.fromJson(json, JailSettings.class);
	}
}
