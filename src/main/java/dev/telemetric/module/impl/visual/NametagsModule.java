package dev.telemetric.module.impl.visual;

import dev.telemetric.api.HeadStatusApi;
import dev.telemetric.api.HeadStatusCache;
import dev.telemetric.game.NametagAdapter;
import dev.telemetric.game.NametagAdapter.PlayerNametag;
import dev.telemetric.game.NametagAdapter.TitleLine;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Telos player name tags (the "[Rank] Name / HP: …" text displays above players). Single place for name tag edits:
 * its own settings act while the module is on, and other modules plug in rules with {@link #addHideRule} (e.g. Hide
 * Players) that apply whenever their module wants them to, even with this module off. Game access:
 * {@link NametagAdapter}. <b>Show Status</b> puts each player's HeadStatus head status ({@link HeadStatusCache}) as a
 * new top line into their Telos name tag, styled like Telos' own lines.
 */
public final class NametagsModule extends Module {

    private final BooleanSetting hideOwn = add(new BooleanSetting("Hide Own",
            "Hide your own name tag in third person", false));
    private final BooleanSetting showStatus = add(new BooleanSetting("Show Status",
            "Show each player's head status (e.g. Luna Kills) above their name tag", true));

    /** Rules from other modules; each checks its own module's state. */
    private final List<Predicate<PlayerNametag>> hideRules = new CopyOnWriteArrayList<>();

    public NametagsModule() {
        super("Nametags", "Edits Telos player name tags (other modules like Hide Players use it too)", Category.VISUAL);
        syncOn(this::sync, hideOwn);
        sync();
        NametagAdapter.setTitleSource(this::statusLine);
    }

    /** Title line for a name tag owner (dashed UUID), or null when off / they have no status. */
    private TitleLine statusLine(String ownerId) {
        if (!isEnabled() || !showStatus.get()) return null;
        return HeadStatusCache.get(ownerId).map(NametagsModule::statusLine).orElse(null);
    }

    /**
     * "Luna Kills: 1,234 (#3)" — display name (grey), then count and position (white) when the API sends them. Single
     * place that decides the status text.
     */
    static TitleLine statusLine(HeadStatusApi.HeadStatus status) {
        StringBuilder value = new StringBuilder();
        if (status.count() > 0) value.append(String.format(Locale.ROOT, "%,d", status.count()));
        if (status.position() > 0) value.append(value.isEmpty() ? "" : " ").append("(#").append(status.position()).append(')');
        return new TitleLine(HeadStatusApi.statusName(status.text()), value.toString());
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
