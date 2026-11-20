package dev.telemetric.ui.stash;

import dev.telemetric.game.GameItem;
import dev.telemetric.game.ItemSearchAdapter;

import java.util.Locale;

/**
 * A stash search query. A plain query matches the item name, and a prefix at the very start picks a filter:
 * <ul>
 *   <li>{@code #} tooltip (the name plus every lore line)</li>
 *   <li>{@code &} item id / item tag / item model (e.g. {@code &stick}, {@code &telos:material/pet})</li>
 *   <li>{@code ^} rarity: Irradiated, Gilded, Royal, Bloodshot, Voidbound, Unholy, Tier 2–8, Companions</li>
 *   <li>{@code $} item type: Key, Weapon, Ability, Skin, Mount, Pet, Armour (or any part of the Telos type path, e.g.
 *       {@code $sceptre})</li>
 * </ul>
 * Matching ignores case, and rarity and type names match by prefix ({@code ^blood}, {@code $wea}).
 */
public final class StashSearch {

    public static final String PREFIXES = "#&^$";

    private StashSearch() {}

    /** True if the query filters anything (it's not blank after the prefix). */
    public static boolean active(String query) {
        String q = query.strip();
        if (!q.isEmpty() && PREFIXES.indexOf(q.charAt(0)) >= 0) q = q.substring(1).strip();
        return !q.isEmpty();
    }

    public static boolean matches(GameItem item, String query) {
        ItemSearchAdapter.Info info = ItemSearchAdapter.info(item);
        if (info == null) return false;
        String raw = query.strip().toLowerCase(Locale.ROOT);
        char prefix = raw.isEmpty() ? 0 : raw.charAt(0);
        String q = PREFIXES.indexOf(prefix) >= 0 ? raw.substring(1).strip() : raw;
        if (q.isEmpty()) return true;
        return switch (prefix) {
            case '#' -> info.tooltip().contains(q);
            case '&' -> info.tags().stream().anyMatch(t -> t.contains(q));
            case '^' -> rarityMatches(info, q);
            case '$' -> typeMatches(info, q);
            default -> info.name().contains(q);
        };
    }

    // ------------------------------------------------------------------ rarity

    /**
     * The rarity comes from the tooltip style path ({@code untiered/bloodshot}, tiered loot is {@code tiered/8}).
     * {@code ^tier N} / {@code ^tN} / {@code ^N} match the "N Tier Points" lore line (Tier N items give N points,
     * untiered ones e.g. 11–12), and {@code ^tier} on its own matches every tiered item.
     */
    private static boolean rarityMatches(ItemSearchAdapter.Info info, String q) {
        String query = q.replace(" loot", "").strip();
        String tier = tierNumber(query);
        if (tier != null) return Integer.toString(info.tierPoints()).equals(tier);
        String rarity = info.rarity();
        if (rarity.isEmpty()) return false;
        if (squash(query).equals("tier") || squash(query).equals("tiered")) return rarity.startsWith("tiered/");
        String last = rarity.substring(rarity.lastIndexOf('/') + 1);
        return squash(last).startsWith(squash(query));
    }

    /** Turns "tier 5" / "tier5" / "t5" / "5" into "5", otherwise null. */
    private static String tierNumber(String q) {
        String s = squash(q);
        if (s.startsWith("tier")) s = s.substring(4);
        else if (s.startsWith("t")) s = s.substring(1);
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit) ? s : null;
    }

    // ------------------------------------------------------------------ item type

    /** The Telos type path, e.g. {@code weapon/sceptre/ut-onyx}, {@code misc/dungeon_epic} (a key), {@code pet/...}. */
    private static boolean typeMatches(ItemSearchAdapter.Info info, String q) {
        String category = category(info);
        if (category.isEmpty()) return false;
        String query = squash(q);
        if (category.startsWith(query)) return true;
        if (category.equals("armour") && "armor".startsWith(query)) return true;
        if (squash(info.typePath()).contains(query)) return true;
        // the sub-type from the "Type » …" line: $flying / $land mounts, $melee / $ranged / $healer pets
        return (category.equals("mount") || category.equals("pet")) && info.tooltip().contains("type » " + q);
    }

    /**
     * The display category: Key, Weapon, Ability, Skin, Mount, Pet, Armour, … (lower case). It comes from the type path
     * ({@code mount/fungal_cavern}, {@code pet/ice_cave}). Without one, a tooltip with "Type » Flying/Land" and no Damage
     * line is a mount (pets list Damage/Range/… and Type » Melee/Ranged/Healer).
     */
    static String category(ItemSearchAdapter.Info info) {
        String path = info.typePath();
        if (path.isEmpty()) {
            String t = info.tooltip();
            boolean mountType = t.contains("type » flying") || t.contains("type » land");
            return mountType && !t.contains("damage »") ? "mount" : "";
        }
        int slash = path.indexOf('/');
        String first = slash < 0 ? path : path.substring(0, slash);
        if (path.contains("key") || (first.equals("misc") && path.contains("dungeon"))) return "key";
        return switch (first) {
            case "weapon", "weapons" -> "weapon";
            case "ability", "abilities" -> "ability";
            case "skin", "skins" -> "skin";
            case "mount", "mounts" -> "mount";
            case "pet", "pets" -> "pet";
            case "armour", "armor" -> "armour";
            default -> first;
        };
    }

    private static String squash(String s) {
        return s.replace(" ", "").replace("_", "").replace("-", "");
    }
}
