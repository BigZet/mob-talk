package io.github.mobtalk.server.web;

import com.google.gson.JsonObject;
import io.github.mobtalk.core.MobTalkConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** The admin page on a server: settings, password, certificate, history, metrics and the web server, started once at server
 * start and stopped with it. Wave 1 connects the game: {@link #journal()}, {@link #metrics()}, {@link #errors()} are what
 * DialogueService, the tick sampler and the mod's failure paths report to. */
public final class AdminWeb {
    /** The game's side, supplied by the mod at start. */
    public record Hooks(GameView game, Backups.Saver saver, Path world, Supplier<MobTalkConfig> config,
                        java.util.function.Consumer<UnaryOperator<MobTalkConfig>> updateConfig, java.util.function.Predicate<String> personal,
                        Supplier<JsonObject> limits) { }

    private final AdminSettings settings;
    private final Path settingsFile;
    private final HistoryStore history;
    private final Metrics metrics;
    private final Latency latency;
    private final AdminJournal journal;
    private final ErrorLog errors;
    private final ConfigSources sources;
    private final AdminApi api;
    private final AdminAuth auth;
    private final AdminHttpServer server;
    private final ScheduledExecutorService chores;
    private final String fingerprint;

    private AdminWeb(Path gameDir, Hooks hooks, Consumer<String> log) throws IOException, GeneralSecurityException {
        settingsFile = gameDir.resolve("config").resolve("mobtalk-admin.json");
        settings = AdminSettings.load(settingsFile);
        if (settings.passwordHash.isBlank()) {
            String password = AdminAuth.generatePassword();
            settings.passwordHash = AdminAuth.hash(password);
            settings.save(settingsFile);
            log.accept("Админка: создан пароль " + password + " (показан один раз; сменить: /mobtalk admin web password <новый>)");
        }
        if (!settings.bind.equals("127.0.0.1") && !settings.bind.equals("localhost") && !settings.bind.equals("::1"))
            log.accept("Админка слушает " + settings.bind + ": она видна из сети. Держите сильный пароль или откройте через SSH-туннель.");
        AdminTls.Loaded tls = AdminTls.loadOrCreate(gameDir.resolve("config").resolve("mobtalk-admin.p12"), settings.bind);
        fingerprint = tls.fingerprint();
        ZoneId zone = ZoneId.systemDefault();
        Path data = gameDir.resolve("data").resolve("mobtalk");
        history = new HistoryStore(data.resolve("history"), System::currentTimeMillis, zone, settings.historyDays, log);
        metrics = new Metrics(data.resolve("metrics"), System::currentTimeMillis, log);
        latency = new Latency(System::currentTimeMillis);
        journal = new AdminJournal(history, latency, System::currentTimeMillis, () -> settings.keepPrompts);
        errors = new ErrorLog(history, System::currentTimeMillis);
        sources = new ConfigSources(history);
        sources.add(MobTalkAdminSource.ID, new MobTalkAdminSource(hooks.config(), hooks.updateConfig(), hooks.personal()));
        sources.add("admin", AdminSettings.source(settings, settingsFile, history));
        Backups backups = new Backups(hooks.world(), gameDir.resolve("backups"), hooks.saver(), System::currentTimeMillis, zone);
        api = new AdminApi(new AdminApi.Parts(history, metrics, latency, journal, errors, sources, hooks.game(), backups,
                gameDir.resolve("logs").resolve("latest.log"), hooks.limits(),
                () -> Json.obj("bind", settings.bind, "port", settings.port, "fingerprint", fingerprint), System::currentTimeMillis));
        auth = new AdminAuth(settings.passwordHash, System::currentTimeMillis);
        server = new AdminHttpServer(new AdminHttpServer.Options(settings.bind, settings.port, tls.context(), true), auth, api.router(),
                StaticFiles.classpath("assets/mobtalk/web/"), log);
        chores = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mobtalk-admin-chores"); thread.setDaemon(true); return thread;
        });
        chores.execute(metrics::load);
        chores.scheduleAtFixedRate(() -> { history.prune(); auth.sweep(); }, 1, 360, TimeUnit.MINUTES);
    }

    /** Starts the admin page, or logs why it could not; a broken admin never stops the server. */
    public static AdminWeb start(Path gameDir, Hooks hooks, Consumer<String> log) {
        try {
            AdminWeb web = new AdminWeb(gameDir, hooks, log);
            web.server.start();
            log.accept("Админка: https://" + web.settings.bind + ":" + web.server.port() + "/  отпечаток сертификата SHA-256 " + web.fingerprint);
            return web;
        } catch (IOException | GeneralSecurityException | RuntimeException failure) {
            log.accept("Админка не запущена: " + failure.getMessage());
            return null;
        }
    }

    public void stop() {
        api.close();
        server.stop();
        chores.shutdownNow();
        history.close();
        metrics.close();
    }

    /** {@code /mobtalk admin web password <новый>}: works at once; sessions of the old password stay until they expire. */
    public void setPassword(String password) throws IOException {
        if (password == null || password.length() < 8) throw new IllegalArgumentException("Пароль короче 8 символов.");
        settings.passwordHash = AdminAuth.hash(password);
        settings.save(settingsFile);
        auth.replace(settings.passwordHash);
    }

    /** For {@code /mobtalk admin web}: where the page is and the certificate's fingerprint. */
    public List<String> describe() {
        return List.of("https://" + settings.bind + ":" + server.port() + "/", "SHA-256 " + fingerprint);
    }

    public HistoryStore history() { return history; }
    public Metrics metrics() { return metrics; }
    public Latency latency() { return latency; }
    public AdminJournal journal() { return journal; }
    public ErrorLog errors() { return errors; }
    public ConfigSources sources() { return sources; }
}
