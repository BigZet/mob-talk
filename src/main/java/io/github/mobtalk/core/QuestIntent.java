package io.github.mobtalk.core;

/** The mob's move on favours it invents itself. OFFER asks the player to bring {@code count} of item {@code item} (the reason is
 * voiced in the reply; {@code title} names the favour; {@code rewardDeal}/{@code rewardPercent} is what the mob promises from its
 * own deals). COMPLETE claims the active favour was delivered — the game counts and takes the items before any reward. */
public record QuestIntent(Type type, String item, int count, String title, String rewardDeal, int rewardPercent) {
    public enum Type { NONE, OFFER, COMPLETE }
    public QuestIntent {
        if (type == null) type = Type.NONE;
        item = item == null ? "" : item; title = title == null ? "" : title; rewardDeal = rewardDeal == null ? "" : rewardDeal;
        if (type != Type.OFFER) { item = ""; count = 0; title = ""; rewardDeal = ""; rewardPercent = 0; }
    }
    public static QuestIntent none() { return new QuestIntent(Type.NONE, "", 0, "", "", 0); }
    public static QuestIntent complete() { return new QuestIntent(Type.COMPLETE, "", 0, "", "", 0); }
    /** The favour this intent asks for, with the giver and time filled in by the store. */
    public Favor favor() { return new Favor(item, count, title, rewardDeal, rewardPercent, null, 0); }
}
