package io.github.mobtalk.client;

import io.github.mobtalk.client.cards.CardTableClient;
import io.github.mobtalk.client.cards.CardTableScreen;
import io.github.mobtalk.core.cards.Card;
import io.github.mobtalk.core.cards.CardTable;
import io.github.mobtalk.core.cards.Rank;
import io.github.mobtalk.core.cards.Suit;
import io.github.mobtalk.game.CardTables;
import io.github.mobtalk.server.CardTableService;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.math.Box;

/** Этапы самотеста игры в 21 ({@code -Only cards}, docs/cards-21.md). Священник отказывает словами; фермер садится за стол; три
 * раздачи с подложенной колодой: ставка и раздача, ход игрока, перебор — золотое очко — выигрыш с отъездом фишек. Изумрудов у игрока и
 * в банке жителя в сумме столько же, залог пуст после раздачи, «Встать» закрывает стол. Снимки стола в screenshots. Без ИИ и голоса. */
final class SelfTestCards {
    private static final String TAG = "mobtalk_cards";
    private final SelfTest test;
    private final int fx, fy, fz;
    private Runnable next = () -> { };
    private UUID farmer;
    private int farmerId, priestId, before, guestId;

    SelfTestCards(SelfTest test, int stageX, int stageY, int stageZ) { this.test = test; this.fx = stageX; this.fy = stageY; this.fz = stageZ; }

    private String name() { return test.owner.client.player.getName().getString(); }
    private UUID me() { return test.owner.client.player.getUuid(); }
    private String at(int dx, int dz) { return (fx + dx) + " " + fy + " " + (fz + dz); }
    private CardTableService service() { return CardTables.service(); }
    private <T> T onServer(Supplier<T> work) { return test.server().submit(work::get).join(); }
    private int emeralds() { return onServer(() -> test.serverPlayer().getInventory().count(Items.EMERALD)); }
    private int purse() { return onServer(() -> test.bridge().memory.cards().balance(farmer)); }
    private CardTable.View view() { CardTableClient.State s = CardTableClient.state(); return s == null ? null : s.view(); }
    private static Card c(Rank rank, Suit suit) { return new Card(rank, suit); }
    /** Пауза в миллисекундах, потом {@code then}. */
    private void after(String what, long millis, Runnable then) {
        long until = System.currentTimeMillis() + millis;
        test.await("pause: " + what, (int) (millis / 1000) + 3, () -> System.currentTimeMillis() >= until, then);
    }

    void run(Runnable then) {
        next = then;
        test.owner.client.options.setPerspective(Perspective.FIRST_PERSON);
        window(3); // Окно как у стримера: 1600×900, интерфейс ×3.
        test.log("cards stages: the priest's refusal, a table with the farmer, three stacked deals with screenshots");
        test.command("execute at " + name() + " run kill @e[type=minecraft:villager,distance=..30]");
        test.command("summon minecraft:villager " + at(2, -1) + " {NoAI:1b,PersistenceRequired:1b,Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:farmer\",type:\"minecraft:plains\",level:2}}");
        test.command("summon minecraft:villager " + at(-2, -1) + " {NoAI:1b,PersistenceRequired:1b,Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:cleric\",type:\"minecraft:plains\",level:2}}");
        test.command("summon minecraft:villager " + at(0, -4) + " {NoAI:1b,PersistenceRequired:1b,Tags:[\"" + TAG + "\"],VillagerData:{profession:\"minecraft:farmer\",type:\"minecraft:plains\",level:5}}");
        test.command("clear " + name() + " minecraft:emerald");
        test.command("give " + name() + " minecraft:emerald 40");
        test.command("give " + name() + " mobtalk:card_deck 1");
        test.command("time set 13000");
        test.command("tp " + name() + " " + at(0, 1) + " 180 15");
        test.await("farmer and priest on the stage", 20, () -> {
            List<VillagerEntity> near = test.owner.client.world.getEntitiesByClass(VillagerEntity.class,
                    new Box(fx - 6, fy - 2, fz - 6, fx + 6, fy + 4, fz + 6), Entity::isAlive);
            for (VillagerEntity v : near) {
                String job = v.getVillagerData().getProfession().toString();
                if (job.contains("farmer") && v.getVillagerData().getLevel() == 2) { farmer = v.getUuid(); farmerId = v.getId(); }
                if (job.contains("farmer") && v.getVillagerData().getLevel() == 5) guestId = v.getId();
                if (job.contains("cleric")) priestId = v.getId();
            }
            return farmer != null && priestId != 0 && guestId != 0 && service() != null && emeralds() == 40;
        }, this::portraits);
    }

    // ---- Портрет жителя: бюст целиком при любом масштабе и у обоих рендеров. ----

    private static final String[][] PORTRAITS = {{"human", "2"}, {"human", "3"}, {"human", "auto"}, {"vanilla", "2"}, {"vanilla", "3"}, {"vanilla", "auto"}};
    private void portraits() { portrait(0); }
    private void portrait(int i) {
        if (i >= PORTRAITS.length) {
            test.owner.config().humanVillagers = true;
            window(3);
            after("the window settles", 1200, this::priest);
            return;
        }
        String look = PORTRAITS[i][0], scale = PORTRAITS[i][1];
        test.owner.config().humanVillagers = "human".equals(look);
        if ("auto".equals(scale)) {
            var client = test.owner.client;
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(client.getWindow().getHandle(), 1920, 1080);
            client.options.getGuiScale().setValue(0);
            client.onResolutionChanged();
        } else window(Integer.parseInt(scale));
        String opened = onServer(() -> service().open(me(), farmerId, ""));
        test.check(opened.isEmpty(), "the farmer sits down for the portrait (" + look + ", GUI " + scale + "): " + opened);
        test.await("the table screen opens", 10, () -> test.owner.client.currentScreen instanceof CardTableScreen && view() != null,
                () -> after("the portrait settles", 1500, () -> shot("mobtalk-cards-portrait-" + look + "-gui" + scale + ".png", () -> getUp(() -> portrait(i + 1)))));
    }

    private void priest() {
        String refusal = onServer(() -> service().open(me(), priestId, ""));
        test.check(refusal.contains("грех"), "the priest refuses to play and says why: " + refusal);
        test.check(!(test.owner.client.currentScreen instanceof CardTableScreen), "no table opens for the priest");
        open(List.of(c(Rank.TEN, Suit.HEARTS), c(Rank.NINE, Suit.SPADES), c(Rank.SEVEN, Suit.CLUBS), c(Rank.EIGHT, Suit.DIAMONDS), c(Rank.SIX, Suit.CLUBS)), this::first);
    }

    /** Открыть стол фермера с подложенной первой раздачей. */
    private void open(List<Card> order, Runnable then) {
        onServer(() -> { service().stack(order); return true; });
        String opened = onServer(() -> service().open(me(), farmerId, ""));
        test.check(opened.isEmpty(), "the farmer sits down to play: " + opened);
        test.await("the table screen opens", 10, () -> test.owner.client.currentScreen instanceof CardTableScreen && view() != null
                && "BETTING".equals(view().phase()), () -> after("the table appears", 1500, then));
    }

    // ---- Раздача 1: ставка, раздача, ход игрока, перебор. ----

    private void first() {
        before = emeralds() + purse();
        test.check(purse() == 12, "a level 2 farmer starts with 12 emeralds: " + purse());
        shot("mobtalk-cards-1-empty.png", () -> {
            CardTableClient.send("bet", 5, "");
            CardTableClient.send("bet", 1, "");
            test.await("the stake goes into escrow", 10, () -> emeralds() == 34 && view() != null && view().seats().get(0).pendingBet() == 6, () -> {
                test.check(onServer(() -> test.bridge().memory.cards().stakedBy(me())) == 6, "the escrow holds 6 emeralds in the world memory");
                after("chips land on the circle", 700, () -> shot("mobtalk-cards-2-stake.png", () -> {
                    CardTableClient.send("deal", 0, "");
                    test.await("cards are dealt", 10, () -> view() != null && "PLAYERS".equals(view().phase()), () -> after("cards fly in", 1200, () -> {
                        CardTable.View v = view();
                        test.check(v.dealer().size() == 1 && v.dealer().getFirst() == -1, "ochko: the villager's only card lies face down");
                        test.check(v.seats().get(0).hands().getFirst().cards().size() == 1, "ochko: one open card for the player");
                        shot("mobtalk-cards-3-deal.png", () -> {
                            CardTableClient.send("hit", 0, "");
                            after("the second card", 900, () -> shot("mobtalk-cards-4-turn.png", () -> {
                                CardTableClient.send("hit", 0, "");
                                test.await("the hand goes bust", 10, () -> view() != null && "SETTLED".equals(view().phase()),
                                        () -> after("the bust flash", 420, () -> shot("mobtalk-cards-5-bust.png", () -> after("the deal ends", 3000, this::firstDone))));
                            }));
                        });
                    }));
                }));
            });
        });
    }
    private void firstDone() {
        test.check(onServer(() -> test.bridge().memory.cards().stakedBy(me())) == 0, "the escrow is released after the deal");
        test.check(emeralds() + purse() == before, "emeralds only moved between the player and the farmer: " + emeralds() + " + " + purse() + " = " + before);
        getUp(() -> open(List.of(c(Rank.ACE, Suit.SPADES), c(Rank.NINE, Suit.SPADES), c(Rank.ACE, Suit.HEARTS), c(Rank.SEVEN, Suit.DIAMONDS),
                c(Rank.SIX, Suit.CLUBS), c(Rank.SIX, Suit.HEARTS)), this::golden));
    }

    // ---- Раздача 2: золотое очко. ----

    private void golden() {
        CardTableClient.send("bet", 5, "");
        after("the stake", 600, () -> {
            CardTableClient.send("deal", 0, "");
            test.await("cards are dealt", 10, () -> view() != null && "PLAYERS".equals(view().phase()), () -> after("cards fly in", 900, () -> {
                CardTableClient.send("hit", 0, "");
                test.await("two aces settle the deal", 10, () -> view() != null && "SETTLED".equals(view().phase()), () -> after("the golden glow", 650, () -> {
                    test.check(view().seats().get(0).hands().getFirst().natural(), "two aces are golden ochko");
                    shot("mobtalk-cards-6-golden.png", () -> after("the deal ends", 4500, () -> getUp(() -> open(List.of(c(Rank.TEN, Suit.HEARTS),
                            c(Rank.NINE, Suit.SPADES), c(Rank.NINE, Suit.CLUBS), c(Rank.SEVEN, Suit.DIAMONDS), c(Rank.TEN, Suit.SPADES), c(Rank.EIGHT, Suit.HEARTS)), this::win))));
                }));
            }));
        });
    }

    // ---- Раздача 3: выигрыш, стопка едет к игроку. ----

    private void win() {
        int wallet = emeralds();
        CardTableClient.send("bet", 9, "");
        CardTableClient.send("bet", 1, "");
        after("the stake", 700, () -> {
            CardTableClient.send("deal", 0, "");
            test.await("cards are dealt", 10, () -> view() != null && "PLAYERS".equals(view().phase()), () -> after("cards fly in", 900, () -> {
                CardTableClient.send("hit", 0, "");
                after("the second card", 700, () -> {
                    CardTableClient.send("stand", 0, "");
                    test.await("the villager plays and pays", 10, () -> view() != null && "SETTLED".equals(view().phase()), () -> {
                        test.check(emeralds() > wallet, "19 beats the farmer: winnings came to the inventory (" + wallet + " → " + emeralds() + ")");
                        test.await("the winning stack starts towards the player", 12, CardTableScreen::winChipsFlying, () -> shot("mobtalk-cards-7-win-a.png", () -> after("chips travel", 120,
                                () -> shot("mobtalk-cards-8-win-b.png", () -> after("the deal ends", 3000, () -> {
                                    test.check(emeralds() + purse() == before, "after three deals the sum is the same: " + emeralds() + " + " + purse() + " = " + before);
                                    window(2);
                                    after("the window rescales", 1200, () -> shot("mobtalk-cards-9-gui2.png", () -> { window(3); getUp(this::cheat); }));
                                })))));
                    });
                });
            }));
        });
    }

    // ---- Этап 4: рукав хитреца и «Ловлю!», слово жителю, приглашение словами. ----

    private void cheat() {
        onServer(() -> { service().stackCheat(List.of(c(Rank.TEN, Suit.HEARTS), c(Rank.NINE, Suit.SPADES), c(Rank.NINE, Suit.CLUBS), c(Rank.KING, Suit.DIAMONDS),
                c(Rank.TEN, Suit.SPADES), c(Rank.SIX, Suit.HEARTS), c(Rank.EIGHT, Suit.CLUBS)), io.github.mobtalk.core.cards.DealerStyle.TRICKSTER); return true; });
        String opened = onServer(() -> service().open(me(), farmerId, ""));
        test.check(opened.isEmpty(), "the farmer sits down again, as a trickster: " + opened);
        test.await("the table screen opens", 10, () -> test.owner.client.currentScreen instanceof CardTableScreen && view() != null && "BETTING".equals(view().phase()), () -> {
            int before = emeralds() + purse();
            CardTableClient.send("bet", 2, "");
            after("the stake", 600, () -> {
                CardTableClient.send("deal", 0, "");
                test.await("cards are dealt", 10, () -> view() != null && "PLAYERS".equals(view().phase()), () -> after("cards fly in", 900, () -> {
                    CardTableClient.send("hit", 0, "");
                    after("the second card", 700, () -> {
                        CardTableClient.send("stand", 0, "");
                        test.await("the villager reaches for his sleeve", 15, CardTableScreen::cheatShowing, () -> shot("mobtalk-cards-10-cheat.png", () -> {
                            int wallet = emeralds();
                            CardTableClient.send("catch", 0, "");
                            test.await("he is caught", 10, () -> emeralds() > wallet, () -> after("the catch shows", 500, () -> shot("mobtalk-cards-11-caught.png", () -> {
                                test.check(emeralds() + purse() == before, "a caught cheat pays from his own purse: " + emeralds() + " + " + purse() + " = " + before);
                                test.check(onServer(() -> test.bridge().memory.facts(farmer).stream().anyMatch(f -> f.text().contains("рукава"))), "the villager remembers being caught");
                                say();
                            })));
                        }));
                    });
                }));
            });
        });
    }

    private void say() {
        // Сначала дождаться, пока прошлые реплики (поймал, конец раздачи) допечатаются: ответ на слово игрока — следующая.
        test.await("the earlier lines settle", 15, () -> CardTableScreen.lineAge() > 4000, () -> {
            String before = CardTableScreen.shownLine();
            test.owner.client.execute(() -> CardTableClient.send("say", 0, "Ну ты и хитрец, а ещё друг называется!"));
            test.await("the villager answers at the table", 30, () -> !CardTableScreen.shownLine().equals(before) && CardTableScreen.lineAge() > 3000, () -> {
                test.log("villager at the table: «" + CardTableScreen.shownLine() + "»");
                shot("mobtalk-cards-12-say.png", () -> getUp(this::offer));
            });
        });
    }
    /** Игрок сам зовёт сыграть словами: модель отвечает с game OFFER — стол открывается; отказ словами тоже допустим. */
    private void offer() {
        test.command("time set 6000"); // Днём: ночью тревожный житель вправе отказать.
        test.owner.client.execute(() -> net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
                new io.github.mobtalk.net.TalkTextC2S(guestId, "Привет! Давай сыграем в карты на изумруды? В очко, по маленькой!", false, new byte[0])));
        test.await("farmer refuses or opens the table (game OFFER)", 60, () -> test.owner.client.currentScreen instanceof CardTableScreen, () -> {
            boolean opened = test.owner.client.currentScreen instanceof CardTableScreen;
            if (opened) test.log("OK: asked in words, the farmer agreed (game OFFER) and the table opened");
            else test.log("the farmer answered without opening the table (the model chose not to play)");
            after("the table settles", 1500, () -> shot("mobtalk-cards-13-offer.png", () -> {
                if (opened) stakes();
                else {
                    String open = onServer(() -> service().open(me(), guestId, ""));
                    test.check(open.isEmpty(), "the second farmer sits down: " + open);
                    test.await("the table screen opens", 10, () -> test.owner.client.currentScreen instanceof CardTableScreen && view() != null, this::stakes);
                }
            }));
        });
    }

    // ---- Этап 5: предметы, услуга и скидка на кону. ----

    private int diamonds() { return onServer(() -> test.serverPlayer().getInventory().count(Items.DIAMOND)); }
    private void stakes() {
        UUID guest = onServer(() -> test.serverPlayer().getWorld().getEntityById(guestId) instanceof VillagerEntity v ? v.getUuid() : null);
        test.command("item replace entity " + name() + " weapon.mainhand with minecraft:diamond 2");
        // Житель тепл к игроку (+20, как за помощь): скидку на кон ставят только знакомым.
        onServer(() -> { test.bridge().memory.recordHelp(guest, "minecraft:villager", "Фермер", me(), name(), System.currentTimeMillis()); return true; });
        test.await("two diamonds in hand", 10, () -> diamonds() == 2 && view() != null && "BETTING".equals(view().phase()), () -> {
            // Услуга (4) первой: в ставку до 16 после неё влезает один алмаз (8), второй остаётся в руке.
            CardTableClient.send("favor", 0, "");
            CardTableClient.send("item", 0, "");
            CardTableClient.send("discount", 0, "");
            test.await("diamonds, a favour and the villager's discount are on the stake", 10, () -> {
                CardTableClient.State s = CardTableClient.state();
                CardTableClient.Extra e = s == null ? null : s.extra(s.you());
                return diamonds() == 1 && e != null && e.items().contains("minecraft:diamond") && e.favor() && e.discount() > 0;
            }, () -> after("the icons settle", 800, () -> {
                test.check(view().seats().get(0).pendingBet() == io.github.mobtalk.core.CardLedger.FAVOR_VALUE + 8,
                        "the stake counts the favour (4) and one diamond (8), the second stays in hand: " + view().seats().get(0).pendingBet());
                shot("mobtalk-cards-14-stakes.png", () -> {
                    CardTableClient.send("unbet", 0, "");
                    test.await("taking the stake back returns the diamonds", 10, () -> diamonds() == 2, () -> getUp(this::settings));
                });
            }));
        });
    }

    // ---- Этап 7: группа «Игра в 21» в F7. ----

    private void settings() {
        test.owner.client.execute(() -> test.owner.openSettings(null));
        test.await("F7 opens", 10, () -> test.owner.client.currentScreen instanceof MobTalkSettingsScreen, () -> {
            test.owner.client.execute(() -> ((MobTalkSettingsScreen) test.owner.client.currentScreen).showGroup("social", "cards"));
            after("the social tab scrolls to the game of 21", 1200, () -> shot("mobtalk-cards-15-f7.png", () -> {
                test.check(test.owner.client.currentScreen instanceof MobTalkSettingsScreen, "the settings stay open for the screenshot");
                test.owner.client.execute(() -> test.owner.client.setScreen(null));
                after("the settings close", 1500, this::finish);
            }));
        });
    }

    // ---- Подготовка мира для ручной проверки: к жителю в деревне, изумруды, колоды, алмаз; стол открывается. ----

    void prep(Runnable then) {
        next = then;
        if (test.owner.client.player != null && test.owner.client.player.isDead()) {
            test.log("the saved player is dead: respawning");
            test.owner.client.player.requestRespawn();
            test.owner.client.setScreen(null);
            after("respawn", 4000, () -> prep(then));
            return;
        }
        net.minecraft.server.world.ServerWorld world = (net.minecraft.server.world.ServerWorld) test.serverPlayer().getWorld();
        net.minecraft.util.math.BlockPos spot = io.github.mobtalk.game.StructureCapture.villageHouseSpot(world, test.serverPlayer().getBlockPos(), 100);
        if (spot == null) { test.fail("no village within 1600 blocks"); next.run(); return; }
        test.log("village house spot " + spot.toShortString());
        test.command("gamemode survival " + name());
        test.command("time set 1000"); test.command("weather clear");
        test.command("effect give " + name() + " minecraft:slow_falling 20 1 true");
        test.command("effect give " + name() + " minecraft:resistance 60 255 true");
        test.command("tp " + name() + " " + spot.getX() + " " + (spot.getY() + 4) + " " + spot.getZ());
        after("the village loads", 6000, () -> {
            VillagerEntity villager = onServer(() -> {
                var player = test.serverPlayer();
                return player.getWorld().getEntitiesByClass(VillagerEntity.class, player.getBoundingBox().expand(64), v -> v.isAlive() && !v.isBaby()
                        && !v.getVillagerData().getProfession().toString().contains("cleric")).stream()
                        .min(java.util.Comparator.comparingDouble(v -> v.squaredDistanceTo(player))).orElse(null);
            });
            if (villager == null) { test.fail("no grown villager near the village spot"); next.run(); return; }
            test.log("villager " + villager.getName().getString() + " at " + villager.getBlockPos().toShortString());
            var pos = villager.getBlockPos();
            // Игрок встаёт на клетку жителя (она свободна), житель — на ту же клетку: никто не застрянет в стене.
            test.command("tp " + name() + " " + (pos.getX() + 0.5) + " " + pos.getY() + " " + (pos.getZ() + 0.5) + " 90 10");
            test.command("spawnpoint " + name() + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            test.command("tp " + villager.getUuidAsString() + " " + (pos.getX() + 0.5) + " " + pos.getY() + " " + (pos.getZ() + 0.5));
            test.command("clear " + name()); // Тестовая копия мира: инвентарь освобождается под ставки.
            test.command("give " + name() + " minecraft:emerald 64");
            test.command("give " + name() + " mobtalk:card_deck 3");
            test.command("give " + name() + " minecraft:diamond 1");
            after("the player stands by the villager", 3000, () -> {
                String opened = onServer(() -> service().open(me(), villager.getId(), ""));
                test.check(opened.isEmpty(), "/mobtalk cards open works next to " + villager.getName().getString() + ": " + opened);
                test.await("the table screen opens", 10, () -> test.owner.client.currentScreen instanceof CardTableScreen && view() != null, () ->
                        after("the table settles", 2000, () -> shot("mobtalk-cards-prep.png", () -> {
                            test.check(emeralds() == 64, "64 emeralds in the inventory: " + emeralds());
                            CardTableClient.send("leave", 0, "");
                            test.owner.client.execute(() -> test.owner.client.setScreen(null));
                            after("the table closes", 2000, next);
                        })));
            });
        });
    }

    /** Окно 1600×900 и масштаб интерфейса {@code scale}. */
    private void window(int scale) {
        var client = test.owner.client;
        org.lwjgl.glfw.GLFW.glfwSetWindowSize(client.getWindow().getHandle(), 1600, 900);
        client.options.getGuiScale().setValue(scale);
        client.onResolutionChanged();
    }

    private void getUp(Runnable then) {
        CardTableClient.send("leave", 0, "");
        test.owner.client.execute(() -> test.owner.client.setScreen(null));
        test.await("the table closes when the only player gets up", 10, () -> !onServer(() -> service().playing(farmer)), then);
    }

    private void shot(String file, Runnable then) {
        ScreenshotRecorder.saveScreenshot(test.owner.client.runDirectory, file, test.owner.client.getFramebuffer(), text -> test.log("screenshot: " + text.getString()));
        after("after the screenshot", 300, then);
    }

    private void finish() {
        test.command("kill @e[tag=" + TAG + "]");
        next.run();
    }
}
