package io.github.divineblock.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/** API keys for OpenRouter and Venice, adapted from Mob Talk's OpenRouterToken but entirely this mod's own: the environment
 * variable, a local token file next to the config, or the key saved in the settings (DPAPI-encrypted under
 * %LOCALAPPDATA%\DivineBlock). Called on workers; keys never reach the config, prompts or logs. */
public final class ApiKeys {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("divineblock");
    public enum Provider {
        OPENROUTER("OpenRouter", "OPENROUTER_API_KEY", "divineblock-openrouter-token.txt", "DivineBlock/openrouter-key.dpapi"),
        VENICE("Venice", "VENICE_API_KEY", "divineblock-venice-token.txt", "DivineBlock/venice-key.dpapi");
        public final String label;
        final String variable, plainFile, encryptedFile;
        Provider(String label, String variable, String plainFile, String encryptedFile) {
            this.label = label; this.variable = variable; this.plainFile = plainFile; this.encryptedFile = encryptedFile;
        }
    }
    public enum Source { ENVIRONMENT, FILE, ENCRYPTED, NONE }
    /** Where the key comes from and a masked hint such as sk-or-v1…9f3c; never the key itself. */
    public record Status(Source source, String hint) { }

    private static final String SECURITY_MODULE = "$ErrorActionPreference='Stop'; "
            + "Import-Module ($PSHOME+'\\Modules\\Microsoft.PowerShell.Security\\Microsoft.PowerShell.Security.psd1'); ";
    private static final String DECRYPT = SECURITY_MODULE
            + "$secret = ConvertTo-SecureString ([Console]::In.ReadToEnd().Trim()); "
            + "([System.Net.NetworkCredential]::new('', $secret)).Password";
    private static final String ENCRYPT = SECURITY_MODULE
            + "ConvertFrom-SecureString (ConvertTo-SecureString ([Console]::In.ReadToEnd().Trim()) -AsPlainText -Force)";

    /** Each PowerShell decrypt takes seconds (tens beside a loading Whisper), so a decrypted key is kept in memory. */
    private static final java.util.Map<Provider, String> DECRYPTED = new java.util.concurrent.ConcurrentHashMap<>();

    private ApiKeys() { }

    public static String load(Path configDirectory, Provider provider) throws IOException {
        String variable = System.getenv(provider.variable);
        if (variable != null && !variable.isBlank()) return validate(variable, provider);
        Path plain = configDirectory.resolve(provider.plainFile);
        if (Files.isRegularFile(plain) && Files.size(plain) <= 4096) {
            String token = Files.readString(plain, StandardCharsets.UTF_8).replace("﻿", "").strip();
            if (!token.isBlank()) return validate(token, provider);
        }
        String cached = DECRYPTED.get(provider);
        if (cached != null) return cached;
        String token = decrypt(provider.encryptedFile, provider);
        if (!token.isBlank()) { token = validate(token, provider); DECRYPTED.put(provider, token); return token; }
        throw new IOException("Нет ключа " + provider.label + ": вставьте его в настройках Божественного блока.");
    }

    public static Status status(Path configDirectory, Provider provider) {
        try {
            String variable = System.getenv(provider.variable);
            if (variable != null && !variable.isBlank()) return new Status(Source.ENVIRONMENT, mask(variable.strip()));
            Path plain = configDirectory.resolve(provider.plainFile);
            if (Files.isRegularFile(plain)) return new Status(Source.FILE, mask(Files.readString(plain, StandardCharsets.UTF_8).strip()));
            Path local = localAppData();
            if (local != null && Files.isRegularFile(local.resolve(provider.encryptedFile))) return new Status(Source.ENCRYPTED, "зашифрован Windows");
        } catch (IOException | RuntimeException ignored) { }
        return new Status(Source.NONE, "");
    }

    /** Saves a key from the settings screen: DPAPI-encrypted for the Windows user, else a local token file. */
    public static void store(Path configDirectory, Provider provider, String key) throws IOException {
        DECRYPTED.remove(provider);
        String token = validate(key == null ? "" : key, provider);
        if (token.chars().anyMatch(Character::isWhitespace)) throw new IOException("Ключ " + provider.label + " не должен содержать пробелов.");
        Path local = localAppData(), windows = windows();
        Path plain = configDirectory.resolve(provider.plainFile);
        if (local == null || windows == null) { writeAtomically(plain, token); return; }
        String encrypted = powershell(windows, ENCRYPT, token, 65536, provider);
        if (encrypted.isBlank() || encrypted.length() >= 65536) throw new IOException("Windows не смог зашифровать ключ " + provider.label + ".");
        writeAtomically(local.resolve(provider.encryptedFile), encrypted);
        if (!token.equals(decrypt(provider.encryptedFile, provider))) throw new IOException("Не удалось проверить сохранённый ключ " + provider.label + ".");
        Files.deleteIfExists(plain); // The plain file wins over DPAPI and would hide the new key.
    }

    /** Removes this mod's keys; the environment variable and Mob Talk's key stay as they are. */
    public static void remove(Path configDirectory, Provider provider) throws IOException {
        DECRYPTED.remove(provider);
        Files.deleteIfExists(configDirectory.resolve(provider.plainFile));
        Path local = localAppData();
        if (local != null) Files.deleteIfExists(local.resolve(provider.encryptedFile));
    }

    static String mask(String token) { return token.length() <= 16 ? "****" : token.substring(0, 8) + "…" + token.substring(token.length() - 4); }

    private static String validate(String token, Provider provider) throws IOException {
        String stripped = token.strip();
        if (stripped.isBlank() || stripped.length() > 512 || stripped.contains("\r") || stripped.contains("\n"))
            throw new IOException("Ключ " + provider.label + " пуст или имеет неверный формат.");
        return stripped;
    }

    private static String decrypt(String relative, Provider provider) throws IOException {
        Path local = localAppData(), windows = windows();
        if (local == null || windows == null) { LOG.info("ключ: нет LOCALAPPDATA ({}) или SystemRoot ({})", local, windows); return ""; }
        Path file = local.resolve(relative);
        if (!Files.isRegularFile(file) || Files.size(file) > 65536) { LOG.info("ключ {}: нет файла {}", provider.label, file); return ""; }
        String blob = Files.readString(file, StandardCharsets.UTF_8).replace("﻿", "").strip();
        return powershell(windows, DECRYPT, blob, 513, provider);
    }

    /** Secrets travel through stdin, never the command line; an empty result means PowerShell failed. */
    private static String powershell(Path windows, String command, String input, int maximum, Provider provider) throws IOException {
        Path executable = windows.resolve("System32/WindowsPowerShell/v1.0/powershell.exe");
        Process process = null;
        try {
            process = new ProcessBuilder(executable.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", command)
                    .start();
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            // PowerShell with the Security module takes ~6 s on an idle machine and more beside running games: allow 30 s.
            if (!process.waitFor(30, TimeUnit.SECONDS)) { LOG.info("ключ {}: PowerShell не ответил за 30 с", provider.label); return ""; }
            if (process.exitValue() != 0) {
                String error = new String(process.getErrorStream().readNBytes(600), StandardCharsets.UTF_8).strip();
                LOG.info("ключ {}: PowerShell завершился с кодом {}: {}", provider.label, process.exitValue(), error);
                return "";
            }
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
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), "divineblock-key", ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path localAppData() { String v = System.getenv("LOCALAPPDATA"); return v == null ? null : Path.of(v); }
    private static Path windows() { String v = System.getenv("SystemRoot"); return v == null ? null : Path.of(v); }
}
