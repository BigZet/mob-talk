package io.github.mobtalk.core;

/** LLM targets are snapshot aliases; the authoritative adapter may persist a validated target UUID. */
public record ActionIntent(ActionType type, String target) {
    public ActionIntent {
        type = type == null ? ActionType.NONE : type;
        target = target == null ? "" : target;
        if (type == ActionType.NONE || type == ActionType.STOP) target = "";
    }
    public static ActionIntent none() { return new ActionIntent(ActionType.NONE, ""); }
}
