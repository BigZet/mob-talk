package io.github.mobtalk.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Called on the conversation worker; credentials never become config, prompts, or logs. */
final class OpenRouterToken {
    private OpenRouterToken() { }

    static String load(Path configDirectory) throws IOException {
        String environment = System.getenv("OPENROUTER_API_KEY");
        if (environment != null && !environment.isBlank()) return validate(environment);
        for (String filename : new String[] { "mobtalk-openrouter-token.txt" }) {
            Path file = configDirectory.resolve(filename);
            if (Files.isRegularFile(file)) {
                String token;
                try {
                    if (Files.size(file) > 4096) throw new IOException();
                    token = Files.readString(file, StandardCharsets.UTF_8).replace("\uFEFF", "").strip();
                } catch (IOException failure) {
                    throw new IOException("Не удалось прочитать локальный ключ OpenRouter.");
                }
                if (!token.isBlank()) return validate(token);
            }
        }
        String token = loadDpapi();
        if (!token.isBlank()) return validate(token);
        throw new IOException("Добавьте ключ OpenRouter в config/mobtalk-openrouter-token.txt или OPENROUTER_API_KEY.");
    }

    private static String validate(String token) throws IOException {
        String stripped = token.strip();
        if (stripped.isBlank() || stripped.length() > 512 || stripped.contains("\r") || stripped.contains("\n"))
            throw new IOException("Локальный ключ OpenRouter пуст или имеет неверный формат.");
        return stripped;
    }

    static Path encryptedKey(Path localAppData) {
        Path encrypted = localAppData.resolve("MobTalk/openrouter-key.dpapi");
        return Files.isRegularFile(encrypted) ? encrypted : null;
    }

    private static String loadDpapi() throws IOException {
        String localAppData = System.getenv("LOCALAPPDATA");
        String windows = System.getenv("SystemRoot");
        if (localAppData == null || windows == null) return "";
        Path encrypted = encryptedKey(Path.of(localAppData));
        if (encrypted == null) return "";
        Path powershell = Path.of(windows, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        // A PowerShell 7 parent may pass PSModulePath without Windows PowerShell's built-in modules.
        String command = "$ErrorActionPreference='Stop'; "
                + "Import-Module ($PSHOME+'\\Modules\\Microsoft.PowerShell.Security\\Microsoft.PowerShell.Security.psd1'); "
                + "$mobTalkSecret = ConvertTo-SecureString ([Console]::In.ReadToEnd().Trim()); "
                + "([System.Net.NetworkCredential]::new('', $mobTalkSecret)).Password";
        Process process = null;
        try {
            if (Files.size(encrypted) > 65536) return "";
            process = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try (var input = process.getOutputStream()) {
                input.write(Files.readString(encrypted, StandardCharsets.UTF_8).strip().replace("\uFEFF", "").getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return "";
            return new String(process.getInputStream().readNBytes(513), StandardCharsets.UTF_8).strip();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Загрузка локального ключа OpenRouter отменена.");
        } catch (IOException failure) {
            throw new IOException("Не удалось открыть сохранённый локальный ключ OpenRouter.");
        } finally {
            if (process != null) process.destroyForcibly();
        }
    }
}
