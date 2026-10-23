package io.github.mobtalk.server.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.UUID;

/** What the admin page reads from and does to the running game. JSON in the shapes of docs/admin-web.md, so the stub serves
 * fixtures and the live server (wave 1: players, ConversationStore, the divine block) builds the same trees.
 * Methods are called on admin threads; a live implementation hops to the server thread itself where the game needs it.
 * Failures are {@link ApiException}s: 404 for an unknown player or mob, 409 for «offline», 503 for a missing mod. */
public interface GameView {
    /** {@code online}, {@code maxPlayers}, {@code uptimeSeconds}, {@code streamer}, {@code dialoguesActive}, {@code llmQueue}, {@code llmInFlight}. */
    JsonObject overview();

    /** Every known player as in {@code GET /api/players}, without the money fields (the API adds them from the history). */
    JsonArray players();
    /** One player with {@code relations}, {@code settings}, {@code activity}; null when unknown. */
    JsonObject player(UUID player);
    /** {@code level}, {@code kick}, {@code ban}, {@code unban}, {@code whitelist}; answers {@code {"ok":true,"message":…}}. */
    JsonObject playerAction(UUID player, String action, JsonObject body);
    JsonObject setStreamer(UUID player);
    /** Online players where they are: {"t":…,"players":[{"player","online","world","x","y","z","yaw","biome","health","food","gamemode"}]}.
     * The only answers with coordinates (docs/admin-web.md, «Местоположение»); they never go into the history or logs. */
    JsonObject positions();
    /** One player; offline — the last known place and {@code respawn}; null when unknown. */
    JsonObject position(UUID player);
    /** Moves an online player to {"world","x","y","z"}, {"toPlayer":uuid}, {"to":"spawn"} or {"to":"respawn"}; 409 when offline. */
    JsonObject teleport(UUID player, JsonObject target);
    /** Map markers of a world besides the admin's waypoints: [{"kind":"village|boss|spawn","id","name","x","z"}]; the first spawn is
     * the world's spawn in {@code /api/map/info}. */
    JsonArray mapMarkers(String world);

    JsonObject mobs(String village, String query, String cursor, int limit);
    /** One mob in full; null when unknown. */
    JsonObject mob(UUID mob);
    /** {@code card}, {@code reset}, {@code clear-history}. */
    JsonObject mobAction(UUID mob, String action, JsonObject body);
    JsonObject villages();
    JsonObject forgive(String village, UUID player);

    JsonObject divineSouls();
    JsonObject divineActive();

    /** Version, mods, uptime, world, players, pack lines; the API adds its own {@code admin} part. */
    JsonObject server();
    List<String> commandRoots();
    /** Runs a command as the console: {@code {"ok":…,"result":n,"output":[…]}}. */
    JsonObject runCommand(String command);
    JsonObject reloadConfig();
    /** «Новый мир»: заявка с сидом и типом ({@code NewWorld}) и остановка сервера; мир сменится при следующем запуске. */
    JsonObject newWorld(String seed, String type, String by);
    /** Папка мира ({@code level-name}): её название админ вводит, подтверждая новый мир. */
    String worldDir();

    /** What each online player is doing, for «Сейчас»: [{"player":{…},"activity":"talking"}]. */
    JsonArray activities();
    /** Stops a dialogue the admin cancelled; false when it already ended. */
    boolean cancelDialogue(String id);
}
