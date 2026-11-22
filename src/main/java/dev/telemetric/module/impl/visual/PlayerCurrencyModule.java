package dev.telemetric.module.impl.visual;

import dev.telemetric.game.NametagAdapter;
import dev.telemetric.game.NametagAdapter.CurrencyColors;
import dev.telemetric.game.NametagAdapter.CurrencyLine;
import dev.telemetric.game.NametagAdapter.FragmentCount;
import dev.telemetric.game.NametagAdapter.TitlePosition;
import dev.telemetric.game.PlayerAdapter;
import dev.telemetric.game.ServerGate;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.impl.BooleanSetting;
import dev.telemetric.setting.impl.ColorSetting;
import dev.telemetric.setting.impl.GroupSetting;
import dev.telemetric.setting.impl.ModeSetting;
import dev.telemetric.tracking.BalanceLookup;

import java.util.ArrayList;
import java.util.List;

/**
 * Visual → <b>Player Currency</b>: a line in every loaded player's Telos name tag with their fame and fragments,
 * {@code "❖1,632,163.3 | 0 <icon> 93 <icon> 39 <icon> 69 <icon>"}. The numbers come from {@code /balance <user>},
 * sent in the background by {@link BalanceLookup} (rate-limited, cached, replies kept out of the chat). Fame picks
 * which balance is shown; Auto (default) follows each player's mode glyph in their name tag
 * ({@code TelosCharacter.modeFromGlyph}): normal → normal, seasonal → seasonal, ironman / group / unknown → none. The line sits between the name and the HP
 * line by default; Position moves it, and Replace HP puts it in the HP line's place.
 */
public final class PlayerCurrencyModule extends Module {

    /** Fragment names in /balance reply order. */
    private static final String[] FRAGMENTS = {"Irradiated", "Gilded", "Royal", "Bloodshot"};

    private final ModeSetting fame = add(new ModeSetting("Fame",
            "Which fame balance to show. Auto: each player's own mode (from the mode icon in their name tag): Normal "
                    + "fame for normal, Seasonal for seasonal, none for ironman", "Auto",
            "Auto", "Normal", "Seasonal", "Nothing"));
    private final GroupSetting fragmentsGroup = add(new GroupSetting("Fragments", "Which fragment counts to show"));
    private final BooleanSetting[] fragments = new BooleanSetting[FRAGMENTS.length];

    {
        for (int i = 0; i < FRAGMENTS.length; i++) {
            fragments[i] = add(fragmentsGroup.child(new BooleanSetting(FRAGMENTS[i],
                    "Show " + FRAGMENTS[i] + " Fragments", true)));
        }
    }

    private final ModeSetting position = add(new ModeSetting("Position",
            "Where the line sits: Top (above the name), Middle (between name and HP) or Bottom (under everything)",
            "Middle", "Top", "Middle", "Bottom"));
    private final BooleanSetting replaceHp = add(new BooleanSetting("Replace HP",
            "Show the line in place of the HP line (Position is ignored then)", false));
    private final BooleanSetting hideSelf = add(new BooleanSetting("Hide Yourself",
            "Don't show the line in your own name tag", false));

    private final GroupSetting colorsGroup = add(new GroupSetting("Colors", "Colours of the currency line"));
    private final ColorSetting normalIcon = add(colorsGroup.child(new ColorSetting("Normal Fame Icon",
            "❖ in front of Normal fame", 0xFFFFFFFF, false)));
    private final ColorSetting seasonalIcon = add(colorsGroup.child(new ColorSetting("Seasonal Fame Icon",
            "❖ in front of Seasonal fame (yellow like in /balance)", 0xFFFFFF55, false)));
    private final ColorSetting fameColor = add(colorsGroup.child(new ColorSetting("Fame Count",
            "The fame number", 0xFF55FFFF, false)));
    private final ColorSetting separatorColor = add(colorsGroup.child(new ColorSetting("Separator",
            "The \" | \" between fame and fragments", 0xFF555555, false)));
    private final ColorSetting fragmentColor = add(colorsGroup.child(new ColorSetting("Fragment Count",
            "The fragment numbers", 0xFF55FFFF, false)));
    private final ColorSetting fragmentIconColor = add(colorsGroup.child(new ColorSetting("Fragment Icons",
            "Tint of the fragment icons (white = original)", 0xFFFFFFFF, false)));

    public PlayerCurrencyModule() {
        super("Player Currency", "Shows each player's fame and fragments in their name tag (via /balance)",
                Category.INVENTORY);
        syncOn(this::sync, position, replaceHp);
        sync();
        BalanceLookup.register();
        NametagAdapter.setCurrencySource(this::currencyFor);
    }

    private void sync() {
        TitlePosition pos = switch (position.get()) {
            case "Top" -> TitlePosition.TOP;
            case "Bottom" -> TitlePosition.BOTTOM;
            default -> TitlePosition.MIDDLE;
        };
        NametagAdapter.setCurrencyLayout(pos, replaceHp.get());
        if (!isEnabled()) BalanceLookup.cancel();
    }

    @Override
    public void onTick() {
        if (!ServerGate.onTelos() || !PlayerAdapter.isInWorld()) {
            BalanceLookup.cancel();
            return;
        }
        BalanceLookup.tick(PlayerAdapter.loadedPlayerNames());
    }

    private CurrencyLine currencyFor(String ownerName, boolean self, String mode) {
        if (!isEnabled() || (self && hideSelf.get())) return null;
        BalanceLookup.Balance b = BalanceLookup.get(ownerName);
        if (b == null) return null;
        String fameMode = switch (fame.get()) {
            case "Auto" -> "normal".equals(mode) || "seasonal".equals(mode) ? mode : null; // ironman: none
            case "Normal" -> "normal";
            case "Seasonal" -> "seasonal";
            default -> null;
        };
        String fameValue = fameMode == null ? null : b.fame().get(fameMode);
        List<FragmentCount> shown = new ArrayList<>();
        for (int i = 0; i < b.fragments().size(); i++) {
            if (i < fragments.length && !fragments[i].get()) continue;
            BalanceLookup.Fragment f = b.fragments().get(i);
            shown.add(new FragmentCount(f.have(), f.glyph()));
        }
        if (fameValue == null && shown.isEmpty()) return null;
        CurrencyColors colors = new CurrencyColors(
                "seasonal".equals(fameMode) ? seasonalIcon.get() : normalIcon.get(), fameColor.get(),
                separatorColor.get(), fragmentColor.get(), fragmentIconColor.get());
        return new CurrencyLine(fameValue, List.copyOf(shown), colors);
    }
}
