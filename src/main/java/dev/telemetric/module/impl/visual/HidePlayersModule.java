package dev.telemetric.module.impl.visual;

import dev.telemetric.Telemetric;
import dev.telemetric.game.PlayerVisibilityAdapter;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.setting.impl.TextSetting;
import dev.telemetric.ui.notification.Toasts;

import java.util.List;

/**
 * Hides every other player (model, riders and their Telos name tag via {@link NametagModule}) except whitelisted
 * usernames. Add names with "Add Player" + Enter, remove them by clicking them in the Whitelist dropdown.
 */
public final class HidePlayersModule extends Module {

    private final MultiChoiceSetting whitelist = add(new MultiChoiceSetting("Whitelist",
            "Players that stay visible (click a name to remove it)", List::of));
    private final TextSetting addPlayer = add(new TextSetting("Add Player",
            "Type a username and press Enter to add it to the whitelist", "", 16)
            .filter(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')
            .onSubmit(this::addToWhitelist));

    public HidePlayersModule() {
        super("Hide Players", "Hides other players and their name tags, except whitelisted names", Category.WORLD);
        addPlayer.notPersistent();
        syncOn(this::sync, whitelist);
    }

    @Override
    public void onPostInit() {
        Telemetric.get().modules().get(NametagModule.class)
                .addHideRule(tag -> isEnabled() && !tag.self() && hides(tag.owner()));
    }

    private void sync() {
        PlayerVisibilityAdapter.setHiddenPlayers(isEnabled() ? this::hides : null);
    }

    private boolean hides(String username) {
        return !whitelist.contains(username);
    }

    private void addToWhitelist(String name) {
        String trimmed = name.strip();
        if (trimmed.isEmpty()) return;
        if (whitelist.contains(trimmed)) {
            Toasts.info(trimmed + " is already whitelisted");
        } else {
            whitelist.toggle(trimmed);
            Toasts.success(trimmed + " whitelisted");
        }
        addPlayer.set("");
    }
}
