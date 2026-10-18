package io.github.mobtalk.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** One extra request at the first meeting: the model writes who this creature is — a name, a life, its own wish and secret —
 * and, for the first villager met in a village, the village itself. Seeds from the species are hints, never a script. */
final class BiographyWriter {
    private static final Gson JSON = new Gson();
    private BiographyWriter() { }

    /** The invented name and life of one mob, plus a village (name and story) when it is the first villager met there. */
    record Biography(String name, String story, String desire, String secret, String villageName, String villageStory) { }

    /** What the writer is told about the creature and its neighbours. {@code village} is the known village to stay consistent
     * with, or null; {@code takenNames}/{@code takenStories} belong to earlier mobs of the species, {@code takenVillages} to all villages. */
    record Facts(MobSnapshot snapshot, ConversationStore.Profile profile, ConversationStore.Village village,
                 List<String> takenNames, List<String> takenStories, List<String> takenVillages) {
        boolean villager() { return "minecraft:villager".equals(snapshot.type()); }
        boolean founds() { return villager() && village == null; }
    }

    static Biography write(OpenRouterConversationClient client, MobTalkConfig config, Facts facts) throws IOException, InterruptedException {
        try { return write(client, config, facts, false); }
        catch (IOException malformed) { return write(client, config, facts, true); } // A cut-off JSON once in a while: one more try.
    }

    private static Biography write(OpenRouterConversationClient client, MobTalkConfig config, Facts facts, boolean retry) throws IOException, InterruptedException {
        SpeciesCatalog.Species species = SpeciesCatalog.effective(facts.snapshot().type(), config);
        JsonArray messages = new JsonArray();
        messages.add(ReplySchema.message("system", instructions(facts)));
        messages.add(ReplySchema.message("user", "Факты о персонаже: " + factsJson(species, facts)));
        JsonObject body = client.body(messages, 2048, retry ? 0.8 : 1.0, ReplySchema.biographyFormat(), false);
        byte[] bytes = client.complete(body);
        try {
            JsonObject response = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject answer = JsonParser.parseString(response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString()).getAsJsonObject();
            String story = TextBounds.characters(ReplySchema.string(answer, "story"), 1500);
            if (story.isBlank()) throw new IllegalArgumentException();
            boolean founds = facts.founds();
            return new Biography(TextBounds.characters(ReplySchema.string(answer, "name"), 60), story,
                    TextBounds.characters(ReplySchema.optional(answer, "desire"), 400), TextBounds.characters(ReplySchema.optional(answer, "secret"), 400),
                    founds ? TextBounds.characters(ReplySchema.string(answer, "villageName"), 60) : "",
                    founds ? TextBounds.characters(ReplySchema.string(answer, "villageStory"), 1500) : "");
        } catch (RuntimeException malformed) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            throw new IOException(client.service() + " не написал биографию" + (text.contains("\"finish_reason\":\"length\"") ? " (ответ обрезан)." : "."));
        }
    }

    private static String instructions(Facts facts) {
        return "Ты сочиняешь биографию персонажа для мода Minecraft 1.21.1, где игрок разговаривает с мобами голосом. Аудитория взрослая. "
                + "Пиши по-русски, конкретно и по-майнкрафтовски: места, события, вещи и мобы из игры, а не абстракции. Без упоминания игрока и без морали.\n"
                + "name — имя или кличка в одно-два слова, в духе персонажа и вида (у животных — кличка, у жителя — имя, у монстров — прозвище); не из списка занятых.\n"
                + "story — 3–5 предложений во втором лице («Ты родился…»): откуда ты, одно событие, которое тебя изменило, кого или что ты потерял или ищешь, "
                + "одна странность или привычка. Всё должно сочетаться с характером и с тем, где персонаж стоит сейчас.\n"
                + "desire — одно-два предложения: чего именно ЭТОТ персонаж хочет от жизни (конкретная, достижимая в Minecraft цель). "
                + "secret — одно-два предложения: что он скрывает и выдаёт только другу. typicalDesire и typicalSecret в фактах — лишь то, что обычно бывает у этого вида: "
                + "придумай другое, своё, не пересказывай их. Не повторяй мотивы из takenStories (это истории сородичей): у каждого своя судьба и свои события.\n"
                + (facts.founds() ? "villageName — название деревни (1–3 слова, живое, не «Деревня №1»), не из занятых; villageStory — 3–4 предложения: как деревня возникла, чем славится, "
                        + "какая беда или легенда с ней связана, кто в ней заметен (профессии, без имён). История жителя должна вытекать из истории деревни.\n"
                : "villageName и villageStory — пустые строки.\n")
                + (facts.snapshot().mobDetails().containsKey("meetingPoint") ? "В этой деревне уже есть колокол на площади — не мечтай о нём, он есть.\n" : "")
                + "Ответ — JSON с полями name, story, desire, secret, villageName, villageStory.";
    }

    private static JsonObject factsJson(SpeciesCatalog.Species species, Facts facts) {
        MobSnapshot snapshot = facts.snapshot(); ConversationStore.Profile profile = facts.profile();
        String archetype = species.variants().get(Math.max(0, Math.min(2, profile.variantSlot))).prompt();
        JsonObject json = new JsonObject();
        json.addProperty("species", species.name()); json.addProperty("speciesPrompt", species.prompt()); json.addProperty("archetype", archetype);
        json.addProperty("quirks", species.quirks()); json.addProperty("typicalDesire", species.desire()); json.addProperty("typicalSecret", species.secret());
        json.addProperty("preference", profile.preference); json.addProperty("fear", profile.fear);
        json.addProperty("biome", snapshot.biome()); json.addProperty("dimension", snapshot.dimension()); json.addProperty("timeOfDay", snapshot.timeOfDay());
        for (String key : List.of("profession", "level", "baby", "tamed", "variant", "customName"))
            if (snapshot.mobDetails().get(key) != null) json.addProperty(key, String.valueOf(snapshot.mobDetails().get(key)));
        if (facts.village() != null) { json.addProperty("villageName", facts.village().name()); json.addProperty("villageStory", facts.village().story()); }
        json.add("takenNames", JSON.toJsonTree(facts.takenNames())); json.add("takenStories", JSON.toJsonTree(facts.takenStories()));
        json.add("takenVillageNames", JSON.toJsonTree(facts.takenVillages()));
        return json;
    }
}
