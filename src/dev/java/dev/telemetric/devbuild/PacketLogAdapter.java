package dev.telemetric.devbuild;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * DEV MC ADAPTER — turns received packets into readable log lines for {@link PacketLogModule}.
 * {@link #onReceive} runs on the Netty thread (from {@code ConnectionMixin}) and only queues; the module drains
 * the queue on the client thread. Packets are dumped by reflection (all instance fields), with item stacks shown
 * with their components and text components as plain strings.
 */
public final class PacketLogAdapter {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_LINE = 6000, MAX_DEPTH = 4, MAX_ITEMS = 40, MAX_QUEUE = 200_000;

    /** Always dropped (heartbeats / clock). */
    private static final Set<String> SKIP = Set.of(
            "ClientboundKeepAlivePacket", "ClientboundPingPacket", "ClientboundSetTimePacket",
            "ClientboundBundleDelimiterPacket", "ClientboundPongResponsePacket");
    /** Dropped while "Hide Noise" is on. */
    private static final Set<String> NOISE = Set.of(
            "ClientboundMoveEntityPacket$Pos", "ClientboundMoveEntityPacket$Rot", "ClientboundMoveEntityPacket$PosRot",
            "ClientboundTeleportEntityPacket", "ClientboundEntityPositionSyncPacket", "ClientboundRotateHeadPacket",
            "ClientboundSetEntityMotionPacket", "ClientboundMoveMinecartPacket", "ClientboundUpdateAttributesPacket",
            "ClientboundLevelChunkWithLightPacket", "ClientboundLightUpdatePacket", "ClientboundForgetLevelChunkPacket",
            "ClientboundChunkBatchStartPacket", "ClientboundChunkBatchFinishedPacket",
            "ClientboundSetChunkCacheCenterPacket", "ClientboundChunksBiomesPacket");
    private static final String PARTICLES = "ClientboundLevelParticlesPacket";

    static volatile boolean enabled, hideNoise = true, particles;
    static final Queue<String> QUEUE = new ConcurrentLinkedQueue<>();

    private PacketLogAdapter() {}

    /** Netty thread. */
    public static void onReceive(Packet<?> packet) {
        if (!enabled || packet == null) return;
        try {
            log(packet, LocalTime.now().format(TIME), "");
        } catch (Throwable t) {
            QUEUE.add("!! could not format " + packet.getClass().getName() + ": " + t);
        }
    }

    private static void log(Packet<?> packet, String time, String prefix) {
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> sub : bundle.subPackets()) log(sub, time, prefix + "[bundle] ");
            return;
        }
        String name = name(packet.getClass());
        if (SKIP.contains(name) || hideNoise && NOISE.contains(name) || !particles && PARTICLES.equals(name)) return;
        if (QUEUE.size() >= MAX_QUEUE) return;
        String line = time + " " + prefix + name + " " + fields(packet, 0);
        if (line.length() > MAX_LINE) line = line.substring(0, MAX_LINE) + " …(cut)";
        QUEUE.add(line);
    }

    /** Client thread: player position + dimension, or null when not in a world. */
    static String playerSpot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        Vec3 p = mc.player.position();
        return String.format("%.1f %.1f %.1f in %s", p.x, p.y, p.z, dev.telemetric.game.DungeonAdapter.dimensionId());
    }

    /** Client thread: player position, or null. */
    static Vec3 playerPos() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : mc.player.position();
    }

    // ------------------------------------------------------------------ formatting

    private static String name(Class<?> c) {
        String n = c.getName();
        return n.substring(n.lastIndexOf('.') + 1);
    }

    private static String fields(Object o, int depth) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Class<?> c = o.getClass(); c != null && c != Object.class && c != Record.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Object v;
                try {
                    f.setAccessible(true);
                    v = f.get(o);
                } catch (Throwable t) {
                    v = "<" + t.getClass().getSimpleName() + ">";
                }
                if (!first) sb.append(", ");
                first = false;
                sb.append(f.getName()).append('=').append(value(v, depth + 1));
            }
        }
        return sb.append('}').toString();
    }

    private static String value(Object v, int depth) {
        if (v == null) return "null";
        if (v instanceof CharSequence s) return '"' + s.toString() + '"';
        if (v instanceof Number || v instanceof Boolean || v instanceof Enum<?> || v instanceof Character) return v.toString();
        if (v instanceof Component c) return "text\"" + c.getString() + '"';
        if (v instanceof ItemStack s) {
            if (s.isEmpty()) return "item(empty)";
            return "item(" + s.getCount() + "x " + s.typeHolder().getRegisteredName() + " " + s.getComponentsPatch() + ")";
        }
        if (v instanceof Optional<?> opt) return opt.map(x -> value(x, depth)).orElse("empty");
        if (depth > MAX_DEPTH) return String.valueOf(v);
        if (v instanceof Collection<?> col) return iter(col, depth);
        if (v instanceof Map<?, ?> map) return iter(map.entrySet(), depth);
        if (v instanceof Map.Entry<?, ?> e) return value(e.getKey(), depth) + ":" + value(e.getValue(), depth);
        if (v.getClass().isArray()) return v.getClass().getComponentType().isPrimitive()
                ? v.getClass().getComponentType().getName() + "[" + java.lang.reflect.Array.getLength(v) + "]"
                : iter(java.util.Arrays.asList((Object[]) v), depth);
        if (v instanceof Record r) return name(r.getClass()) + fields(r, depth);
        if (v.getClass().getName().startsWith("net.minecraft.network.protocol.")) return name(v.getClass()) + fields(v, depth);
        return String.valueOf(v);
    }

    private static String iter(Collection<?> col, int depth) {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (Object x : col) {
            if (i > 0) sb.append(", ");
            if (i++ >= MAX_ITEMS) {
                sb.append("…+").append(col.size() - MAX_ITEMS);
                break;
            }
            sb.append(value(x, depth + 1));
        }
        return sb.append(']').toString();
    }
}
