package io.github.mobtalk.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;

/** The "Место" block of the system prompt: what the place analysis found, in words the model must take as fact, and the
 * grouped objects around the creature. The snapshot JSON drops the same facts so they are not paid for twice. */
public final class PlacePrompt {
    private PlacePrompt() { }

    static final String RULE = "Это факты о месте, они верны: пользуйся ими, а не пересказывай. Если что-то в снимке или в твоём представлении им противоречит — верь этим фактам.";

    /** Russian labels for the classifier's classes. */
    private static final Map<String, String> LOOKS = Map.ofEntries(Map.entry("PIT", "яма"), Map.entry("CHAMBER", "закрытая камера"), Map.entry("FUNNEL", "воронка"),
            Map.entry("CAGE", "клетка"), Map.entry("HOUSE", "дом"), Map.entry("FARM", "ферма"), Map.entry("MINE", "шахта"), Map.entry("TRAP", "ловушка"),
            Map.entry("OPEN", "открытое место"), Map.entry("PILLAR", "столб"), Map.entry("POND", "водоём"));

    static Map<?, ?> place(MobSnapshot snapshot) {
        return snapshot != null && snapshot.environmentDetails().get("place") instanceof Map<?, ?> place ? place : null;
    }

    /** The block, or nothing when the snapshot carries no analysis (a blind creature, an unloaded area, an old snapshot). */
    public static String block(MobSnapshot snapshot) {
        Map<?, ?> place = place(snapshot);
        if (place == null || !(place.get("summary") instanceof String summary) || summary.isBlank()) return "";
        StringBuilder text = new StringBuilder("\n\nМесто, где ты стоишь. ").append(RULE).append('\n').append(summary);
        // Registry facts (vanilla structures) and trees by rule: short lines, as sure as the flood's.
        for (String key : List.of("structures", "trees"))
            if (place.get(key) instanceof List<?> lines)
                for (Object line : lines) if (line != null && !String.valueOf(line).isBlank()) text.append('\n').append(line);
        if (place.get("objects") instanceof List<?> objects && !objects.isEmpty())
            text.append("\nЧто вокруг: ").append(String.join("; ", objects.stream().map(String::valueOf).toList())).append('.');
        if (place.get("looksLike") instanceof String label && !label.isBlank()) {
            double confidence = place.get("looksLikeConfidence") instanceof Number n ? n.doubleValue() : 0;
            text.append("\nПохоже на: ").append(LOOKS.getOrDefault(label, label.toLowerCase(java.util.Locale.ROOT)))
                    .append(" (уверенность ").append(Math.round(confidence * 100)).append(" %) — это догадка о назначении места; факты выше точнее.");
        }
        return text.toString();
    }

    /** The one-line reason the creature cannot leave, for the fight estimate; empty without an analysis. */
    public static String escape(MobSnapshot snapshot) {
        Map<?, ?> place = place(snapshot);
        return place != null && Boolean.TRUE.equals(place.get("trapped")) && place.get("escape") instanceof String escape ? escape : "";
    }

    /** The snapshot JSON without the place details: the block above already says them. */
    public static JsonElement withoutPlace(JsonElement snapshot) {
        if (snapshot instanceof JsonObject object && object.get("environmentDetails") instanceof JsonObject environment) environment.remove("place");
        return snapshot;
    }
}
