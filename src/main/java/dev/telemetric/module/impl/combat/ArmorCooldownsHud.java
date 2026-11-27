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
import dev.telemetric.setting.impl.NumberSetting;
import dev.telemetric.ui.render.Render2D;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Armor HUD with ability cooldowns: worn pieces with a bar under those that have an ability, plus swapped-off pieces
 * whose cooldown is still running. Inventory Pieces adds spare armor with an ability in extra rows or columns; Align
 * to Slot puts each spare next to the worn piece of its slot. Data comes from {@link CooldownTracker}.
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

    public enum RowSide implements EnumSetting.Nameable {
        ABOVE("Above"), BELOW("Below");
        private final String label;
        RowSide(String label) { this.label = label; }
        @Override public String displayName() { return label; }
    }

    public enum ColumnSide implements EnumSetting.Nameable {
        LEFT("Left"), RIGHT("Right");
        private final String label;
        ColumnSide(String label) { this.label = label; }
        @Override public String displayName() { return label; }
    }

    private static final int ICON = 16, GAP = 1, BAR_H = 4, STRIP_GAP = 3;
    private static final float[] PREVIEW_PROGRESS = {0f, 0.35f, 0.7f, 1f};

    private final EnumSetting<Orientation> orientation = add(new EnumSetting<>("Orientation",
            "Pieces in a row or a column", Orientation.HORIZONTAL));
    private final EnumSetting<Pieces> pieces = add(new EnumSetting<>("Pieces",
            "All worn armor, or only pieces with an ability", Pieces.ALL));
    private final BooleanSetting inventory = add(new BooleanSetting("Inventory Pieces",
            "Also show armor with an ability from your inventory (own row / column), so you know when to switch", false));
    private final EnumSetting<RowSide> rowSide = add(new EnumSetting<>("Inventory Row",
            "Inventory pieces above or below your worn armor", RowSide.BELOW)
            .visibleWhen(() -> inventory.get() && orientation.get() == Orientation.HORIZONTAL));
    private final EnumSetting<ColumnSide> columnSide = add(new EnumSetting<>("Inventory Column",
            "Inventory pieces left or right of your worn armor", ColumnSide.RIGHT)
            .visibleWhen(() -> inventory.get() && orientation.get() == Orientation.VERTICAL));
    private final BooleanSetting alignToSlot = add(new BooleanSetting("Align to Slot",
            "Each inventory piece sits next to the worn piece of its slot (boots by boots)", true)
            .visibleWhen(inventory::get));
    private final NumberSetting spaceX = add(new NumberSetting("Horizontal Spacing",
            "Pixels between pieces side by side (negative = overlap)", 0, -8, 16, 1).withSuffix("px"));
    private final NumberSetting spaceY = add(new NumberSetting("Vertical Spacing",
            "Pixels between pieces above each other (negative = overlap)", 1, -8, 16, 1).withSuffix("px"));
    private final BooleanSetting showBars = add(new BooleanSetting("Show Bars",
            "Cooldown bar under every piece that has an ability", true));
    private final CooldownBar bar = addBundle(new CooldownBar());

    /** One drawn piece. A null {@code cooldown} means it's ready (or has no ability). */
    private record Piece(GameItem item, ArmorSlot slot, boolean ability, Cooldown cooldown) {}

    /** Worn (+ swapped-off) pieces, and the inventory pieces shown in extra rows / columns. */
    private record Layout(List<Piece> main, List<Piece> spare) {
        static final Layout EMPTY = new Layout(List.of(), List.of());
        boolean isEmpty() { return main.isEmpty() && spare.isEmpty(); }
    }

    private volatile Layout current = Layout.EMPTY;

    public ArmorCooldownsHud() {
        super("Armor Cooldowns", "Your armor with cooldown bars for armor abilities (ready popup and sound: Combat → Ready Alerts)",
                Category.COMBAT, 0.0, 0.75, false);
        addFrameSettings(false);
    }

    @Override
    public void onTick() {
        CooldownTracker tracker = CooldownTracker.get();
        ItemInfo[] worn = new ItemInfo[ArmorSlot.values().length];
        Set<String> wornGroups = new HashSet<>();
        for (ArmorSlot slot : ArmorSlot.values()) {
            worn[slot.ordinal()] = CombatAdapter.worn(slot);
            if (worn[slot.ordinal()] != null) wornGroups.add(worn[slot.ordinal()].group());
        }

        List<Piece> spare = new ArrayList<>(4);
        Set<String> spareGroups = new HashSet<>();
        if (inventory.get()) {
            List<ItemInfo> inv = CombatAdapter.inventoryArmor();
            for (ArmorSlot slot : ArmorSlot.values()) { // grouped by slot: helmets first, boots last
                for (ItemInfo info : inv) {
                    if (info.slot() != slot || wornGroups.contains(info.group()) || !spareGroups.add(info.group())) continue;
                    spare.add(new Piece(info.item(), slot, true, tracker.get(info.group())));
                }
            }
        }

        List<Piece> main = new ArrayList<>(8);
        for (ArmorSlot slot : ArmorSlot.values()) {
            ItemInfo w = worn[slot.ordinal()];
            if (w != null) {
                Cooldown c = tracker.get(w.group());
                boolean ability = w.hasAbility() || c != null;
                if (ability || pieces.get() == Pieces.ALL) main.add(new Piece(w.item(), slot, ability, c));
            }
            for (Cooldown c : tracker.active()) { // swapped-off pieces of this slot that are still cooling down
                if (c.is(Kind.ARMOR) && c.info().slot() == slot && !wornGroups.contains(c.group())
                        && !spareGroups.contains(c.group())) {
                    main.add(new Piece(c.info().item(), slot, true, c));
                }
            }
        }
        current = main.isEmpty() && spare.isEmpty() ? Layout.EMPTY : new Layout(main, spare);
    }

    @Override
    protected void onDisable() {
        current = Layout.EMPTY;
    }

    @Override
    public boolean hasData() {
        return !current.isEmpty();
    }

    private Layout shown(boolean preview) {
        Layout layout = current;
        if (!layout.isEmpty() || !preview) return layout;
        List<Piece> main = new ArrayList<>(4);
        for (ArmorSlot slot : ArmorSlot.values()) main.add(new Piece(CombatAdapter.previewArmor(slot), slot, true, null));
        List<Piece> spare = inventory.get()
                ? List.of(new Piece(CombatAdapter.previewArmor(ArmorSlot.FEET), ArmorSlot.FEET, true, null)) : List.of();
        return new Layout(main, spare);
    }

    /**
     * The strips to draw: main first, then the inventory strips from nearest to farthest. Aligned strips use the
     * main strip's positions ({@code null} = gap); a spare whose slot isn't in the main strip goes after its end.
     */
    private List<List<Piece>> strips(Layout l) {
        List<List<Piece>> out = new ArrayList<>(3);
        out.add(l.main());
        if (l.spare().isEmpty()) return out;
        if (!alignToSlot.get()) {
            out.add(l.spare());
            return out;
        }
        List<List<Piece>> extra = new ArrayList<>(2);
        int overflow = l.main().size();
        for (Piece p : l.spare()) {
            int pos = -1;
            for (int i = 0; i < l.main().size(); i++) {
                if (l.main().get(i).slot() == p.slot()) { pos = i; break; }
            }
            if (pos < 0) pos = overflow++;
            List<Piece> target = null;
            for (List<Piece> strip : extra) {
                if (strip.size() <= pos || strip.get(pos) == null) { target = strip; break; }
            }
            if (target == null) extra.add(target = new ArrayList<>());
            while (target.size() <= pos) target.add(null);
            target.set(pos, p);
        }
        out.addAll(extra);
        return out;
    }

    private int cellHeight() {
        return showBars.get() ? ICON + GAP + BAR_H : ICON;
    }

    private boolean horizontal() {
        return orientation.get() == Orientation.HORIZONTAL;
    }

    /** Length of a strip of {@code n} positions along its own direction. */
    private int stripLength(int n, boolean horizontal) {
        if (n <= 0) return 0;
        return horizontal ? n * stepX() - spaceX.getInt() : n * stepY() - spaceY.getInt();
    }

    /** Distance from one piece to the next side by side / above each other. */
    private int stepX() { return ICON + spaceX.getInt(); }
    private int stepY() { return cellHeight() + spaceY.getInt(); }

    /** Distance between strips (inventory rows / columns): the spacing plus a small fixed gap. */
    private int laneX() { return stepX() + STRIP_GAP - 1; }
    private int laneY() { return stepY() + STRIP_GAP - 1; }

    private int longest(List<List<Piece>> strips) {
        int n = 1;
        for (List<Piece> s : strips) n = Math.max(n, s.size());
        return n;
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        List<List<Piece>> s = strips(shown(preview));
        if (horizontal()) return stripLength(longest(s), true);
        return (s.size() - 1) * laneX() + ICON;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        List<List<Piece>> s = strips(shown(preview));
        if (!horizontal()) return stripLength(longest(s), false);
        return (s.size() - 1) * laneY() + cellHeight();
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        List<List<Piece>> s = strips(shown(preview));
        boolean sample = current.isEmpty();
        boolean horizontal = horizontal();
        long now = System.currentTimeMillis();
        int width = contentWidth(r, preview);
        int count = s.size();
        // main strip is at index 0 (Below / Right) or at the far end (Above / Left), spares spread outward from it
        boolean before = horizontal ? rowSide.get() == RowSide.ABOVE : columnSide.get() == ColumnSide.LEFT;
        for (int k = 0; k < count; k++) {
            int lane = before ? count - 1 - k : k;
            int x = horizontal ? 0 : lane * laneX();
            int y = horizontal ? lane * laneY() : 0;
            drawStrip(r, s.get(k), x, y, horizontal, alignRight, sample, now, width);
        }
    }

    /** Draws one row / column; rows are mirrored against the right edge of {@code width} when the HUD aligns right. */
    private void drawStrip(Render2D r, List<Piece> list, int x0, int y0, boolean horizontal, boolean alignRight,
                           boolean sample, long now, int width) {
        for (int i = 0; i < list.size(); i++) {
            Piece piece = list.get(i);
            if (piece == null) continue;
            int x = horizontal ? (alignRight ? width - ICON - i * stepX() : x0 + i * stepX()) : x0;
            int y = horizontal ? y0 : y0 + i * stepY();
            r.itemIcon(piece.item(), x, y);
            if (showBars.get() && piece.ability()) {
                float remaining = sample ? PREVIEW_PROGRESS[i % PREVIEW_PROGRESS.length]
                        : piece.cooldown() == null ? 0f : piece.cooldown().remaining(now);
                bar.draw(r, x, y + ICON + GAP, ICON, BAR_H, remaining, false);
            }
        }
    }
}
