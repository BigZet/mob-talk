package io.github.mobtalk.server.web;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** The admin password (a PBKDF2 hash, never the password itself), login sessions and the brake on guessing. */
public final class AdminAuth {
    public enum Level { PLAYER, STREAMER, ADMIN }
    public record Session(String token, String user, Level level, long expires) { }

    public static final long SESSION_MILLIS = 12L * 60 * 60 * 1000;
    public static final int ATTEMPTS_PER_MINUTE = 5;
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private volatile String hash;
    private final LongSupplier clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> attempts = new HashMap<>();

    /** No hash, no admin page: the server refuses to start rather than run open. */
    public AdminAuth(String passwordHash, LongSupplier clock) {
        if (passwordHash == null || !passwordHash.startsWith("pbkdf2$")) throw new IllegalStateException("Пароль админки не задан.");
        this.hash = passwordHash; this.clock = clock;
    }

    /** A new password hash; sessions opened with the old one stay until they expire. */
    public void replace(String passwordHash) {
        if (passwordHash == null || !passwordHash.startsWith("pbkdf2$")) throw new IllegalArgumentException("не хэш пароля");
        hash = passwordHash;
    }

    /** A session for the password, an exception for a wrong one or the sixth try within a minute from this address. */
    public Session login(String ip, String password) {
        long now = clock.getAsLong();
        synchronized (attempts) {
            Deque<Long> recent = attempts.computeIfAbsent(ip, key -> new ArrayDeque<>());
            while (!recent.isEmpty() && recent.peekFirst() <= now - 60_000) recent.pollFirst();
            if (recent.size() >= ATTEMPTS_PER_MINUTE) {
                int wait = (int) Math.max(1, (recent.peekFirst() + 60_000 - now + 999) / 1000);
                throw new ApiException(429, "too_many", "Слишком много попыток входа. Подождите минуту.", wait);
            }
            recent.addLast(now);
        }
        if (password == null || !verify(password, hash)) throw new ApiException(401, "wrong_password", "Неверный пароль.");
        return open("admin", Level.ADMIN);
    }

    /** A session without a password: for tests and for a future per-user login. */
    public Session open(String user, Level level) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        Session session = new Session(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), user, level, clock.getAsLong() + SESSION_MILLIS);
        sessions.put(session.token(), session);
        return session;
    }

    public Session session(String token) {
        if (token == null) return null;
        Session session = sessions.get(token);
        if (session == null) return null;
        if (session.expires() <= clock.getAsLong()) { sessions.remove(token); return null; }
        return session;
    }

    public void logout(String token) { if (token != null) sessions.remove(token); }

    /** Forgets expired sessions and old attempts; called now and then so a long-running server does not keep them. */
    public void sweep() {
        long now = clock.getAsLong();
        sessions.values().removeIf(session -> session.expires() <= now);
        synchronized (attempts) {
            for (Iterator<Deque<Long>> it = attempts.values().iterator(); it.hasNext(); ) {
                Deque<Long> recent = it.next();
                while (!recent.isEmpty() && recent.peekFirst() <= now - 60_000) recent.pollFirst();
                if (recent.isEmpty()) it.remove();
            }
        }
    }

    /** {@code pbkdf2$<iterations>$<salt>$<hash>}, base64. */
    public static String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
    }

    public static boolean verify(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4) return false;
        try {
            byte[] salt = Base64.getDecoder().decode(parts[2]), expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, Integer.parseInt(parts[1])));
        } catch (IllegalArgumentException broken) { return false; }
    }

    /** Sixteen letters and digits without look-alikes, printed once at the first start. */
    public static String generatePassword() {
        String alphabet = "abcdefghjkmnpqrstuvwxyzACDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 16; i++) password.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        return password.toString();
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, iterations, 256)).getEncoded();
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
}
