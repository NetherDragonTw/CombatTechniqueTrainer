package combattechnique;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Practice sessions: once a player turns one on, every measured hit adds to it until they reset it (it survives
 * leaving, rejoining and server restarts: plugins/CombatTechniqueTrainer/sessions.yml). Practice time only counts while
 * the session is on and the player is online. Shown in a sidebar scoreboard, refreshed every second.
 */
final class Sessions {
    static final class Session {
        boolean on;
        long practiceMs;          // time on, online
        long since;               // when the current online stretch started (0 = not counting)
        int displaceHits, displaced;
        int kbHits, jumpResets;
        double pushSum;
    }

    private final File file;
    private final Map<UUID, Session> sessions = new HashMap<UUID, Session>();
    private final Map<UUID, Scoreboard> boards = new HashMap<UUID, Scoreboard>();
    private final Map<UUID, List<String>> lines = new HashMap<UUID, List<String>>();

    Sessions(File dataFolder) {
        this.file = new File(dataFolder, "sessions.yml");
        load();
    }

    Session get(UUID id) { return sessions.get(id); }

    /** The session to count into, or null when the player has none on. */
    Session active(UUID id) { Session s = sessions.get(id); return s != null && s.on ? s : null; }

    void start(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) { s = new Session(); sessions.put(p.getUniqueId(), s); }
        if (!s.on) { s.on = true; s.since = System.currentTimeMillis(); }
        show(p);
        save();
    }

    void stop(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || !s.on) return;
        pause(s);
        s.on = false;
        hide(p);
        save();
    }

    void reset(Player p) {
        Session s = sessions.get(p.getUniqueId());
        boolean on = s != null && s.on;
        s = new Session();
        if (on) { s.on = true; s.since = System.currentTimeMillis(); }
        sessions.put(p.getUniqueId(), s);
        if (on) update(p);
        save();
    }

    private static void pause(Session s) {
        if (s.since > 0) { s.practiceMs += System.currentTimeMillis() - s.since; s.since = 0; }
    }

    void join(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || !s.on) return;
        s.since = System.currentTimeMillis();
        show(p);
    }

    void quit(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s != null && s.on) pause(s);
        boards.remove(p.getUniqueId());
        lines.remove(p.getUniqueId());
        save();
    }

    void addDisplace(UUID id, boolean ok) {
        Session s = active(id);
        if (s == null) return;
        s.displaceHits++;
        if (ok) s.displaced++;
    }

    void addKnockback(UUID id, boolean reset, double push) {
        Session s = active(id);
        if (s == null) return;
        s.kbHits++;
        if (reset) s.jumpResets++;
        s.pushSum += push;
    }

    static long timeMs(Session s) { return s.practiceMs + (s.since > 0 ? System.currentTimeMillis() - s.since : 0); }

    static String duration(long ms) {
        long sec = ms / 1000, h = sec / 3600, m = sec / 60 % 60, x = sec % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, x) : String.format(Locale.ROOT, "%d:%02d", m, x);
    }

    static String pct(int a, int b) { return b == 0 ? "-" : Math.round(100.0 * a / b) + "%"; }

    // ---- sidebar ----------------------------------------------------------------------------------------------------

    private void show(Player p) {
        Scoreboard b = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = b.registerNewObjective("ctt", "dummy");
        o.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Practice");
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        boards.put(p.getUniqueId(), b);
        lines.put(p.getUniqueId(), new ArrayList<String>());
        p.setScoreboard(b);
        update(p);
    }

    private void hide(Player p) {
        boards.remove(p.getUniqueId());
        lines.remove(p.getUniqueId());
        p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    /** Rewrites the sidebar lines that changed (entries are the text; scores only keep the order). */
    void update(Player p) {
        Session s = active(p.getUniqueId());
        Scoreboard b = boards.get(p.getUniqueId());
        if (s == null || b == null) return;
        Objective o = b.getObjective("ctt");
        List<String> want = new ArrayList<String>();
        want.add(ChatColor.GRAY + "Time " + ChatColor.WHITE + duration(timeMs(s)));
        want.add(ChatColor.RESET + " ");
        want.add(ChatColor.YELLOW + "Displacement");
        want.add(ChatColor.GRAY + " Hits " + ChatColor.WHITE + s.displaceHits);
        want.add(ChatColor.GRAY + " Displaced " + ChatColor.WHITE + s.displaced);
        want.add(ChatColor.GRAY + " Rate " + ChatColor.GREEN + pct(s.displaced, s.displaceHits));
        want.add(ChatColor.RESET + "  ");
        want.add(ChatColor.AQUA + "Knockback taken");
        want.add(ChatColor.GRAY + " Hits " + ChatColor.WHITE + s.kbHits + ChatColor.RESET);
        want.add(ChatColor.GRAY + " Jump resets " + ChatColor.WHITE + s.jumpResets);
        want.add(ChatColor.GRAY + " Rate " + ChatColor.GREEN + pct(s.jumpResets, s.kbHits) + ChatColor.RESET);
        want.add(ChatColor.GRAY + " Avg push " + ChatColor.WHITE + (s.kbHits == 0 ? "-" : String.format(Locale.ROOT, "%.2f", s.pushSum / s.kbHits)));
        List<String> have = lines.get(p.getUniqueId());
        for (String old : have) if (!want.contains(old)) b.resetScores(old);
        for (int i = 0; i < want.size(); i++) {
            String line = want.get(i).length() > 40 ? want.get(i).substring(0, 40) : want.get(i);
            o.getScore(line).setScore(want.size() - i);
        }
        lines.put(p.getUniqueId(), want);
    }

    // ---- storage ----------------------------------------------------------------------------------------------------

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String k : y.getKeys(false)) {
            ConfigurationSection c = y.getConfigurationSection(k);
            if (c == null) continue;
            try {
                Session s = new Session();
                s.on = c.getBoolean("on");
                s.practiceMs = c.getLong("practiceMs");
                s.displaceHits = c.getInt("displaceHits");
                s.displaced = c.getInt("displaced");
                s.kbHits = c.getInt("kbHits");
                s.jumpResets = c.getInt("jumpResets");
                s.pushSum = c.getDouble("pushSum");
                sessions.put(UUID.fromString(k), s);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            String k = e.getKey().toString();
            y.set(k + ".on", s.on);
            y.set(k + ".practiceMs", timeMs(s));
            y.set(k + ".displaceHits", s.displaceHits);
            y.set(k + ".displaced", s.displaced);
            y.set(k + ".kbHits", s.kbHits);
            y.set(k + ".jumpResets", s.jumpResets);
            y.set(k + ".pushSum", s.pushSum);
        }
        try { y.save(file); } catch (IOException ignored) {}
    }

    /** On shutdown: close every online stretch so it is saved. */
    void close() {
        for (Session s : sessions.values()) pause(s);
        save();
    }
}
