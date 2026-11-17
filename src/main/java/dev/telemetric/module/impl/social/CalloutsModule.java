package dev.telemetric.module.impl.social;

import dev.telemetric.Telemetric;
import dev.telemetric.chat.Callouts;
import dev.telemetric.chat.ChatLink;
import dev.telemetric.chat.ChatText;
import dev.telemetric.data.CalloutTable;
import dev.telemetric.data.TelosData;
import dev.telemetric.game.BossBarAdapter;
import dev.telemetric.game.BossBarAdapter.KnownBar;
import dev.telemetric.game.ChatAdapter;
import dev.telemetric.game.ChatHudAdapter;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.PortalAdapter;
import dev.telemetric.game.TelosLocations;
import dev.telemetric.game.TelosServer;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.KeybindSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Social → Callouts:
 * <ul>
 *   <li><b>Callout Key</b>: sends the boss you fight ({@code Teleport for X - 42% HP}, in dungeons with its phase from
 *       {@code callouts.json}), the portal next to you ({@code … - 12s left}) or {@code Currently in <area>};
 *       a public call is held back while someone else just called the same thing in your realm;</li>
 *   <li><b>Click to Teleport</b>: other players' call-outs get a link that teleports you to them — across realms on a
 *       second click ({@code /joinq} the caller's realm, then {@code /tp} once you arrived);</li>
 *   <li><b>Realm Links</b>: the {@code [realm]} tag of player chat joins that realm (right-click: join + teleport).</li>
 * </ul>
 * Call-outs are player text: they are only remembered and linked, never acted on without your click.
 */
public class CalloutsModule extends Module {

    private static final long CONFIRM_MS = 5000, CROSS_REALM_TIMEOUT_MS = 10_000, REALM_CHECK_MS = 250;

    private final KeybindSetting calloutKey = add(new KeybindSetting("Callout Key",
            "Sends the boss / portal / dungeon you are at to chat", InputUtil.KEY_F6).onPress(this::callOut));
    private final BooleanSetting clickToTeleport = add(new BooleanSetting("Click to Teleport",
            "Click another player's call-out to teleport to them (twice when they are in another realm)", true));
    private final BooleanSetting realmLinks = add(new BooleanSetting("Realm Links",
            "Click the [realm] tag of a chat line to join that realm; right-click joins and teleports to the sender", true));
    private final BooleanSetting respectCooldown = add(new BooleanSetting("Wait for Others",
            "Hold back a public call-out of something another player just called in your realm", true));

    private final Callouts memory = new Callouts();
    private String confirmPlayer;
    private long confirmUntil;
    private String pendingPlayer, pendingRealm;
    private long pendingUntil, lastRealmCheck;

    public CalloutsModule() {
        super("Callouts", "Boss / portal call-out key, click-to-teleport and realm links in chat", Category.SOCIAL);
        ChatHudAdapter.addLinker(this::links);
    }

    // ------------------------------------------------------------------ incoming lines

    /** Every new chat line: remembers call-outs, links them and the realm tag. */
    private List<ChatLink> links(String plain) {
        if (!isEnabled()) return List.of();
        Callouts.Line line = Callouts.line(plain);
        if (line == null) return List.of();
        long now = System.currentTimeMillis();
        Callouts.Call call = Callouts.parse(plain);
        if (call != null) memory.remember(call, now);
        String player = line.player();
        if (player.equalsIgnoreCase(PlayerAdapter.name())) return List.of();

        List<ChatLink> out = new ArrayList<>(2);
        String realm = line.realm();
        if (realmLinks.get() && realm != null && !line.isShout() && TelosData.REALMS.get().isKnown(line.tag())) {
            String shown = line.tag();
            out.add(new ChatLink(0, line.prefixEnd(), "Join " + shown + "\nRight-click: join & teleport to " + player,
                    () -> join(shown), () -> {
                        memory.rememberRealm(player, shown, System.currentTimeMillis());
                        teleportTo(player);
                    }));
        }
        if (call != null && clickToTeleport.get() && line.contentStart() < plain.length()) {
            out.add(new ChatLink(line.contentStart(), plain.length(), "Click to teleport to " + player,
                    () -> teleportTo(player), null));
        }
        return out;
    }

    private void join(String realm) {
        TelosServer here = TelosServer.current();
        if (here != null && here.realm().equalsIgnoreCase(realm)) {
            post("You are already in " + realm);
            return;
        }
        PlayerAdapter.sendCommand("joinq " + realm);
    }

    /** {@code /tp} in your realm; in another realm: confirm with a second click, then join + teleport on arrival. */
    private void teleportTo(String player) {
        long now = System.currentTimeMillis();
        String theirs = memory.realmOf(player, now);
        TelosServer here = TelosServer.current();
        if (theirs == null || (here != null && here.realmKey().equals(theirs))) {
            PlayerAdapter.sendCommand("tp " + player);
            return;
        }
        if (player.equalsIgnoreCase(confirmPlayer) && now <= confirmUntil) {
            confirmPlayer = null;
            pendingPlayer = player;
            pendingRealm = theirs;
            pendingUntil = now + CROSS_REALM_TIMEOUT_MS;
            PlayerAdapter.sendCommand("joinq " + theirs);
            post("Joining " + theirs + " to teleport to " + player + "…");
        } else {
            confirmPlayer = player;
            confirmUntil = now + CONFIRM_MS;
            post(player + " is in " + theirs + " — click again to join and teleport");
        }
    }

    @Override
    public void onTick() {
        if (pendingPlayer == null) return;
        long now = System.currentTimeMillis();
        if (now > pendingUntil) {
            post("Cross-realm teleport to " + pendingPlayer + " timed out");
            pendingPlayer = null;
            return;
        }
        if (now - lastRealmCheck < REALM_CHECK_MS) return;
        lastRealmCheck = now;
        TelosServer here = TelosServer.current();
        if (here != null && here.realmKey().equals(pendingRealm)) {
            PlayerAdapter.sendCommand("tp " + pendingPlayer);
            pendingPlayer = null;
        }
    }

    @Override
    protected void onDisable() {
        pendingPlayer = null;
        confirmPlayer = null;
    }

    // ------------------------------------------------------------------ callout key

    private void callOut() {
        if (!PlayerAdapter.isInWorld()) return;
        CalloutTable table = TelosData.CALLOUTS.get();
        String area = PlayerAdapter.locationName();
        List<KnownBar> bars = BossBarAdapter.knownBossBars();
        String target = null, message = null;
        int hp = -1;

        if (DungeonAdapter.isInDungeon()) {
            String where = area == null ? "a dungeon" : area;
            KnownBar boss = bars.isEmpty() ? null : bars.getFirst();
            CalloutTable.Duo duo = boss == null ? null : table.duoOf(boss.boss());
            if (duo != null) {
                int a = duoHp(bars, table, duo.bosses().get(0)), b = duoHp(bars, table, duo.bosses().get(1));
                target = duo.name();
                hp = Math.min(a, b);
                message = Callouts.duoAt(duo.name(), a, b, where);
            } else if (boss != null && boss.percent() > 0) {
                hp = table.bossPercent(boss.boss(), boss.percent());
                String phase = table.phase(boss.boss(), hp);
                target = boss.boss();
                message = Callouts.bossAt(boss.boss(), hp, phase != null ? phase : where);
            } else {
                PlayerAdapter.sendChat(Callouts.currentlyIn(where)); // no boss yet: nothing to cool down
                return;
            }
        } else if (isNexus()) {
            post("No boss or portal here");
            return;
        } else {
            KnownBar boss = bars.isEmpty() ? null : bars.getFirst();
            if (boss != null && boss.percent() > 0) {
                hp = table.bossPercent(boss.boss(), boss.percent());
                target = boss.boss();
                message = Callouts.teleport(target, hp);
            } else {
                PortalAdapter.Portal portal = PortalAdapter.nearest();
                String model = portal == null ? null
                        : portal.model().isEmpty() && table.unmodelledArea().equalsIgnoreCase(area) ? table.unmodelledPortal()
                        : portal.model();
                String label = model == null || model.isEmpty() ? null : table.portalLabel(model, TelosLocations.DUNGEONS);
                if (label != null) {
                    target = label;
                    message = Callouts.portal(label, portal.secondsLeft());
                } else if (portal != null && !portal.model().isEmpty()) {
                    Telemetric.LOGGER.warn("Callouts: unknown portal model {}", portal.model());
                }
            }
        }
        if (message == null) {
            post("No boss or portal nearby");
            return;
        }
        send(target, hp, message);
    }

    /** Sends a call-out, unless (public chat) someone just called the same thing in this realm. */
    private void send(String target, int hp, String message) {
        long now = System.currentTimeMillis();
        TelosServer here = TelosServer.current();
        ChatModule chat = Telemetric.get().modules().get(ChatModule.class);
        boolean publicChat = chat == null || chat.channels().isDefault();
        if (publicChat && here != null) {
            long wait = respectCooldown.get()
                    ? memory.cooldownLeft(here.realmKey(), target, hp, now, TelosData.CALLOUTS.get().cooldown()) : 0;
            if (wait > 0) {
                post(String.format(Locale.ROOT, "%s was just called out in this realm — wait %.1fs", target, wait / 1000.0));
                return;
            }
            memory.called(here.realmKey(), target, hp, now);
        }
        PlayerAdapter.sendChat(message);
    }

    private static int duoHp(List<KnownBar> bars, CalloutTable table, String boss) {
        for (KnownBar b : bars) if (b.boss().equalsIgnoreCase(boss)) return table.bossPercent(boss, b.percent());
        return 0;
    }

    private static boolean isNexus() {
        return "telos:hub".equals(DungeonAdapter.dimensionId());
    }

    private static void post(String text) {
        ChatAdapter.post(ChatText.of().add(text, ChatText.GRAY));
    }
}
