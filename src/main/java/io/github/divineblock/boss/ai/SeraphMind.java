package io.github.divineblock.boss.ai;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.thrown.ThrownItemEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.TridentItem;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** The seraph's v2 mind: remembers the last {@link #T} ticks of the fight (seen {@link #REACT} ticks late, like a person),
 * asks the {@link SeraphBrain} and turns its answer into steering, height, the wing dash, the fiery sweep that throws
 * arrows back, and when and where to loose the burning feathers. Every feature and constant mirrors
 * {@code ml/seraph_dodge/v2/duel.py} (Duel._tokens, Duel._items_for("s")); change both together and retrain. */
public final class SeraphMind {
    public static final double ORBIT = 12.0, HOVER = 3.5;
    public static final double STEER_ACC = 0.06, STEER_CAP = 0.16, LIFT = 2.0;
    /** The wing dash eases in and out: blocks per tick over its ticks, about 8.5 blocks in all. */
    public static final double[] DASH_PROFILE = {0.7, 1.3, 1.6, 1.6, 1.4, 1.0, 0.6, 0.3};
    public static final int DASH_TICKS = DASH_PROFILE.length, DASH_CD = 60;
    /** Steering and height follow the net smoothly: each tick moves this much of the way back to the old value. */
    public static final double SMOOTH = 0.75;
    public static final int DEFLECT_WIND = 3, DEFLECT_ACTIVE = 6, DEFLECT_CD = 80;
    public static final int CAST_TICKS = 20, ATTACK_CD = 45;
    public static final double REFLECT_SPEED = 2.0;
    /** After the sweep it cannot dash (and takes double harm), after a dash it cannot sweep, for this many ticks: a beat the
     * player can read. Not in the trainer's training rules (duel.py --recover mirrors it); the net simply finds the move refused. */
    public static final int RECOVER = 20;
    static final double WALK = 0.2158, EYE = 1.62;
    static final int T = 16, REACT = 2, P = 8, ITEM = 11, TOK = 23;

    private static SeraphBrain brain;
    private static boolean tried;

    private final float[][] tokens = new float[T + REACT][];
    private final float[][][] items = new float[REACT + 1][][];
    private int filled;
    private Vec3d lastFoe;
    private int dashCd, dashLeft, deflectCd, deflectT, attackCd = 30, foeCool, noDash, noDeflect;
    private Vec3d dashDir = Vec3d.ZERO;
    private double steerX, steerZ, liftS;
    private boolean wasUsing, released, dashStarted;
    private float[] lastOut = new float[0], lastToken = new float[TOK];
    private float[][] lastItems = new float[P][ITEM];
    private String refused = "";

    /** One tick's decision. Steering is world-space in [-1, 1]; aimSide/aimUp are the feathers' lead in [-1, 1]. */
    public record Move(double steerX, double steerZ, double steerAmount, double lift, boolean dashing, Vec3d dashDir, double dashSpeed,
                       boolean startDeflect, boolean startFeathers, double aimSide, double aimUp, float shotSoon) { }

    public static synchronized SeraphBrain brain() {
        if (!tried) {
            tried = true;
            brain = SeraphBrain.load("/assets/divineblock/ai/seraph_brain.bin");
            if (brain != null && (brain.tokDim != TOK || brain.itemDim != ITEM || brain.act != 8 || brain.T != T)) brain = null;
        }
        return brain;
    }

    public boolean deflecting() { return deflectT > 0 && deflectT <= DEFLECT_ACTIVE; }
    public boolean closingWings() { return deflectT > DEFLECT_ACTIVE; }
    public boolean dashing() { return dashLeft > 0; }
    /** Right after the sweep: it cannot dash yet and takes double harm. */
    public boolean recovering() { return noDash > 0 && deflectT == 0; }
    /** The fight log: the net's raw outputs this tick, what was refused and why, whether the foe just loosed, a dash began. */
    public float[] lastOut() { return lastOut; }
    /** The fight log: this tick's own token and projectiles, as pushed (the net reads them {@link #REACT} ticks later). */
    public float[] lastToken() { return lastToken; }
    public float[][] lastItems() { return lastItems; }
    public String refused() { return refused; }
    public boolean foeReleased() { return released; }
    public boolean dashStarted() { return dashStarted; }
    public int dashCd() { return dashCd; }
    public int deflectCd() { return deflectCd; }
    public int attackCd() { return attackCd; }

    /** Call every server tick while it fights, casting or not (the memory must not skip ticks).
     * {@code castProgress} 0..1 while a cast runs, else -1. Returns null when there is no net. */
    public Move tick(LivingEntity self, LivingEntity foe, double ground, double castProgress, double mastery) {
        return tick(self, foe, ground, castProgress, mastery, false);
    }

    /** The same, {@code stunned} while its weak point is open: it remembers, but neither steers, dashes, sweeps nor throws. */
    public Move tick(LivingEntity self, LivingEntity foe, double ground, double castProgress, double mastery, boolean stunned) {
        SeraphBrain b = brain();
        if (b == null) return null;
        Vec3d bpos = self.getPos();
        double ang = Math.atan2(foe.getZ() - bpos.z, foe.getX() - bpos.x), c = Math.cos(ang), s = Math.sin(ang);
        Vec3d foeVel = lastFoe == null ? Vec3d.ZERO : foe.getPos().subtract(lastFoe);
        lastFoe = foe.getPos();
        boolean using = foe.isUsingItem() && drawsBow(foe.getActiveItem());
        released = wasUsing && !using;
        if (released) foeCool = 2;
        wasUsing = using;
        lastToken = token(self, foe, ground, castProgress, c, s, foeVel, using);
        lastItems = items(self, c, s);
        push(lastToken, lastItems);
        boolean casting = castProgress >= 0;
        if (stunned) { dashLeft = 0; deflectT = 0; }

        float[] out = b.forward(java.util.Arrays.copyOfRange(tokens, 0, T), items[0]);
        lastOut = out;
        StringBuilder no = new StringBuilder();
        double ax = stunned ? 0 : clamp(out[0]), ay = stunned ? 0 : clamp(out[1]), az = stunned ? 0 : clamp(out[2]);
        double wx = ax * c - az * s, wz = ax * s + az * c;
        steerX = SMOOTH * steerX + (1 - SMOOTH) * wx;
        steerZ = SMOOTH * steerZ + (1 - SMOOTH) * wz;
        liftS = SMOOTH * liftS + (1 - SMOOTH) * ay;
        boolean deflect = out[4] > 0 && deflectCd == 0 && !casting && dashLeft == 0;
        if (deflect && (stunned || noDeflect > 0)) { no.append(stunned ? "deflect:stun " : "deflect:recover "); deflect = false; }
        if (deflect && mastery < 1 && self.getRandom().nextDouble() >= mastery) { deflectCd = DEFLECT_CD / 2; deflect = false; no.append("deflect:skill "); }
        if (deflect) { deflectT = DEFLECT_WIND + DEFLECT_ACTIVE; deflectCd = DEFLECT_CD; noDash = deflectT + RECOVER; }
        boolean dash = out[3] > 0 && dashCd == 0 && !casting && deflectT == 0;
        if (dash && (stunned || noDash > 0)) { no.append(stunned ? "dash:stun " : "dash:recover "); dash = false; }
        if (dash && mastery < 1 && self.getRandom().nextDouble() >= mastery) { dashCd = DASH_CD / 2; dash = false; no.append("dash:skill "); }
        dashStarted = dash;
        if (dash) {
            double len = Math.hypot(wx, wz);
            dashDir = len < 1e-6 ? Vec3d.ZERO : new Vec3d(wx / len, 0, wz / len);
            dashLeft = DASH_TICKS; dashCd = DASH_CD; noDeflect = DASH_TICKS + RECOVER;
        }
        boolean feathers = out[5] > 0 && attackCd == 0 && !casting && dashLeft == 0;
        if (feathers && stunned) { no.append("feathers:stun "); feathers = false; }
        refused = no.toString().trim();
        boolean dashingNow = dashLeft > 0;
        double dashSpeed = dashingNow ? DASH_PROFILE[Math.min(DASH_TICKS - dashLeft, DASH_TICKS - 1)] : 0;
        dashLeft = Math.max(dashLeft - 1, 0); dashCd = Math.max(dashCd - 1, 0);
        deflectT = Math.max(deflectT - 1, 0); deflectCd = Math.max(deflectCd - 1, 0);
        attackCd = Math.max(attackCd - 1, 0); foeCool = Math.max(foeCool - 1, 0);
        noDash = Math.max(noDash - 1, 0); noDeflect = Math.max(noDeflect - 1, 0);
        float soon = (float) (1 / (1 + Math.exp(-out[8])));
        return new Move(steerX, steerZ, Math.min(1, Math.hypot(steerX, steerZ)), casting || stunned ? 0 : liftS, dashingNow, dashDir, dashSpeed, deflect, feathers,
                clamp(out[6]), clamp(out[7]), soon);
    }

    /** The feathers flew: the next volley waits. */
    public void fired() { attackCd = ATTACK_CD; }

    /** Where the feathers aim: the foe's eyes moved by the net's lead (side up to 4 blocks, up to 2 blocks up). */
    public static Vec3d featherAim(LivingEntity self, LivingEntity foe, double side, double up) {
        double ang = Math.atan2(foe.getZ() - self.getZ(), foe.getX() - self.getX()), c = Math.cos(ang), s = Math.sin(ang);
        double ox = 0, oz = side * 4;
        return foe.getPos().add(ox * c - oz * s, EYE + up * 2, ox * s + oz * c);
    }

    private void push(float[] tok, float[][] its) {
        if (filled == 0) { java.util.Arrays.fill(tokens, tok); java.util.Arrays.fill(items, its); filled = 1; return; }
        System.arraycopy(tokens, 1, tokens, 0, tokens.length - 1); tokens[tokens.length - 1] = tok;
        System.arraycopy(items, 1, items, 0, items.length - 1); items[items.length - 1] = its;
    }

    private float[] token(LivingEntity self, LivingEntity foe, double ground, double castProgress, double c, double s,
                          Vec3d foeVel, boolean using) {
        float[] o = new float[TOK];
        int i = 0;
        Vec3d center = self.getPos().add(0, self.getHeight() / 2, 0);
        Vec3d eye = foe.getPos().add(0, EYE, 0);
        i = put(o, i, rot(self.getVelocity(), c, s).multiply(1 / 0.6));
        o[i++] = (float) ((self.getY() - ground - HOVER) / 3);
        o[i++] = (float) (1 - dashCd / (double) DASH_CD);
        o[i++] = (float) (1 - deflectCd / (double) DEFLECT_CD);
        o[i++] = (float) MathHelper.clamp(1 - attackCd / (double) ATTACK_CD, 0, 1);
        o[i++] = (float) (castProgress >= 0 ? castProgress : 0);
        o[i++] = deflectT > 0 ? 1 : 0;
        i = put(o, i, rot(eye.subtract(center), c, s).multiply(1 / 20.0));
        i = put(o, i, rot(new Vec3d(foeVel.x, 0, foeVel.z), c, s).multiply(1 / WALK));
        int use = using ? foe.getItemUseTime() : 0;
        o[i++] = using ? 1 : 0;
        o[i++] = using ? BowItem.getPullProgress(use) : 0;
        Vec3d look = foe.getRotationVec(1f);
        i = put(o, i, rot(look, c, s));
        Vec3d toB = center.subtract(eye).normalize();
        o[i++] = (float) (Math.acos(MathHelper.clamp(look.dotProduct(toB), -1, 1)) / 0.5);
        o[i++] = foeCool > 0 ? 1 : 0;
        o[i++] = dashLeft > 0 ? 1 : 0;
        return o;
    }

    private float[][] items(LivingEntity self, double c, double s) {
        record Shot(Vec3d rel, Vec3d v, double tca, double key) { }
        Vec3d center = self.getPos().add(0, self.getHeight() / 2, 0);
        List<Shot> shots = new ArrayList<>();
        Box around = self.getBoundingBox().expand(48);
        // arrows in the air and thrown things (snowballs, bottles: they open its weak point) — all as «the player's shot»
        for (ProjectileEntity p : self.getWorld().getEntitiesByClass(ProjectileEntity.class, around,
                p -> p.isAlive() && p.getOwner() != self && (p instanceof PersistentProjectileEntity arrow ? flying(arrow) : p instanceof ThrownItemEntity))) {
            Vec3d rel = p.getPos().subtract(center), v = p.getVelocity();
            double along = rel.dotProduct(v);
            if (along >= 0) continue;
            double tca = MathHelper.clamp(-along / (v.lengthSquared() + 1e-9), 0, 40);
            shots.add(new Shot(rel, v, tca, tca + rel.add(v.multiply(tca)).length() * 0.2));
        }
        shots.sort((x, y) -> Double.compare(x.key(), y.key()));
        float[][] out = new float[P][ITEM];
        for (int k = 0; k < Math.min(P, shots.size()); k++) {
            Shot sh = shots.get(k);
            int i = put(out[k], 0, rot(sh.rel(), c, s).multiply(1 / 20.0));
            i = put(out[k], i, rot(sh.v(), c, s).multiply(1 / 3.0));
            out[k][i++] = 1; out[k][i++] = 0; out[k][i++] = 0;      // вид: стрела игрока
            out[k][i++] = (float) Math.min(sh.tca() / 20, 1);
            out[k][i] = 1;
        }
        return out;
    }

    /** In the air, not stuck in a block (a stuck arrow keeps its last velocity and would look like one coming in). */
    public static boolean flying(PersistentProjectileEntity p) {
        return !((io.github.divineblock.mixin.ProjectileGroundAccessor) p).divineblock$inGround();
    }

    static boolean drawsBow(ItemStack stack) { return stack.getItem() instanceof BowItem || stack.getItem() instanceof TridentItem; }

    /** Arrows (and tridents) that would strike this tick, for the sweep to throw back. */
    public static boolean strikesThisTick(PersistentProjectileEntity p, Box box) {
        Vec3d from = p.getPos(), to = from.add(p.getVelocity());
        return box.expand(0.3).raycast(from, to).isPresent() || box.expand(0.3).contains(from);
    }

    /** Throws an arrow back at its shooter (or straight back when the shooter is gone). */
    public static void throwBack(LivingEntity self, PersistentProjectileEntity p) {
        Vec3d at = p.getOwner() instanceof LivingEntity shooter ? shooter.getEyePos() : p.getPos().subtract(p.getVelocity().multiply(10));
        Vec3d dir = at.subtract(p.getPos()).normalize();
        double jitter = Math.toRadians(2.0);
        dir = dir.add(self.getRandom().nextGaussian() * jitter, self.getRandom().nextGaussian() * jitter, self.getRandom().nextGaussian() * jitter).normalize();
        p.setOwner(self);
        p.setVelocity(dir.multiply(REFLECT_SPEED));
        if (p instanceof TridentEntity) p.setVelocity(dir.multiply(REFLECT_SPEED * 0.8));
        p.velocityModified = true;
    }

    static Vec3d rot(Vec3d v, double c, double s) { return new Vec3d(v.x * c + v.z * s, v.y, -v.x * s + v.z * c); }
    private static double clamp(float v) { return Math.max(-1, Math.min(1, v)); }
    private static int put(float[] o, int i, Vec3d v) { o[i] = (float) v.x; o[i + 1] = (float) v.y; o[i + 2] = (float) v.z; return i + 3; }
}
