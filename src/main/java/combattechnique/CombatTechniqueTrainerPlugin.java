package combattechnique;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Practice dummies for 1.8 PvP techniques.
 *
 * Displacement ("/ctt target"): a villager that cannot walk stands in front of you; hit it and the plugin tells you,
 * from the server's own numbers, how far your yaw was turned for the hit, which way the target actually flew, and how
 * long your look was away. The target goes back to its spot 1.5 s after each hit. Logged to displace.csv.
 *
 * Taking knockback ("/ctt hitme"): a sparring dummy hits you on a fixed rhythm with vanilla 1.8 knockback; the plugin
 * tells you when your client moved, whether you jumped on the tick the knockback arrived (a jump reset), and how far
 * you were pushed. Logged to knockback.csv.
 */
public final class CombatTechniqueTrainerPlugin extends JavaPlugin implements Listener {
    private static final int RETURN_TICKS = 30;     // back to its spot after a hit
    private static final int MEASURE_TICKS = 3;     // how long the push is followed
    private static final double SUCCESS_DEG = 25;   // the victim flew at least this far off straight away
    private static final double AWAY_DEG = 20;      // "looking away" for the look timing
    private static final long LOOK_KEEP_MS = 1000;

    private final Map<UUID, Location> targets = new HashMap<UUID, Location>();      // target -> its spot
    private final Map<UUID, Deque<long[]>> looks = new HashMap<UUID, Deque<long[]>>(); // player -> (ms, yaw * 1000)
    private final Map<UUID, int[]> stats = new HashMap<UUID, int[]>();             // player -> {hits with extra kb, displaced}
    private final Map<UUID, Sparring> sparring = new HashMap<UUID, Sparring>();    // player -> the dummy hitting them
    private final Map<UUID, double[]> kbStats = new HashMap<UUID, double[]>();     // player -> {hits, jump resets, push sum, push sum without / with a reset}

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getDataFolder().mkdirs();
    }

    @Override
    public void onDisable() {
        clearTargets();
        for (UUID id : new ArrayList<UUID>(sparring.keySet())) stopSparring(id, false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
        Player p = (Player) sender;
        String sub = args.length > 0 ? args[0].toLowerCase() : "help";
        if (sub.equals("target") || sub.equals("spawn")) {
            double distance = 3.0;
            if (args.length > 1) try { distance = Math.max(1.5, Math.min(6, Double.parseDouble(args[1]))); } catch (NumberFormatException ignored) {}
            spawn(p, distance);
        } else if (sub.equals("stick")) {
            int level = 1;
            if (args.length > 1) try { level = Math.max(1, Math.min(5, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) {}
            giveStick(p, level);
        } else if (sub.equals("hitme")) {
            if (args.length > 1 && args[1].equalsIgnoreCase("stop")) { stopSparring(p.getUniqueId(), true); return true; }
            long interval = 1000;
            int level = 1;
            if (args.length > 1) try { interval = Math.max(250, Math.min(5000, Long.parseLong(args[1]))); } catch (NumberFormatException ignored) {}
            if (args.length > 2) try { level = Math.max(0, Math.min(5, Integer.parseInt(args[2]))); } catch (NumberFormatException ignored) {}
            startSparring(p, interval, level);
        } else if (sub.equals("clear")) {
            int n = targets.size() + sparring.size();
            clearTargets();
            for (UUID id : new ArrayList<UUID>(sparring.keySet())) stopSparring(id, false);
            p.sendMessage(ChatColor.GRAY + "Removed " + n + " dummy/dummies.");
        } else if (sub.equals("stats")) {
            int[] s = stats.get(p.getUniqueId());
            if (s == null || s[0] == 0) p.sendMessage(ChatColor.GRAY + "Displacement: no hits with extra knockback yet.");
            else p.sendMessage(ChatColor.YELLOW + "Displacement: " + s[1] + " of " + s[0] + " hits with extra knockback displaced (" + Math.round(100.0 * s[1] / s[0]) + "%).");
            double[] k = kbStats.get(p.getUniqueId());
            if (k == null || k[0] == 0) p.sendMessage(ChatColor.GRAY + "Knockback: no hits taken yet.");
            else p.sendMessage(ChatColor.YELLOW + "Knockback: " + (int) k[0] + " hits taken, jump reset on " + (int) k[1] + " ("
                    + Math.round(100 * k[1] / k[0]) + "%), average push " + fmt2(k[2] / k[0]) + " blocks; without a jump reset "
                    + (k[0] - k[1] > 0 ? fmt2(k[3] / (k[0] - k[1])) : "-") + ", with " + (k[1] > 0 ? fmt2(k[4] / k[1]) : "-") + ".");
        } else if (sub.equals("reset")) {
            stats.remove(p.getUniqueId());
            kbStats.remove(p.getUniqueId());
            p.sendMessage(ChatColor.GRAY + "Your results were reset.");
        } else {
            p.sendMessage(ChatColor.YELLOW + "/ctt spawn [distance]" + ChatColor.GRAY + " - a target in front of you (default 3 blocks)");
            p.sendMessage(ChatColor.YELLOW + "/ctt stick [level]" + ChatColor.GRAY + " - a Knockback stick (default Knockback I, given with /ctt target)");
            p.sendMessage(ChatColor.YELLOW + "/ctt hitme [interval_ms] [level]" + ChatColor.GRAY + " - a dummy that hits you (default every 1000 ms, extra knockback 1 like a sprint hit)");
            p.sendMessage(ChatColor.YELLOW + "/ctt hitme stop" + ChatColor.GRAY + " - stop it");
            p.sendMessage(ChatColor.YELLOW + "/ctt clear" + ChatColor.GRAY + " - remove all dummies");
            p.sendMessage(ChatColor.YELLOW + "/ctt stats" + ChatColor.GRAY + " / " + ChatColor.YELLOW + "/ctt reset" + ChatColor.GRAY + " - your results");
            p.sendMessage(ChatColor.GRAY + "Displacement: sprint or use a Knockback item, it only turns the extra knockback.");
        }
        return true;
    }

    private void spawn(Player p, double distance) {
        Location eye = p.getLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(distance);
        Location at = eye.clone().add(dir);
        at.setY(p.getLocation().getY());
        at.setYaw((float) Knockback.yawOf(-dir.getX(), -dir.getZ())); // facing the player
        at.setPitch(0);
        Villager v = (Villager) p.getWorld().spawnEntity(at, EntityType.VILLAGER);
        v.setCustomName(ChatColor.GOLD + "Displace target");
        v.setCustomNameVisible(true);
        v.setRemoveWhenFarAway(false);
        v.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 255, false, false), true);
        v.setMaxHealth(1024); // never dies between two heals
        v.setHealth(1024);
        targets.put(v.getUniqueId(), at.clone());
        giveStick(p, 1);
        p.sendMessage(ChatColor.GREEN + "Target spawned " + distance + " blocks ahead. Sprint-hit it while turning your look away for the hit.");
    }

    /** A Knockback stick like Bed Wars' (Knockback I), unless the player already carries one of the same level. */
    private void giveStick(Player p, int level) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() == Material.STICK && it.getEnchantmentLevel(Enchantment.KNOCKBACK) == level) return;
        }
        ItemStack stick = new ItemStack(Material.STICK);
        stick.addUnsafeEnchantment(Enchantment.KNOCKBACK, level);
        ItemMeta meta = stick.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Knockback Stick" + (level != 1 ? " " + level : ""));
        stick.setItemMeta(meta);
        p.getInventory().addItem(stick);
        p.sendMessage(ChatColor.GRAY + "Gave you a Knockback " + level + " stick.");
    }

    private void clearTargets() {
        for (UUID id : targets.keySet()) {
            for (org.bukkit.World w : getServer().getWorlds()) for (Entity e : w.getEntities()) if (e.getUniqueId().equals(id)) e.remove();
        }
        targets.clear();
    }

    private boolean isTarget(Entity e) { return e != null && targets.containsKey(e.getUniqueId()); }

    // The player's look history (server side: the rotations the client sent).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        long now = System.currentTimeMillis();
        Deque<long[]> q = looks.get(e.getPlayer().getUniqueId());
        if (q == null) { q = new ArrayDeque<long[]>(); looks.put(e.getPlayer().getUniqueId(), q); }
        float yaw = e.getTo().getYaw();
        if (q.isEmpty() || q.peekLast()[1] != Math.round(yaw * 1000)) q.addLast(new long[]{now, Math.round(yaw * 1000)});
        while (!q.isEmpty() && now - q.peekFirst()[0] > LOOK_KEEP_MS) q.removeFirst();
        Sparring s = sparring.get(e.getPlayer().getUniqueId());
        if (s != null && s.pending != null) s.pending.move(e.getFrom(), e.getTo(), now);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { looks.remove(e.getPlayer().getUniqueId()); stopSparring(e.getPlayer().getUniqueId(), false); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!isTarget(e.getEntity()) || !(e.getDamager() instanceof Player)) return;
        final Player p = (Player) e.getDamager();
        final LivingEntity t = (LivingEntity) e.getEntity();
        final Location from = t.getLocation().clone();
        final Location pl = p.getLocation().clone();
        final long hitAt = System.currentTimeMillis();
        final double bearing = Knockback.yawOf(from.getX() - pl.getX(), from.getZ() - pl.getZ());
        final double offset = Knockback.signed(bearing, pl.getYaw()); // the yaw the server used for this hit
        ItemStack held = p.getItemInHand();
        final int kbLevel = held == null ? 0 : held.getEnchantmentLevel(Enchantment.KNOCKBACK);
        final boolean sprinting = p.isSprinting();
        final int level = kbLevel + (sprinting ? 1 : 0);
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() { report(p, t, from, bearing, offset, level, sprinting, kbLevel, hitAt); if (t.isValid()) t.setHealth(t.getMaxHealth()); }
        }, MEASURE_TICKS);
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() {
                if (!t.isValid()) return;
                Location home = targets.get(t.getUniqueId());
                if (home != null) { t.teleport(home); t.setVelocity(new Vector(0, 0, 0)); }
                t.setHealth(t.getMaxHealth());
            }
        }, RETURN_TICKS);
    }

    private void report(Player p, LivingEntity t, Location from, double bearing, double offset, int level, boolean sprinting, int kbLevel, long hitAt) {
        if (!p.isOnline() || !t.isValid()) return;
        Location to = t.getLocation();
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double moved = Math.hypot(dx, dz);
        Double dev = moved < 0.15 ? null : Knockback.signed(bearing, Knockback.yawOf(dx, dz));
        double expected = Knockback.expectedDeviation(offset, level);
        long[] away = awayWindow(p.getUniqueId(), bearing, hitAt);
        boolean ok = level > 0 && dev != null && Math.abs(dev) >= SUCCESS_DEG;
        if (level > 0) {
            int[] s = stats.get(p.getUniqueId());
            if (s == null) { s = new int[2]; stats.put(p.getUniqueId(), s); }
            s[0]++;
            if (ok) s[1]++;
        }
        String extra = level == 0 ? ChatColor.RED + "no extra knockback (not sprinting, no Knockback item)"
                : ChatColor.GRAY + "extra kb " + level + (sprinting ? " (sprint" + (kbLevel > 0 ? " + Knockback " + kbLevel : "") + ")" : " (Knockback " + kbLevel + ")");
        String look = away == null ? ChatColor.GRAY + "look away: not seen in your rotations"
                : ChatColor.GRAY + "look away: " + away[1] + " ms (" + (away[1] / 50 + 1) + " tick" + (away[1] >= 50 ? "s" : "") + "), max " + away[0] + " deg";
        p.sendMessage((ok ? ChatColor.GREEN + "DISPLACED " : ChatColor.YELLOW + "straight ")
                + ChatColor.WHITE + "yaw off " + fmt(offset) + " deg, flew " + (dev == null ? "?" : fmt(dev)) + " deg off straight"
                + ChatColor.GRAY + " (physics: " + fmt(expected) + ")");
        p.sendMessage("  " + extra + ChatColor.DARK_GRAY + " | " + look);
        log(p, offset, level, dev, expected, away, moved);
    }

    /** {max degrees away from the bearing, ms the look stayed >= AWAY_DEG away} around the hit, or null. */
    private long[] awayWindow(UUID id, double bearing, long hitAt) {
        Deque<long[]> q = looks.get(id);
        if (q == null) return null;
        List<long[]> s = new ArrayList<long[]>();
        for (Iterator<long[]> it = q.iterator(); it.hasNext(); ) { long[] x = it.next(); if (Math.abs(x[0] - hitAt) <= 500) s.add(x); }
        long start = -1, end = -1, max = 0;
        for (int i = 0; i < s.size(); i++) {
            double err = Math.abs(Knockback.signed(bearing, s.get(i)[1] / 1000.0));
            if (err >= AWAY_DEG) {
                if (start < 0 || s.get(i)[0] - end > 300) start = s.get(i)[0];
                end = i + 1 < s.size() ? s.get(i + 1)[0] : s.get(i)[0];
                if (Math.abs(s.get(i)[0] - hitAt) <= 300) max = Math.max(max, Math.round(err));
            }
        }
        if (start < 0 || max == 0) return null;
        return new long[]{max, Math.max(0, end - start)};
    }

    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.0f", v); }

    private void log(Player p, double offset, int level, Double dev, double expected, long[] away, double moved) {
        File f = new File(getDataFolder(), "displace.csv");
        boolean header = !f.exists();
        try (FileWriter w = new FileWriter(f, true)) {
            if (header) w.write("time,player,yawOffsetDeg,extraLevel,flewOffDeg,expectedDeg,awayMs,awayMaxDeg,movedBlocks\n");
            w.write(System.currentTimeMillis() + "," + p.getName() + "," + fmt(offset) + "," + level + "," + (dev == null ? "" : fmt(dev)) + ","
                    + fmt(expected) + "," + (away == null ? "" : away[1]) + "," + (away == null ? "" : away[0]) + "," + String.format(java.util.Locale.ROOT, "%.2f", moved) + "\n");
        } catch (IOException ignored) {
        }
    }

    // Targets: no trading, no other damage, no wandering.
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) { if (isTarget(e.getRightClicked()) || isSparring(e.getRightClicked())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if ((isTarget(e.getEntity()) || isSparring(e.getEntity())) && !(e instanceof EntityDamageByEntityEvent)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) { if (isTarget(e.getEntity()) || isSparring(e.getEntity())) e.setCancelled(true); }

    // The sparring dummy itself takes no damage (players hitting it back would only push it off its spot).
    @EventHandler(ignoreCancelled = true)
    public void onHitDummy(EntityDamageByEntityEvent e) { if (isSparring(e.getEntity())) e.setCancelled(true); }

    // ---- Taking knockback: a sparring dummy that hits you ----------------------------------------------------------

    private static final double SPAR_REACH = 4.0;      // the dummy only hits within this distance (eyes ignored)
    private static final int PUSH_MOVES = 10;          // push is measured over this many moves after the knockback lands
    private static final long MEASURE_MS = 1500;       // give up on a hit after this long
    private static final double JUMP_DY = 0.42;        // first move of a vanilla jump
    private static final double DY_EPS = 0.004;
    private static final double AFTER_JUMP_DY = (JUMP_DY - 0.08) * 0.98; // second move of a jump

    private final class Sparring {
        final UUID player;
        final Villager dummy;
        final Location home;
        final int level;
        int task = -1;
        Hit pending;

        Sparring(UUID player, Villager dummy, Location home, int level) {
            this.player = player; this.dummy = dummy; this.home = home; this.level = level;
        }
    }

    /** One hit on the player, followed through their next moves. */
    private final class Hit {
        final Player p;
        final long at;
        final int level;
        final double kbX, kbZ, kbY;    // the velocity sent
        final Location start;
        final boolean onGround;
        final int ping;
        final List<double[]> moves = new ArrayList<double[]>(); // {ms after the hit, dy, x, z}
        boolean done;

        Hit(Player p, long at, int level, double[] v, Location start, boolean onGround, int ping) {
            this.p = p; this.at = at; this.level = level; this.kbX = v[0]; this.kbY = v[1]; this.kbZ = v[2];
            this.start = start; this.onGround = onGround; this.ping = ping;
        }

        void move(Location from, Location to, long now) {
            if (done || from.getWorld() != start.getWorld()) return;
            moves.add(new double[]{now - at, to.getY() - from.getY(), to.getX(), to.getZ()});
            int rise = riseIndex();
            if (rise >= 0 && moves.size() - rise > PUSH_MOVES) finish();
        }

        /** The first move that went up by at least 0.3 (the knockback, or a jump), or -1. */
        int riseIndex() {
            for (int i = 0; i < moves.size(); i++) if (moves.get(i)[1] >= 0.3) return i;
            return -1;
        }

        void finish() {
            if (done) return;
            done = true;
            Sparring s = sparring.get(p.getUniqueId());
            if (s != null && s.pending == this) s.pending = null;
            if (p.isOnline()) reportKnockback(this);
        }
    }

    private boolean isSparring(Entity e) {
        if (e == null) return false;
        for (Sparring s : sparring.values()) if (s.dummy.getUniqueId().equals(e.getUniqueId())) return true;
        return false;
    }

    private void startSparring(final Player p, long intervalMs, int level) {
        stopSparring(p.getUniqueId(), false);
        Location eye = p.getLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(2.5);
        Location at = eye.clone().add(dir);
        at.setY(p.getLocation().getY());
        at.setYaw((float) Knockback.yawOf(-dir.getX(), -dir.getZ()));
        at.setPitch(0);
        Villager v = (Villager) p.getWorld().spawnEntity(at, EntityType.VILLAGER);
        v.setCustomName(ChatColor.RED + "Sparring dummy");
        v.setCustomNameVisible(true);
        v.setRemoveWhenFarAway(false);
        v.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 255, false, false), true);
        final Sparring s = new Sparring(p.getUniqueId(), v, at.clone(), level);
        sparring.put(p.getUniqueId(), s);
        long ticks = Math.max(5, Math.round(intervalMs / 50.0));
        s.task = getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override public void run() { swing(s); }
        }, 40L, ticks);
        p.sendMessage(ChatColor.GREEN + "Sparring dummy spawned. It hits you every " + ticks * 50 + " ms within " + (int) SPAR_REACH
                + " blocks, extra knockback " + level + (level == 1 ? " (like a sprint hit)" : "") + ". /ctt hitme stop to end.");
        if (p.getGameMode() == GameMode.CREATIVE) p.sendMessage(ChatColor.GRAY + "Creative players take no knockback: switch to survival.");
    }

    private void stopSparring(UUID id, boolean tell) {
        Sparring s = sparring.remove(id);
        if (s == null) {
            Player p = getServer().getPlayer(id);
            if (tell && p != null) p.sendMessage(ChatColor.GRAY + "No sparring dummy is running.");
            return;
        }
        if (s.task >= 0) getServer().getScheduler().cancelTask(s.task);
        if (s.dummy.isValid()) s.dummy.remove();
        Player p = getServer().getPlayer(id);
        if (tell && p != null) p.sendMessage(ChatColor.GRAY + "Sparring dummy removed.");
    }

    private void swing(Sparring s) {
        Player p = getServer().getPlayer(s.player);
        if (p == null || !s.dummy.isValid()) { stopSparring(s.player, false); return; }
        if (s.pending != null && System.currentTimeMillis() - s.pending.at > MEASURE_MS) s.pending.finish();
        if (s.pending != null) return; // still following the last hit
        if (p.getWorld() != s.home.getWorld() || p.isDead() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        Location pl = p.getLocation();
        double dx = pl.getX() - s.home.getX(), dz = pl.getZ() - s.home.getZ();
        if (Math.hypot(dx, dz) > SPAR_REACH) return;

        // Face the player (the extra knockback goes along the dummy's yaw, as with a player's hit).
        Location face = s.home.clone();
        face.setYaw((float) Knockback.yawOf(dx, dz));
        s.dummy.teleport(face);

        Vector old = p.getVelocity();
        double[] v = Knockback.hitVelocity(new double[]{old.getX(), old.getY(), old.getZ()}, dx, dz, face.getYaw(), s.level);
        @SuppressWarnings("deprecation") boolean onGround = p.isOnGround();
        p.setNoDamageTicks(0);
        p.damage(0.01); // the hurt animation and sound; no source, so no second knockback from the server
        p.setHealth(p.getMaxHealth());
        p.setVelocity(new Vector(v[0], v[1], v[2]));
        s.pending = new Hit(p, System.currentTimeMillis(), s.level, v, pl.clone(), onGround, ping(p));
    }

    private void reportKnockback(Hit h) {
        int rise = h.riseIndex();
        double vy = h.kbY;
        String jump;
        boolean reset = false;
        Long riseMs = null;
        if (rise < 0) {
            jump = ChatColor.GRAY + "no upward move seen";
        } else {
            double dy = h.moves.get(rise)[1];
            riseMs = Math.round(h.moves.get(rise)[0]);
            boolean jumped = Math.abs(dy - JUMP_DY) < DY_EPS && Math.abs(vy - JUMP_DY) >= DY_EPS;
            double next = rise + 1 < h.moves.size() ? h.moves.get(rise + 1)[1] : 0;
            if (!h.onGround) jump = ChatColor.GRAY + "already in the air when hit";
            else if (jumped && Math.abs(next - vy) < 0.01 && Math.abs(next - AFTER_JUMP_DY) > 0.005) jump = ChatColor.YELLOW + "jumped a tick early (the knockback came after the jump)";
            else if (jumped) { jump = ChatColor.GREEN + "JUMP RESET (jumped on the tick the knockback arrived)"; reset = true; }
            else if (Math.abs(dy - vy) < 0.02) jump = ChatColor.GRAY + "no jump on the knockback tick";
            else jump = ChatColor.GRAY + "first rise " + fmt3(dy) + " (knockback " + fmt3(vy) + ")";
        }
        // Push: distance travelled along the knockback direction from where the player stood when hit.
        double len = Math.hypot(h.kbX, h.kbZ);
        double ux = len > 1e-6 ? h.kbX / len : 0, uz = len > 1e-6 ? h.kbZ / len : 0;
        double push = 0, side = 0;
        if (!h.moves.isEmpty()) {
            double[] last = h.moves.get(h.moves.size() - 1);
            double mx = last[2] - h.start.getX(), mz = last[3] - h.start.getZ();
            push = mx * ux + mz * uz;
            side = -mx * uz + mz * ux;
        }
        double[] k = kbStats.get(h.p.getUniqueId());
        if (k == null) { k = new double[5]; kbStats.put(h.p.getUniqueId(), k); }
        k[0]++; k[2] += push;
        if (reset) { k[1]++; k[4] += push; } else k[3] += push;

        h.p.sendMessage(jump);
        h.p.sendMessage("  " + ChatColor.WHITE + "pushed " + fmt2(push) + " blocks" + ChatColor.GRAY + " (" + fmt2(side) + " sideways) over "
                + Math.min(h.moves.size(), (rise < 0 ? 0 : rise) + PUSH_MOVES) + " moves"
                + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "knockback seen " + (riseMs == null ? "?" : riseMs + " ms") + " after the hit, ping " + h.ping + " ms");
        logKnockback(h, jump, riseMs, push, side);
    }

    private void logKnockback(Hit h, String jump, Long riseMs, double push, double side) {
        File f = new File(getDataFolder(), "knockback.csv");
        boolean header = !f.exists();
        int rise = h.riseIndex();
        try (FileWriter w = new FileWriter(f, true)) {
            if (header) w.write("time,player,level,pingMs,onGroundAtHit,kbY,firstRiseMs,firstRiseDy,nextDy,result,pushBlocks,sideBlocks\n");
            String result = ChatColor.stripColor(jump).split(" \\(")[0].toLowerCase(java.util.Locale.ROOT);
            w.write(h.at + "," + h.p.getName() + "," + h.level + "," + h.ping + "," + h.onGround + "," + fmt3(h.kbY) + ","
                    + (riseMs == null ? "" : riseMs) + "," + (rise < 0 ? "" : fmt3(h.moves.get(rise)[1])) + ","
                    + (rise < 0 || rise + 1 >= h.moves.size() ? "" : fmt3(h.moves.get(rise + 1)[1])) + "," + result + ","
                    + fmt2(push) + "," + fmt2(side) + "\n");
        } catch (IOException ignored) {
        }
    }

    /** The server's ping estimate for the player (CraftPlayer -> EntityPlayer.ping), or -1. */
    private static int ping(Player p) {
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            return handle.getClass().getField("ping").getInt(handle);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String fmt2(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }

    private static String fmt3(double v) { return String.format(java.util.Locale.ROOT, "%.3f", v); }
}
