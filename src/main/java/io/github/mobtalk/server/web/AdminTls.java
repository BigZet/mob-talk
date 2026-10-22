package io.github.mobtalk.server.web;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** The admin page's own self-signed certificate: made once with the JDK's keytool, kept in {@code config/mobtalk-admin.p12}.
 * The store password is fixed: whoever can read the file can read the server's config too, so a password beside it adds nothing. */
public final class AdminTls {
    private AdminTls() { }

    static final char[] STORE_PASSWORD = "mobtalk-admin".toCharArray();
    static final String ALIAS = "mobtalk-admin";

    public record Loaded(SSLContext context, String fingerprint) { }

    /** Loads the store, creating it first when it is missing. */
    public static Loaded loadOrCreate(Path store, String hostName) throws IOException, GeneralSecurityException {
        if (!Files.isRegularFile(store)) create(store, hostName);
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) { keys.load(in, STORE_PASSWORD); }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, STORE_PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return new Loaded(context, fingerprint(keys.getCertificate(ALIAS)));
    }

    public static Certificate certificate(Path store) throws IOException, GeneralSecurityException {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) { keys.load(in, STORE_PASSWORD); }
        return keys.getCertificate(ALIAS);
    }

    /** SHA-256 of the certificate as AB:CD:…, what the browser shows when asked to trust it. */
    public static String fingerprint(Certificate certificate) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
    }

    static void create(Path store, String hostName) throws IOException {
        Files.createDirectories(store.toAbsolutePath().getParent());
        String keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool").toString();
        String host = hostName == null || hostName.isBlank() || hostName.equals("0.0.0.0") ? "localhost" : hostName;
        String san = "dns:localhost,ip:127.0.0.1" + (host.equals("localhost") || host.equals("127.0.0.1") ? ""
                : host.matches("[0-9.]+") ? ",ip:" + host : ",dns:" + host);
        List<String> command = List.of(keytool, "-genkeypair", "-alias", ALIAS, "-keyalg", "EC", "-groupname", "secp256r1",
                "-validity", "3650", "-dname", "CN=Mob Talk admin", "-ext", "SAN=" + san,
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", new String(STORE_PASSWORD), "-noprompt");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("keytool не ответил за минуту"); }
            if (process.exitValue() != 0 || !Files.isRegularFile(store)) throw new IOException("keytool не создал сертификат: " + output.strip());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("создание сертификата прервано");
        }
    }
}
