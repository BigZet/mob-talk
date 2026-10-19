package io.github.mobtalk.core;

/** What a friend may be asked beyond following: wait here (STAY), guard the player or a place (GUARD), leave someone alone (SPARE).
 * Pure rules and lease bookkeeping on game ticks; the game adapter moves the mob and asks these for every decision. */
public final class FriendActions {
    /** Ten game minutes: every friend request ends by itself unless the player renews it. */
    public static final long LEASE_TICKS = 12_000;
    /** A waiting or guarding mob gives up once the player who asked is this far away (or gone from the world). */
    public static final double LEAVE_RANGE = 48;
    /** A guard keeps within NEAR–FAR of what it guards, attacks hostiles closer than ALERT to it and breaks off a chase past CHASE. */
    public static final double GUARD_NEAR = 3, GUARD_FAR = 6, GUARD_ALERT = 8, GUARD_CHASE = 16;
    /** A waiting mob pushed further than this from its spot walks back. */
    public static final double STAY_DRIFT = 2.5;
    public static final String PLAYER = "player", HERE = "here";
    private FriendActions() { }

    public static boolean isFriendAction(ActionType type) {
        return type == ActionType.STAY || type == ActionType.GUARD || type == ActionType.SPARE;
    }

    /** Waiting is a favour: a creature the player tamed, or one that likes them at least as much as a truce takes. A hostile species
     * below the truce does not wait for its prey. */
    public static boolean mayStay(int relationship, boolean ownedByPlayer) { return ownedByPlayer || relationship >= CombatRules.TRUCE; }
    /** Guarding takes a fighter that counts the player as its own (an iron golem included: only from ALLY). */
    public static boolean mayGuard(boolean fighter, int relationship, boolean ownedByPlayer) {
        return fighter && (ownedByPlayer || relationship >= CombatRules.ALLY);
    }
    /** Mercy is asked of hostile species only; peaceful ones attack nobody unprovoked anyway. */
    public static boolean maySpare(boolean hostileSpecies) { return hostileSpecies; }
    /** Under a truce a hostile may spare another creature; before one it may spare the player themself (in a truce it leaves them
     * alone anyway, and an enemy's promise about a third creature is worth nothing). */
    public static boolean maySpareTarget(boolean truce, boolean targetIsPlayer) { return targetIsPlayer != truce; }

    /** The model's target in the form the game keeps, or null when this action cannot take it: STAY is always "here", GUARD the
     * player or "here", SPARE the player or an observed alias eN. */
    public static String target(ActionType type, String raw) {
        String target = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (type) {
            case STAY -> HERE;
            case GUARD -> target.equals(PLAYER) || target.equals(HERE) ? target : target.isEmpty() ? PLAYER : null;
            case SPARE -> target.equals(PLAYER) || target.matches("e[0-9]+") ? target : null;
            default -> null;
        };
    }

    /** A friend request from the model, checked against the snapshot the model saw; NONE when it may not be carried out. */
    public static ActionIntent validate(ActionType type, String raw, MobSnapshot snapshot) {
        String target = target(type, raw);
        if (target == null || !snapshot.availableActions().contains(type)) return ActionIntent.none();
        if (type == ActionType.GUARD && !Boolean.TRUE.equals(snapshot.mobDetails().get("fighter"))) return ActionIntent.none();
        boolean player = target.equals(PLAYER);
        if (player && snapshot.playerId() == null) return ActionIntent.none();
        if (type == ActionType.SPARE) {
            if (!maySpareTarget(Boolean.TRUE.equals(snapshot.mobDetails().get("truce")), player)) return ActionIntent.none();
            if (!player && !perceived(target, snapshot)) return ActionIntent.none();
        }
        return new ActionIntent(type, target);
    }
    private static boolean perceived(String key, MobSnapshot snapshot) {
        return snapshot.observedEntities().stream().anyMatch(entity -> entity.key().equals(key) && entity.id() != null
                && (entity.visible() || "minecraft:warden".equals(snapshot.type()) && Boolean.TRUE.equals(entity.details().get("audible"))));
    }

    /** Why a lease stops: its time ran out, the player walked off, the player cancelled it (in words or with another action), the
     * one it concerns is gone, the player hit the mob, or the truce it relied on broke. */
    public enum End { NONE, EXPIRED, LEFT, CANCELLED, TARGET_GONE, HURT, TRUCE_BROKEN }

    /** One running request on world game time. */
    public static final class Lease {
        public final ActionType type;
        public final String target;
        private long expires;
        public Lease(ActionType type, String target, long now) { this.type = type; this.target = target; expires = now + LEASE_TICKS; }
        public boolean same(ActionType type, String target) { return this.type == type && this.target.equals(target); }
        /** The same request again: the clock restarts, nothing is announced. */
        public void refresh(long now) { expires = now + LEASE_TICKS; }
        public long expires() { return expires; }
        public long remaining(long now) { return Math.max(0, expires - now); }
        /** {@code playerDistance}: NaN when the player is dead, offline or in another world. SPARE does not care where the player is. */
        public End check(long now, double playerDistance, boolean targetPresent) {
            if (now >= expires) return End.EXPIRED;
            if (!targetPresent) return End.TARGET_GONE;
            if (type != ActionType.SPARE && (Double.isNaN(playerDistance) || playerDistance > LEAVE_RANGE)) return End.LEFT;
            return End.NONE;
        }
    }

    /** What a new intent from the same player does to a running STAY/GUARD: ordinary talk (NONE) and a SPARE leave it, the same request
     * refreshes it silently, anything else (STOP, FOLLOW, FLEE, ATTACK or another STAY/GUARD) replaces it. */
    public enum Change { KEEP, REFRESH, REPLACE }
    public static Change change(Lease running, ActionIntent asked) {
        if (running == null || asked == null || asked.type() == ActionType.NONE || asked.type() == ActionType.SPARE) return Change.KEEP;
        return running.same(asked.type(), asked.target()) ? Change.REFRESH : Change.REPLACE;
    }

    /** Guard movement with hysteresis: once it walks back it comes to NEAR, then holds still until the distance exceeds FAR. */
    public static boolean approach(boolean approaching, double distance) { return distance > (approaching ? GUARD_NEAR : GUARD_FAR); }
    /** A hostile this close to what is guarded is attacked. */
    public static boolean threatens(double distanceToGuarded) { return distanceToGuarded <= GUARD_ALERT; }
    /** A chase this far from what is guarded is given up and the guard returns. */
    public static boolean breakOff(double foeDistanceToGuarded) { return foeDistanceToGuarded > GUARD_CHASE; }

    /** A running lease as /mobtalk state shows it; read from the client thread. */
    public record Status(ActionType type, String target, long expires) { }
}
