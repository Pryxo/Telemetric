package dev.telemetric.game;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * Joins a server id with Mojang's session server, the same call vanilla makes when joining an online-mode server.
 * The HeadStatus API challenge auth uses it. The access token only ever goes to Mojang, never to the API.
 */
public final class MojangSessionAdapter {

    private MojangSessionAdapter() {}

    /** Name of the signed-in account (for {@code X-Player-Name}). */
    public static String playerName() {
        return Minecraft.getInstance().getUser().getName();
    }

    /** UUID of the signed-in account (dashed form, for {@code /status/{uuid}}). */
    public static String playerUuid() {
        return Minecraft.getInstance().getUser().getProfileId().toString();
    }

    /**
     * Joins {@code serverId} on Mojang's session server for the signed-in account. Blocks on the network, so call it
     * off the client thread.
     *
     * @return the account name that joined
     * @throws Exception if Mojang refuses (offline account, bad token, …)
     */
    public static String join(String serverId) throws Exception {
        Minecraft client = Minecraft.getInstance();
        User user = client.getUser();
        client.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);
        return user.getName();
    }
}
