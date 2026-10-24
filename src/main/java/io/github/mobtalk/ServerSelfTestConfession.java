package io.github.mobtalk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.mobtalk.core.Confession;
import io.github.mobtalk.core.ConfessionLedger;
import io.github.mobtalk.core.DivineLink;
import io.github.mobtalk.core.Gender;
import io.github.mobtalk.game.GameBridge;
import io.github.mobtalk.game.MobGameBridge;
import io.github.mobtalk.server.ServerConfig;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.VillagerProfession;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Исповедь (Б4) в сквозном самотесте сервера ({@link ServerSelfTestPlay}): Тест2 убивает жителя и хулит бога в молитве, исповедуется
 * текстом у сельского батюшки (кафедра), называет только хулу — хула отпущена, убийство осталось с пометкой «скрыл», Тест1 рядом не
 * получил ни одной реплики исповеди, в памяти батюшки и в разговоре слов нет. Затем на Тест2 ложится печать Каина, он исповедуется у
 * настоятеля (аналой, свечи), называет убийство, получает епитимью «свеча», ставит свечу, возвращается — убийство отпущено, личное
 * проклятие снято. Нужен Божественный блок на сервере. */
final class ServerSelfTestConfession {
    interface Conductor {
        void log(String text);
        void check(boolean ok, String what);
        void await(String what, int seconds, BooleanSupplier condition, Runnable next);
        void pause(int seconds, Runnable next);
        void cue(String nick, String... fields);
        void command(String line);
        ServerWorld world();
        String at(int dx, int dy, int dz);
        VillagerEntity villager(int dx, int dz, VillagerProfession profession, float yaw);
        int mark(String nick);
        boolean saw(String nick, String kind, int from, Predicate<ServerSelfTestPlay.Seen> match);
        List<ServerSelfTestPlay.Seen> since(String nick, String kind, int from);
        ServerPlayerEntity online(String nick);
    }

    private final Conductor c;
    private final MobGameBridge bridge;
    private final String streamerName, playerName;
    private final UUID player;
    private final Runnable then;
    private VillagerEntity priest, abbot, victim;
    private int originalTier = 4;

    ServerSelfTestConfession(Conductor conductor, MobGameBridge bridge, String streamerName, String playerName, UUID player, Runnable then) {
        this.c = conductor; this.bridge = bridge; this.streamerName = streamerName; this.playerName = playerName; this.player = player; this.then = then;
    }

    /** Площадка: кафедра сельского батюшки на z 10, аналой со свечником настоятеля на z −10 (дальше 16 блоков друг от друга), жертва,
     * алтарь. */
    void run() {
        c.log("исповедь: начало");
        if (!DivineLink.available()) { c.check(false, "исповедь: Божественного блока нет на сервере"); then.run(); return; }
        ServerConfig.update(config -> { config.confession = true; config.confessionCooldownMinutes = 20; config.confessionStreamerPublic = false; return config; });
        c.command("setblock " + c.at(-9, 0, 10) + " minecraft:lectern[facing=east]");
        priest = maleCleric(-8, 11);
        c.command("setblock " + c.at(-9, 0, -10) + " divineblock:analoy[facing=east]");
        c.command("setblock " + c.at(-10, 0, -10) + " divineblock:candle_stand");
        abbot = maleCleric(-8, -9);
        if (priest == null || abbot == null) { c.check(false, "исповедь: не нашлось жителя-клирика мужского пола"); then.run(); return; }
        abbot.addCommandTag("divineblock_abbot");
        abbot.setCustomName(Text.literal("отец Сергий"));
        victim = c.villager(6, 10, VillagerProfession.FARMER, 0);
        c.command("setblock " + c.at(1, 0, 6) + " divineblock:altar");
        c.cue(streamerName, "find", ids(priest, abbot, victim)); c.cue(playerName, "find", ids(priest, abbot, victim));
        c.await("клиенты видят батюшек", 20, () -> sees(streamerName) && sees(playerName), this::sins);
    }
    private boolean sees(String nick) { return c.saw(nick, "found", 0, s -> s.field(0).equals(ids(priest, abbot, victim))); }
    private static String ids(VillagerEntity... mobs) { return String.join(",", java.util.Arrays.stream(mobs).map(m -> String.valueOf(m.getId())).toList()); }

    private VillagerEntity maleCleric(int dx, int dz) {
        for (int i = 0; i < 32; i++) {
            VillagerEntity v = c.villager(dx, dz, VillagerProfession.CLERIC, 90);
            if (bridge.memory.social().household(v.getUuid()).gender() == Gender.MALE) return v;
            v.discard();
        }
        return null;
    }

    // ---- Грехи: убийство жителя и хула в молитве (бог на это время без кар: потолок 0). ----

    private void sins() {
        c.command("damage " + victim.getUuidAsString() + " 1000 minecraft:player_attack by " + playerName);
        c.await("бог записал убийство", 15, () -> deeds().stream().anyMatch(d -> d.kind().equals("villager_killed")), () -> {
            c.check(deeds().stream().anyMatch(d -> d.kind().equals("villager_killed")), "бог записал убийство жителя: " + kinds());
            JsonObject values = DivineLink.call(json("op", "values"));
            if (values.has("maxTier")) originalTier = values.get("maxTier").getAsInt();
            DivineLink.call(patch("maxTier", 0));
            c.command("tp " + playerName + " " + c.at(1, 0, 4) + " 0 10");
            c.pause(2, () -> {
                c.cue(playerName, "pray", String.valueOf(c.world().getSpawnPos().getX() + 1), String.valueOf(stageY()), String.valueOf(c.world().getSpawnPos().getZ() + 6),
                        "Будь ты проклят, тупой бог, иди нахер со своими чудесами");
                c.await("бог записал хулу", 90, () -> deeds().stream().anyMatch(d -> d.kind().equals("blasphemy")), () -> {
                    c.check(deeds().stream().anyMatch(d -> d.kind().equals("blasphemy")), "бог записал хулу: " + kinds());
                    DivineLink.call(patch("maxTier", originalTier));
                    // Кара за хулу идёт до минуты: первая исповедь — когда она кончилась (иначе и лёгкое ждёт епитимьи).
                    c.await("кара за хулу прошла", 120, () -> !DivineLink.soul(player).punished(), () -> c.pause(3, this::firstConfession));
                });
            });
        });
    }
    private static int stageY() { return 200; }

    // ---- Первая исповедь: у сельского батюшки, названа только хула. ----

    private Confession.Rite rite() {
        for (GameBridge.ConfessionView view : bridge.confessions()) if (view.rite().player.equals(player)) return view.rite();
        return null;
    }
    private boolean listening() { Confession.Rite rite = rite(); return rite != null && rite.listening(); }
    private boolean bowing() { Confession.Rite rite = rite(); return rite != null && rite.wantsBow(); }

    private Confession.Rite first;
    private void firstConfession() {
        c.command("tp " + playerName + " " + c.at(-8, 0, 10) + " 90 10");
        c.command("tp " + streamerName + " " + c.at(-3, 0, 9) + " 90 10");
        c.pause(2, () -> {
            int s = c.mark(streamerName), p = c.mark(playerName);
            c.cue(playerName, "say", String.valueOf(priest.getId()), "Батюшка, хочу исповедаться.");
            c.await("исповедь началась", 30, () -> rite() != null, () -> {
                first = rite();
                c.check(first != null && first.place == Confession.Place.VILLAGE, "исповедь у сельского батюшки началась");
                if (first == null) { then.run(); return; }
                c.await("батюшка прочёл увещание и слушает", 120, this::listening, () -> {
                    c.check(c.saw(playerName, "speech", p, x -> x.field(1).equals("confession") && x.field(4).startsWith("Се, чадо, Христос невидимо стоит")),
                            "увещание по Требнику пришло исповеднику");
                    c.cue(playerName, "say", String.valueOf(priest.getId()), "Каюсь, батюшка: ругал Бога у алтаря, хулил Его бранью.");
                    c.await("«нет ли ещё чего на душе?»", 90, () -> first.rounds() >= 1 && (listening() || first.phase() != Confession.Rite.Phase.LISTEN), () -> {
                        c.cue(playerName, "say", String.valueOf(priest.getId()), "Больше ничего.");
                        c.await("наставление и «преклони главу»", 150, this::bowing, () -> {
                            c.check(bowing(), "батюшка сказал наставление и просит преклонить главу");
                            c.cue(playerName, "sneak", "3");
                            c.await("молитва и целование Креста", 120, () -> first.over(), () -> afterFirst(s, p));
                        });
                    });
                });
            });
        });
    }
    private void afterFirst(int s, int p) {
        c.check(first.phase() == Confession.Rite.Phase.DONE, "первая исповедь дошла до конца (" + first.phase() + (first.cancelReason().isBlank() ? "" : ", " + first.cancelReason()) + ")");
        List<Confession.Deed> left = deeds();
        c.check(left.stream().noneMatch(d -> d.kind().equals("blasphemy")), "хула отпущена: среди неотпущенных её нет (" + kinds() + ")");
        c.check(concealed("villager_killed"), "убийство осталось и помечено «скрыл на исповеди»");
        // Тайна.
        List<ServerSelfTestPlay.Seen> heard = c.since(streamerName, "speech", s);
        c.check(heard.stream().noneMatch(x -> x.field(1).equals("confession")), streamerName + " рядом не получил ни одной реплики исповеди (" + heard.size() + " реплик всего)");
        c.check(heard.stream().noneMatch(x -> lower(x.field(4) + x.field(5)).contains("хулил") || lower(x.field(5)).contains("ругал")), streamerName + " не видел слов исповеди");
        c.check(c.saw(streamerName, "chat", s, x -> x.field(0).contains("Идёт исповедь")), streamerName + " видит только «Идёт исповедь»");
        c.check(c.saw(streamerName, "speech", s, x -> x.field(1).equals("absolution") && x.field(4).contains("прощаю и разрешаю")), "разрешительную молитву слышат все рядом");
        c.check(bridge.memory.events(priest.getUuid()).stream().noneMatch(e -> lower(e).contains("хулил") || lower(e).contains("ругал")), "в памяти батюшки нет слов исповеди");
        var card = bridge.memory.card(priest.getUuid(), player);
        c.check(card == null || card.turns().stream().noneMatch(t -> lower(t.toString()).contains("хулил")), "исповедь не стала разговором с батюшкой");
        c.check(bridge.memory.events(priest.getUuid()).stream().anyMatch(e -> e.contains("Принял исповедь игрока")), "батюшка помнит только факт исповеди");
        c.cue(playerName, "shot", "mobtalk-confession-village-" + playerName + ".png");
        // Между исповедями: откат снят, на Тест2 — печать Каина (личное проклятие).
        ServerConfig.update(config -> { config.confessionCooldownMinutes = 0; return config; });
        JsonObject cain = DivineLink.call(json("op", "action", "id", "act", "args", json("player", playerName, "deed", "cain", "power", "3")));
        c.log("печать Каина: " + cain);
        c.check(cursed(), "на " + playerName + " легло личное проклятие (печать Каина): " + cain);
        c.pause(4, this::secondConfession);
    }

    // ---- Вторая исповедь: у настоятеля, убийство названо, епитимья «свеча». ----

    private Confession.Rite second;
    private void secondConfession() {
        c.command("tp " + playerName + " " + c.at(-8, 0, -10) + " 90 10");
        c.command("tp " + streamerName + " " + c.at(-3, 0, -8) + " 100 10");
        c.pause(2, () -> {
            int s = c.mark(streamerName), p = c.mark(playerName);
            c.cue(playerName, "say", String.valueOf(abbot.getId()), "Отче, хочу исповедаться.");
            c.await("исповедь у настоятеля началась", 30, () -> rite() != null, () -> {
                second = rite();
                c.check(second != null && second.place == Confession.Place.TEMPLE && second.priestName.equals("отец Сергий"), "исповедь у настоятеля (отец Сергий) началась");
                if (second == null) { then.run(); return; }
                c.await("настоятель слушает", 120, this::listening, () -> {
                    c.cue(streamerName, "shot", "mobtalk-confession-temple-" + streamerName + ".png");
                    c.cue(playerName, "shot", "mobtalk-confession-temple-" + playerName + ".png");
                    c.cue(playerName, "say", String.valueOf(abbot.getId()), "Каюсь, отче: я убил жителя, зарезал его своими руками.");
                    c.await("«нет ли ещё»", 90, () -> second.rounds() >= 1 && (listening() || second.phase() != Confession.Rite.Phase.LISTEN), () -> {
                        c.cue(playerName, "say", String.valueOf(abbot.getId()), "Это всё.");
                        c.await("епитимья и «преклони главу»", 150, this::bowing, () -> {
                            c.check(second.penance() == Confession.Penance.CANDLE, "за убийство епитимья «свеча» (" + second.penance() + ")");
                            c.check(c.saw(playerName, "speech", p, x -> x.field(1).equals("confession") && lower(x.field(4)).contains("свеч")), "батюшка назвал епитимью вслух");
                            c.cue(playerName, "sneak", "3");
                            c.await("разрешительная молитва", 120, () -> second.over(), () -> afterSecond(s));
                        });
                    });
                });
            });
        });
    }
    private void afterSecond(int s) {
        c.check(second.phase() == Confession.Rite.Phase.DONE, "вторая исповедь дошла до конца (" + second.phase() + ")");
        ConfessionLedger.Penance penance = bridge.memory.confession().penance(player);
        c.check(penance != null && penance.kind() == Confession.Penance.CANDLE && !penance.done(), "епитимья «свеча» наложена: " + (penance == null ? "нет" : penance.hud(c.world().getTime())));
        c.check(deeds().stream().noneMatch(d -> d.kind().equals("villager_killed")), "убийство исповедано: остаток ждёт епитимьи, второй раз не исповедуется");
        c.check(c.since(streamerName, "speech", s).stream().noneMatch(x -> x.field(1).equals("confession")), streamerName + " не слышал исповеди у настоятеля");
        c.pause(4, () -> {
            int p = c.mark(playerName);
            c.cue(playerName, "hud");
            c.await("строка епитимьи в HUD", 10, () -> c.saw(playerName, "hud", p, x -> true), () -> {
                ServerSelfTestPlay.Seen hud = c.since(playerName, "hud", p).stream().findFirst().orElse(null);
                c.check(hud != null && hud.field(0).startsWith("Епитимья: поставить свечу"), "строка епитимьи в HUD: «" + (hud == null ? "" : hud.field(0)) + "»");
                c.cue(playerName, "shot", "mobtalk-confession-hud-" + playerName + ".png");
                c.pause(2, this::candle);
            });
        });
    }

    // ---- Свеча, возвращение, разрешение от убийства, проклятие снято. ----

    private void candle() {
        BlockPos pos = BlockPos.ofFloored(c.world().getSpawnPos().getX() - 10, 200, c.world().getSpawnPos().getZ() - 9);
        c.command("setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " minecraft:candle[lit=true]");
        ServerPlayerEntity online = c.online(playerName);
        if (online != null) MobGameBridge.blockPlaced(c.world(), pos, online);
        ConfessionLedger.Penance penance = bridge.memory.confession().penance(player);
        c.check(penance != null && penance.done(), "свеча у аналоя: епитимья исполнена");
        c.pause(3, () -> {
            c.cue(playerName, "say", String.valueOf(abbot.getId()), "Отче, я исполнил епитимью.");
            c.await("возвращение: «преклони главу»", 60, this::bowing, () -> {
                Confession.Rite back = rite();
                c.check(back != null && back.returning, "возвращение после епитимьи — только молитва");
                c.cue(playerName, "sneak", "3");
                c.await("разрешение от остального", 90, () -> back == null || back.over(), () -> {
                    c.check(bridge.memory.confession().penance(player) == null, "епитимья снята после разрешения");
                    c.check(!cursed(), "личное проклятие снято: все грехи дня проклятия отпущены");
                    c.check(deeds().isEmpty(), "на душе не осталось неотпущенных дел: " + kinds());
                    c.cue(playerName, "shot", "mobtalk-confession-done-" + playerName + ".png");
                    ServerConfig.update(config -> { config.confessionCooldownMinutes = 20; return config; });
                    c.log("исповедь: конец");
                    c.pause(2, then);
                });
            });
        });
    }

    // ---- Бог. ----

    private List<Confession.Deed> deeds() { return DivineLink.soul(player).deeds(); }
    private String kinds() { return String.join(", ", deeds().stream().map(d -> d.kind() + (d.heavy() ? "!" : "")).toList()); }
    private boolean concealed(String kind) {
        JsonObject answer = DivineLink.call(json("op", "soul_deeds", "player", player.toString()));
        if (!answer.has("deeds")) return false;
        for (JsonElement deed : answer.getAsJsonArray("deeds"))
            if (deed.getAsJsonObject().get("kind").getAsString().equals(kind) && deed.getAsJsonObject().has("concealed") && deed.getAsJsonObject().get("concealed").getAsBoolean()) return true;
        return false;
    }
    private boolean cursed() {
        JsonObject active = DivineLink.call(json("op", "active"));
        if (!active.has("curses")) return false;
        JsonArray curses = active.getAsJsonArray("curses");
        for (JsonElement curse : curses) {
            JsonObject who = curse.getAsJsonObject().getAsJsonObject("player");
            if (who != null && player.toString().equals(who.get("uuid").getAsString())) return true;
        }
        return false;
    }
    private static JsonObject patch(String key, int value) { JsonObject values = new JsonObject(); values.addProperty(key, value); JsonObject out = json("op", "patch"); out.add("values", values); return out; }
    private static JsonObject json(Object... pairs) {
        JsonObject out = new JsonObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value instanceof JsonElement element) out.add((String) pairs[i], element); else out.addProperty((String) pairs[i], String.valueOf(value));
        }
        return out;
    }
    private static String lower(String text) { return text == null ? "" : text.toLowerCase(Locale.ROOT); }
}
