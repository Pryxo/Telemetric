package dev.telemetric.devbuild;

import dev.telemetric.Telemetric;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.BooleanSetting;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * TEMP research tool: appends every packet the client receives to {@code config/telemetric/packets.log}
 * (one line per packet, all fields dumped). Off by default. Marks teleports (> 8 blocks in one tick) with a
 * {@code ## TELEPORT} line so the packets around entering a puzzle are easy to find. Stops at {@link #MAX_BYTES}.
 */
final class PacketLogModule extends Module {

    private static final long MAX_BYTES = 200L * 1024 * 1024;
    private static final double TELEPORT_DIST_SQ = 8 * 8;

    private final Path file = FabricLoader.getInstance().getConfigDir().resolve(Telemetric.MOD_ID).resolve("packets.log");
    private final BooleanSetting hideNoise = add(new BooleanSetting("Hide Noise",
            "Skip entity movement, chunk and light packets", true));
    private final BooleanSetting particles = add(new BooleanSetting("Particles",
            "Also log particle packets (very many)", false));
    private Vec3 lastPos;
    private boolean full;

    PacketLogModule() {
        super("Packet Log", "TEMP: writes every received packet to config/telemetric/packets.log", DevCategory.DEV);
        add(new ActionSetting("Mark", "Write a ## MARK line (with your position) into the log", () -> mark("MARK")));
        add(new ActionSetting("Clear", "Empty packets.log", this::clear));
    }

    @Override
    protected void onEnable() {
        full = false;
        lastPos = null;
        PacketLogAdapter.QUEUE.clear();
        mark("START");
        PacketLogAdapter.enabled = true;
    }

    @Override
    protected void onDisable() {
        PacketLogAdapter.enabled = false;
        mark("STOP");
        flush();
    }

    @Override
    public void onTick() {
        PacketLogAdapter.hideNoise = hideNoise.get();
        PacketLogAdapter.particles = particles.get();
        Vec3 pos = PacketLogAdapter.playerPos();
        if (pos != null && lastPos != null && pos.distanceToSqr(lastPos) > TELEPORT_DIST_SQ) {
            PacketLogAdapter.QUEUE.add(String.format("## TELEPORT from %.1f %.1f %.1f to %s",
                    lastPos.x, lastPos.y, lastPos.z, PacketLogAdapter.playerSpot()));
        }
        lastPos = pos;
        flush();
    }

    private void mark(String what) {
        PacketLogAdapter.QUEUE.add("## " + what + " " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + " at " + PacketLogAdapter.playerSpot());
    }

    private void flush() {
        if (PacketLogAdapter.QUEUE.isEmpty()) return;
        if (full) {
            PacketLogAdapter.QUEUE.clear();
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                String line;
                while ((line = PacketLogAdapter.QUEUE.poll()) != null) {
                    w.write(line);
                    w.newLine();
                }
            }
            if (Files.size(file) > MAX_BYTES) {
                full = true;
                Files.writeString(file, "## FULL — logging stopped, press Clear\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
        } catch (IOException ex) {
            Telemetric.LOGGER.warn("Packet Log: could not write {}: {}", file, ex.toString());
            PacketLogAdapter.QUEUE.clear();
        }
    }

    private void clear() {
        PacketLogAdapter.QUEUE.clear();
        full = false;
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            Telemetric.LOGGER.warn("Packet Log: could not delete {}: {}", file, ex.toString());
        }
        if (isEnabled()) mark("CLEARED");
    }
}
