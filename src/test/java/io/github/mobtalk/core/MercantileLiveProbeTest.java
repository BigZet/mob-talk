package io.github.mobtalk.core;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Живая проба меркантильных горожанок на ключе OpenRouter: приторная речь с богатым и бедным, подарки, ужин со счётом,
 * «годовщина», жена «на шубу», сплетня подружки о жмоте. Выключена по умолчанию; {@code MOBTALK_LIVE=1} запускает,
 * {@code MOBTALK_LIVE_OUT} — файл Markdown для итога. Речь уходит на мёртвый порт: TTS не оплачивается. */
class MercantileLiveProbeTest {
    @TempDir Path directory;

    private static MobSnapshot lady(UUID id, UUID player, long revision, String held, List<String> events, int wealth) {
        return new MobSnapshot(id, "minecraft:villager", "Житель", 20, 20, "minecraft:overworld", "minecraft:plains", "day", "clear", 3,
                held, List.of(), events, player, Map.of("name", "Стив", "health", 20, "maxHealth", 20, "wealth", wealth),
                Map.of("baby", false, "profession", "minecraft:cleric", "position", Map.of("x", 100, "y", 64, "z", 100),
                        "villageSite", Map.of("key", "mobtalk:city_panel")),
                Map.of(), List.of(), List.of(ActionType.NONE), revision);
    }

    private OpenRouterConversationClient llm;
    private ConversationEngine engine;
    private final StringBuilder out = new StringBuilder();
    private final UUID player = UUID.randomUUID();

    private UUID meet(String name, String village, String story) {
        UUID mob = UUID.randomUUID();
        ConversationStore store = engine.memory();
        store.observe(lady(mob, player, 0, "minecraft:air", List.of(), 0));
        store.setBackstory(mob, name, story, "", "", "");
        ConversationStore.Profile p = store.state.profiles.get(mob.toString());
        p.social.gender = Gender.FEMALE.name(); p.social.persona = Persona.MERCANTILE.name(); p.social.traits.clear(); p.villageId = village;
        return mob;
    }
    private ConversationStore.Relation rel(UUID mob) {
        ConversationStore.Profile p = engine.memory().state.profiles.get(mob.toString());
        ConversationStore.Relation r = p.relations.get(player.toString());
        if (r.rapport == null) r.rapport = new Rapport();
        return r;
    }
    private void say(UUID mob, String held, int wealth, String said) throws Exception {
        ConversationStore store = engine.memory();
        int before = store.relationship(mob, player);
        long started = System.nanoTime();
        ConversationEngine.PreparedReply prepared = engine.prepare(lady(mob, player, store.revision(mob), held, store.events(mob), wealth), said);
        double seconds = (System.nanoTime() - started) / 1e9;
        GestureIntent gesture = prepared.gesture();
        assertTrue(engine.commitDelivered(prepared, store.revision(mob)));
        out.append("\n**Игрок** (кошелёк ≈").append(wealth).append(" изумр.").append("minecraft:air".equals(held) ? "" : ", в руке `" + held + "`")
                .append("): «").append(said).append("»\n\n");
        if (gesture != null && gesture.gesture() != null) out.append("Жест **").append(gesture.gesture()).append("** → ").append(gesture.outcome())
                .append(gesture.note().isBlank() ? "" : " (" + gesture.note() + ")").append(". ");
        out.append("Отношение ").append(before).append(" → ").append(store.relationship(mob, player))
                .append(String.format(java.util.Locale.ROOT, ", %.1f с.", seconds)).append("\n\n> ").append(prepared.reply().speakerName()).append(": ")
                .append(prepared.reply().responseText()).append('\n');
    }
    private void initiative(UUID mob, String name, int wealth, String reason) throws Exception {
        ConversationStore store = engine.memory();
        Initiative.Cue cue = new Initiative.Cue(mob, name, Initiative.Trigger.CONCERN, reason, List.of(), System.currentTimeMillis());
        ConversationEngine.PreparedReply reply = engine.prepare(lady(mob, player, store.revision(mob), "minecraft:air", store.events(mob), wealth), "",
                ConversationEngine.Overheard.initiative(Initiative.cueText(cue, new Initiative.Verdict(true, Initiative.Reaction.REMARK, 1))));
        engine.commitDelivered(reply, store.revision(mob));
        out.append("\n*Сама подходит* (повод: ").append(reason).append(")\n\n> ").append(reply.reply().speakerName()).append(": ")
                .append(reply.reply().responseText().isBlank() ? "(промолчала)" : reply.reply().responseText()).append('\n');
    }

    @Test void mercantileWomen() throws Exception {
        assumeTrue("1".equals(System.getenv("MOBTALK_LIVE")), "live probes run only with MOBTALK_LIVE=1");
        String key = OpenRouterToken.load(Path.of(System.getenv().getOrDefault("MOBTALK_CONFIG_DIR", "config-not-here")));
        MobTalkConfig config = new MobTalkConfig(); config.playerGender = "male";
        Tuning.apply(config);
        out.append("# Живая проба: меркантильные горожанки\n\nМодель: `").append(config.chatModel())
                .append("`. Исходы решает игра до запроса; модель их озвучивает. TTS не вызывается.\n");
        try {
            llm = new OpenRouterConversationClient(key, config);
            engine = new ConversationEngine(config, directory, llm, new OpenRouterSpeechClient(key, config, URI.create("http://127.0.0.1:9/speech")));
            ConversationStore store = engine.memory();

            out.append("\n## 1. Богатый ухажёр: приторная речь, комплимент, подарки\n");
            UUID lera = meet("Лерочка", "c1", "Ты выросла в городе, работаешь в храме при больнице, мечтаешь о квартире в новой панельке и отпуске у моря.");
            say(lera, "minecraft:air", 200, "Привет, красавица! Как дела?");
            say(lera, "minecraft:air", 200, "У тебя очень красивые глаза.");
            say(lera, "minecraft:poppy", 200, "Держи цветочек, это тебе.");
            say(lera, "minecraft:diamond", 200, "А вот это тебе — алмаз.");

            out.append("\n## 2. Бедный ухажёр\n");
            UUID sveta = meet("Светуля", "c1", "Ты продавщица в универмаге, любишь глянцевые журналы и подругам рассказываешь, кто чем тебя порадовал.");
            say(sveta, "minecraft:air", 2, "Привет! Пойдём погуляем вечером?");
            say(sveta, "minecraft:air", 2, "Ну почему ты так холодна? Я же хороший.");

            out.append("\n## 3. Ужин-«тарелочница»: приглашение и счёт\n");
            out.append("\nИгра: ").append(store.startDinner(lera, player, "Стив", 200, config)).append(" (счёт ").append(store.dinnerBill(lera, player)).append(" изумр.)\n");
            say(lera, "minecraft:air", 200, "Пойдём в ресторан, я угощаю!");
            say(lera, "minecraft:air", 200, "Ну как тебе тут, нравится?");
            out.append("\nИгра: ").append(store.endDinner(lera, player, "Стив", true, true)).append('\n');
            say(lera, "minecraft:air", 190, "Подожди, а ты куда так быстро убежала?");

            out.append("\n## 4. Сплетня подружки: игрок сбежал от счёта\n");
            UUID first = meet("Милана", "c2", "Ты маникюрщица, знаешь все новости района.");
            out.append("\nИгра: ").append(store.startDinner(first, player, "Стив", 80, config)).append('\n');
            out.append("\nИгра: ").append(store.endDinner(first, player, "Стив", false, false)).append('\n');
            UUID friend = meet("Виолетта", "c2", "Ты подруга Миланы, работаешь в кассе кинотеатра.");
            say(friend, "minecraft:air", 80, "Привет, красавица! Может, сходим куда-нибудь?");
            say(friend, "minecraft:air", 80, "Почему ты на меня так смотришь?");
            out.append("\n## 5. Сама клянчит и напоминает о «годовщине»\n");
            UUID kristina = meet("Кристиночка", "c1", "Ты администратор в салоне красоты, обожаешь золото и фотографироваться.");
            ConversationStore.Relation kr = rel(kristina);
            kr.score = 80; kr.rapport.courtship(Courtship.DATING);
            kr.rapport.datingAt = System.currentTimeMillis() - (config.mercAnniversaryMinutes + 1) * 60_000L;
            kr.rapport.paidAt = System.currentTimeMillis() - (config.mercCoolMinutes + 1) * 60_000L;
            String anniversary = store.mercantileBeg(kristina, player, config);
            initiative(kristina, "Кристиночка", 120, anniversary == null ? "(игра не дала повода)" : anniversary);
            kr.rapport.begAt = -1; kr.rapport.anniversaryAt = -1;
            kr.rapport.anniversaries = 1000;
            String beg = store.mercantileBeg(kristina, player, config);
            initiative(kristina, "Кристиночка", 120, beg == null ? "(игра не дала повода)" : beg);

            out.append("\n## 6. Жена: «на шубу»\n");
            UUID zhena = meet("Анжела", "c1", "Ты бывшая официантка, теперь жена игрока и хозяйка дома; подруги замужем за купцами.");
            ConversationStore.Relation zr = rel(zhena);
            zr.score = 90; zr.rapport.courtship(Courtship.MARRIED);
            say(zhena, "minecraft:air", 150, "Привет, дорогая, я дома! Как прошёл день?");
            say(zhena, "minecraft:air", 150, "Что-то ты грустная. Что случилось?");

        } finally {
            if (engine != null) engine.close();
            if (llm != null) llm.close();
            String path = System.getenv("MOBTALK_LIVE_OUT");
            if (path != null && !path.isBlank()) Files.writeString(Path.of(path), out.toString(), StandardCharsets.UTF_8);
            System.out.println(out);
        }
    }
}
