package combattechnique;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The few 1.8.8 (v1_8_R3) server internals the player NPC needs, by reflection so the plugin builds against the
 * Bukkit API only. Names used here are the ones Spigot keeps unobfuscated (setLocation, getId, playerConnection,
 * sendPacket) or are found by parameter / field TYPE.
 */
final class Nms {
    private Nms() {}

    static final String CB = Bukkit.getServer().getClass().getPackage().getName();       // org.bukkit.craftbukkit.v1_8_R3
    static final String NMS = "net.minecraft.server." + CB.substring(CB.lastIndexOf('.') + 1);

    static Class<?> nms(String name) throws ClassNotFoundException { return Class.forName(NMS + "." + name); }

    static Class<?> cb(String name) throws ClassNotFoundException { return Class.forName(CB + "." + name); }

    static Object handle(Object craft) throws Exception { return craft.getClass().getMethod("getHandle").invoke(craft); }

    /** A constructor whose parameters accept these argument types (first match). */
    static Object create(Class<?> c, Object... args) throws Exception {
        outer:
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            Class<?>[] p = k.getParameterTypes();
            if (p.length != args.length) continue;
            for (int i = 0; i < p.length; i++) {
                if (args[i] == null) { if (p[i].isPrimitive()) continue outer; continue; }
                if (!box(p[i]).isInstance(args[i])) continue outer;
            }
            k.setAccessible(true);
            return k.newInstance(args);
        }
        throw new NoSuchMethodException("no constructor of " + c.getSimpleName() + " for these arguments");
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == int.class) return Integer.class;
        if (c == byte.class) return Byte.class;
        if (c == boolean.class) return Boolean.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == long.class) return Long.class;
        if (c == short.class) return Short.class;
        return Character.class;
    }

    /** First instance field of the given type (any superclass), or null. */
    static Field fieldOfType(Class<?> c, Class<?> type) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    static void send(Player p, Object packet) {
        try {
            Object conn = handle(p).getClass().getField("playerConnection").get(handle(p));
            for (Method m : conn.getClass().getMethods()) {
                if (m.getName().equals("sendPacket") && m.getParameterTypes().length == 1) { m.invoke(conn, packet); return; }
            }
        } catch (Exception ignored) {
        }
    }

    /** The Netty channel of a player's connection, or null. */
    static io.netty.channel.Channel channel(Player p) {
        try {
            Object h = handle(p);
            Object conn = h.getClass().getField("playerConnection").get(h);
            Field nm = fieldOfType(conn.getClass(), nms("NetworkManager"));
            Object manager = nm.get(conn);
            Field ch = fieldOfType(manager.getClass(), io.netty.channel.Channel.class);
            return (io.netty.channel.Channel) ch.get(manager);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- the NPC entity --------------------------------------------------------------------------------------------

    /** A server-side player entity that is never added to the world (so the server neither ticks nor saves it). */
    static Object newPlayer(World world, UUID id, String name) throws Exception {
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object ws = handle(world);
        Object profile = Class.forName("com.mojang.authlib.GameProfile").getConstructor(UUID.class, String.class).newInstance(id, name);
        Object manager = create(nms("PlayerInteractManager"), ws);
        return create(nms("EntityPlayer"), server, ws, profile, manager);
    }

    static int id(Object entity) throws Exception { return (Integer) entity.getClass().getMethod("getId").invoke(entity); }

    static void setLocation(Object entity, double x, double y, double z, float yaw, float pitch) throws Exception {
        entity.getClass().getMethod("setLocation", double.class, double.class, double.class, float.class, float.class).invoke(entity, x, y, z, yaw, pitch);
    }

    static Object playerInfo(String action, Object player) throws Exception {
        Class<?> c = nms("PacketPlayOutPlayerInfo");
        Class<?> e = null;
        for (Class<?> k : c.getDeclaredClasses()) if (k.isEnum() && k.getSimpleName().equals("EnumPlayerInfoAction")) e = k;
        if (e == null) throw new ClassNotFoundException("EnumPlayerInfoAction");
        Object act = null;
        for (Object o : e.getEnumConstants()) if (((Enum<?>) o).name().equals(action)) act = o;
        Object arr = Array.newInstance(nms("EntityPlayer"), 1);
        Array.set(arr, 0, player);
        return create(c, act, arr);
    }

    static Object spawn(Object player) throws Exception { return create(nms("PacketPlayOutNamedEntitySpawn"), player); }

    static Object teleport(Object entity) throws Exception { return create(nms("PacketPlayOutEntityTeleport"), entity); }

    static Object headRotation(Object entity, float yaw) throws Exception {
        return create(nms("PacketPlayOutEntityHeadRotation"), entity, (byte) Math.floor(yaw * 256f / 360f));
    }

    /** 0 = swing the arm, 1 = hurt (red flash). */
    static Object animation(Object entity, int kind) throws Exception { return create(nms("PacketPlayOutAnimation"), entity, kind); }

    static Object destroy(int id) throws Exception { return create(nms("PacketPlayOutEntityDestroy"), (Object) new int[]{id}); }

    static Object equipment(int id, int slot, ItemStack item) throws Exception {
        Object nmsItem = cb("inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class).invoke(null, item);
        return create(nms("PacketPlayOutEntityEquipment"), id, slot, nmsItem);
    }

    // ---- reading packets by field type (the obfuscated names change, the layout does not) ---------------------------

    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, List<Field>> FIELDS = new java.util.concurrent.ConcurrentHashMap<Class<?>, List<Field>>();

    static List<Field> fields(Class<?> c) {
        List<Field> cached = FIELDS.get(c);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<Field>();
        List<Class<?>> chain = new ArrayList<Class<?>>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) chain.add(0, k);
        for (Class<?> k : chain) for (Field f : k.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            out.add(f);
        }
        FIELDS.put(c, out);
        return out;
    }

    static Object nth(Object o, Class<?> type, int n) {
        int i = 0;
        for (Field f : fields(o.getClass())) {
            if (!(type.isPrimitive() ? f.getType() == type : type.isAssignableFrom(f.getType()))) continue;
            if (i++ == n) try { return f.get(o); } catch (IllegalAccessException e) { return null; }
        }
        return null;
    }

    static String enumName(Object o) {
        for (Field f : fields(o.getClass())) {
            if (!f.getType().isEnum()) continue;
            try { Object v = f.get(o); return v == null ? null : ((Enum<?>) v).name(); } catch (IllegalAccessException e) { return null; }
        }
        return null;
    }
}
