package dev.telemetric.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

/**
 * Whether we're on Telos Realms. The mod (ClickGUI, HUD, module ticks, keybinds) only runs while connected to a server
 * whose address matches {@code *.telosrealms.*}, e.g. {@code play.telosrealms.com} or {@code telosrealms.com}. In
 * singleplayer, on LAN or on other servers it's off.
 */
public final class ServerGate {

    private ServerGate() {}

    public static boolean onTelos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.isLocalServer()) return false;
        ServerData server = mc.getCurrentServer();
        if (server == null) return false;
        String ip = server.ip;
        if (ip != lastIp) { // cheap enough per entity and per frame: the address is only parsed when the server changes
            lastIp = ip;
            lastResult = isTelosAddress(ip);
        }
        return lastResult;
    }

    private static String lastIp;
    private static boolean lastResult;

    /** True if {@code address} (host, optional {@code :port}) has a {@code telosrealms} label before the TLD. */
    static boolean isTelosAddress(String address) {
        if (address == null) return false;
        String host = address.trim().toLowerCase(Locale.ROOT);
        int colon = host.lastIndexOf(':');
        if (colon >= 0 && host.indexOf(':') == colon) host = host.substring(0, colon); // strip the port (not for IPv6)
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        String[] labels = host.split("\\.");
        for (int i = 0; i < labels.length - 1; i++) {
            if (labels[i].equals("telosrealms")) return true;
        }
        return false;
    }
}
