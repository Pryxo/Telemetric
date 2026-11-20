package dev.telemetric.module.impl.combat;

import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.CombatAdapter.ItemInfo;
import dev.telemetric.game.CombatAdapter.Kind;
import dev.telemetric.game.GameItem;
import dev.telemetric.hud.HudModule;
import dev.telemetric.module.Category;
import dev.telemetric.module.impl.combat.CooldownTracker.Cooldown;
import dev.telemetric.ui.render.Render2D;

/**
 * Weapon Cooldown / Ability Cooldown: a small bar and item icon beside the crosshair (movable in the HUD editor)
 * showing the cooldown of the held weapon or ability. When nothing of that kind is held, the most recently used one
 * keeps showing until its cooldown ends. When it's ready you get a full bar, a short "!" flash, the Ready Alert and
 * the Ready Sound.
 * <p>
 * One class with two instances ({@link #weapon()}, {@link #ability()}). The data comes from {@link CooldownTracker}.
 */
public final class HeldCooldownHud extends HudModule {

    private static final int BAR_W = 5, ICON = 16, GAP = 1;

    private final Kind kind;
    private final String slotLabel;
    private final ReadyCue cue;
    private final CooldownBar bar;

    /** What to draw, refreshed every tick. The bar itself is interpolated per frame from the cooldown's times. */
    private volatile GameItem icon;
    private volatile Cooldown cooldown;
    private long readyAt;

    public static HeldCooldownHud weapon() {
        return new HeldCooldownHud(Kind.WEAPON, "Weapon", 0.445, "Weapon Ready!", "block.note_block.banjo");
    }

    public static HeldCooldownHud ability() {
        return new HeldCooldownHud(Kind.ABILITY, "Ability", 0.555, "Ability Ready!", "entity.player.levelup");
    }

    private HeldCooldownHud(Kind kind, String slotLabel, double defaultX, String readyText, String readySound) {
        super(slotLabel + " Cooldown", "Cooldown bar of your " + slotLabel.toLowerCase(java.util.Locale.ROOT)
                + " beside the crosshair, with a popup and sound when it is ready", Category.COMBAT, defaultX, 0.5, false);
        this.kind = kind;
        this.slotLabel = slotLabel;
        this.cue = addBundle(new ReadyCue(readyText, readySound));
        this.bar = addBundle(new CooldownBar());
        addFrameSettings(false);
        CooldownTracker.get().addListener(this::onReady);
    }

    private void onReady(Cooldown done) {
        if (!isEnabled() || !done.is(kind)) return;
        readyAt = System.currentTimeMillis();
        cue.fire(done.info().name(), slotLabel);
    }

    @Override
    public void onTick() {
        CooldownTracker tracker = CooldownTracker.get();
        ItemInfo held = CombatAdapter.held(kind);
        Cooldown c = held != null ? tracker.get(held.group()) : tracker.latest(kind);
        cooldown = c;
        icon = held != null ? held.item() : c != null ? c.info().item() : null;
    }

    @Override
    protected void onDisable() {
        icon = null;
        cooldown = null;
        readyAt = 0;
    }

    @Override
    public boolean hasData() {
        return icon != null;
    }

    @Override
    public int contentWidth(Render2D r, boolean preview) {
        return BAR_W + GAP + ICON;
    }

    @Override
    public int contentHeight(Render2D r, boolean preview) {
        return ICON;
    }

    @Override
    public void draw(Render2D r, boolean preview, boolean alignRight) {
        GameItem item = icon;
        Cooldown c = cooldown;
        float remaining = c == null ? 0f : c.remaining(System.currentTimeMillis());
        if (item == null && preview) {
            item = CombatAdapter.previewItem(kind);
            remaining = 0.4f;
        }
        // the bar sits on the crosshair side: the weapon is [icon][bar] to the left of it, the ability [bar][icon] to the right
        boolean barRight = (kind == Kind.WEAPON) != alignRight;
        int barX = barRight ? ICON + GAP : 0;
        int iconX = barRight ? 0 : BAR_W + GAP;
        bar.draw(r, barX, 0, BAR_W, ICON, remaining, true);
        bar.drawFlash(r, barX + BAR_W / 2, ICON / 2, readyAt);
        r.itemIcon(item, iconX, 0);
    }
}
