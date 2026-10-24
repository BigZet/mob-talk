package io.github.divineblock.boss.ai;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SnowballItem;
import net.minecraft.item.ThrowablePotionItem;
import net.minecraft.item.TridentItem;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** The seraph's learned dodge («рывок крыльями»): reads the foe's weapon and the projectiles in the air the same way the
 * trainer ({@code ml/seraph_dodge/sim.py}) does, asks the {@link DodgeNet}, and says how to steer, how high to fly and when
 * to dash. Every constant here mirrors one in sim.py; change both together and retrain. */
public final class SeraphDodge {
    public static final double ORBIT = 12.0;
    static final double HOVER = 3.5;
    public static final double STEER_ACC = 0.04, STEER_CAP = 0.08, LIFT = 2.0;
    public static final double DASH_SPEED = 1.0;
    static final int DASH_TICKS = 5, DASH_CD = 50;
    static final double EYE = 1.62;
    static final int K = 4, OBS = 3 + 1 + 1 + 3 + 1 + 6 + 1 + 1 + 3 + 1 + K * 9;
    static final int NONE = 0, BOW = 1, CROSSBOW = 2, TRIDENT = 3, SNOWBALL = 4, POTION = 5;

    private static DodgeNet net;
    private static boolean tried;

    private int dashCooldown;
    private int dashLeft;
    private Vec3d dashDir = Vec3d.ZERO;

    /** What the net decided for this tick. {@code steerX/Z} are world-space, in [-1, 1]; {@code lift} moves the hover height. */
    public record Move(double steerX, double steerZ, double steerAmount, double lift, boolean dashing, Vec3d dashDir) { }

    public static synchronized DodgeNet net() {
        if (!tried) {
            tried = true;
            net = DodgeNet.load("/assets/divineblock/ai/seraph_dodge.bin");
            if (net != null && (net.inputSize() != OBS || net.outputSize() != 4)) net = null;
        }
        return net;
    }

    /** One decision. {@code ground} is the top of the ground below the seraph (as {@code SeraphEntity.groundBelow}). */
    /** {@code mastery} 0..1 is the share of the net's dashes the seraph really makes (the «умение» setting); a missed one
     * still tires the wings for half the pause, as in the trainer. */
    public Move decide(LivingEntity self, LivingEntity foe, double ground, double mastery) {
        DodgeNet n = net();
        if (n == null) return null;
        Vec3d bpos = self.getPos();
        double dx = foe.getX() - bpos.x, dz = foe.getZ() - bpos.z;
        double ang = Math.atan2(dz, dx), c = Math.cos(ang), s = Math.sin(ang);
        float[] a = n.forward(observe(self, foe, ground, c, s));
        // action in the frame «toward the foe» -> world
        double ax = a[0], ay = a[1], az = a[2];
        double wx = ax * c - az * s, wz = ax * s + az * c;
        if (a[3] > 0 && dashCooldown == 0 && mastery < 1 && self.getRandom().nextDouble() >= mastery) dashCooldown = DASH_CD / 2;
        else if (a[3] > 0 && dashCooldown == 0) {
            double len = Math.hypot(wx, wz);
            dashDir = len < 1e-6 ? Vec3d.ZERO : new Vec3d(wx / len, 0, wz / len);
            dashLeft = DASH_TICKS;
            dashCooldown = DASH_CD;
        }
        dashCooldown = Math.max(dashCooldown - 1, 0);
        boolean dashing = dashLeft > 0;
        dashLeft = Math.max(dashLeft - 1, 0);
        return new Move(wx, wz, Math.min(1, Math.hypot(ax, az)), ay, dashing, dashDir);
    }

    float[] observe(LivingEntity self, LivingEntity foe, double ground, double c, double s) {
        float[] o = new float[OBS];
        int i = 0;
        double h = self.getHeight();
        Vec3d center = self.getPos().add(0, h / 2, 0);
        Vec3d eye = foe.getPos().add(0, EYE, 0);
        Vec3d bv = rot(self.getVelocity(), c, s).multiply(1 / 0.6);
        i = put(o, i, bv);
        o[i++] = (float) ((self.getY() - ground - HOVER) / 3.0);
        o[i++] = (float) (1.0 - dashCooldown / (double) DASH_CD);
        i = put(o, i, rot(eye.subtract(center), c, s).multiply(1 / 20.0));
        int weapon = weapon(foe);
        int use = foe.isUsingItem() ? foe.getItemUseTime() : 0;
        ItemStack stack = foe.isUsingItem() ? foe.getActiveItem() : foe.getMainHandStack();
        boolean loaded = weapon == CROSSBOW && CrossbowItem.isCharged(stack);
        o[i++] = use > 0 ? 1 : 0;
        o[i + weapon] = 1; i += 6;
        double charge = 0;
        if (weapon == BOW && use > 0) charge = BowItem.getPullProgress(use);
        else if (weapon == TRIDENT && use > 0) charge = Math.min(use / 10.0, 1.0);
        else if (weapon == CROSSBOW) charge = loaded ? 1.0 : Math.min(use / 25.0, 1.0);
        o[i++] = (float) charge;
        o[i++] = loaded ? 1 : 0;
        Vec3d look = foe.getRotationVec(1f);
        i = put(o, i, rot(look, c, s));
        Vec3d toB = center.subtract(eye).normalize();
        o[i++] = (float) (Math.acos(MathHelper.clamp(look.dotProduct(toB), -1, 1)) / 0.5);
        // projectiles: the K soonest to pass near
        record Shot(Vec3d rel, Vec3d v, double tca, double key, boolean splash) { }
        List<Shot> shots = new ArrayList<>();
        Box around = self.getBoundingBox().expand(48);
        for (ProjectileEntity p : self.getWorld().getEntitiesByClass(ProjectileEntity.class, around, p -> p.isAlive() && p.getOwner() != self)) {
            Vec3d rel = p.getPos().subtract(center), v = p.getVelocity();
            double vv = v.lengthSquared() + 1e-9, along = rel.dotProduct(v);
            if (along >= 0) continue;
            double tca = MathHelper.clamp(-along / vv, 0, 40);
            double miss = rel.add(v.multiply(tca)).length();
            shots.add(new Shot(rel, v, tca, tca + miss * 0.2, p instanceof PotionEntity));
        }
        shots.sort((x, y) -> Double.compare(x.key(), y.key()));
        for (int k = 0; k < K; k++) {
            if (k < shots.size()) {
                Shot sh = shots.get(k);
                i = put(o, i, rot(sh.rel(), c, s).multiply(1 / 20.0));
                i = put(o, i, rot(sh.v(), c, s).multiply(1 / 3.0));
                o[i++] = sh.splash() ? 1 : 0;
                o[i++] = (float) Math.min(sh.tca() / 20.0, 1.0);
                o[i++] = 1;
            } else i += 9;
        }
        return o;
    }

    static int weapon(LivingEntity foe) {
        ItemStack stack = foe.isUsingItem() ? foe.getActiveItem() : foe.getMainHandStack();
        if (stack.getItem() instanceof BowItem) return BOW;
        if (stack.getItem() instanceof CrossbowItem) return CROSSBOW;
        if (stack.getItem() instanceof TridentItem) return TRIDENT;
        if (stack.getItem() instanceof SnowballItem) return SNOWBALL;
        if (stack.getItem() instanceof ThrowablePotionItem) return POTION;
        return NONE;
    }

    /** World -> the frame where x points from the seraph to its foe. */
    static Vec3d rot(Vec3d v, double c, double s) { return new Vec3d(v.x * c + v.z * s, v.y, -v.x * s + v.z * c); }

    private static int put(float[] o, int i, Vec3d v) {
        o[i] = (float) v.x; o[i + 1] = (float) v.y; o[i + 2] = (float) v.z;
        return i + 3;
    }
}
