package io.github.mobtalk.core;

/** A favour a mob of this species may ask of the player, from trades.json. BRING: hand over {@code count} of item {@code target};
 * KILL: kill {@code count} entities of type {@code target}. {@code pitch} is how the mob describes it, in its own words. */
public record QuestDefinition(String id, String title, Kind kind, String target, int count, String pitch, Reward reward) {
    public enum Kind { BRING, KILL }
    /** What completing the quest changes: relationship points, a secret deal opened, a deal priced down by a share of its current price. */
    public record Reward(int relationship, String unlockDeal, String discountDeal, int discountPercent) {
        public Reward {
            unlockDeal = unlockDeal == null ? "" : unlockDeal;
            discountDeal = discountDeal == null ? "" : discountDeal;
            discountPercent = Math.max(0, Math.min(90, discountPercent));
        }
    }
    public QuestDefinition {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title");
        if (kind == null) throw new IllegalArgumentException("kind");
        if (target == null || target.isBlank()) throw new IllegalArgumentException("target");
        if (count < 1) throw new IllegalArgumentException("count");
        pitch = pitch == null ? "" : pitch;
        if (reward == null) reward = new Reward(0, "", "", 0);
    }
}
