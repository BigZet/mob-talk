package io.github.parrotcages;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Клетки в веб-админке Mob Talk по протоколу {@code mobtalk-admin} (entrypoint в fabric.mod.json, JSON на входе и выходе,
 * без общего кода): группа настроек «Клетка» и кнопки по каждому узнику — «Освободить» и «+5 мин». Тот же объект
 * лежит в общем реестре Fabric под {@link #SHARED}: им пользуется F8 Viewer Parrots в одиночной игре.
 * Админка зовёт с своих потоков; всё, что трогает мир, уходит на поток сервера.
 */
public final class AdminBridge implements UnaryOperator<String> {
	public static final String SHARED = "parrotcages:admin";

	public AdminBridge() { }

	@Override
	public String apply(String request) {
		try {
			JsonObject in = JsonParser.parseString(request == null ? "" : request).getAsJsonObject();
			String op = in.has("op") ? in.get("op").getAsString() : "";
			return switch (op) {
				case "describe" -> describe().toString();
				case "values" -> values().toString();
				case "patch" -> patch(in).toString();
				case "journal" -> items(new JsonArray()).toString();
				case "actions" -> onServer(AdminBridge::actions).toString();
				case "action" -> onServer(() -> action(in)).toString();
				default -> error("неизвестный запрос: " + op).toString();
			};
		} catch (RuntimeException broken) {
			return error("запрос не разобран: " + broken.getClass().getSimpleName()).toString();
		}
	}

	private static JsonObject describe() {
		JsonObject out = new JsonObject();
		out.addProperty("id", ParrotCages.MOD_ID);
		out.addProperty("title", "Клетки для попугаев");
		JsonArray fields = new JsonArray();
		JailSettings defaults = new JailSettings();
		Map<String, Object> initial = defaults.values();
		for (JailSettings.Field field : JailSettings.FIELDS) {
			JsonObject f = new JsonObject();
			f.addProperty("key", field.key()); f.addProperty("title", field.title()); f.addProperty("type", field.type());
			if (field.type().equals("int")) { f.addProperty("min", field.min()); f.addProperty("max", field.max()); f.addProperty("step", 1); }
			f.add("default", primitive(initial.get(field.key())));
			f.addProperty("help", field.help());
			fields.add(f);
		}
		JsonObject group = new JsonObject();
		group.addProperty("id", "jail"); group.addProperty("title", "Клетка");
		group.add("fields", fields);
		JsonArray groups = new JsonArray();
		groups.add(group);
		out.add("groups", groups);
		return out;
	}

	private static JsonObject values() {
		JsonObject out = new JsonObject();
		JailSettings.get().values().forEach((key, value) -> out.add(key, primitive(value)));
		return out;
	}

	private static JsonObject patch(JsonObject in) {
		if (!in.has("values") || !in.get("values").isJsonObject()) return error("нет values");
		Map<String, Object> changes = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : in.getAsJsonObject("values").entrySet()) {
			JsonElement value = entry.getValue();
			changes.put(entry.getKey(), !value.isJsonPrimitive() ? null : value.getAsJsonPrimitive().isBoolean() ? (Object) value.getAsBoolean()
					: value.getAsJsonPrimitive().isNumber() ? (Object) value.getAsDouble() : value.getAsString());
		}
		Map<String, String> errors = JailSettings.patch(changes);
		JsonObject out = new JsonObject();
		out.addProperty("ok", errors.isEmpty());
		JsonArray applied = new JsonArray();
		changes.keySet().stream().filter(key -> !errors.containsKey(key)).forEach(applied::add);
		out.add("applied", applied);
		JsonObject errorJson = new JsonObject();
		errors.forEach(errorJson::addProperty);
		out.add("errors", errorJson);
		return out;
	}

	/** По две кнопки на узника; названия несут имя и остаток срока, поэтому список и есть «Узники». */
	private static JsonObject actions() {
		JsonArray list = new JsonArray();
		for (Jail.Prisoner prisoner : Jail.prisoners()) {
			ParrotCageBlockEntity cage = Jail.cageOf(prisoner.parrot());
			if (cage == null || cage.getWorld() == null) continue;
			String left = Jail.clock(prisoner.releaseAt(), cage.getWorld().getTime());
			list.add(action("release:" + prisoner.parrot(), "Освободить " + prisoner.name() + " (" + left + ")"));
			list.add(action("extend:" + prisoner.parrot(), "+" + Jail.EXTEND_STEP + " мин " + prisoner.name()));
		}
		JsonObject out = new JsonObject();
		out.add("actions", list);
		return out;
	}

	private static JsonObject action(String id, String title) {
		JsonObject a = new JsonObject();
		a.addProperty("id", id); a.addProperty("title", title);
		a.add("args", new JsonArray());
		return a;
	}

	private static JsonObject action(JsonObject in) {
		String id = in.has("id") ? in.get("id").getAsString() : "";
		int colon = id.indexOf(':');
		UUID parrot;
		try { parrot = colon < 0 ? null : UUID.fromString(id.substring(colon + 1)); } catch (IllegalArgumentException invalid) { parrot = null; }
		if (parrot == null) return error("нет такого действия: " + id);
		boolean done = switch (id.substring(0, colon)) {
			case "release" -> Jail.release(parrot, "admin", null);
			case "extend" -> Jail.extend(parrot, Jail.EXTEND_STEP);
			default -> false;
		};
		if (!done) return error("Попугай уже не в тюрьме или далеко от загруженных чанков");
		JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		String text = id.startsWith("release") ? "Попугай отпущен" : "Срок продлён на " + Jail.EXTEND_STEP + " мин";
		out.addProperty("message", text); out.addProperty("text", text);
		return out;
	}

	private static JsonObject onServer(java.util.function.Supplier<JsonObject> work) {
		MinecraftServer server = Jail.server();
		if (server == null) return error("мир не открыт");
		if (server.isOnThread()) return work.get();
		try { return server.submit(work::get).get(5, TimeUnit.SECONDS); }
		catch (Exception failure) { return error("сервер не ответил: " + failure.getClass().getSimpleName()); }
	}

	private static JsonObject items(JsonArray items) { JsonObject out = new JsonObject(); out.add("items", items); return out; }

	private static JsonElement primitive(Object value) {
		return value instanceof Boolean flag ? new JsonPrimitive(flag) : value instanceof Number number ? new JsonPrimitive(number) : new JsonPrimitive(String.valueOf(value));
	}

	private static JsonObject error(String message) {
		JsonObject out = new JsonObject();
		out.addProperty("ok", false);
		out.addProperty("error", message);
		return out;
	}
}
