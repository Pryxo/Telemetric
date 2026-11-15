package dev.telemetric.module.impl.visual;

import dev.telemetric.game.NametagAdapter;
import dev.telemetric.game.NametagAdapter.PlayerNametag;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Telos player name tags (the "[Rank] Name / HP: …" text displays above players). Single place for name tag edits:
 * its own settings act while the module is on, and other modules plug in rules with {@link #addHideRule} (e.g. Hide
 * Players) that apply whenever their module wants them to, even with this module off. Game access:
 * {@link NametagAdapter}.
 */
public final class NametagsModule extends Module {

    private final BooleanSetting hideOwn = add(new BooleanSetting("Hide Own",
            "Hide your own name tag in third person", false));

    /** Rules from other modules; each checks its own module's state. */
    private final List<Predicate<PlayerNametag>> hideRules = new CopyOnWriteArrayList<>();

    public NametagsModule() {
        super("Nametags", "Edits Telos player name tags (other modules like Hide Players use it too)", Category.VISUAL);
        syncOn(this::sync, hideOwn);
        sync();
    }

    /** Adds a rule that hides the name tags it matches (called from other modules' {@code onPostInit}). */
    public void addHideRule(Predicate<PlayerNametag> rule) {
        hideRules.add(rule);
        sync();
    }

    private void sync() {
        boolean own = isEnabled() && hideOwn.get();
        NametagAdapter.setHideRule(!own && hideRules.isEmpty() ? null : tag -> hides(tag, own));
    }

    private boolean hides(PlayerNametag tag, boolean own) {
        if (own && tag.self()) return true;
        for (Predicate<PlayerNametag> rule : hideRules) if (rule.test(tag)) return true;
        return false;
    }
}
