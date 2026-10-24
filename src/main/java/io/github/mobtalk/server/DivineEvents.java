package io.github.mobtalk.server;

import io.github.mobtalk.game.MobGameBridge;
import java.util.function.Consumer;

/** Entrypoint {@code mobtalk-events}: the divine block pushes each deed of the god here as one JSON event (idea Б1, protocol v1 in
 * {@link io.github.mobtalk.core.DivineTidings}). No shared code between the mods: the other side looks this entrypoint up by name
 * and calls {@link #accept}. Any thread; the work moves to the server thread. */
public final class DivineEvents implements Consumer<String> {
    @Override public void accept(String json) { MobGameBridge.divineEvent(json); }
}
