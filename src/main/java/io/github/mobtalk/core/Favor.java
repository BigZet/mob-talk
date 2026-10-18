package io.github.mobtalk.core;

import java.util.UUID;

/** A favour a mob asked of the player in conversation: bring {@code count} of item {@code item}. The model invents it
 * (and the reason, voiced in the reply); the game only verifies the items. {@code rewardDeal} is the mob's own deal it
 * promised: a locked secret opens, and {@code rewardPercent} lowers its price permanently. One active favour per mob
 * and player; only the mob that asked accepts the delivery. */
public record Favor(String item, int count, String title, String rewardDeal, int rewardPercent, UUID giver, long startedMillis) {
    public static final int MAX_COUNT = 64, MAX_PERCENT = 50, RELATIONSHIP = 25;
    public Favor {
        if (item == null || !item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("item");
        if (count < 1 || count > MAX_COUNT) throw new IllegalArgumentException("count");
        title = title == null || title.isBlank() ? count + "× " + item.substring(item.indexOf(':') + 1) : title.strip();
        if (title.length() > 60) title = title.substring(0, 60).strip();
        rewardDeal = rewardDeal == null ? "" : rewardDeal;
        rewardPercent = Math.max(0, Math.min(MAX_PERCENT, rewardPercent));
    }
}
