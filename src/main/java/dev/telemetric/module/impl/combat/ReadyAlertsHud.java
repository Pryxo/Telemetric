package dev.telemetric.module.impl.combat;

import dev.telemetric.Telemetric;
import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.CombatAdapter.ArmorSlot;
import dev.telemetric.game.CombatAdapter.ItemInfo;
import dev.telemetric.game.CombatAdapter.Kind;
import dev.telemetric.module.impl.combat.CooldownTracker.Cooldown;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.GroupToggleSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.setting.impl.TextSetting;
import dev.telemetric.ui.notification.Toasts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Every "… Ready!" popup and sound, in one movable place. It listens to {@link CooldownTracker} itself, and its
 * Settings window has one tab per slot (Main Hand = weapons, Off Hand = ability items, Helmet … Boots = armor
 * abilities), each with its own switch, text, colour, time and sound. "Add Item" scans the player for items with a
 * cooldown; picking one adds a custom tab just for that item (matched by name), which then replaces its slot's tab.
 * <p>
 * Each tab's Position can send its popups to a separate element ({@link ReadyAlertGroupHud}, "Ready Alerts 2" …)
 * that is moved on its own. Popup stacking and drawing: {@link ReadyAlertDisplay}.
 */
public final class ReadyAlertsHud extends ReadyAlertDisplay {

    /** Fixed number of custom item tabs (pre-registered so profiles load and save them like any other setting). */
    private static final int CUSTOM_TABS = 12;
    private static final long SCAN_MS = 500;
    private static final String PICK = "Choose…", NONE = "No cooldown items found";

    /** A custom tab: {@code item} holds the item's name, blank = unused (the tab is hidden). */
    private record CustomTab(TextSetting item, ReadyAlertTab alert) {}

    private final ModeSetting addItem;
    private final ReadyAlertTab mainHand, offHand;
    private final ReadyAlertTab[] armor = new ReadyAlertTab[ArmorSlot.values().length];
    private final List<CustomTab> custom = new ArrayList<>(CUSTOM_TABS);
    private final List<ReadyAlertGroupHud> groups = new ArrayList<>();

    private List<String> scanned = List.of(PICK);
    private long scannedAt;

    public ReadyAlertsHud() {
        super("Ready Alerts", "Popup and sound when a cooldown ends: one tab per slot, plus custom tabs for single "
                + "items (Add Item). Move it in the HUD editor; a tab's Position can move its popup to a separate "
                + "element", 0.5, 0.32);
        double[] groupY = {0.22, 0.42, 0.52};
        for (int i = 0; i < ReadyAlertTab.SEPARATE_COUNT; i++) groups.add(new ReadyAlertGroupHud(i + 2, groupY[i % groupY.length]));

        addItem = add(new ModeSetting("Add Item", "Scans your hands, armor and inventory for items with a cooldown. "
                + "Pick one to give it its own tab (it then replaces the slot's tab for that item)", PICK, this::scanOptions)
                .searchable().notPersistent());
        addItem.onChange(v -> {
            if (PICK.equals(v)) return;
            if (!NONE.equals(v)) addCustom(v);
            addItem.set(PICK);
        });

        mainHand = slotTab("Main Hand", "Weapons (Telos weapon cooldowns)", false, "Weapon Ready!",
                "block.note_block.banjo", () -> heldName(Kind.WEAPON, "Weapon"));
        offHand = slotTab("Off Hand", "Ability items (tomes, quivers, …)", true, "Ability Ready!",
                "entity.player.levelup", () -> heldName(Kind.ABILITY, "Ability"));
        for (ArmorSlot slot : ArmorSlot.values()) {
            armor[slot.ordinal()] = slotTab(slot.label(), "Armor abilities of your " + slot.label().toLowerCase(Locale.ROOT),
                    true, "{slot} Ready!", "entity.player.levelup", () -> {
                        ItemInfo worn = CombatAdapter.worn(slot);
                        return worn != null ? worn.name() : slot.label();
                    });
        }
        for (int i = 1; i <= CUSTOM_TABS; i++) custom.add(customTab(i));

        CooldownTracker.get().addListener(this::onReady);
    }

    static ReadyAlertsHud get() {
        return Telemetric.get().modules().get(ReadyAlertsHud.class);
    }

    // ---- tabs ----

    private ReadyAlertTab slotTab(String label, String what, boolean on, String text, String sound, Supplier<String> testItem) {
        GroupToggleSetting tab = new GroupToggleSetting(label + " Alert", "Ready Alert for " + what
                + ". The switch turns this slot's popup and sound on or off", on).withLabel(label);
        return addBundle(new ReadyAlertTab(label, tab, text, sound, testItem, () -> label));
    }

    private CustomTab customTab(int index) {
        String prefix = "Custom " + index;
        TextSetting item = new TextSetting(prefix + " Item", "Name of the item this tab is for", "", 64)
                .visibleWhen(() -> false);
        GroupToggleSetting tab = new GroupToggleSetting(prefix + " Alert",
                "Ready Alert for this item only (replaces its slot's tab). The switch turns it on or off", true) {
            @Override
            public String getLabel() {
                return item.get().isBlank() ? prefix : item.get();
            }
        };
        tab.visibleWhen(() -> !item.get().isBlank());
        ReadyAlertTab alert = new ReadyAlertTab(prefix, tab, "{item} Ready!", "entity.player.levelup",
                item::get, () -> slotLabelOf(item.get()));
        CustomTab ct = new CustomTab(item, alert);
        alert.extra(tab.child(item));
        alert.extra(tab.child(new ActionSetting(prefix + " Remove", "Delete this item's tab (its slot's tab is used again)",
                () -> removeCustom(ct)).withLabel("Remove Tab")));
        addBundle(alert);
        return ct;
    }

    private void addCustom(String itemName) {
        if (customFor(itemName) != null) return;
        for (CustomTab ct : custom) {
            if (!ct.item().get().isBlank()) continue;
            ct.alert().reset();
            ct.item().set(itemName);
            scannedAt = 0;
            requestSection(ct.alert().tab().getLabel());
            Toasts.success("Added a Ready Alert tab for " + itemName);
            return;
        }
        Toasts.error("All " + CUSTOM_TABS + " custom tabs are in use (remove one first)");
    }

    private void removeCustom(CustomTab ct) {
        ct.alert().reset(); // also clears the item name, which hides the tab
        scannedAt = 0;
    }

    private CustomTab customFor(String itemName) {
        if (itemName == null || itemName.isBlank()) return null;
        for (CustomTab ct : custom) if (ct.item().get().equalsIgnoreCase(itemName)) return ct;
        return null;
    }

    /** "Choose…", then every cooldown item on the player that has no tab yet (re-scanned at most every half second). */
    private List<String> scanOptions() {
        long now = System.currentTimeMillis();
        if (now - scannedAt < SCAN_MS) return scanned;
        scannedAt = now;
        List<String> list = new ArrayList<>();
        list.add(PICK);
        for (ItemInfo info : CombatAdapter.cooldownItems()) offer(list, info);
        for (Cooldown c : CooldownTracker.get().active()) offer(list, c.info()); // swapped-off items still cooling
        if (list.size() == 1) list.add(NONE);
        scanned = list;
        return list;
    }

    private void offer(List<String> list, ItemInfo info) {
        if (info == null || info.name().isBlank() || customFor(info.name()) != null) return;
        for (String s : list) if (s.equalsIgnoreCase(info.name())) return;
        list.add(info.name());
    }

    // ---- firing ----

    private void onReady(Cooldown done) {
        if (!isEnabled() || done.info() == null) return;
        ItemInfo info = done.info();
        CustomTab ct = customFor(info.name());
        ReadyAlertTab tab = ct != null ? ct.alert() : slotTab(info);
        if (tab != null) tab.fire(info.name(), slotLabel(info));
    }

    private ReadyAlertTab slotTab(ItemInfo info) {
        return switch (info.kind()) {
            case WEAPON -> mainHand;
            case ABILITY -> offHand;
            case ARMOR -> info.slot() != null ? armor[info.slot().ordinal()] : null;
            case OTHER -> null;
        };
    }

    private static String slotLabel(ItemInfo info) {
        return switch (info.kind()) {
            case WEAPON -> "Main Hand";
            case ABILITY -> "Off Hand";
            case ARMOR -> info.slot() != null ? info.slot().label() : "Armor";
            case OTHER -> "Item";
        };
    }

    /** {slot} for a custom tab's Test button: the slot of that item if it's on the player right now. */
    private static String slotLabelOf(String itemName) {
        for (ItemInfo info : CombatAdapter.cooldownItems()) if (info.name().equalsIgnoreCase(itemName)) return slotLabel(info);
        return "Item";
    }

    private static String heldName(Kind kind, String fallback) {
        ItemInfo held = CombatAdapter.held(kind);
        return held != null ? held.name() : fallback;
    }

    // ---- separate elements ----

    /** The separate popup elements (registered right after this module). */
    public List<ReadyAlertGroupHud> groups() {
        return groups;
    }

    /** Where a tab with this Position shows its popups (unknown = the main element). */
    ReadyAlertDisplay display(String position) {
        for (ReadyAlertGroupHud g : groups) if (g.option().equals(position)) return g;
        return this;
    }

    /** Whether any active tab (switched on; custom tabs: in use) shows its popups at this Position. */
    boolean uses(String position) {
        for (ReadyAlertTab t : slotTabs()) if (t.isOn() && t.position().equals(position)) return true;
        for (CustomTab ct : custom)
            if (!ct.item().get().isBlank() && ct.alert().isOn() && ct.alert().position().equals(position)) return true;
        return false;
    }

    private List<ReadyAlertTab> slotTabs() {
        List<ReadyAlertTab> list = new ArrayList<>(List.of(mainHand, offHand));
        list.addAll(List.of(armor));
        return list;
    }
}
