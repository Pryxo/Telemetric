package dev.telemetric.module.impl.combat;

import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.CombatAdapter.ArmorSlot;
import dev.telemetric.game.CombatAdapter.ItemInfo;
import dev.telemetric.game.CombatAdapter.Kind;
import dev.telemetric.game.GameItem;
import dev.telemetric.hud.HudModule;
import dev.telemetric.module.Category;
import dev.telemetric.module.impl.combat.CooldownTracker.Cooldown;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.EnumSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.ui.render.Render2D;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Armor HUD with ability cooldowns. It shows every worn piece, a cooldown bar under the pieces that have an ability,
 * and pieces you swapped off while their cooldown is still running (next to the piece that replaced them). Each slot
 * can pop a Ready Alert ({@code {slot} Ready!}) and play a sound. The data comes from {@link CooldownTracker}.
 */
public final class ArmorCooldownsHud extends HudModule {

    public enum Orientation implements EnumSetting.Nameable {
        HORIZONTAL("Horizontal"), VERTICAL("Vertical");
        private final String label;
        Orientation(String label) { this.label = label; }
        @Override public String displayName() { return label; }
    }

    public enum Pieces implements EnumSetting.Nameable {
        ALL("All Armor"), ABILITY("Ability Pieces");
        private final String label;
        Pieces(String label) { this.label = label; }
        @Override public String displayName() { return label; }
    }

    private static final int ICON = 16, GAP = 1, BAR_H = 4;
    private static final float[] PREVIEW_PROGRESS = {0f, 0.35f, 0.7f, 1f};
    private static final List<String> SLOT_LABELS = Arrays.stream(ArmorSlot.values()).map(ArmorSlot::label).toList();

    private final EnumSetting<Orientation> orientation = add(new EnumSetting<>("Orientation",
            "Pieces in a row or a column", Orientation.HORIZONTAL));
    private final EnumSetting<Pieces> pieces = add(new EnumSetting<>("Pieces",
            "All worn armor, or only pieces with an ability", Pieces.ALL));
    private final BooleanSetting showBars = add(new BooleanSetting("Show Bars",
            "Cooldown bar under every piece that has an ability", true));
    private final ReadyCue cue = addBundle(new ReadyCue("{slot} Ready!", "entity.player.levelup"));
    private final MultiChoiceSetting silent = add(new MultiChoiceSetting("Silent Slots",
            "No Ready Alert / Sound for these slots", () -> SLOT_LABELS));
    private final CooldownBar bar = addBundle(new CooldownBar());

    /** One drawn piece. A null {@code cooldown} means it's ready (or has no ability). */
    private record Piece(GameItem item, boolean ability, Cooldown cooldown) {}

    private volatile List<Piece> current = List.of();

    public ArmorCooldownsHud() {
        super("Armor Cooldowns", "Your armor with cooldown bars for armor abilities, a popup and sound when ready",
                Category.COMBAT, 0.0, 0.75, false);
        addFrameSettings(false);
        CooldownTracker.get().addListener(this::onReady);
    }

    private void onReady(Cooldown done) {
        if (!isEnabled() || !done.is(Kind.ARMOR) || done.info().slot() == null) return;
        String slot = done.info().slot().label();
        if (!silent.contains(slot)) cue.fire(done.info().name(), slot);
    }

    @Override
    public void onTick() {
        CooldownTracker tracker = CooldownTracker.get();
        List<Piece> list = new ArrayList<>(8);
        for (ArmorSlot slot : ArmorSlot.values()) {
            ItemInfo worn = CombatAdapter.worn(slot);
            if (worn != null) {
                Cooldown c = tracker.get(worn.group());
                boolean ability = worn.hasAbility() || c != null;
                if (ability || pieces.get() == Pieces.ALL) list.add(new Piece(worn.item(), ability, c));
            }
            for (Cooldown c : tracker.active()) { // swapped-off pieces of this slot that are still cooling down
                if (c.is(Kind.ARMOR) && c.info().slot() == slot && (worn == null || !c.group().equals(worn.group()))) {
                    list.add(new Piece(c.info().item(), true, c));
                }
            }
        }
        current = list;
    }

    @Override
    protected void onDisable() {
        current = List.of();
    }

    @Override
    public boolean hasData() {
        return !current.isEmpty();
    }

    private List<Piece> shown(boolean preview) {
        List<Piece> list = current;
        if (!list.isEmpty() || !preview) return list;
        List<Piece> sample = new ArrayList<>(4);
        for (ArmorSlot slot : ArmorSlot.values()) sample.add(new Piece(CombatAdapter.previewArmor(slot), true, null));
        return sample;
    }

    private int cellHeight() {
        return showBars.get() ? ICON + GAP + BAR_H : ICON;
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        int n = Math.max(1, shown(preview).size());
        return orientation.get() == Orientation.HORIZONTAL ? n * ICON : ICON;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        int n = Math.max(1, shown(preview).size());
        return orientation.get() == Orientation.HORIZONTAL ? cellHeight() : n * (cellHeight() + GAP) - GAP;
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        List<Piece> list = shown(preview);
        boolean sample = current.isEmpty();
        boolean horizontal = orientation.get() == Orientation.HORIZONTAL;
        long now = System.currentTimeMillis();
        int x = 0, y = 0;
        for (int i = 0; i < list.size(); i++) {
            Piece piece = list.get(horizontal && alignRight ? list.size() - 1 - i : i);
            r.itemIcon(piece.item(), x, y);
            if (showBars.get() && piece.ability()) {
                float remaining = sample ? PREVIEW_PROGRESS[i % PREVIEW_PROGRESS.length]
                        : piece.cooldown() == null ? 0f : piece.cooldown().remaining(now);
                bar.draw(r, x, y + ICON + GAP, ICON, BAR_H, remaining, false);
            }
            if (horizontal) x += ICON;
            else y += cellHeight() + GAP;
        }
    }
}
