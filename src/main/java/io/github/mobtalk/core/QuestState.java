package io.github.mobtalk.core;

/** This player's progress on one quest of one mob, persisted in the world memory. */
public record QuestState(Status status, int progress, long startedMillis) {
    public enum Status { ACTIVE, DONE }
}
