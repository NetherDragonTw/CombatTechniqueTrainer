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
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LargeFireball;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.Sound;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import org.bukkit.event.player.PlayerVelocityEvent;
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
    private final Map<UUID, Mover> movers = new HashMap<UUID, Mover>();            // moving targets
    private final Map<UUID, String> partners = new HashMap<UUID, String>();        // players whose hits taken are measured
    private int moverTask = -1;
    private final Map<UUID, PlayerNpc> npcs = new HashMap<UUID, PlayerNpc>();                 // owner -> their player NPC
    private final java.util.concurrent.ConcurrentHashMap<Integer, PlayerNpc> npcById = new java.util.concurrent.ConcurrentHashMap<Integer, PlayerNpc>();
    private int npcTask = -1;
    private final Map<UUID, Hit> partnerHits = new HashMap<UUID, Hit>();          // player -> knockback from a partner being followed
    private final Map<UUID, Object[]> partnerHitAt = new HashMap<UUID, Object[]>(); // player -> {ms, partner name, level, start, onGround}
    private final Map<UUID, Deque<long[]>> looks = new HashMap<UUID, Deque<long[]>>(); // player -> (ms, yaw * 1000)
    private final Map<UUID, int[]> stats = new HashMap<UUID, int[]>();             // player -> {hits with extra kb, displaced}
    private final Map<UUID, Sparring> sparring = new HashMap<UUID, Sparring>();    // player -> the dummy hitting them
    private final Map<UUID, double[]> kbStats = new HashMap<UUID, double[]>();     // player -> {hits, jump resets, push sum, push sum without / with a reset}

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getDataFolder().mkdirs();
        for (Player p : getServer().getOnlinePlayers()) listen(p);
        sessions = new Sessions(getDataFolder());
        for (Player p : getServer().getOnlinePlayers()) sessions.join(p);
        getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override public void run() { for (Player p : getServer().getOnlinePlayers()) sessions.update(p); }
        }, 20L, 20L);
    }

    private Sessions sessions;

    @Override
    public void onDisable() {
        clearTargets();
        for (UUID id : new ArrayList<UUID>(sparring.keySet())) stopSparring(id, false);
        for (UUID id : new ArrayList<UUID>(npcs.keySet())) removeNpc(id);
        for (Player p : getServer().getOnlinePlayers()) unlisten(p);
        if (sessions != null) sessions.close();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage("Players only."); return true; }
        Player p = (Player) sender;
        String sub = args.length > 0 ? args[0].toLowerCase() : "help";
        if (sub.equals("target") || sub.equals("spawn")) {
            boolean move = args.length > 1 && args[1].equalsIgnoreCase("move");
            int i = move ? 2 : 1;
            double speed = 2.5, width = 4, distance = 3.0;
            if (move && args.length > i) { speed = clamp(args[i], 0.5, 6, speed); i++; }
            if (move && args.length > i) { width = clamp(args[i], 1, 10, width); i++; }
            if (args.length > i) distance = clamp(args[i], 1.5, 6, distance);
            Villager v = spawn(p, distance, move);
            if (move) {
                Location home = targets.get(v.getUniqueId());
                Vector side = new Vector(-Math.cos(Math.toRadians(home.getYaw())), 0, -Math.sin(Math.toRadians(home.getYaw())));
                movers.put(v.getUniqueId(), new Mover(v, home, side, width / 2, speed));
                startMovers();
                p.sendMessage(ChatColor.GRAY + "It walks side to side over " + fmt1(width) + " blocks at " + fmt1(speed) + " blocks/s (a sprinting player moves about 5.6, a strafing one about 4.3).");
            }
        } else if (sub.equals("partner")) {
            partner(p, args);
        } else if (sub.equals("fireball")) {
            if (args.length > 1 && (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("stop"))) { stopFireballs(p, true); return true; }
            long interval = args.length > 1 ? (long) clamp(args[1], 1000, 20000, 4000) : 4000;
            double distance = args.length > 2 ? clamp(args[2], 4, 30, 12) : 12;
            startFireballs(p, interval, distance);
        } else if (sub.equals("npc")) {
            if (args.length > 1 && args[1].equalsIgnoreCase("setback")) {
                Integer t = args.length > 2 ? parseTicks(args[2]) : null;
                if (t == null) {
                    p.sendMessage(ChatColor.RED + "/ctt npc setback <time>" + ChatColor.GRAY + " - e.g. 30t (ticks) or 1.5s (seconds), 5t-200t; now "
                            + npcSetback(p.getUniqueId()) + "t (" + fmt2(npcSetback(p.getUniqueId()) / 20.0) + "s)");
                    return true;
                }
                npcSetbacks.put(p.getUniqueId(), t);
                PlayerNpc cur = npcs.get(p.getUniqueId());
                if (cur != null) cur.setbackTicks = t;
                p.sendMessage(ChatColor.GREEN + "Player dummy goes back to its spot " + t + " ticks (" + fmt2(t / 20.0) + " s) after each hit.");
                return true;
            }
            if (args.length > 1 && (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("remove"))) {
                p.sendMessage(ChatColor.GRAY + (removeNpc(p.getUniqueId()) ? "Player dummy removed." : "You have no player dummy."));
                return true;
            }
            long interval = args.length > 1 ? (long) clamp(args[1], 0, 5000, 1000) : 1000;
            if (interval > 0 && interval < 250) interval = 250;
            int level = args.length > 2 ? (int) clamp(args[2], 0, 5, 1) : 1;
            double distance = args.length > 3 ? clamp(args[3], 1.5, 6, 3) : 3;
            spawnNpc(p, interval, level, distance);
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
            partners.clear();
            for (UUID id : new ArrayList<UUID>(sparring.keySet())) stopSparring(id, false);
            n += npcs.size();
            for (UUID id : new ArrayList<UUID>(npcs.keySet())) removeNpc(id);
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
        } else if (sub.equals("session")) {
            String op = args.length > 1 ? args[1].toLowerCase() : "";
            Sessions.Session cur = sessions.get(p.getUniqueId());
            if (op.equals("on") || op.equals("start")) {
                sessions.start(p);
                p.sendMessage(ChatColor.GREEN + "Practice session on" + (cur != null && cur.displaceHits + cur.kbHits > 0 ? " (continuing: /ctt session reset to start over)." : ".")
                        + ChatColor.GRAY + " It keeps counting across logins until you reset it.");
            } else if (op.equals("off") || op.equals("stop")) {
                sessions.stop(p);
                p.sendMessage(ChatColor.GRAY + "Practice session paused (kept). /ctt session on to continue.");
            } else if (op.equals("reset")) {
                sessions.reset(p);
                p.sendMessage(ChatColor.GRAY + "Practice session reset.");
            } else {
                if (cur == null) { p.sendMessage(ChatColor.GRAY + "No session yet: /ctt session on"); return true; }
                p.sendMessage(ChatColor.YELLOW + "Session " + (cur.on ? "on" : "paused") + ", " + Sessions.duration(Sessions.timeMs(cur))
                        + ": displaced " + cur.displaced + "/" + cur.displaceHits + " (" + Sessions.pct(cur.displaced, cur.displaceHits) + "), jump resets "
                        + cur.jumpResets + "/" + cur.kbHits + " (" + Sessions.pct(cur.jumpResets, cur.kbHits) + ")");
            }
        } else if (sub.equals("reset")) {
            stats.remove(p.getUniqueId());
            kbStats.remove(p.getUniqueId());
            p.sendMessage(ChatColor.GRAY + "Your results were reset.");
        } else {
            p.sendMessage(ChatColor.YELLOW + "/ctt target [distance]" + ChatColor.GRAY + " - a target in front of you (default 3 blocks)");
            p.sendMessage(ChatColor.YELLOW + "/ctt target move [speed] [width] [distance]" + ChatColor.GRAY + " - a target that walks side to side (default 2.5 blocks/s over 4 blocks)");
            p.sendMessage(ChatColor.YELLOW + "/ctt partner add|remove <player>" + ChatColor.GRAY + " / " + ChatColor.YELLOW + "list" + ChatColor.GRAY + " - measure your hits on a friend too");
            p.sendMessage(ChatColor.YELLOW + "/ctt stick [level]" + ChatColor.GRAY + " - a Knockback stick (default Knockback I, given with /ctt target)");
            p.sendMessage(ChatColor.YELLOW + "/ctt npc [interval_ms] [level] [distance]" + ChatColor.GRAY + " - a player dummy: hit it (displacement) and it hits you back with a swing (knockback); interval 0 = it never hits; /ctt npc off");
            p.sendMessage(ChatColor.YELLOW + "/ctt npc setback <30t|1.5s>" + ChatColor.GRAY + " - how long after a hit it is put back on its spot (default 30 ticks)");
            p.sendMessage(ChatColor.YELLOW + "/ctt fireball [interval_ms] [distance]" + ChatColor.GRAY + " - fireballs thrown at you to hit back (default every 4000 ms from 12 blocks; from your player dummy if you have one); /ctt fireball off");
            p.sendMessage(ChatColor.YELLOW + "/ctt hitme [interval_ms] [level]" + ChatColor.GRAY + " - a dummy that hits you (default every 1000 ms, extra knockback 1 like a sprint hit)");
            p.sendMessage(ChatColor.YELLOW + "/ctt hitme stop" + ChatColor.GRAY + " - stop it");
            p.sendMessage(ChatColor.YELLOW + "/ctt clear" + ChatColor.GRAY + " - remove all dummies");
            p.sendMessage(ChatColor.YELLOW + "/ctt session on|off|reset" + ChatColor.GRAY + " - a practice session on the sidebar: hits, success rates, time (kept until you reset it)");
            p.sendMessage(ChatColor.YELLOW + "/ctt stats" + ChatColor.GRAY + " / " + ChatColor.YELLOW + "/ctt reset" + ChatColor.GRAY + " - your results");
            p.sendMessage(ChatColor.GRAY + "Displacement: sprint or use a Knockback item, it only turns the extra knockback.");
        }
        return true;
    }

    private static double clamp(String v, double min, double max, double dflt) {
        try { return Math.max(min, Math.min(max, Double.parseDouble(v))); } catch (NumberFormatException e) { return dflt; }
    }

    private Villager spawn(Player p, double distance, boolean moving) {
        Location eye = p.getLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(distance);
        Location at = eye.clone().add(dir);
        at.setY(p.getLocation().getY());
        at.setYaw((float) Knockback.yawOf(-dir.getX(), -dir.getZ())); // facing the player
        at.setPitch(0);
        Villager v = (Villager) p.getWorld().spawnEntity(at, EntityType.VILLAGER);
        v.setCustomName(ChatColor.GOLD + (moving ? "Moving target" : "Displace target"));
        v.setCustomNameVisible(true);
        v.setRemoveWhenFarAway(false);
        v.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, Integer.MAX_VALUE, 255, false, false), true);
        v.setMaxHealth(1024); // never dies between two heals
        v.setHealth(1024);
        targets.put(v.getUniqueId(), at.clone());
        giveStick(p, 1);
        p.sendMessage(ChatColor.GREEN + "Target spawned " + distance + " blocks ahead. Sprint-hit it while turning your look away for the hit.");
        return v;
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
        movers.clear();
        if (moverTask >= 0) { getServer().getScheduler().cancelTask(moverTask); moverTask = -1; }
    }

    private boolean isTarget(Entity e) { return e != null && (targets.containsKey(e.getUniqueId()) || partners.containsKey(e.getUniqueId())); }

    private boolean isDummyTarget(Entity e) { return e != null && targets.containsKey(e.getUniqueId()); }

    // ---- Moving targets: walk side to side across the line of the hit, paused while knocked back ----------------

    private static final class Mover {
        final Villager v;
        final Location home;      // middle of the path, facing the player who spawned it
        final Vector side;        // unit vector across
        final double half, speed; // half the path length, blocks per second
        double along;             // distance walked so far
        boolean paused;

        Mover(Villager v, Location home, Vector side, double half, double speed) {
            this.v = v; this.home = home; this.side = side; this.half = half; this.speed = speed;
        }

        /** Where it stands now: a triangle wave between -half and +half. */
        Location at() {
            double period = 4 * half, s = along % period;
            double off = s < 2 * half ? -half + s : 3 * half - s;
            Location l = home.clone().add(side.clone().multiply(off));
            l.setYaw(home.getYaw());
            l.setPitch(0);
            return l;
        }
    }

    private void startMovers() {
        if (moverTask >= 0) return;
        moverTask = getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override public void run() {
                for (Iterator<Mover> it = movers.values().iterator(); it.hasNext(); ) {
                    Mover m = it.next();
                    if (!m.v.isValid()) { it.remove(); continue; }
                    if (m.paused) continue;
                    m.along += m.speed / 20.0;
                    m.v.teleport(m.at());
                }
            }
        }, 1L, 1L);
    }

    // ---- Partners: a friend whose hits taken are measured like a target's ------------------------------------------

    private void partner(Player p, String[] args) {
        String op = args.length > 1 ? args[1].toLowerCase() : "list";
        if (op.equals("list")) {
            p.sendMessage(ChatColor.GRAY + "Partners: " + (partners.isEmpty() ? "none" : String.join(", ", partners.values())));
            return;
        }
        if (args.length < 3) { p.sendMessage(ChatColor.RED + "/ctt partner add|remove <player>"); return; }
        if (op.equals("remove")) {
            Player q = getServer().getPlayerExact(args[2]);
            boolean removed = q != null ? partners.remove(q.getUniqueId()) != null : partners.values().removeIf(n -> n.equalsIgnoreCase(args[2]));
            p.sendMessage(ChatColor.GRAY + (removed ? "Removed " + args[2] + "." : args[2] + " is not a partner."));
            return;
        }
        if (!op.equals("add")) { p.sendMessage(ChatColor.RED + "/ctt partner add|remove <player>"); return; }
        Player q = getServer().getPlayerExact(args[2]);
        if (q == null) { p.sendMessage(ChatColor.RED + args[2] + " is not online."); return; }
        if (q.equals(p)) { p.sendMessage(ChatColor.RED + "Hit someone else."); return; }
        partners.put(q.getUniqueId(), q.getName());
        p.sendMessage(ChatColor.GREEN + "Practice partner " + q.getName() + ": your hits on them are measured (their own movement adds to where they fly), "
                + "and so is the knockback you take from them (jump resets). They are healed after each of your hits.");
        q.sendMessage(ChatColor.GRAY + p.getName() + " added you as a practice partner: hits between you are measured.");
    }

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
        Hit ph = partnerHits.get(e.getPlayer().getUniqueId());
        if (ph != null) ph.move(e.getFrom(), e.getTo(), now);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        removeNpc(e.getPlayer().getUniqueId());
        stopFireballs(e.getPlayer(), false);
        sessions.quit(e.getPlayer());
        unlisten(e.getPlayer());
        looks.remove(e.getPlayer().getUniqueId());
        partners.remove(e.getPlayer().getUniqueId());
        partnerHits.remove(e.getPlayer().getUniqueId());
        partnerHitAt.remove(e.getPlayer().getUniqueId());
        stopSparring(e.getPlayer().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!isTarget(e.getEntity()) || !(e.getDamager() instanceof Player)) return;
        final Player p = (Player) e.getDamager();
        if (!(e.getEntity() instanceof LivingEntity)) return;
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
        final boolean dummy = isDummyTarget(t);
        final Mover mover = movers.get(t.getUniqueId());
        final String kind = mover != null ? "moving" : dummy ? "dummy" : "partner";
        if (mover != null) mover.paused = true;
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() { report(p, t, from, bearing, offset, level, sprinting, kbLevel, hitAt, kind); if (dummy && t.isValid()) t.setHealth(t.getMaxHealth()); }
        }, MEASURE_TICKS);
        if (!dummy) { // a partner is a real player: nothing to put back, but practice should not kill them
            getServer().getScheduler().runTaskLater(this, new Runnable() {
                @Override public void run() { if (t.isValid() && !t.isDead()) t.setHealth(t.getMaxHealth()); }
            }, 1L);
            return;
        }
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() {
                if (!t.isValid()) return;
                Location home = mover != null ? mover.at() : targets.get(t.getUniqueId());
                if (home != null) { t.teleport(home); t.setVelocity(new Vector(0, 0, 0)); }
                t.setHealth(t.getMaxHealth());
                if (mover != null) mover.paused = false;
            }
        }, RETURN_TICKS);
    }

    private void report(Player p, LivingEntity t, Location from, double bearing, double offset, int level, boolean sprinting, int kbLevel, long hitAt, String kind) {
        if (!p.isOnline() || !t.isValid()) return;
        reportDisplace(p, from, t.getLocation(), bearing, offset, level, sprinting, kbLevel, hitAt, kind);
    }

    void reportDisplace(Player p, Location from, Location to, double bearing, double offset, int level, boolean sprinting, int kbLevel, long hitAt, String kind) {
        if (!p.isOnline()) return;
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
            sessions.addDisplace(p.getUniqueId(), ok);
        }
        String extra = level == 0 ? ChatColor.RED + "no extra knockback (not sprinting, no Knockback item)"
                : ChatColor.GRAY + "extra kb " + level + (sprinting ? " (sprint" + (kbLevel > 0 ? " + Knockback " + kbLevel : "") + ")" : " (Knockback " + kbLevel + ")");
        String look = away == null ? ChatColor.GRAY + "look away: not seen in your rotations"
                : ChatColor.GRAY + "look away: " + away[1] + " ms (" + (away[1] / 50 + 1) + " tick" + (away[1] >= 50 ? "s" : "") + "), max " + away[0] + " deg";
        p.sendMessage((ok ? ChatColor.GREEN + "DISPLACED " : ChatColor.YELLOW + "straight ")
                + ChatColor.WHITE + "yaw off " + fmt(offset) + " deg, flew " + (dev == null ? "?" : fmt(dev)) + " deg off straight"
                + ChatColor.GRAY + " (physics: " + fmt(expected) + ")");
        p.sendMessage("  " + extra + ChatColor.DARK_GRAY + " | " + look);
        log(p, offset, level, dev, expected, away, moved, kind);
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

    private static String fmt1(double v) { return String.format(java.util.Locale.ROOT, "%.1f", v); }

    private void log(Player p, double offset, int level, Double dev, double expected, long[] away, double moved, String kind) {
        File f = new File(getDataFolder(), "displace.csv");
        boolean header = !f.exists();
        try (FileWriter w = new FileWriter(f, true)) {
            if (header) w.write("time,player,target,yawOffsetDeg,extraLevel,flewOffDeg,expectedDeg,awayMs,awayMaxDeg,movedBlocks\n");
            w.write(System.currentTimeMillis() + "," + p.getName() + "," + kind + "," + fmt(offset) + "," + level + "," + (dev == null ? "" : fmt(dev)) + ","
                    + fmt(expected) + "," + (away == null ? "" : away[1]) + "," + (away == null ? "" : away[0]) + "," + String.format(java.util.Locale.ROOT, "%.2f", moved) + "\n");
        } catch (IOException ignored) {
        }
    }

    // Targets: no trading, no other damage, no wandering.
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) { if (isDummyTarget(e.getRightClicked()) || isSparring(e.getRightClicked())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if ((isDummyTarget(e.getEntity()) || isSparring(e.getEntity())) && !(e instanceof EntityDamageByEntityEvent)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) { if (isDummyTarget(e.getEntity()) || isSparring(e.getEntity())) e.setCancelled(true); }

    // The sparring dummy itself takes no damage (players hitting it back would only push it off its spot).
    @EventHandler(ignoreCancelled = true)
    public void onHitDummy(EntityDamageByEntityEvent e) { if (isSparring(e.getEntity())) e.setCancelled(true); }

    // ---- Player dummy: a player NPC you hit (displacement) and that hits you with a swing (knockback) ------------------

    private static final double NPC_REACH = 3.0;   // it hits you within this distance (feet to feet)
    private static final long HURT_COOLDOWN_MS = 500; // vanilla: a second hit within 10 ticks gives no knockback
    private static final String LISTENER = "ctt_npc";
    private int tick;

    // ---- Fireball practice: fireballs thrown at you to hit back ------------------------------------------------------

    private final Map<UUID, Integer> fireballTasks = new HashMap<UUID, Integer>();  // player -> repeating task
    private final Map<UUID, Object[]> shots = new HashMap<UUID, Object[]>();        // fireball -> {player uuid, launch ms, from}

    private void startFireballs(final Player p, long intervalMs, final double distance) {
        stopFireballs(p, false);
        final UUID id = p.getUniqueId();
        long ticks = Math.max(20, Math.round(intervalMs / 50.0));
        int task = getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override public void run() {
                Player q = getServer().getPlayer(id);
                if (q == null) { stopFireballs(null, false); return; }
                launch(q, distance);
            }
        }, 40L, ticks);
        fireballTasks.put(id, task);
        p.sendMessage(ChatColor.GREEN + "Fireballs every " + ticks * 50 + " ms" + (npcs.containsKey(id) ? " from your player dummy." : " from " + fmt1(distance) + " blocks in front of where you face now.")
                + " Hit them back; each one is timed from its throw. /ctt fireball off to stop.");
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        fireFrom.put(id, eye.clone().add(dir.normalize().multiply(distance)).add(0, 1, 0));
    }

    private final Map<UUID, Location> fireFrom = new HashMap<UUID, Location>();

    private void stopFireballs(Player p, boolean tell) {
        if (p == null) {
            for (Iterator<Map.Entry<UUID, Integer>> it = fireballTasks.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Integer> e = it.next();
                if (getServer().getPlayer(e.getKey()) == null) { getServer().getScheduler().cancelTask(e.getValue()); it.remove(); fireFrom.remove(e.getKey()); }
            }
            return;
        }
        Integer t = fireballTasks.remove(p.getUniqueId());
        fireFrom.remove(p.getUniqueId());
        if (t != null) getServer().getScheduler().cancelTask(t);
        if (tell) p.sendMessage(ChatColor.GRAY + (t != null ? "Fireballs stopped." : "No fireballs running."));
    }

    private void launch(Player p, double distance) {
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return;
        PlayerNpc n = npcs.get(p.getUniqueId());
        Location from;
        if (n != null && n.world == p.getWorld()) { from = n.location().add(0, 1.62, 0); n.swingArm(); }
        else from = fireFrom.get(p.getUniqueId());
        if (from == null || from.getWorld() != p.getWorld()) return;
        Vector dir = p.getEyeLocation().toVector().subtract(from.toVector());
        if (dir.lengthSquared() < 4) return;
        dir.normalize();
        Location at = from.clone().add(dir.clone().multiply(1.0));
        at.setDirection(dir);
        LargeFireball f = (LargeFireball) p.getWorld().spawn(at, LargeFireball.class);
        f.setDirection(dir);
        f.setVelocity(dir.clone().multiply(0.6));
        f.setYield(0f);          // no explosion damage to blocks or players
        f.setIsIncendiary(false);
        shots.put(f.getUniqueId(), new Object[]{p.getUniqueId(), System.currentTimeMillis(), from.clone()});
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFireballHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Fireball) || !(e.getDamager() instanceof Player)) return;
        Object[] s = shots.remove(e.getEntity().getUniqueId());
        if (s == null) return;
        Player p = (Player) e.getDamager();
        long react = System.currentTimeMillis() - (Long) s[1];
        double dist = e.getEntity().getLocation().distance(p.getEyeLocation());
        p.sendMessage(ChatColor.GREEN + "Returned " + ChatColor.WHITE + "in " + react + " ms" + ChatColor.GRAY + " (fireball " + fmt1(dist) + " blocks from your eyes)");
        if (p.getUniqueId().equals(s[0])) sessions.addFireball(p.getUniqueId(), react);
        logFireball(p, react, dist, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFireballLand(ProjectileHitEvent e) {
        Object[] s = shots.remove(e.getEntity().getUniqueId());
        if (s == null) return;
        Player p = getServer().getPlayer((UUID) s[0]);
        if (p == null) return;
        p.sendMessage(ChatColor.RED + "Not returned" + ChatColor.GRAY + " (it hit after " + (System.currentTimeMillis() - (Long) s[1]) + " ms)");
        sessions.addFireball(p.getUniqueId(), null);
        logFireball(p, null, null, false);
        final UUID id = p.getUniqueId();
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() { Player q = getServer().getPlayer(id); if (q != null && !q.isDead()) q.setHealth(q.getMaxHealth()); }
        }, 2L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFireballExplode(EntityExplodeEvent e) {
        if (shots.containsKey(e.getEntity().getUniqueId()) || e.getEntity() instanceof Fireball && e.getEntity().hasMetadata("ctt")) e.blockList().clear();
    }

    private void logFireball(Player p, Long react, Double dist, boolean returned) {
        File f = new File(getDataFolder(), "fireballs.csv");
        boolean header = !f.exists();
        try (FileWriter w = new FileWriter(f, true)) {
            if (header) w.write("time,player,returned,reactionMs,distanceBlocks\n");
            w.write(System.currentTimeMillis() + "," + p.getName() + "," + returned + "," + (react == null ? "" : react) + "," + (dist == null ? "" : fmt2(dist)) + "\n");
        } catch (IOException ignored) {
        }
    }
    private final Map<UUID, Integer> npcSetbacks = new HashMap<UUID, Integer>(); // owner -> setback ticks, kept for the next dummy

    private int npcSetback(UUID owner) { Integer t = npcSetbacks.get(owner); return t == null ? RETURN_TICKS : t; }

    /** "30t" / "30" = ticks, "1.5s" = seconds; 5-200 ticks, or null. */
    static Integer parseTicks(String v) {
        String x = v.trim().toLowerCase(java.util.Locale.ROOT);
        double ticks;
        try {
            if (x.endsWith("s")) ticks = Double.parseDouble(x.substring(0, x.length() - 1)) * 20;
            else if (x.endsWith("t")) ticks = Double.parseDouble(x.substring(0, x.length() - 1));
            else ticks = Double.parseDouble(x);
        } catch (NumberFormatException e) {
            return null;
        }
        if (Double.isNaN(ticks)) return null;
        return (int) Math.max(5, Math.min(200, Math.round(ticks)));
    }

    private void spawnNpc(Player p, long interval, int level, double distance) {
        removeNpc(p.getUniqueId());
        Location eye = p.getLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) dir = new Vector(0, 0, 1);
        dir.normalize().multiply(distance);
        Location at = eye.clone().add(dir);
        at.setY(p.getLocation().getY());
        at.setYaw((float) Knockback.yawOf(-dir.getX(), -dir.getZ()));
        PlayerNpc n;
        try {
            n = new PlayerNpc(p.getUniqueId(), at, npcName(), interval, level);
            n.setbackTicks = npcSetback(p.getUniqueId());
        } catch (Throwable t) {
            p.sendMessage(ChatColor.RED + "Cannot create a player dummy on this server (" + t.getClass().getSimpleName() + ": " + t.getMessage() + "). It needs Spigot / Paper 1.8.8.");
            getLogger().warning("Player dummy failed: " + t);
            return;
        }
        npcs.put(p.getUniqueId(), n);
        npcById.put(n.id, n);
        for (Player v : n.world.getPlayers()) n.show(v);
        n.nextHit = System.currentTimeMillis() + 2000;
        startNpcTask();
        giveStick(p, 1);
        p.sendMessage(ChatColor.GREEN + "Player dummy " + n.name + " spawned " + fmt1(distance) + " blocks ahead (on flat ground: it lands at this height). "
                + (interval > 0 ? "It hits you every " + interval + " ms within " + fmt1(NPC_REACH) + " blocks, extra knockback " + level + "." : "It does not hit back.")
                + " Hit it to practise displacement. /ctt npc off to remove.");
    }

    private String npcName() {
        for (int i = 1; ; i++) {
            String n = i == 1 ? "Sparring" : "Sparring" + i;
            boolean used = getServer().getPlayerExact(n) != null;
            for (PlayerNpc x : npcs.values()) if (x.name.equals(n)) used = true;
            if (!used) return n;
        }
    }

    private boolean removeNpc(UUID owner) {
        PlayerNpc n = npcs.remove(owner);
        if (n == null) return false;
        npcById.remove(n.id);
        for (Player v : n.world.getPlayers()) n.hide(v);
        return true;
    }

    private void startNpcTask() {
        if (npcTask >= 0) return;
        npcTask = getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            @Override public void run() {
                tick++;
                if (npcs.isEmpty()) { getServer().getScheduler().cancelTask(npcTask); npcTask = -1; return; }
                for (PlayerNpc n : new ArrayList<PlayerNpc>(npcs.values())) npcTick(n);
            }
        }, 1L, 1L);
    }

    private void npcTick(PlayerNpc n) {
        n.tick();
        if (n.returnTick >= 0 && tick >= n.returnTick) { n.returnTick = -1; n.home(); }
        Player o = getServer().getPlayer(n.owner);
        if (o != null && o.getWorld() == n.world && !n.moving) {
            Location ol = o.getLocation();
            n.yaw = (float) Knockback.yawOf(ol.getX() - n.x, ol.getZ() - n.z);
        }
        n.sync(false);
        if (o == null || o.getWorld() != n.world || n.intervalMs <= 0 || n.moving || n.returnTick >= 0) return;
        long now = System.currentTimeMillis();
        if (now < n.nextHit || o.isDead() || o.getGameMode() == GameMode.CREATIVE || o.getGameMode() == GameMode.SPECTATOR) return;
        Hit last = partnerHits.get(o.getUniqueId());
        if (last != null && !last.done) return; // still following its last hit
        Location ol = o.getLocation();
        double dx = ol.getX() - n.x, dz = ol.getZ() - n.z;
        if (Math.hypot(dx, dz) > NPC_REACH || Math.abs(ol.getY() - n.y) > 2) return;
        n.nextHit = now + n.intervalMs;
        n.swingArm(); // the swing comes before the hit, as with a 1.8 client
        Vector old = o.getVelocity();
        double[] v = Knockback.hitVelocity(new double[]{old.getX(), old.getY(), old.getZ()}, dx, dz, n.yaw, n.level);
        @SuppressWarnings("deprecation") boolean onGround = o.isOnGround();
        o.setNoDamageTicks(0);
        o.damage(0.01); // hurt animation and sound; no source, so the server adds no knockback of its own
        o.setHealth(o.getMaxHealth());
        o.setVelocity(new Vector(v[0], v[1], v[2]));
        final Hit h = new Hit(o, now, n.level, v, ol.clone(), onGround, ping(o), n.name);
        partnerHits.put(o.getUniqueId(), h);
        final UUID id = o.getUniqueId();
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() { if (partnerHits.get(id) == h) h.finish(); }
        }, MEASURE_MS / 50);
    }

    /** A player attacked the dummy (from the Netty thread, moved to the main thread). */
    private void npcHit(Player p, PlayerNpc n, float yaw, boolean sprinting, long at) {
        if (!p.isOnline() || p.getWorld() != n.world || !npcs.containsValue(n)) return;
        Location pl = p.getLocation();
        if (Math.hypot(pl.getX() - n.x, pl.getZ() - n.z) > 6 || at - n.lastHurt < HURT_COOLDOWN_MS) return;
        n.lastHurt = at;
        ItemStack held = p.getItemInHand();
        int kbLevel = held == null ? 0 : held.getEnchantmentLevel(Enchantment.KNOCKBACK);
        int level = kbLevel + (sprinting ? 1 : 0);
        final Location from = n.location();
        double bearing = Knockback.yawOf(n.x - pl.getX(), n.z - pl.getZ());
        double offset = Knockback.signed(bearing, yaw);
        n.knock(pl.getX(), pl.getZ(), yaw, level);
        n.hurtFlash();
        n.world.playSound(from, Sound.HURT_FLESH, 1f, 1f);
        n.returnTick = tick + n.setbackTicks;
        final Player fp = p;
        final PlayerNpc fn = n;
        final double fb = bearing, fo = offset;
        final int fl = level, fk = kbLevel;
        final boolean fs = sprinting;
        final long fat = at;
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() { reportDisplace(fp, from, fn.location(), fb, fo, fl, fs, fk, fat, "npc"); }
        }, MEASURE_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        listen(p);
        sessions.join(p);
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() { for (PlayerNpc n : npcs.values()) if (p.isOnline() && n.world == p.getWorld()) n.show(p); }
        }, 10L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        final Player p = e.getPlayer();
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() { for (PlayerNpc n : npcs.values()) if (p.isOnline() && n.world == p.getWorld()) n.show(p); }
        }, 10L);
    }

    /**
     * Reads each player's own packets as they arrive: the last look they sent (the yaw the server would use for a hit),
     * their sprint state, and attacks on a player dummy (the server ignores those: the dummy is not in its world).
     */
    private final class NpcListener extends ChannelDuplexHandler {
        final Player p;
        volatile float yaw;
        volatile boolean sprinting;

        NpcListener(Player p) { this.p = p; this.yaw = p.getLocation().getYaw(); this.sprinting = p.isSprinting(); }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try { read(msg); } catch (Throwable ignored) {}
            super.channelRead(ctx, msg);
        }

        private void read(Object m) {
            String c = m.getClass().getSimpleName();
            if (c.equals("PacketPlayInLook") || c.equals("PacketPlayInPositionLook")) {
                Object y = Nms.nth(m, float.class, 0);
                if (y != null) yaw = (Float) y;
            } else if (c.equals("PacketPlayInEntityAction")) {
                String a = Nms.enumName(m);
                if ("START_SPRINTING".equals(a)) sprinting = true;
                else if ("STOP_SPRINTING".equals(a)) sprinting = false;
            } else if (c.equals("PacketPlayInUseEntity") && !npcById.isEmpty()) {
                Object id = Nms.nth(m, int.class, 0);
                final PlayerNpc n = id == null ? null : npcById.get((Integer) id);
                if (n == null || !"ATTACK".equals(Nms.enumName(m))) return;
                final float y = yaw;
                final boolean s = sprinting;
                final long at = System.currentTimeMillis();
                // A sprint-hit ends the attacker's sprint (the client does the same on its side).
                sprinting = false;
                getServer().getScheduler().runTask(CombatTechniqueTrainerPlugin.this, new Runnable() {
                    @Override public void run() { npcHit(p, n, y, s, at); }
                });
            }
        }
    }

    private void listen(Player p) {
        final Channel ch = Nms.channel(p);
        if (ch == null) return;
        final NpcListener l = new NpcListener(p);
        ch.eventLoop().execute(new Runnable() {
            @Override public void run() {
                if (ch.pipeline().get(LISTENER) != null) ch.pipeline().remove(LISTENER);
                if (ch.pipeline().get("packet_handler") != null) ch.pipeline().addBefore("packet_handler", LISTENER, l);
            }
        });
    }

    private void unlisten(Player p) {
        final Channel ch = Nms.channel(p);
        if (ch == null) return;
        ch.eventLoop().execute(new Runnable() {
            @Override public void run() { if (ch.pipeline().get(LISTENER) != null) ch.pipeline().remove(LISTENER); }
        });
    }

    // ---- Partners hitting you: the real knockback the server gave, followed like the dummy's -----------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPartnerHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player) || !(e.getEntity() instanceof Player)) return;
        Player a = (Player) e.getDamager(), v = (Player) e.getEntity();
        if (!partners.containsKey(a.getUniqueId()) || partners.containsKey(v.getUniqueId())) return;
        Hit old = partnerHits.get(v.getUniqueId());
        if (old != null) old.finish(); // a new hit ends the last one
        ItemStack held = a.getItemInHand();
        int level = (held == null ? 0 : held.getEnchantmentLevel(Enchantment.KNOCKBACK)) + (a.isSprinting() ? 1 : 0);
        @SuppressWarnings("deprecation") boolean onGround = v.isOnGround();
        partnerHitAt.put(v.getUniqueId(), new Object[]{System.currentTimeMillis(), a.getName(), level, v.getLocation().clone(), onGround});
        final UUID id = v.getUniqueId();
        // The knockback goes out with the server's entity tracker later in this tick (PlayerVelocityEvent); if no
        // event came, use the velocity the server set.
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() {
                Player q = getServer().getPlayer(id);
                if (q != null && partnerHitAt.containsKey(id)) startPartnerHit(q, q.getVelocity());
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        if (partnerHitAt.containsKey(e.getPlayer().getUniqueId())) startPartnerHit(e.getPlayer(), e.getVelocity());
    }

    private void startPartnerHit(Player v, Vector vel) {
        Object[] h = partnerHitAt.remove(v.getUniqueId());
        if (h == null || System.currentTimeMillis() - (Long) h[0] > 200) return;
        Hit hit = new Hit(v, (Long) h[0], (Integer) h[2], new double[]{vel.getX(), vel.getY(), vel.getZ()}, (Location) h[3], (Boolean) h[4], ping(v), (String) h[1]);
        partnerHits.put(v.getUniqueId(), hit);
        final UUID id = v.getUniqueId();
        final Hit fh = hit;
        getServer().getScheduler().runTaskLater(this, new Runnable() {
            @Override public void run() { if (partnerHits.get(id) == fh) fh.finish(); }
        }, MEASURE_MS / 50);
    }

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
        final String source;           // "dummy", or the partner who hit
        final List<double[]> moves = new ArrayList<double[]>(); // {ms after the hit, dy, x, z}
        boolean done;

        Hit(Player p, long at, int level, double[] v, Location start, boolean onGround, int ping, String source) {
            this.p = p; this.at = at; this.level = level; this.kbX = v[0]; this.kbY = v[1]; this.kbZ = v[2];
            this.start = start; this.onGround = onGround; this.ping = ping; this.source = source;
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
            if (partnerHits.get(p.getUniqueId()) == this) partnerHits.remove(p.getUniqueId());
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
        s.pending = new Hit(p, System.currentTimeMillis(), s.level, v, pl.clone(), onGround, ping(p), "dummy");
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
        sessions.addKnockback(h.p.getUniqueId(), reset, push);

        h.p.sendMessage((h.source.equals("dummy") ? "" : ChatColor.DARK_GRAY + "[" + h.source + "] ") + jump);
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
            if (header) w.write("time,player,level,pingMs,onGroundAtHit,kbY,firstRiseMs,firstRiseDy,nextDy,result,pushBlocks,sideBlocks,source\n");
            String result = ChatColor.stripColor(jump).split(" \\(")[0].toLowerCase(java.util.Locale.ROOT);
            w.write(h.at + "," + h.p.getName() + "," + h.level + "," + h.ping + "," + h.onGround + "," + fmt3(h.kbY) + ","
                    + (riseMs == null ? "" : riseMs) + "," + (rise < 0 ? "" : fmt3(h.moves.get(rise)[1])) + ","
                    + (rise < 0 || rise + 1 >= h.moves.size() ? "" : fmt3(h.moves.get(rise + 1)[1])) + "," + result + ","
                    + fmt2(push) + "," + fmt2(side) + "," + h.source + "\n");
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
