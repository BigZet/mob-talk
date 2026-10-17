package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/** API keys for OpenRouter (speech, and chat by default) and Venice (optional chat). Called on workers; credentials never
 * become config, prompts, or logs. Methods without a provider mean OpenRouter. */
public final class OpenRouterToken {
    public enum Provider {
        OPENROUTER("OpenRouter", "OPENROUTER_API_KEY", "mobtalk-openrouter-token.txt", "MobTalk/openrouter-key.dpapi"),
        VENICE("Venice", "VENICE_API_KEY", "mobtalk-venice-token.txt", "MobTalk/venice-key.dpapi");

        public final String label;
        final String variable, plainFile, encryptedFile;
        Provider(String label, String variable, String plainFile, String encryptedFile) {
            this.label = label; this.variable = variable; this.plainFile = plainFile; this.encryptedFile = encryptedFile;
        }
    }

    static final String PLAIN_FILE = Provider.OPENROUTER.plainFile;
    // A PowerShell 7 parent may pass PSModulePath without Windows PowerShell's built-in modules.
    private static final String SECURITY_MODULE = "$ErrorActionPreference='Stop'; "
            + "Import-Module ($PSHOME+'\\Modules\\Microsoft.PowerShell.Security\\Microsoft.PowerShell.Security.psd1'); ";
    private static final String DECRYPT = SECURITY_MODULE
            + "$mobTalkSecret = ConvertTo-SecureString ([Console]::In.ReadToEnd().Trim()); "
            + "([System.Net.NetworkCredential]::new('', $mobTalkSecret)).Password";
    private static final String ENCRYPT = SECURITY_MODULE
            + "ConvertFrom-SecureString (ConvertTo-SecureString ([Console]::In.ReadToEnd().Trim()) -AsPlainText -Force)";

    public enum Source { ENVIRONMENT, FILE, ENCRYPTED, NONE }
    /** Where the active key comes from and a masked hint such as sk-or-v1…9f3c; never the key itself. */
    public record Status(Source source, String hint) { }
    private record Loaded(Source source, String token) { }

    private OpenRouterToken() { }

    static String load(Path configDirectory) throws IOException { return load(configDirectory, Provider.OPENROUTER); }
    static String load(Path configDirectory, Provider provider) throws IOException {
        return resolve(configDirectory, provider, Environment.current(provider)).token();
    }

    public static Status status(Path configDirectory) { return status(configDirectory, Provider.OPENROUTER); }
    public static Status status(Path configDirectory, Provider provider) { return status(configDirectory, provider, Environment.current(provider)); }

    /** Saves the key from the settings menu: DPAPI-encrypted for the Windows user, else the local token file. */
    public static void store(Path configDirectory, String key) throws IOException { store(configDirectory, Provider.OPENROUTER, key); }
    public static void store(Path configDirectory, Provider provider, String key) throws IOException {
        OpenRouterConnection.forgetKey();
        store(configDirectory, provider, key, Environment.current(provider));
    }

    /** Removes keys the menu can manage; the environment variable stays under the user's control. */
    public static void remove(Path configDirectory) throws IOException { remove(configDirectory, Provider.OPENROUTER); }
    public static void remove(Path configDirectory, Provider provider) throws IOException {
        OpenRouterConnection.forgetKey();
        remove(configDirectory, provider, Environment.current(provider));
    }

    static Status status(Path configDirectory, Environment environment) { return status(configDirectory, Provider.OPENROUTER, environment); }
    static Status status(Path configDirectory, Provider provider, Environment environment) {
        try {
            Loaded loaded = resolve(configDirectory, provider, environment);
            return new Status(loaded.source(), mask(loaded.token()));
        } catch (IOException missing) {
            return new Status(Source.NONE, "");
        }
    }

    static void store(Path configDirectory, String key, Environment environment) throws IOException { store(configDirectory, Provider.OPENROUTER, key, environment); }
    static void store(Path configDirectory, Provider provider, String key, Environment environment) throws IOException {
        String token = validate(key == null ? "" : key, provider);
        if (token.chars().anyMatch(Character::isWhitespace)) throw new IOException("Ключ " + provider.label + " не должен содержать пробелов.");
        Path plain = configDirectory.resolve(provider.plainFile);
        if (environment.localAppData() == null || environment.windows() == null) {
            writeAtomically(plain, token);
            return;
        }
        String encrypted = powershell(environment.windows(), ENCRYPT, token, 65536, provider);
        if (encrypted.isBlank() || encrypted.length() >= 65536) throw new IOException("Windows не смог зашифровать ключ " + provider.label + ".");
        Path target = environment.localAppData().resolve(provider.encryptedFile);
        writeAtomically(target, encrypted);
        if (!token.equals(loadDpapi(environment, provider))) throw new IOException("Не удалось проверить сохранённый ключ " + provider.label + ".");
        // The plain file has priority over DPAPI, so a stale one would hide the new key.
        Files.deleteIfExists(plain);
    }

    static void remove(Path configDirectory, Environment environment) throws IOException { remove(configDirectory, Provider.OPENROUTER, environment); }
    static void remove(Path configDirectory, Provider provider, Environment environment) throws IOException {
        try {
            Files.deleteIfExists(configDirectory.resolve(provider.plainFile));
            if (environment.localAppData() != null) Files.deleteIfExists(environment.localAppData().resolve(provider.encryptedFile));
        } catch (IOException failure) {
            throw new IOException("Не удалось удалить сохранённый ключ " + provider.label + ".");
        }
    }

    private static Loaded resolve(Path configDirectory, Provider provider, Environment environment) throws IOException {
        if (environment.variable() != null && !environment.variable().isBlank()) return new Loaded(Source.ENVIRONMENT, validate(environment.variable(), provider));
        Path file = configDirectory.resolve(provider.plainFile);
        if (Files.isRegularFile(file)) {
            String token;
            try {
                if (Files.size(file) > 4096) throw new IOException();
                token = Files.readString(file, StandardCharsets.UTF_8).replace("﻿", "").strip();
            } catch (IOException failure) {
                throw new IOException("Не удалось прочитать локальный ключ " + provider.label + ".");
            }
            if (!token.isBlank()) return new Loaded(Source.FILE, validate(token, provider));
        }
        String token = loadDpapi(environment, provider);
        if (!token.isBlank()) return new Loaded(Source.ENCRYPTED, validate(token, provider));
        throw new IOException("Вставьте ключ " + provider.label + " в настройках Mob Talk (вкладка «Общее»).");
    }

    private static String validate(String token, Provider provider) throws IOException {
        String stripped = token.strip();
        if (stripped.isBlank() || stripped.length() > 512 || stripped.contains("\r") || stripped.contains("\n"))
            throw new IOException("Ключ " + provider.label + " пуст или имеет неверный формат.");
        return stripped;
    }

    static String mask(String token) {
        return token.length() <= 16 ? "****" : token.substring(0, 8) + "…" + token.substring(token.length() - 4);
    }

    static Path encryptedKey(Path localAppData) { return encryptedKey(localAppData, Provider.OPENROUTER); }
    static Path encryptedKey(Path localAppData, Provider provider) {
        Path encrypted = localAppData.resolve(provider.encryptedFile);
        return Files.isRegularFile(encrypted) ? encrypted : null;
    }

    private static String loadDpapi(Environment environment, Provider provider) throws IOException {
        if (environment.localAppData() == null || environment.windows() == null) return "";
        Path encrypted = encryptedKey(environment.localAppData(), provider);
        if (encrypted == null) return "";
        try {
            if (Files.size(encrypted) > 65536) return "";
            String blob = Files.readString(encrypted, StandardCharsets.UTF_8).strip().replace("﻿", "");
            return powershell(environment.windows(), DECRYPT, blob, 513, provider);
        } catch (IOException failure) {
            throw new IOException("Не удалось открыть сохранённый локальный ключ " + provider.label + ".");
        }
    }

    /** Secrets travel through stdin, never the command line; an empty result means PowerShell failed. */
    private static String powershell(Path windows, String command, String input, int maximum, Provider provider) throws IOException {
        Path executable = windows.resolve("System32/WindowsPowerShell/v1.0/powershell.exe");
        Process process = null;
        try {
            process = new ProcessBuilder(executable.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            // Outputs are a few hundred bytes and fit the pipe buffer, so a hung PowerShell cannot block the read.
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return "";
            return new String(process.getInputStream().readNBytes(maximum), StandardCharsets.UTF_8).strip();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Операция с ключом " + provider.label + " отменена.");
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }

    private static void writeAtomically(Path target, String contents) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), "mobtalk-key", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** variable is the provider's own environment variable, read for that provider only. */
    record Environment(String variable, Path localAppData, Path windows) {
        static Environment current() { return current(Provider.OPENROUTER); }
        static Environment current(Provider provider) {
            String local = System.getenv("LOCALAPPDATA"), windows = System.getenv("SystemRoot");
            return new Environment(System.getenv(provider.variable), local == null ? null : Path.of(local), windows == null ? null : Path.of(windows));
        }
    }
}
