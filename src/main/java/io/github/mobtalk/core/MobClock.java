package io.github.mobtalk.core;

/** Часы Mob Talk: настоящее время плюс сдвиг, которым {@code /mobtalk admin skip} проматывает таймеры в минутах (голод, чары, особый
 * вечер, ревность), на которые {@code /tick} не действует. Сдвиг хранится в памяти мира ({@link ConversationStore#skip}). */
public final class MobClock {
    private MobClock() { }

    static volatile long offset;

    public static long now() { return System.currentTimeMillis() + offset; }
    public static long offset() { return offset; }
}
