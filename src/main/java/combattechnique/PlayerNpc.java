package combattechnique;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * A practice dummy that looks like a player. It exists only as packets: a server-side player entity that is never added
 * to the world, so the server neither moves, ticks nor saves it. This class keeps its position and runs its knockback
 * with 1.8 movement rules (move by the motion, then gravity 0.08 and drag 0.98 on y, friction 0.546 on the ground or
 * 0.91 in the air on x / z). The floor is its spawn height (use it on flat ground).
 */
final class PlayerNpc {
    final UUID owner;
    final World world;
    final Object handle;
    final int id;
    final String name;
    final double homeX, homeY, homeZ;
    long intervalMs;     // 0 = never hits
    int level;           // extra knockback of its hits
    double x, y, z, vx, vy, vz;
    float yaw, sentYaw = Float.NaN;
    boolean onGround = true, moving;
    long lastHurt, nextHit;
    int returnTick = -1;

    PlayerNpc(UUID owner, Location at, String name, long intervalMs, int level) throws Exception {
        this.owner = owner;
        this.world = at.getWorld();
        this.name = name;
        this.handle = Nms.newPlayer(world, UUID.randomUUID(), name);
        this.id = Nms.id(handle);
        this.homeX = at.getX(); this.homeY = at.getY(); this.homeZ = at.getZ();
        this.x = homeX; this.y = homeY; this.z = homeZ;
        this.yaw = at.getYaw();
        this.intervalMs = intervalMs;
        this.level = level;
        Nms.setLocation(handle, x, y, z, yaw, 0);
    }

    Location location() { return new Location(world, x, y, z, yaw, 0); }

    // ---- packets -------------------------------------------------------------------------------------------------

    void show(Player viewer) {
        try {
            Nms.send(viewer, Nms.playerInfo("ADD_PLAYER", handle)); // stays in the tab list: clients treat it as a player
            Nms.send(viewer, Nms.spawn(handle));
            Nms.send(viewer, Nms.headRotation(handle, yaw));
            Nms.send(viewer, Nms.equipment(id, 0, new ItemStack(Material.WOOD_SWORD)));
        } catch (Exception ignored) {
        }
    }

    void hide(Player viewer) {
        try {
            Nms.send(viewer, Nms.destroy(id));
            Nms.send(viewer, Nms.playerInfo("REMOVE_PLAYER", handle));
        } catch (Exception ignored) {
        }
    }

    private void broadcast(Object packet) {
        if (packet == null) return;
        for (Player p : world.getPlayers()) Nms.send(p, packet);
    }

    /** Sends the current position / look if it changed. */
    void sync(boolean force) {
        try {
            Nms.setLocation(handle, x, y, z, yaw, 0);
            if (force || moving || Float.isNaN(sentYaw) || Math.abs(Knockback.signed(sentYaw, yaw)) > 1) {
                broadcast(Nms.teleport(handle));
                broadcast(Nms.headRotation(handle, yaw));
                sentYaw = yaw;
            }
        } catch (Exception ignored) {
        }
    }

    void swingArm() { try { broadcast(Nms.animation(handle, 0)); } catch (Exception ignored) {} }

    void hurtFlash() { try { broadcast(Nms.animation(handle, 1)); } catch (Exception ignored) {} }

    // ---- movement ----------------------------------------------------------------------------------------------------

    /** A melee hit from `attacker` at (ax, az) with the yaw the server got before the attack packet. */
    void knock(double ax, double az, float attackerYaw, int extraLevel) {
        double[] v = Knockback.hitVelocity(new double[]{vx, vy, vz}, x - ax, z - az, attackerYaw, extraLevel);
        vx = v[0]; vy = v[1]; vz = v[2];
        onGround = false;
        moving = true;
    }

    /** One server tick of 1.8 movement. */
    void tick() {
        if (!moving) return;
        x += vx; y += vy; z += vz;
        if (y <= homeY) { y = homeY; vy = 0; onGround = true; }
        vy = (vy - 0.08) * 0.98;
        double f = onGround ? 0.6 * 0.91 : 0.91;
        vx *= f; vz *= f;
        if (onGround) vy = 0;
        if (onGround && Math.abs(vx) < 0.003 && Math.abs(vz) < 0.003) { vx = 0; vz = 0; moving = false; sync(true); }
    }

    void home() {
        x = homeX; y = homeY; z = homeZ; vx = vy = vz = 0; onGround = true; moving = false;
        sync(true);
    }
}
