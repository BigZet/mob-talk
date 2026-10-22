package io.github.mobtalk.server.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** The page's files: from the jar ({@code assets/mobtalk/web/}) on a server, from a folder on disk for the stub. */
public interface StaticFiles {
    /** The bytes of a relative path, or null when there is no such file. */
    byte[] read(String path) throws IOException;

    static StaticFiles classpath(String root) {
        return path -> {
            try (InputStream in = StaticFiles.class.getClassLoader().getResourceAsStream(root + path)) { return in == null ? null : in.readAllBytes(); }
        };
    }

    static StaticFiles folder(Path root) {
        Path base = root.toAbsolutePath().normalize();
        return path -> {
            Path file = base.resolve(path).normalize();
            return file.startsWith(base) && Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
        };
    }

    Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"), Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"), Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"), Map.entry("woff2", "font/woff2"), Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("webmanifest", "application/manifest+json"));

    static String type(String path) {
        int dot = path.lastIndexOf('.');
        return TYPES.getOrDefault(dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT), "application/octet-stream");
    }

    /** No parent steps, no backslashes, no hidden files: a clean relative path or null. */
    static String clean(String path) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (relative.isEmpty()) return "index.html";
        if (relative.contains("\\") || relative.contains("\0")) return null;
        for (String part : relative.split("/")) if (part.isEmpty() || part.startsWith(".")) return null;
        return relative;
    }
}
