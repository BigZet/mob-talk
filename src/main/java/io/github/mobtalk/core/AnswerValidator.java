package io.github.mobtalk.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a completion into an {@link OpenRouterConversationClient.Answer} the game may act on: every intent is checked against
 * the snapshot the model saw, and a reply that promises what the game will not do is rejected with a hint for the correction. */
final class AnswerValidator {
    private AnswerValidator() { }

    /** A reply the game must not voice; the model gets one or two corrections. trade=false for language or empty replies;
     * own=true when the mob's own deal, secret or favour failed rather than the villager window. */
    static final class Rejected extends IOException {
        final boolean trade, own;
        Rejected(String hint) { this(hint, true); }
        Rejected(String hint, boolean trade) { this(hint, trade, false); }
        Rejected(String hint, boolean trade, boolean own) { super(hint); this.trade = trade; this.own = own; }
    }

    /** What the parser needs beyond the bytes: limits from the config and the memory the facts are appended to. */
    record Bounds(int words, String previousMemory, int memoryLimit, int relationship, boolean jsonObjectOnly, String service, boolean overheard, boolean wary,
                  java.util.List<String> recentReplies, SocialTurn social) {
        /** {@code social}: the gestures on offer with their outcomes ({@link SocialTurn}); null outside the social layer. */
        Bounds(int words, String previousMemory, int memoryLimit, int relationship, boolean jsonObjectOnly, String service, boolean overheard, boolean wary) {
            this(words, previousMemory, memoryLimit, relationship, jsonObjectOnly, service, overheard, wary, java.util.List.of(), null);
        }
        Bounds(int words, String previousMemory, int memoryLimit, int relationship, boolean jsonObjectOnly, String service) {
            this(words, previousMemory, memoryLimit, relationship, jsonObjectOnly, service, false);
        }
        /** {@code wary}: the mob's state ({@link MobState.View#refusesDeals()}) forbids any concession in this answer. */
        Bounds(int words, String previousMemory, int memoryLimit, int relationship, boolean jsonObjectOnly, String service, boolean overheard) {
            this(words, previousMemory, memoryLimit, relationship, jsonObjectOnly, service, overheard, false);
        }
    }

    static OpenRouterConversationClient.Answer parse(byte[] bytes, MobSnapshot snapshot, Bounds bounds) throws IOException {
        TradingView view = TradingView.of(snapshot);
        try {
            JsonObject response = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            var content = response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content");
            if (!content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString()) throw new IllegalArgumentException();
            JsonObject answer = JsonParser.parseString(content.getAsString()).getAsJsonObject();
            if (bounds.jsonObjectOnly() && (!answer.has("action") || !answer.has("socialIntent") || !answer.has("bargain")))
                throw new IllegalArgumentException();
            String reply = TextBounds.reply(spokenText(ReplySchema.string(answer, "reply")), bounds.words());
            // newFacts becomes one fact with its importance (MemoryRanking) and memory stays null: the store appends to what it holds, so a
            // summary or reflection that landed meanwhile survives. A full "memory" from an older or fallback answer still replaces it as before.
            String facts = answer.has("newFacts") ? TextBounds.characters(ReplySchema.string(answer, "newFacts"), MemoryNotes.FACTS_LIMIT) : null;
            String memory = facts != null ? null : TextBounds.characters(ReplySchema.string(answer, "memory"), bounds.memoryLimit());
            MemoryFact fact = facts == null || facts.isBlank() ? null : MemoryFact.of(facts, ReplySchema.importance(answer), System.currentTimeMillis());
            if (bounds.overheard() && SpeechTags.strip(reply).codePoints().noneMatch(Character::isLetter)) return new OpenRouterConversationClient.Answer("", memory, SocialIntent.NONE,
                    answer.has("action") && answer.get("action").isJsonObject() ? validateAction(answer.getAsJsonObject("action"), snapshot) : ActionIntent.none(),
                    BargainIntent.none(), DealIntent.none(), "", QuestIntent.none());
            if (reply.isBlank()) throw new IllegalArgumentException();
            if (SpeechTags.strip(reply).codePoints().noneMatch(Character::isLetter))
                throw new Rejected("Реплика пустая: в reply нет ни одного слова. Скажи вслух нормальную фразу по характеру.", false);
            // DeepSeek answered about 3% of lines in English despite the rule; such a line must not be voiced to a Russian player.
            if (foreignWords(SpeechTags.strip(reply), snapshot) >= 3)
                throw new Rejected("Реплика не на русском. Скажи ту же мысль по-русски, своими словами и в своей манере.", false);
            String echoed = repeated(reply, bounds.recentReplies());
            if (echoed != null) throw new Rejected("Ты почти дословно повторяешь свою недавнюю реплику («" + TextBounds.characters(echoed, 80)
                    + "»). Скажи иначе: другими словами, короче, с другой мыслью — или одним междометием.", false);
            SocialIntent social = SocialIntent.NONE;
            // The player's words were not addressed to a bystander: it neither judges them nor trades on them.
            if (bounds.overheard()) return new OpenRouterConversationClient.Answer(reply, memory, SocialIntent.NONE,
                    answer.has("action") && answer.get("action").isJsonObject() ? validateAction(answer.getAsJsonObject("action"), snapshot) : ActionIntent.none(),
                    BargainIntent.none(), DealIntent.none(), "", QuestIntent.none());
            if (answer.has("socialIntent")) { try { social = SocialIntent.valueOf(ReplySchema.string(answer, "socialIntent").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException ignored) { } }
            ActionIntent action = answer.has("action") && answer.get("action").isJsonObject() ? validateAction(answer.getAsJsonObject("action"), snapshot) : ActionIntent.none();
            BargainIntent bargain = BargainIntent.none();
            if (answer.has("bargain")) {
                if (!answer.get("bargain").isJsonObject()) throw new IllegalArgumentException();
                JsonObject requested = answer.getAsJsonObject("bargain");
                bargain = validateBargain(requested, snapshot);
                String type = ReplySchema.string(requested, "type").toUpperCase(Locale.ROOT);
                boolean ordinaryRefusal = "DECLINE".equals(type) && !view.windowOpen();
                if (!"NONE".equals(type) && bargain.type() == BargainIntent.Type.NONE && !ordinaryRefusal)
                    throw new Rejected(otherRow(requested, view)); // Do not voice a promise whose price intent was rejected.
                // Too shaken or distrustful to concede: the refusal must be spoken, not a price the window would then show.
                if (bounds.wary() && bargain.type() == BargainIntent.Type.OFFER) throw new Rejected(PromptTexts.WARY_TRADE);
            }
            DealIntent deal = DealIntent.none();
            if (answer.has("deal") && answer.get("deal").isJsonObject()) {
                JsonObject requested = answer.getAsJsonObject("deal");
                deal = validateDeal(requested, snapshot);
                // A refusal changes nothing, so a DECLINE on an unknown deal is ordinary dialogue; a dropped OFFER would voice a false promise.
                if (deal.type() == DealIntent.Type.NONE && requested.get("type") instanceof JsonPrimitive type && type.isString()
                        && "OFFER".equalsIgnoreCase(type.getAsString()))
                    throw new Rejected(dealRejection(requested, view), true, true);
                if (bounds.wary() && deal.type() == DealIntent.Type.OFFER) throw new Rejected(PromptTexts.WARY_TRADE, true, true);
            }
            String unlock = answer.has("unlock") && answer.get("unlock").isJsonPrimitive() ? validateUnlock(ReplySchema.string(answer, "unlock"), view, bounds.relationship()) : "";
            QuestIntent quest = answer.has("quest") && answer.get("quest").isJsonObject() ? validateQuest(answer.getAsJsonObject("quest"), view, reply) : QuestIntent.none();
            Map<?,?> active = view.activeFavor();
            if (quest.type() != QuestIntent.Type.COMPLETE && active != null && claimsDelivery(reply))
                throw new Rejected("В реплике ты принимаешь услугу («" + active.get("title") + "»), но quest не COMPLETE — игра ничего не забрала. "
                        + (Boolean.TRUE.equals(active.get("canComplete")) ? "Если принимаешь — верни quest COMPLETE."
                        : "Игрок ещё не принёс нужного (" + active.get("have") + " из " + active.get("count") + "): не говори, что принял или засчитал."), true, true);
            if (view.villager() && !view.villagerOffers() && !view.ownTrading() && promisesDiscount(reply)) throw new Rejected(PromptTexts.NO_GOODS);
            int unbacked = unbackedPrice(reply, bargain, deal, snapshot);
            if (unbacked >= 0) {
                boolean own = view.ownDeals() && !view.villager();
                throw new Rejected("В реплике названа цена " + unbacked + ", но " + (own ? "deal" : "bargain") + " её не применяет, и игра её не покажет. "
                        + (own ? "Либо верни deal OFFER с ценой из allowedPrices этой сделки и назови в реплике ровно её, либо не называй новую цену."
                        : view.windowOpen() ? "Либо верни OFFER с ценой из allowedPrices и назови в реплике ровно её, либо не называй новую цену."
                        : view.spokenAvailable() ? "Либо верни bargain OFFER с offerIndex нужной строки и ценой из её allowedPrices и назови в реплике ровно её, либо не называй новую цену."
                        : "Окно торговли закрыто: не называй новую цену, а предложи открыть торговлю и выбрать товар."), true, own);
            }
            // The thought is optional: an older or fallback answer without it keeps the mob's previous one.
            GestureIntent gesture = gesture(answer, bounds.social());
            checkGesture(reply, gesture);
            checkTender(reply, bounds.social());
            return new OpenRouterConversationClient.Answer(reply, memory, social, action, bargain, deal, unlock, quest, biography(answer),
                    MobState.cleanThought(ReplySchema.optional(answer, "thought")), fact, gesture);
        } catch (Rejected invalid) { throw invalid; }
        catch (RuntimeException malformed) { throw new IOException(bounds.service() + " вернул неверный ответ. Повторите вопрос."); }
    }

    /** The gesture the model named, with the outcome decided before the request; none when absent, unknown or not on offer. */
    static GestureIntent gesture(JsonObject answer, SocialTurn social) {
        if (social == null || !answer.has("gesture")) return GestureIntent.none();
        return social.intent(Gesture.of(ReplySchema.optional(answer, "gesture")));
    }

    /** A reply that contradicts the decided outcome of a gesture where words can be checked at all: see {@link Romance}. */
    static void checkGesture(String reply, GestureIntent gesture) throws Rejected {
        if (gesture == null || reply == null) return;
        boolean courting = gesture.gesture() == Gesture.DATE || gesture.gesture() == Gesture.PROPOSE;
        if (courting && gesture.outcome() != Gesture.Outcome.ACCEPTED && CONSENT.matcher(reply).find())
            throw new Rejected(SocialPrompt.consentRejection(gesture.note()), false);
    }
    /** Трек N: в особый вечер ответ, ушедший в откровенность, переписывается (а если и повтор не вышел — звучит запасная реплика). */
    static void checkTender(String reply, SocialTurn social) throws Rejected {
        if (social == null || social.facts().stream().noneMatch(fact -> fact.startsWith(SpecialEvening.MARK))) return;
        if (SpecialEvening.explicit(reply)) throw new Rejected(SpecialEvening.REJECTION, false);
    }
    /** Words of consent to courtship or marriage, not preceded by a negation. */
    private static final Pattern CONSENT = Pattern.compile("(?<!не )(согласн\\p{L}*|выйду за|"
            + "стану тво\\p{L}+|давай встречаться|"
            + "теперь мы пара|женюсь)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** The life the mob invented inside its reply, or null when the answer has none or the story is blank. */
    static BiographyWriter.Biography biography(JsonObject answer) {
        if (!answer.has("biography") || !answer.get("biography").isJsonObject()) return null;
        JsonObject life = answer.getAsJsonObject("biography");
        String story = TextBounds.characters(ReplySchema.optional(life, "story"), 1500);
        if (story.isBlank()) return null;
        return new BiographyWriter.Biography(TextBounds.characters(ReplySchema.optional(life, "name"), 60), story,
                TextBounds.characters(ReplySchema.optional(life, "desire"), 400), TextBounds.characters(ReplySchema.optional(life, "secret"), 400),
                TextBounds.characters(ReplySchema.optional(life, "villageName"), 60), TextBounds.characters(ReplySchema.optional(life, "villageStory"), 1500));
    }

    static ActionIntent validateAction(JsonObject action, MobSnapshot snapshot) {
        try {
            ActionType type = ActionType.valueOf(ReplySchema.string(action, "type").toUpperCase(Locale.ROOT));
            if (type == ActionType.NONE || !snapshot.availableActions().contains(type)) return ActionIntent.none();
            if (type == ActionType.FLEE && Boolean.TRUE.equals(snapshot.mobDetails().get("trapped"))) return ActionIntent.none(); // Nowhere to run.
            if (type == ActionType.STOP) return new ActionIntent(type, "");
            if (FriendActions.isFriendAction(type)) return FriendActions.validate(type, ReplySchema.string(action, "target"), snapshot);
            String target = ReplySchema.string(action, "target");
            if (type == ActionType.FOLLOW && !target.equals("player")) return ActionIntent.none();
            boolean known = target.equals("player") ? snapshot.playerId() != null : target.matches("e[0-9]+") && snapshot.observedEntities().stream()
                    .anyMatch(entity -> entity.key().equals(target) && (entity.visible()
                            || "minecraft:warden".equals(snapshot.type()) && Boolean.TRUE.equals(entity.details().get("audible")))
                            && entity.id() != null && Double.isFinite(entity.distance()) && entity.distance() >= 0);
            return known ? new ActionIntent(type, target) : ActionIntent.none();
        } catch (RuntimeException invalid) { return ActionIntent.none(); }
    }

    /** The model may propose only an existing selected offer; the game still applies authoritative rules. */
    static BargainIntent validateBargain(JsonObject bargain, MobSnapshot snapshot) {
        TradingView view = TradingView.of(snapshot);
        try {
            BargainIntent.Type type = BargainIntent.Type.valueOf(ReplySchema.string(bargain, "type").toUpperCase(Locale.ROOT));
            if (type == BargainIntent.Type.NONE) return BargainIntent.none();
            // Window shut: a promise on one of the villager's rows (SpokenOffer) instead of a price in the open window.
            if (!view.windowOpen()) return view.spokenAvailable() ? SpokenOffer.validate(view.spoken(), type.name(), ReplySchema.integer(bargain, "offerIndex"),
                    bargain.has("price") ? ReplySchema.integer(bargain, "price") : -1) : BargainIntent.none();
            Map<?,?> context = view.bargaining();
            if (!view.permittedBargainTypes().contains(type.name())) return BargainIntent.none();
            int selected = TradingView.integer(context.get(context.containsKey("selectedIndex") ? "selectedIndex" : "selectedOffer"));
            int index = ReplySchema.integer(bargain, "offerIndex");
            if (selected < 0 || index != selected) return BargainIntent.none();
            if (type == BargainIntent.Type.DECLINE) return new BargainIntent(type, selected, 0);
            if (!Boolean.TRUE.equals(context.get("available"))) return BargainIntent.none();
            int vanilla = TradingView.integer(context.get("vanillaCount"));
            if (type == BargainIntent.Type.OFFER) {
                // price is what the villager says aloud; older answers may still carry a discount from vanillaCount.
                int discount = bargain.has("price") ? vanilla - ReplySchema.integer(bargain, "price") : ReplySchema.integer(bargain, "discount");
                if (discount <= 0 || discount > TradingView.integer(context.get("maximumDiscount")) || discount >= vanilla
                        || !TradingView.allowedPrice(context, vanilla - discount)) return BargainIntent.none();
                return new BargainIntent(type, selected, discount);
            }
            if (!(context.get("state") instanceof Map<?,?> state) || !"PENDING".equals(state.get("status"))
                    || TradingView.integer(state.get("offerIndex")) != selected || TradingView.integer(state.get("vanillaCount")) != vanilla
                    || !(context.get("fingerprint") instanceof String key) || !key.equals(state.get("key"))
                    || TradingView.wholeNumber(state.get("expiresTick")) <= TradingView.wholeNumber(context.get("serverTick"))) return BargainIntent.none();
            int pendingPrice = TradingView.integer(state.get("price")), pendingDiscount = TradingView.integer(state.get("discount"));
            if (pendingDiscount <= 0 || pendingDiscount >= vanilla || pendingPrice != vanilla - pendingDiscount
                    || !TradingView.allowedPrice(context, pendingPrice)) return BargainIntent.none();
            // ACCEPT always consumes the stored pending price; the model's discount is never used.
            return new BargainIntent(BargainIntent.Type.ACCEPT, selected, 0);
        } catch (RuntimeException invalid) { return BargainIntent.none(); }
    }

    /** The usual reason in play: the player names a different good than the row selected in the trade window. */
    private static String otherRow(JsonObject requested, TradingView view) {
        try {
            int asked = ReplySchema.integer(requested, "offerIndex");
            if (view.spokenAvailable()) return SpokenOffer.rejection(view.spoken(), asked, requested.get("price"));
            if (view.bargaining() != null && view.bargaining().get("selectedIndex") instanceof Number selected && asked != selected.intValue())
                return "Ты пытался уступить по строке " + asked + ", а в окне выбрана строка " + selected + ": скидка возможна только по выбранной. "
                        + "Если игрок говорит о другом товаре, попроси выбрать его строку в окне торговли и цену не называй.";
        } catch (RuntimeException unreadable) { /* Fall back to the general correction. */ }
        return "";
    }

    /** The model may price only an existing visible deal within its allowedPrices; the game still applies Trading.permitted. */
    static DealIntent validateDeal(JsonObject deal, MobSnapshot snapshot) {
        TradingView view = TradingView.of(snapshot);
        try {
            DealIntent.Type type = DealIntent.Type.valueOf(ReplySchema.string(deal, "type").toUpperCase(Locale.ROOT));
            if (type == DealIntent.Type.NONE || snapshot.playerId() == null || !view.permittedDealTypes().contains(type.name())) return DealIntent.none();
            String id = ReplySchema.string(deal, "id");
            Map<?,?> known = view.ownDeal(id);
            if (known == null) return DealIntent.none();
            if (type == DealIntent.Type.DECLINE) return new DealIntent(type, id, 0);
            int price = ReplySchema.integer(deal, "price");
            if (!Boolean.TRUE.equals(known.get("canOffer")) || !TradingView.allowedPrice(known, price)) return DealIntent.none();
            return new DealIntent(type, id, price);
        } catch (RuntimeException invalid) { return DealIntent.none(); }
    }

    private static String dealRejection(JsonObject requested, TradingView view) {
        try {
            String id = ReplySchema.string(requested, "id");
            Map<?,?> deal = view.ownDeal(id);
            if (deal == null) return "Сделки с id «" + id + "» у тебя нет; твои сделки: " + TradingView.ids(view.deals())
                    + ". Если игрок хочет то, чего у тебя нет, скажи это по характеру (deal NONE).";
            if (!Boolean.TRUE.equals(deal.get("canOffer")) || !view.permittedDealTypes().contains("OFFER"))
                return "По сделке «" + id + "» уступка сейчас невозможна: " + deal.get("reason") + ". Текущая цена " + deal.get("currentPrice")
                        + ". Верни NONE или DECLINE и не обещай скидку.";
            return "Цена " + requested.get("price") + " по сделке «" + id + "» недопустима: разрешены только allowedPrices " + deal.get("allowedPrices")
                    + " (сейчас " + deal.get("currentPrice") + ", не ниже minPrice " + deal.get("minPrice") + "). Назови одну из них через OFFER или откажи через DECLINE.";
        } catch (RuntimeException unreadable) { return "Поле deal заполнено неверно: нужны type, id существующей сделки и целая price из её allowedPrices."; }
    }

    /** A secret deal opens only when the game says it may; otherwise the mob is told to hint without opening. */
    private static String validateUnlock(String id, TradingView view, int relationship) throws Rejected {
        if (id == null || id.isBlank()) return "";
        Map<?,?> secret = view.secret(id);
        if (secret == null) {
            if (view.ownDeal(id) != null) return ""; // Already open: nothing to unlock, nothing to correct.
            throw new Rejected("Секретной сделки «" + id + "» у тебя нет (есть: " + TradingView.ids(view.secrets()) + "); unlock оставь пустым.", true, true);
        }
        if (Boolean.TRUE.equals(secret.get("canUnlockNow"))) return id;
        StringBuilder why = new StringBuilder("Сделка «" + id + "» ещё закрыта: ");
        if (secret.get("unlockRelationship") instanceof Number needed && relationship < needed.intValue())
            why.append("отношение ").append(relationship).append(" из ").append(needed).append("; ");
        if (Boolean.TRUE.equals(secret.get("requiresFavor"))) why.append("сначала игрок должен оказать тебе услугу; ");
        if (secret.get("reason") instanceof String reason && !reason.isBlank()) why.append(reason).append("; ");
        throw new Rejected(why + "намекни, что есть кое-что для своих, но не открывай и товар не называй (unlock \"\").", true, true);
    }

    /** OFFER only while no favour is pending and only for one of the drawn candidates (whose count wins); COMPLETE only when the game counted the items. */
    private static QuestIntent validateQuest(JsonObject quest, TradingView view, String reply) throws Rejected {
        QuestIntent.Type type;
        try { type = QuestIntent.Type.valueOf(ReplySchema.string(quest, "type").toUpperCase(Locale.ROOT)); }
        catch (RuntimeException malformed) { return QuestIntent.none(); }
        if (type == QuestIntent.Type.NONE) return QuestIntent.none();
        Map<?,?> favor = view.favor(), active = view.activeFavor();
        if (favor == null) return QuestIntent.none();
        if (type == QuestIntent.Type.COMPLETE) {
            if (active == null) throw new Rejected("Игрок тебе сейчас ничего не должен: принимать нечего — quest NONE.", true, true);
            if (Boolean.TRUE.equals(active.get("canComplete"))) return QuestIntent.complete();
            throw new Rejected("Игрок ещё не принёс нужного: у него " + active.get("have") + " из " + active.get("count") + "× " + active.get("itemName")
                    + ". Не принимай услугу «" + active.get("title") + "» (quest NONE), а в реплике скажи, чего не хватает.", true, true);
        }
        if (active != null) throw new Rejected("Игрок уже должен тебе услугу «" + active.get("title") + "» (" + active.get("have") + " из "
                + active.get("count") + "× " + active.get("itemName") + "): новую не проси — quest NONE, а в реплике напомни, чего ждёшь.", true, true);
        String item, title, reward; int percent;
        try { item = ReplySchema.string(quest, "item"); title = ReplySchema.string(quest, "title"); reward = ReplySchema.string(quest, "rewardDeal"); ReplySchema.integer(quest, "count"); percent = ReplySchema.integer(quest, "rewardPercent"); }
        catch (RuntimeException malformed) { throw new Rejected("Поле quest заполнено неверно: для OFFER нужны item (id предмета), count (1–64), title, rewardDeal, rewardPercent.", true, true); }
        Map<?,?> candidate = null;
        for (Object value : view.candidates()) if (value instanceof Map<?,?> row && item.equals(row.get("item"))) candidate = row;
        if (candidate == null || !Trading.itemExists(item))
            throw new Rejected("Предмет «" + item + "» сейчас просить нельзя: выбери один из trading.favor.candidates — " + MobPrompt.candidateList(view)
                    + " (item и count ровно как там) — или ничего не проси (quest NONE).", true, true);
        int count = TradingView.integer(candidate.get("count")); // The game's amount, whatever the model wrote.
        if (!reward.isEmpty() && view.ownDeal(reward) == null && view.secret(reward) == null)
            throw new Rejected("Сделки «" + reward + "» у тебя нет: в rewardDeal только id из deals " + TradingView.ids(view.deals())
                    + " или secret " + TradingView.ids(view.secrets()) + ", либо пустая строка.", true, true);
        if (reward.isEmpty() && percent > 0) throw new Rejected("rewardPercent без rewardDeal бессмыслен: назови сделку или поставь 0.", true, true);
        // The item is the game's, the story is the model's: a request for melon slices told as «find my lost book» leaves the player
        // carrying the wrong thing. The spoken line must name what is asked for, and a title about something else is replaced.
        String itemName = String.valueOf(candidate.get("itemName"));
        if (!mentions(SpeechTags.strip(reply), itemName))
            throw new Rejected("Ты просишь " + count + "× " + itemName + " (quest OFFER), но в реплике об этом ни слова. Скажи прямо, что тебе нужно именно это и зачем; "
                    + "если твоя беда про другую вещь, которой нет в trading.favor.candidates, ничего не проси — quest NONE.", true, true);
        if (!mentions(title, itemName)) title = itemName;
        return new QuestIntent(type, item, count, title, reward, Math.max(0, Math.min(Favor.MAX_PERCENT, percent)));
    }
    /** The text names the item: some word of its display name, by its stem («Ломтик арбуза» → «ломтики», «арбуз»). */
    static boolean mentions(String text, String itemName) {
        if (text == null || itemName == null) return false;
        String lower = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (String word : itemName.toLowerCase(Locale.ROOT).replace('ё', 'е').split("[^\\p{L}]+")) {
            if (word.length() < 3) continue;
            String stem = word.substring(0, word.length() <= 4 ? 3 : Math.max(4, word.length() - 2));
            if (lower.contains(stem)) return true;
        }
        return false;
    }

    // ---- Text checks on the spoken line

    private static final Pattern LATIN_WORD = Pattern.compile("\\b[A-Za-z]{3,}\\b");
    /** Latin words of three letters or more, not counting the player's own name. */
    static int foreignWords(String reply, MobSnapshot snapshot) {
        Object name = snapshot.playerDetails().get("name");
        Matcher words = LATIN_WORD.matcher(reply);
        int count = 0;
        while (words.find()) if (!(name instanceof String player && words.group().equalsIgnoreCase(player))) count++;
        return count;
    }

    /** Drops stray punctuation before the first word and quotes wrapped around the whole line. */
    /** The recent own line this reply mostly repeats (word sets overlap by {@link #REPEAT_OVERLAP} or more), else null.
     * Short replies («Да.», «Уходи.») are allowed to recur: only lines of four or more content words count. */
    static final double REPEAT_OVERLAP = 0.6;
    static String repeated(String reply, java.util.List<String> recent) {
        java.util.Set<String> words = contentWords(reply);
        if (words.size() < 4 || recent == null) return null;
        for (String previous : recent) {
            java.util.Set<String> other = contentWords(previous);
            if (other.size() < 4) continue;
            long common = words.stream().filter(other::contains).count();
            double overlap = (double) common / Math.min(words.size(), other.size());
            if (overlap >= REPEAT_OVERLAP) return previous;
        }
        return null;
    }
    private static java.util.Set<String> contentWords(String text) {
        java.util.Set<String> words = new java.util.HashSet<>();
        if (text == null) return words;
        for (String raw : text.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) if (raw.length() >= 3) words.add(raw);
        return words;
    }

    static String spokenText(String reply) {
        // A leading "[" opens an intonation tag (SpeechTags), not stray punctuation.
        String text = reply.strip().replaceFirst("^(?:(?!\\[)[\\p{Punct}\\s«])+(?=[\\p{L}\\d\\[])", "");
        if (reply.strip().startsWith(":") || reply.strip().startsWith("«")) text = text.replaceFirst("[»\"]\\s*$", "");
        // A closing quote with no opening one ("Лучший песик»?") is a typo the voice would read as nothing anyway.
        if (text.indexOf('»') >= 0 && text.indexOf('«') < 0) text = text.replace("»", "");
        return text.strip();
    }

    private static final Pattern DELIVERY_TALK = Pattern.compile("(?<!не )(?<!не\\s)(принимаю|засчитываю|засчитано|услуг[аи] оказан|дело сделано|выполнен[оа]\\b|принял[аи]?\\b)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** "Принимаю!" with nothing taken is the favour-world version of a voiced price the window never charged. */
    static boolean claimsDelivery(String reply) { return reply != null && DELIVERY_TALK.matcher(reply).find(); }

    /** «Сделаю скидку», «отдам дешевле»: обещание уступки, а не отказ («скидку не дам»). */
    private static final Pattern DISCOUNT_PROMISE = Pattern.compile("(?<!не )(?<!не\s)(сделаю|сделаем|дам|даю|отдам|уступлю|сбавлю|скину|могу)\s+(тебе\s+|вам\s+)?(скидк|дешевл|подешевле|уступ)"
            + "|со\s+скидкой|скидк[ау]\s+(тебе|вам)\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** A villager with nothing to sell (no profession or no offers yet) promised a discount. */
    static boolean promisesDiscount(String reply) { return reply != null && DISCOUNT_PROMISE.matcher(reply).find(); }

    private static final Pattern PRICE_TALK = Pattern.compile("скид|уступ|дешевл|сбав|скин|договорил|обновил|теперь|продам|отдам за|стоит|цен[аыу]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\d]+");
    private static final Map<String,Integer> NUMBER_WORDS = numberWords();

    private static Map<String,Integer> numberWords() {
        Map<String,Integer> words = new HashMap<>();
        String[][] forms = {
                {"1", "один", "одна", "одно", "одну", "одного", "одной"}, {"2", "два", "две", "двух", "двум", "двумя"},
                {"3", "три", "трех", "трем", "тремя"}, {"4", "четыре", "четырех", "четырем", "четырьмя"},
                {"5", "пять", "пяти", "пятью"}, {"6", "шесть", "шести", "шестью"}, {"7", "семь", "семи"},
                {"8", "восемь", "восьми", "восемью"}, {"9", "девять", "девяти", "девятью"}, {"10", "десять", "десяти", "десятью"},
                {"40", "сорок", "сорока"}, {"50", "пятьдесят", "пятидесяти"}, {"60", "шестьдесят", "шестидесяти"}};
        for (String[] form : forms) for (int i = 1; i < form.length; i++) words.put(form[i], Integer.parseInt(form[0]));
        String[] stems = {"одиннадцат", "двенадцат", "тринадцат", "четырнадцат", "пятнадцат", "шестнадцат", "семнадцат", "восемнадцат",
                "девятнадцат", "двадцат", "тридцат"};
        int[] values = {11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 30};
        for (int i = 0; i < stems.length; i++) for (String ending : List.of("ь", "и", "ью")) words.put(stems[i] + ending, values[i]);
        return Map.copyOf(words);
    }

    /** Numbers in order, digits or Russian words; "двадцать две" becomes 22. */
    static List<Integer> spokenNumbers(String text) {
        List<Integer> numbers = new ArrayList<>();
        Matcher tokens = TOKEN.matcher(text.toLowerCase(Locale.ROOT).replace('ё', 'е'));
        boolean tens = false;
        while (tokens.find()) {
            String token = tokens.group();
            Integer value = token.chars().allMatch(Character::isDigit) ? (token.length() <= 4 ? Integer.valueOf(token) : null) : NUMBER_WORDS.get(token);
            if (value == null) { tens = false; continue; }
            if (tens && value < 10) numbers.set(numbers.size() - 1, numbers.getLast() + value);
            else numbers.add(value);
            tens = value >= 20 && value % 10 == 0;
        }
        return numbers;
    }

    /** Flash models voiced "now 18" with bargain NONE, or "eight" while the window got 19. The final number named is the price the
     * player hears; returns it when the trade window would charge something else, or -1. */
    static int unbackedPrice(String reply, BargainIntent bargain, MobSnapshot snapshot) { return unbackedPrice(reply, bargain, DealIntent.none(), snapshot); }
    static int unbackedPrice(String reply, BargainIntent bargain, DealIntent deal, MobSnapshot snapshot) {
        TradingView view = TradingView.of(snapshot);
        boolean villager = view.villager();
        if (reply == null || !villager && !view.ownDeals() || !PRICE_TALK.matcher(reply).find()) return -1;
        Set<Integer> honest = new HashSet<>(Set.of(1));
        for (Object value : view.deals()) if (value instanceof Map<?,?> own) {
            addCounts(honest, own, "sell", "price");
            for (String field : List.of("basePrice", "currentPrice")) if (own.get(field) instanceof Number count) honest.add(count.intValue());
        }
        if (deal.type() == DealIntent.Type.OFFER) honest.add(deal.price());
        // "Все восемь! Загон стоит" names a favour count, not a price.
        Map<?,?> active = view.activeFavor();
        if (active != null) for (String field : List.of("count", "have")) if (active.get(field) instanceof Number number) honest.add(number.intValue());
        for (Object value : view.candidates()) if (value instanceof Map<?,?> candidate && candidate.get("count") instanceof Number number) honest.add(number.intValue());
        if (!villager) {
            List<Integer> numbers = spokenNumbers(reply);
            return numbers.isEmpty() || honest.contains(numbers.getLast()) ? -1 : numbers.getLast();
        }
        for (Object value : view.villagerOfferRows()) if (value instanceof Map<?,?> offer) addCounts(honest, offer, "buy", "secondBuy", "sell");
        Map<?,?> context = view.bargaining();
        if (context != null) {
            addCounts(honest, context, "firstBuy", "secondBuy", "sell");
            for (String field : List.of("vanillaCount", "currentPrice", "baseCount")) if (context.get(field) instanceof Number count) honest.add(count.intValue());
            if (context.get("state") instanceof Map<?,?> state && state.get("price") instanceof Number price
                    && ("ACCEPTED".equals(state.get("status")) || bargain.type() == BargainIntent.Type.ACCEPT)) honest.add(price.intValue());
            if (bargain.type() == BargainIntent.Type.OFFER && context.get("vanillaCount") instanceof Number vanilla)
                honest.add(vanilla.intValue() - bargain.discount());
        }
        honest.addAll(SpokenOffer.honestPrices(view.spoken(), bargain)); // The promise with the window shut, and every row's price.
        List<Integer> numbers = spokenNumbers(reply);
        // Echoing the player's own number ("Шесть? Ладно, восемь") is fine; the last number is the one that sticks.
        return numbers.isEmpty() || honest.contains(numbers.getLast()) ? -1 : numbers.getLast();
    }
    private static void addCounts(Set<Integer> counts, Map<?,?> source, String... fields) {
        for (String field : fields) if (source.get(field) instanceof Map<?,?> item && item.get("count") instanceof Number count) counts.add(count.intValue());
    }
}
