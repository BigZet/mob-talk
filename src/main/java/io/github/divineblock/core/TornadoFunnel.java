package io.github.divineblock.core;

/** The shape of a tornado funnel, shared by the server (where it pulls) and the client (where it is drawn). Clean-room
 * reimplementation from a behavioural description: a chain of 150 one-block layers, each chasing the one below it, so the
 * funnel leans behind its motion and bends on turns; ring radii widen with height into a horn; every layer spins clockwise
 * seen from above, faster near the ground. No game classes: plain numbers, testable. */
public final class TornadoFunnel {
    public static final int LAYERS = 150;
    static final double LAYER_HEIGHT = 1.0, RADIUS_STEP = 0.2;
    /** Base radius by tornado stage F0..F5. */
    static final double[] BASE_RADIUS = { 5, 15, 20, 25, 35, 45 };

    public final double[] x = new double[LAYERS], y = new double[LAYERS], z = new double[LAYERS];
    public final double[] rotation = new double[LAYERS];
    private final int stage;
    private boolean placed;

    /** {@code stage} is the Fujita-like strength 0..5. */
    public TornadoFunnel(int stage) { this.stage = Math.max(0, Math.min(5, stage)); }

    public int stage() { return stage; }
    public double baseRadius() { return BASE_RADIUS[stage]; }
    /** The radius used for layer i: the base plus a fixed step per layer. */
    public double layerRadius(int i) { return baseRadius() + RADIUS_STEP * i; }
    /** The radius above the last layer, the funnel's mouth. */
    public double topRadius() { return baseRadius() + RADIUS_STEP * (LAYERS + 1); }
    /** How far from a layer's centre its cloud ring sits: thin near the ground, flaring toward the top. */
    public double ringRadius(int i) { double r = layerRadius(i); return r * r / topRadius(); }

    /** One tick: layer 0 goes to the base, every higher layer chases the one below it, and every layer turns. */
    public void tick(double baseX, double baseY, double baseZ) {
        double top = topRadius();
        for (int i = 0; i < LAYERS; i++) {
            double tx, ty, tz;
            if (i == 0) { tx = baseX; ty = baseY; tz = baseZ; }
            else { tx = x[i - 1]; ty = y[i - 1] + LAYER_HEIGHT * layerRadius(i) / top; tz = z[i - 1]; }
            if (!placed) { x[i] = tx; y[i] = ty; z[i] = tz; }
            else {
                double dx = tx - x[i], dy = ty - y[i], dz = tz - z[i], distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (distance > 50) { x[i] = tx; y[i] = ty; z[i] = tz; }
                else if (distance > 0.1 * layerRadius(i) / top) {
                    double step = 15 * Math.min(30, distance) / 30 / distance; // Half the way for anything closer than 30.
                    x[i] += dx * step; y[i] += dy * step; z[i] += dz * step;
                }
            }
            // Same tangential speed at every radius, about 1.75 blocks per tick at the bottom and half that at the top.
            rotation[i] += 50.22 * (2 - (i + 1) / (double) LAYERS) / Math.max(0.5, ringRadius(i));
        }
        placed = true;
    }

    /** The funnel's centre at a height: the first layer (from the bottom) above {@code height − 1.5}, else the top. */
    public int layerAt(double height) {
        for (int i = 0; i < LAYERS; i++) if (y[i] > height - 1.5) return i;
        return LAYERS - 1;
    }

    /** Clean-room force model: the velocity change for an entity at (px, py, pz) this tick, as {dx, dy, dz}. Pull reaches
     * 120 blocks (falling linearly to zero), lift 90 blocks; near the ground the push is mostly inward and turns outward
     * high up, so caught creatures spiral up and are flung out. {@code armour} divides the push (1 = none). */
    public double[] force(double px, double py, double pz, double verticalVelocity, double armour) {
        int layer = layerAt(py);
        double cx = x[layer], cz = z[layer];
        double distance = Math.hypot(cx - px, cz - pz);
        double s = stage / 5.0;
        double height = Math.max(0.1, py - y[0]);
        double heightFactor = (175 - height) / 175;
        double theta = Math.toDegrees(Math.atan2(cz - pz, cx - px));
        double angle = Math.toRadians(theta + 180 + 20 + 40 * heightFactor - 40 * s);
        double horizontal = 0.2 * (120 - Math.min(distance, 120)) / 120 / armour;
        double vertical = (0.2 + 0.2 * s) * (90 - Math.min(distance, 90)) / 90 / armour;
        if (verticalVelocity > 0.5) vertical = 0;
        return new double[] { -Math.sin(angle) * horizontal, vertical, Math.cos(angle) * horizontal };
    }

    /** How many cloud quads a ring carries at full density: constant per layer, about 15 (F0) to 32 (F5). */
    public int ringCount(double density) { return Math.max(3, (int) Math.round(2 * Math.PI * topRadius() * density / 15)); }
}
