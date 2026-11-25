package dev.telemetric.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

/**
 * MC ADAPTER for Combat → Sneak Ability. Since 1.21.6 the server learns about sneaking only from
 * {@code ServerboundPlayerInputPacket} (the {@code shift} flag of {@link Input}); there is no separate sneak command.
 * Vanilla sends that packet at most once per tick and only when the input changed ({@code LocalPlayer#lastSentInput}).
 * <ul>
 *   <li>{@link #sendShift} sends one extra input packet that copies the last sent input with another shift flag.
 *       {@code lastSentInput} is left alone, so a sequence that ends on the starting flag leaves vanilla in sync.</li>
 *   <li>{@link #forceShiftKey} makes the vanilla sneak key read as held / released ({@code KeyboardInputMixin}),
 *       so the player really crouches for those ticks.</li>
 * </ul>
 * Everything is a no-op off Telos.
 */
public final class SneakAdapter {

    private SneakAdapter() {}

    private static volatile Boolean forcedShift;

    /** True on Telos with a player and a connection. */
    public static boolean ready() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.getConnection() != null && ServerGate.onTelos();
    }

    /** The shift flag the server last got from vanilla (false outside a world). */
    public static boolean sentShift() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.getLastSentInput().shift();
    }

    /** Sends one input packet: the last sent input with {@code shift}. False if nothing was sent. */
    public static boolean sendShift(boolean shift) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientPacketListener connection = mc.getConnection();
        if (p == null || connection == null || !ServerGate.onTelos()) return false;
        Input in = p.getLastSentInput();
        connection.send(new ServerboundPlayerInputPacket(
                new Input(in.forward(), in.backward(), in.left(), in.right(), in.jump(), shift, in.sprint())));
        return true;
    }

    /** Sneak key read as held ({@code true}), released ({@code false}) or as the real key ({@code null}). */
    public static void forceShiftKey(Boolean state) {
        forcedShift = state;
    }

    /** {@code KeyboardInputMixin} reads this every tick. */
    public static Boolean forcedShift() {
        Boolean f = forcedShift;
        return f != null && ServerGate.onTelos() ? f : null;
    }
}
