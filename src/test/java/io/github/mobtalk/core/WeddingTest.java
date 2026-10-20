package io.github.mobtalk.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Трек L: свадьба у колокола — кто приходит и где стоит, слова, которые зовут на свадьбу и отказывают, порядок службы и то, что
 * она доходит до брака при любом сбое; помолвка, свидетели и сам брак в памяти мира. Без Minecraft. */
class WeddingTest {
    @TempDir Path directory;
    @AfterEach void close() throws Exception { WeddingFaults.clear(); ConversationStore.closeWorld(directory); }

    private static Wedding.Person person(String name, Gender gender, String profession, int bond, boolean kin, int children) {
        return new Wedding.Person(UUID.randomUUID(), name, gender, profession, "дружелюбный", "voice-" + name, bond, kin, children);
    }

    @Test void theVillageComesClericLeadsAndAskedWitnessesStand() {
        Wedding.Person bride = person("Аксинья", Gender.FEMALE, "minecraft:farmer", 95, false, 0);
        Wedding.Person cleric = person("Отец Ефим", Gender.MALE, "minecraft:cleric", 10, false, 0);
        Wedding.Person friend = person("Ладко", Gender.MALE, "minecraft:mason", 60, false, 0);
        Wedding.Person asked = person("Фома", Gender.MALE, "minecraft:librarian", 45, false, 0);
        Wedding.Person girl = person("Марфа", Gender.FEMALE, "minecraft:shepherd", 30, false, 0);
        Wedding.Person mother = person("Ульяна", Gender.FEMALE, "minecraft:none", -20, true, 3);
        Wedding.Person sour = person("Прохор", Gender.MALE, "minecraft:none", -30, false, 0);
        Wedding.Person guest = person("Глаша", Gender.FEMALE, "minecraft:none", 5, false, 0);
        List<Wedding.Person> village = List.of(bride, cleric, friend, asked, girl, mother, sour, guest);
        Wedding.Cast cast = Wedding.cast(bride, null, village, asked.id(), null, false, 16);
        assertEquals(cleric, cast.officiant(), "a priest leads when the village has one");
        assertTrue(cast.priest());
        assertEquals(asked, cast.witnessMan(), "the witness the player asked stands, not the best friend");
        assertEquals(girl, cast.witnessWoman(), "the missing witness is the closest friend of that sex");
        assertTrue(cast.guests().contains(mother), "kin come even when they dislike the player");
        assertEquals(mother, cast.guests().getFirst(), "kin first");
        assertFalse(cast.guests().contains(sour), "someone who dislikes the player stays away");
        assertTrue(cast.guests().containsAll(List.of(friend, guest)));
        assertEquals(List.of(asked, girl, mother, friend, guest), cast.wishers(12), "witnesses wish first, then the guests");
        assertEquals(2, cast.wishers(2).size());

        Wedding.Cast noPriest = Wedding.cast(bride, null, List.of(bride, friend, girl, mother, guest), null, null, false, 16);
        assertEquals(mother, noPriest.officiant(), "without a priest the elder — most children — leads");
        assertFalse(noPriest.priest());
        Wedding.Cast exile = Wedding.cast(bride, null, village, null, null, true, 16);
        assertEquals(List.of(), exile.guests(), "an exile gets only kin: here the kin leads");
        assertEquals(mother, exile.officiant());
        Wedding.Cast alone = Wedding.cast(bride, null, List.of(bride), null, null, false, 16);
        assertNull(alone.officiant());
        assertEquals(bride, alone.leader(), "nobody came: the bride leads it herself");
        assertEquals(2, Wedding.cast(bride, null, village, null, null, false, 2).guests().size(), "guest limit");
    }

    @Test void everyoneHasAPlaceAndNobodySharesIt() {
        List<Wedding.Person> guests = new ArrayList<>();
        Wedding.Person bride = person("Аксинья", Gender.FEMALE, "", 95, false, 0);
        guests.add(bride);
        for (int i = 0; i < 14; i++) guests.add(person("Гость" + i, i % 2 == 0 ? Gender.MALE : Gender.FEMALE, "", 10 + i, false, 0));
        Wedding.Cast cast = Wedding.cast(bride, null, guests, null, null, false, 16);
        List<Wedding.Seat> seats = Wedding.seats(cast, true, Gender.MALE);
        assertEquals(cast.everyone().size(), seats.size());
        Set<String> spots = new HashSet<>();
        for (Wedding.Seat seat : seats) assertTrue(spots.add(Math.round(seat.forward() * 10) + ":" + Math.round(seat.right() * 10)), "two people on one spot: " + seat);
        Wedding.Seat player = Wedding.playerSeat(true), spouse = seats.stream().filter(s -> s.id().equals(bride.id())).findFirst().orElseThrow();
        assertEquals(player.forward(), spouse.forward());
        assertEquals(-player.right(), spouse.right(), "the couple stands side by side");
        assertTrue(spots.add(Math.round(player.forward() * 10) + ":" + Math.round(player.right() * 10)), "nobody stands on the player's spot");
        for (Wedding.Seat seat : seats) if (cast.role(seat.id()) == Wedding.Role.GUEST) assertTrue(seat.forward() > Wedding.COUPLE_FORWARD, "guests stand behind the couple");
    }

    @Test void theWordsOfTheWedding() {
        for (String yes : List.of("Идём на свадьбу!", "пошли жениться", "Пора нам пожениться", "давай сыграем свадьбу", "пойдём к колоколу, любимая"))
            assertTrue(Wedding.calls(yes), yes);
        for (String no : List.of("Как прошла свадьба у соседей?", "привет", "пойдём спать")) assertFalse(Wedding.calls(no), no);
        assertTrue(Wedding.asksWitness("Будь моим свидетелем!"));
        assertTrue(Wedding.asksWitness("будешь свидетельницей на свадьбе?"));
        assertFalse(Wedding.asksWitness("Как дела?"));
        for (String refusal : List.of("Нет.", "нет, я передумал", "Я не согласен", "не готова я", "Свадьбы не будет!", "я против"))
            assertTrue(Wedding.refuses(refusal), refusal);
        for (String consent : List.of("Да!", "Согласен, конечно", "Нет ничего прекраснее тебя — да, клянусь!", "Готов, хоть и страшновато", "", "ну… да",
                "я никогда не буду тебя обижать, клянусь", "Я не готов тебя потерять — люблю тебя"))
            assertFalse(Wedding.refuses(consent), consent);
    }

    private static Wedding.Cast smallCast() {
        Wedding.Person bride = person("Аксинья", Gender.FEMALE, "minecraft:farmer", 95, false, 0);
        Wedding.Person cleric = person("Ефим", Gender.MALE, "minecraft:cleric", 10, false, 0);
        Wedding.Person man = person("Ладко", Gender.MALE, "", 60, false, 0), woman = person("Марфа", Gender.FEMALE, "", 50, false, 0);
        Wedding.Person guest = person("Глаша", Gender.FEMALE, "", 5, false, 0);
        return Wedding.cast(bride, null, List.of(bride, cleric, man, woman, guest), null, null, false, 16);
    }
    /** Игра: выполняет каждое действие службы сразу. Без колец. */
    private static void serve(Wedding.Ceremony c, long now, boolean rings) {
        Wedding.Act act = c.pendingAct(now);
        if (act != null) c.actDone(act, rings, now);
    }

    // Nothing answers at all: no texts from the model, no voice, the player silent. The watchdog still carries the service to the marriage.
    @Test void theServiceReachesTheMarriageWhenEverythingFails() {
        Wedding.Cast cast = smallCast();
        long t = 1_000_000;
        Wedding.Ceremony c = new Wedding.Ceremony(cast, new Wedding.Names("Стив", Gender.MALE, "Ольховка"), 60_000, 30_000, 12, t);
        assertFalse(c.gatherDone(true, t + 1_000), "seated, but the texts are not here yet");
        c.scriptFailed();
        assertTrue(c.gatherDone(true, t + 2_000), "the model failed: the service goes on the stand-in texts");
        List<Wedding.Key> spoken = new ArrayList<>();
        long now = t + 2_000;
        for (int step = 0; step < 4000 && !c.over(); step++) {
            now += 1_000;
            Wedding.Line line = c.claim(now); // The client takes each line but its voice never ends: the watchdog moves on.
            if (line != null) { spoken.add(line.key()); assertFalse(line.text().isBlank(), "a stand-in for " + line.key()); }
            serve(c, now, false);
            c.tick(now);
        }
        assertTrue(c.married(), "the marriage was recorded");
        assertEquals(Wedding.Ceremony.Phase.DONE, c.phase());
        assertTrue(spoken.contains(Wedding.Key.REASK) && spoken.contains(Wedding.Key.SILENCE), "silence was asked about once and then taken for consent: " + spoken);
        assertTrue(spoken.contains(Wedding.Key.NO_RINGS), "no rings: the witness forgot them");
        assertTrue(spoken.indexOf(Wedding.Key.PRONOUNCE) < spoken.indexOf(Wedding.Key.WISH), "wishes come after the pronouncement");
        assertTrue(now - t < Wedding.SERVICE_MILLIS + 10 * 60_000L, "it ends in reasonable time");
    }

    @Test void aServiceWithVoicesVowsAndRings() {
        Wedding.Cast cast = smallCast();
        long t = 0;
        Wedding.Ceremony c = new Wedding.Ceremony(cast, new Wedding.Names("Стив", Gender.MALE, ""), 60_000, 30_000, 12, t);
        assertTrue(c.takeScriptRequest()); assertFalse(c.takeScriptRequest(), "the texts are asked for once");
        c.scriptReady(new Wedding.Script().put(Wedding.Key.OPENING, "Дорогие соседи, сегодня праздник!").wish(cast.witnessMan().id(), "Живите дружно!"));
        assertFalse(c.gatherDone(false, 10_000), "still walking");
        assertTrue(c.gatherDone(false, 60_000), "the gathering time is up");
        List<Wedding.Line> lines = new ArrayList<>();
        long now = 60_000;
        for (int step = 0; step < 200 && !c.over(); step++) {
            now += 500;
            if (c.awaitingVow()) {
                assertTrue(c.said(Wedding.Key.ASK_PLAYER).contains("в радости и в беде"), c.said(Wedding.Key.ASK_PLAYER));
                c.vow("Да, клянусь любить тебя всегда", false, now);
                c.spouseVow("Да, Стив, и я клянусь!");
                continue;
            }
            Wedding.Line line = c.claim(now);
            if (line != null) { lines.add(line); c.lineDone(line.seq(), now); }
            serve(c, now, true);
            c.tick(now);
        }
        while (!c.over()) { now += 1_000; c.tick(now); }
        assertTrue(c.married());
        assertEquals("Да, клянусь любить тебя всегда", c.vowText());
        assertEquals("Дорогие соседи, сегодня праздник!", lines.getFirst().text(), "the model's opening");
        assertEquals(cast.officiant().id(), lines.getFirst().speaker());
        assertEquals("voice-Ефим", lines.getFirst().voiceId());
        Wedding.Line vow = lines.stream().filter(l -> l.key() == Wedding.Key.SPOUSE_VOW).findFirst().orElseThrow();
        assertEquals("Да, Стив, и я клянусь!", vow.text(), "the bride answers the player's own words");
        assertEquals(cast.spouse().id(), vow.speaker());
        assertTrue(lines.stream().noneMatch(l -> l.key() == Wedding.Key.NO_RINGS), "rings were exchanged");
        List<Wedding.Line> wishes = lines.stream().filter(l -> l.key() == Wedding.Key.WISH).toList();
        assertEquals(cast.wishers(12).size(), wishes.size());
        assertEquals("Живите дружно!", wishes.getFirst().text());
        assertFalse(wishes.get(1).text().isBlank(), "a guest the model forgot still wishes");
    }

    @Test void aRefusalAtTheBellEndsItWithoutAMarriage() {
        Wedding.Ceremony c = new Wedding.Ceremony(smallCast(), new Wedding.Names("Стив", Gender.MALE, ""), 60_000, 30_000, 12, 0);
        c.scriptFailed(); c.gatherDone(true, 1);
        long now = 1;
        while (!c.awaitingVow()) { now += 100; Wedding.Line line = c.claim(now); if (line != null) c.lineDone(line.seq(), now); }
        c.vow("Нет, я передумал", true, now);
        assertTrue(c.jilted());
        Wedding.Line last = c.claim(now);
        assertEquals(Wedding.Key.JILTED, last.key());
        c.lineDone(last.seq(), now);
        assertEquals(Wedding.Ceremony.Phase.JILTED, c.phase());
        assertFalse(c.married());
    }

    @Test void aServiceThatDragsOnSkipsToTheMarriage() {
        Wedding.Ceremony c = new Wedding.Ceremony(smallCast(), new Wedding.Names("Стив", null, ""), 60_000, 600_000, 12, 0);
        c.scriptFailed(); c.gatherDone(true, 1);
        c.tick(Wedding.SERVICE_MILLIS + 10);
        Wedding.Beat beat = c.current();
        assertEquals(Wedding.Act.WED, beat.act(), "past the limit everything before the marriage is skipped");
        serve(c, Wedding.SERVICE_MILLIS + 20, false);
        assertTrue(c.married());
        c.cancel();
        assertTrue(c.married(), "cancelling after the marriage does not undo it");
    }

    @Test void theModelsTextsAreParsedAndCleaned() {
        Wedding.Cast cast = smallCast();
        WeddingScript.Input input = new WeddingScript.Input(cast, new Wedding.Names("Стив", Gender.MALE, "Ольховка"), "", "", 12);
        String json = "{\"opening\":\"«Дорогие соседи!»\",\"ask_player\":\"Стив, готов ли ты?\",\"reask_player\":\"\",\"ask_spouse\":\"Аксинья, готова?\","
                + "\"spouse_vow\":\"Да!\",\"rings\":\"Кольца!\",\"no_rings\":\"…\",\"pronounce\":\"Объявляю!\",\"wishes\":[{\"who\":\"w1\",\"text\":\"Ладко: Счастья!\"},"
                + "{\"who\":\"w9\",\"text\":\"кому?\"},{\"who\":\"w2\",\"text\":\"Любви!\"}]}";
        Wedding.Script script = WeddingScript.parse(json, input);
        assertEquals("Дорогие соседи!", script.get(Wedding.Key.OPENING), "quotes around are cut");
        assertNull(script.get(Wedding.Key.NO_RINGS), "no letters — no line");
        assertNull(script.get(Wedding.Key.REASK));
        assertEquals("Счастья!", script.wish(cast.wishers(12).get(0).id()), "the speaker's own name in front is cut");
        assertEquals("Любви!", script.wish(cast.wishers(12).get(1).id()));
        assertTrue(WeddingScript.instructions(input).contains("священник"));
        assertTrue(WeddingScript.factsJson(input).toString().contains("w3"));
        assertEquals(List.of("opening", "ask_player", "reask_player", "ask_spouse", "spouse_vow", "rings", "no_rings", "pronounce"),
                List.copyOf(WeddingScript.keys(true).keySet()));
    }

    // Through the engine: the texts come from the cheap model; a dead model or the self-test's fault switch give an IOException the
    // client turns into the stand-in texts.
    @Test void theEngineWritesTheTextsOrFailsCleanly() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        boolean[] broken = {false};
        server.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (broken[0]) { exchange.sendResponseHeaders(500, -1); exchange.close(); return; }
            byte[] body = completion("{\"opening\":\"Соседи!\",\"ask_player\":\"Готов?\",\"reask_player\":\"Ну?\",\"ask_spouse\":\"Готова?\",\"spouse_vow\":\"Да\","
                    + "\"rings\":\"Кольца\",\"no_rings\":\"Забыл\",\"pronounce\":\"Горько!\",\"wishes\":[]}");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/speech", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        try {
            MobTalkConfig config = new MobTalkConfig(); config.backgroundModel = "test/cheap"; config.requestTimeoutSeconds = 5;
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ConversationEngine engine = new ConversationEngine(config, directory, new OpenRouterConversationClient("unit-test-key", config, URI.create(base + "/chat")),
                    new OpenRouterSpeechClient("unit-test-key", config, URI.create(base + "/speech")));
            WeddingScript.Input input = new WeddingScript.Input(smallCast(), new Wedding.Names("Стив", Gender.MALE, ""), "", "", 12);
            assertEquals("Горько!", engine.weddingScript(input).get(Wedding.Key.PRONOUNCE));
            assertNull(engine.voice("any", "Проверка голоса"), "speech is down: the line goes as text");
            WeddingFaults.llm = true;
            assertThrows(java.io.IOException.class, () -> engine.weddingScript(input));
            WeddingFaults.clear();
            broken[0] = true;
            assertThrows(java.io.IOException.class, () -> engine.weddingScript(input));
            engine.close();
            ConversationStore.closeWorld(directory);
        } finally { server.stop(0); }
    }

    private static MobSnapshot villager(UUID id, UUID player, long revision, String held) { return SocialLayerTest.villager(id, player, revision, false, held); }
    private static void female(ConversationStore store, UUID mob) {
        store.state.profiles.get(mob.toString()).social.gender = Gender.FEMALE.name();
        store.state.profiles.get(mob.toString()).social.traits.clear();
    }

    // The ring makes an engagement, not a marriage; a friend agrees to be a witness; the wedding at the bell marries the two, and
    // everyone who came remembers it.
    @Test void engagementWitnessAndWedding() throws Exception {
        MobTalkConfig config = new MobTalkConfig(); config.playerGender = "male";
        ConversationStore store = ConversationStore.open(directory, config);
        UUID bride = UUID.randomUUID(), witness = UUID.randomUUID(), stranger = UUID.randomUUID(), player = UUID.randomUUID();
        store.observe(villager(bride, player, 0, "minecraft:air"));
        female(store, bride);
        store.state.profiles.get(bride.toString()).relations.get(player.toString()).score = 90;
        GestureIntent proposal = store.profile(villager(bride, player, store.revision(bride), Romance.ENGAGEMENT_RING)).socialTurn.intent(Gesture.PROPOSE);
        assertEquals(Gesture.Outcome.ACCEPTED, proposal.outcome(), proposal.note());
        assertEquals(SocialLedger.WEDDING_HEARTS + 5, proposal.hearts(), "the mod's own ring is the warmest");
        store.social().applyGesture(bride, player, "Стив", proposal, System.currentTimeMillis());
        assertTrue(store.wedding().engaged(bride, player));
        assertNull(store.state.family.spouse(player), "engaged, not married yet");
        assertEquals(Romance.ENGAGEMENT_RING, store.wedding().ring(bride, player));
        ConversationStore.Profile engaged = store.profile(villager(bride, player, store.revision(bride), "minecraft:air"));
        assertFalse(engaged.socialTurn.schemaValues().contains("PROPOSE"), "no second proposal");
        assertTrue(engaged.socialTurn.schemaValues().contains("DIVORCE"), "an engagement can be broken off");
        String prompt = SocialPrompt.gestures(engaged);
        assertTrue(prompt.contains("помолвлены") && prompt.contains("идём на свадьбу"), prompt);

        store.observe(villager(witness, player, 0, "minecraft:air"));
        store.state.profiles.get(witness.toString()).relations.get(player.toString()).score = 50;
        store.observe(villager(stranger, player, 0, "minecraft:air"));
        assertTrue(SocialPrompt.gestures(store.profile(villager(witness, player, store.revision(witness), "minecraft:air"))).contains("соглашайся"));
        assertTrue(SocialPrompt.gestures(store.profile(villager(stranger, player, store.revision(stranger), "minecraft:air"))).contains("не настолько близок"));
        assertEquals("", store.wedding().appointWitness(stranger, player, "Стив", 0), "a stranger is no witness");
        assertFalse(store.wedding().appointWitness(witness, player, "Стив", 0).isBlank());
        UUID[] witnesses = store.wedding().witnesses(bride, player);
        Gender witnessGender = store.state.profiles.get(witness.toString()).social.gender();
        assertEquals(witness, witnessGender == Gender.MALE ? witnesses[0] : witnesses[1]);
        assertTrue(SocialPrompt.gestures(store.profile(villager(bride, player, store.revision(bride), "minecraft:air"))).contains("свидетели на вашей свадьбе"));

        long now = System.currentTimeMillis();
        List<Wedding.Person> people = store.wedding().people(List.of(new WeddingLedger.Resident(bride, "Житель", "minecraft:farmer", true),
                new WeddingLedger.Resident(witness, "Житель", "minecraft:cleric", true), new WeddingLedger.Resident(stranger, "Житель", "minecraft:none", true)), bride, null, player, now);
        Wedding.Person spouse = people.stream().filter(p -> p.id().equals(bride)).findFirst().orElseThrow();
        Wedding.Cast cast = Wedding.cast(spouse, null, people, witnesses[0], witnesses[1], false, 16);
        int witnessBefore = store.relationship(witness, player), strangerBefore = store.relationship(stranger, player), brideBefore = store.relationship(bride, player);
        store.wedding().wed(cast, player, "Стив", Gender.MALE, true, Romance.WEDDING_RING, now);
        assertEquals(bride, store.state.family.spouse(player));
        assertFalse(store.wedding().engaged(bride, player));
        assertEquals(Math.min(100, brideBefore + WeddingLedger.CEREMONY_HEARTS + WeddingLedger.RINGS_HEARTS), store.relationship(bride, player));
        assertTrue(store.relationship(witness, player) > witnessBefore && store.relationship(stranger, player) > strangerBefore, "everyone who came warms to the player");
        assertTrue(store.events(stranger).getLast().contains("свадьб"), store.events(stranger).toString());
        assertTrue(store.events(bride).getLast().contains("супруги"), store.events(bride).toString());
        assertEquals(List.of(Romance.WEDDING_RING), store.wedding().rings(bride));
        assertTrue(SocialPrompt.gestures(store.profile(villager(bride, player, store.revision(bride), "minecraft:air"))).contains("сегодня у вас была свадьба"));
    }

    @Test void runningFromTheAltarAndTheOldWay() throws Exception {
        MobTalkConfig config = new MobTalkConfig(); config.playerGender = "male";
        ConversationStore store = ConversationStore.open(directory, config);
        UUID bride = UUID.randomUUID(), player = UUID.randomUUID();
        store.observe(villager(bride, player, 0, "minecraft:air"));
        female(store, bride);
        store.state.profiles.get(bride.toString()).relations.get(player.toString()).score = 95;
        GestureIntent proposal = store.profile(villager(bride, player, store.revision(bride), "minecraft:diamond")).socialTurn.intent(Gesture.PROPOSE);
        assertEquals(Gesture.Outcome.ACCEPTED, proposal.outcome(), "a diamond does for a ring in an old world");
        store.social().applyGesture(bride, player, "Стив", proposal, System.currentTimeMillis());
        Wedding.Person spouse = store.wedding().people(List.of(new WeddingLedger.Resident(bride, "Житель", "", true)), bride, null, player, 0).getFirst();
        int before = store.relationship(bride, player);
        store.wedding().jilted(Wedding.cast(spouse, null, List.of(spouse), null, null, false, 16), player, "Стив", System.currentTimeMillis());
        assertFalse(store.wedding().engaged(bride, player));
        assertEquals(before + WeddingLedger.JILTED_HEARTS, store.relationship(bride, player));
        assertNull(store.state.family.spouse(player));

        // With the ceremony off a ring marries at once, as before track L.
        config.weddingCeremony = false;
        ConversationStore.closeWorld(directory);
        ConversationStore old = ConversationStore.open(directory, config);
        UUID other = UUID.randomUUID();
        old.observe(villager(other, player, 0, "minecraft:air"));
        female(old, other);
        old.state.profiles.get(other.toString()).relations.get(player.toString()).score = 95;
        GestureIntent ring = old.profile(villager(other, player, old.revision(other), "minecraft:gold_ingot")).socialTurn.intent(Gesture.PROPOSE);
        old.social().applyGesture(other, player, "Стив", ring, System.currentTimeMillis());
        assertEquals(other, old.state.family.spouse(player));
    }

    private static byte[] completion(String content) {
        JsonObject message = new JsonObject(); message.addProperty("role", "assistant"); message.addProperty("content", content);
        JsonObject choice = new JsonObject(); choice.add("message", message); JsonArray choices = new JsonArray(); choices.add(choice);
        JsonObject response = new JsonObject(); response.add("choices", choices);
        return response.toString().getBytes(StandardCharsets.UTF_8);
    }
}
