package io.github.mobtalk.core;

/** A conversational proposal, never permission to set arbitrary prices or transfer items. */
public record BargainIntent(Type type, int offerIndex, int discount) {
    public enum Type { NONE, OFFER, ACCEPT, DECLINE }
    public BargainIntent { if (type == null) type = Type.NONE; }
    public static BargainIntent none() { return new BargainIntent(Type.NONE, -1, 0); }
}
