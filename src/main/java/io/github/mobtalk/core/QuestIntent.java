package io.github.mobtalk.core;

/** The mob's quest move: OFFER hands the player a quest from its species list (it becomes active at once),
 * COMPLETE claims the active quest is done — the game verifies items or kills before any reward. */
public record QuestIntent(Type type, String questId) {
    public enum Type { NONE, OFFER, COMPLETE }
    public QuestIntent {
        if (type == null) type = Type.NONE;
        questId = questId == null ? "" : questId;
        if (type == Type.NONE) questId = "";
    }
    public static QuestIntent none() { return new QuestIntent(Type.NONE, ""); }
}
