package combattechnique;

/**
 * 1.8.9 knockback geometry. A melee hit pushes the victim 0.4 away from the attacker's POSITION, plus 0.5 per sprint /
 * Knockback level along the attacker's server-side YAW (the last rotation the server received before the attack
 * packet). Turning the yaw for that one packet ("displacement", "marlowhit") sends the victim off sideways.
 */
final class Knockback {
    private Knockback() {}

    /**
     * The velocity a 1.8 melee hit gives a victim moving at `v`: half of its old motion, 0.4 straight away from the
     * attacker (and 0.4 up, capped at 0.4), then 0.5 per extra level along the attacker's yaw and 0.1 more up.
     */
    static double[] hitVelocity(double[] v, double awayX, double awayZ, double attackerYaw, int level) {
        double len = Math.hypot(awayX, awayZ);
        if (len < 1e-4) { awayX = 0; awayZ = 1; len = 1; }
        double x = v[0] / 2 + awayX / len * 0.4;
        double y = Math.min(0.4, v[1] / 2 + 0.4);
        double z = v[2] / 2 + awayZ / len * 0.4;
        if (level > 0) {
            double r = Math.toRadians(attackerYaw);
            x += -Math.sin(r) * level * 0.5;
            z += Math.cos(r) * level * 0.5;
            y += 0.1;
        }
        return new double[]{x, y, z};
    }

    /** Minecraft yaw (degrees) of a horizontal direction. */
    static double yawOf(double dx, double dz) {
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** b relative to a, in (-180, 180]. */
    static double signed(double a, double b) {
        double d = ((b - a) % 360 + 540) % 360 - 180;
        return d == -180 ? 180 : d;
    }

    /** Direction of the whole push off "straight away", for an extra part of `level` levels turned `offsetDeg` away. */
    static double expectedDeviation(double offsetDeg, int level) {
        if (level <= 0) return 0;
        double r = Math.toRadians(offsetDeg);
        return Math.toDegrees(Math.atan2(0.5 * level * Math.sin(r), 0.4 + 0.5 * level * Math.cos(r)));
    }
}
