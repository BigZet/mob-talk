package io.github.mobtalk.server.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mobtalk.core.MobTalkConfig;
import io.github.mobtalk.core.Tunables;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Mob Talk as a settings source: every server number of Tunables with its Russian title, nothing personal, F7's tab order. */
class AdminSourceTest {
    private final AtomicReference<MobTalkConfig> config = new AtomicReference<>(new MobTalkConfig());
    private final MobTalkAdminSource source = new MobTalkAdminSource(config::get, config::updateAndGet, MobTalkAdminSource::personal);

    private JsonObject call(String request) { return JsonParser.parseString(source.apply(request)).getAsJsonObject(); }

    private List<JsonObject> fields() {
        List<JsonObject> fields = new ArrayList<>();
        for (JsonElement group : call("{\"op\":\"describe\"}").getAsJsonArray("groups"))
            for (JsonElement field : group.getAsJsonObject().getAsJsonArray("fields")) fields.add(field.getAsJsonObject());
        return fields;
    }

    @Test void describesEveryServerTunableWithARussianTitle() {
        Map<String, JsonObject> byKey = new java.util.HashMap<>();
        for (JsonObject field : fields()) assertNull(byKey.put(field.get("key").getAsString(), field), "поле дважды: " + field);
        for (Tunables.Tunable tunable : Tunables.ALL) {
            if (MobTalkAdminSource.personal(tunable.field())) { assertFalse(byKey.containsKey(tunable.field()), "личное поле в админке: " + tunable.field()); continue; }
            JsonObject field = byKey.get(tunable.field());
            assertNotNull(field, "нет серверного поля " + tunable.field());
            String title = field.get("title").getAsString();
            assertFalse(title.isBlank() || title.contains("%s") || title.equals(tunable.field()), "заголовок не из ru_ru.json: " + title);
            assertTrue(title.chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC), title);
            assertEquals(tunable.min(), field.get("min").getAsDouble(), tunable.field());
            assertEquals(tunable.max(), field.get("max").getAsDouble(), tunable.field());
        }
        for (String key : byKey.keySet()) assertFalse(MobTalkAdminSource.personal(key), "личное поле: " + key);
        assertTrue(byKey.containsKey("llmModel") && byKey.containsKey("ttsProvider"), "модели и провайдеры — серверные");
        assertFalse(byKey.containsKey("whisperModel") || byKey.containsKey("microphoneName") || byKey.containsKey("showStateHud"));
    }

    @Test void groupsFollowTheF7Tabs() {
        List<String> sections = new ArrayList<>();
        for (JsonElement group : call("{\"op\":\"describe\"}").getAsJsonArray("groups")) sections.add(group.getAsJsonObject().get("id").getAsString().split("\\.")[0]);
        Set<String> order = new LinkedHashSet<>(sections);
        List<String> expected = new ArrayList<>();
        for (Tunables.Section section : Tunables.Section.values()) if (order.contains(section.name().toLowerCase())) expected.add(section.name().toLowerCase());
        assertEquals(expected, List.copyOf(order));
        for (int i = 1; i < sections.size(); i++)
            assertTrue(expected.indexOf(sections.get(i - 1)) <= expected.indexOf(sections.get(i)), "разделы не перемешаны: " + sections);
    }

    @Test void patchAppliesInRangeAndRefusesTheRest() {
        JsonObject answer = call("{\"op\":\"patch\",\"values\":{\"maxReplyWords\":30,\"stayDrift\":3.5,\"llmProvider\":\"venice\","
                + "\"maxDistance\":1,\"historyTurns\":2.5,\"vision\":\"sometimes\",\"whisperModel\":\"tiny\",\"enabled\":\"yes\"}}");
        assertEquals(Set.of("maxReplyWords", "stayDrift", "llmProvider"), Set.copyOf(answer.getAsJsonArray("applied").asList().stream().map(JsonElement::getAsString).toList()));
        assertEquals(Set.of("maxDistance", "historyTurns", "vision", "whisperModel", "enabled"), answer.getAsJsonObject("errors").keySet());
        assertEquals(30, config.get().maxReplyWords);
        assertEquals(3.5, config.get().stayDrift);
        assertEquals("venice", config.get().llmProvider);
        assertEquals("large-v3-turbo", config.get().whisperModel, "личное не трогается");
        JsonObject values = call("{\"op\":\"values\"}");
        assertEquals(30, values.get("maxReplyWords").getAsInt());
        assertFalse(values.has("whisperModel"));
    }

    @Test void rudenessIsAChoice() {
        assertEquals(1, call("{\"op\":\"patch\",\"values\":{\"rudeness\":\"1\"}}").getAsJsonArray("applied").size());
        assertEquals(1, config.get().rudeness);
        assertEquals("1", call("{\"op\":\"values\"}").get("rudeness").getAsString());
    }

    @Test void resetSectionKeepsPersonalFields() {
        config.updateAndGet(c -> { MobTalkConfig copy = c.copy(); copy.maxReplyWords = 99; copy.whisperBeamSize = 9; return copy; });
        assertTrue(call("{\"op\":\"action\",\"id\":\"reset-section\",\"args\":{\"section\":\"talk\"}}").get("ok").getAsBoolean());
        assertEquals(new MobTalkConfig().maxReplyWords, config.get().maxReplyWords);
        assertEquals(9, config.get().whisperBeamSize, "Whisper — личная настройка игрока");
    }

    @Test void titlesAndUnitsFromLangLabels() {
        assertEquals("Дальность разговора", MobTalkAdminSource.title("Дальность разговора: %s бл."));
        assertEquals("бл.", MobTalkAdminSource.unit("Дальность разговора: %s бл."));
        assertEquals("слов", MobTalkAdminSource.unit("Длина ответа: до %s слов"));
        assertNull(MobTalkAdminSource.unit("Без значения"));
    }
}
