package dev.telemetric.module.impl.player;

import dev.telemetric.data.TelosData;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.TelosServer;
import dev.telemetric.input.InputUtil;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.KeybindSetting;
import dev.telemetric.ui.notification.Toasts;
import dev.telemetric.ui.realm.RealmSelectorScreen;

/**
 * Player → Keybinds: keys for Telos commands and menus, plus the Realm Selector ({@code ui/realm/RealmSelectorScreen}).
 * Leaving a dungeon through the selector takes three presses within {@link #CONFIRM_MS} ms. You can add a command key
 * with one {@link #command} line.
 */
public class KeybindsModule extends Module {

    private static final long CONFIRM_MS = 5000;
    private static final int DUNGEON_PRESSES = 3;

    private final KeybindSetting realmSelector = add(new KeybindSetting("Realm Selector",
            "Opens the realm selector (3 presses inside a dungeon)", InputUtil.KEY_C).onPress(this::openRealmSelector));

    // Settings render in registration order, so each group is added right before its own keys.
    {
        GroupSetting menus = add(new GroupSetting("Menus", "Open Telos menus"));
        command(menus, "Bosses Menu", "bosses", KeybindSetting.NONE);
        command(menus, "Mounts Menu", "mounts", KeybindSetting.NONE);
        command(menus, "Pets Menu", "pets", KeybindSetting.NONE);
        command(menus, "Backpack", "backpack", KeybindSetting.NONE);
        command(menus, "Spawn Mount", "spawnmount", InputUtil.KEY_V);
        command(menus, "Use Sticker", "showtheselectedsticker", KeybindSetting.NONE);

        GroupSetting toggles = add(new GroupSetting("Toggles", "Show / hide things for yourself"));
        command(toggles, "Toggle Mounts", "togglemounts", KeybindSetting.NONE);
        command(toggles, "Toggle Pets", "togglepets", KeybindSetting.NONE);
        command(toggles, "Toggle Players", "toggleplayers", KeybindSetting.NONE);
        command(toggles, "Toggle Stickers", "togglestickers", KeybindSetting.NONE);

        GroupSetting supporter = add(new GroupSetting("Supporter", "Commands for supporter ranks"));
        command(supporter, "Stash", "stash", KeybindSetting.NONE);
        command(supporter, "Teleport to Centre", "centre", KeybindSetting.NONE);
        command(supporter, "Teleport to Spawn", "spawn", KeybindSetting.NONE);
        command(supporter, "Auction House", "auction", KeybindSetting.NONE);
        command(supporter, "Guilds Menu", "guilds", KeybindSetting.NONE);
        command(supporter, "Reskin Menu", "reskin", KeybindSetting.NONE);
        command(supporter, "Reroll Traits", "reroll", KeybindSetting.NONE);
        command(supporter, "Fragments", "fragments", KeybindSetting.NONE);
    }

    private int presses;
    private long firstPress;
    private String currentRealm, previousRealm;

    public KeybindsModule() {
        super("Keybinds", "Keys for Telos commands, menus and the realm selector", Category.PLAYER);
    }

    /** One key that runs {@code /command} (no leading slash), shown in {@code group}. */
    private void command(GroupSetting group, String name, String command, int defaultKey) {
        add(group.child(new KeybindSetting(name, "Runs /" + command, defaultKey)
                .onPress(() -> PlayerAdapter.sendCommand(command))));
    }

    /** Remembers the realm before this one for "Previous" (only real realms and hubs, not dungeon servers). */
    @Override
    public void onTick() {
        TelosServer s = TelosServer.current();
        if (s == null || !TelosData.REALMS.get().isKnown(s.realm()) || s.realm().equalsIgnoreCase(currentRealm)) return;
        if (currentRealm != null) previousRealm = currentRealm;
        currentRealm = s.realm();
    }

    private void openRealmSelector() {
        if (DungeonAdapter.isInDungeon()) {
            long now = System.currentTimeMillis();
            if (now - firstPress > CONFIRM_MS) {
                presses = 0;
                firstPress = now;
            }
            if (++presses < DUNGEON_PRESSES) {
                int left = DUNGEON_PRESSES - presses;
                Toasts.error("You are in a dungeon — press " + left + " more time" + (left == 1 ? "" : "s") + " to leave");
                return;
            }
            presses = 0;
        }
        RealmSelectorScreen.open(previousRealm);
    }
}
