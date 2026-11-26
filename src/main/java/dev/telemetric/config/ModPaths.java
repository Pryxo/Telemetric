package dev.telemetric.config;

import dev.telemetric.Telemetric;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Every folder and file the mod keeps under {@code config/telemetric/}. No other class builds config paths.
 * <pre>
 * config/telemetric/
 *   README.txt         what lives where
 *   settings/          meta.json, layout.json, profiles/  (your module settings + GUI layout)
 *   saved/             things the mod records for you: stash.json, tracking/
 *   custom/            your own files: bags/ (custom bag pictures)
 *   data/overrides/    hand-placed copies of shared data tables (win over everything)
 *   data/cache/        downloaded shared data tables (safe to delete)
 * </pre>
 * {@link #migrate()} moves files over from the old flat layout once, on the first start with this version.
 */
public final class ModPaths {

    public static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve(Telemetric.MOD_ID);

    public static final Path SETTINGS = ROOT.resolve("settings");
    public static final Path PROFILES = SETTINGS.resolve("profiles");
    public static final Path META = SETTINGS.resolve("meta.json");
    public static final Path LAYOUT = SETTINGS.resolve("layout.json");

    public static final Path SAVED = ROOT.resolve("saved");
    public static final Path STASH = SAVED.resolve("stash.json");
    public static final Path TRACKING = SAVED.resolve("tracking");

    public static final Path CUSTOM = ROOT.resolve("custom");
    public static final Path BAGS = CUSTOM.resolve("bags");
    public static final Path SOUNDS = CUSTOM.resolve("sounds");

    public static final Path DATA = ROOT.resolve("data");
    public static final Path DATA_OVERRIDES = DATA.resolve("overrides");
    public static final Path DATA_CACHE = DATA.resolve("cache");

    private static final String README = """
            Telemetric config folder
            ========================
            settings/          Module settings and GUI layout.
              profiles/        One JSON per config profile (Misc -> Config).
              meta.json        Active profile + auto-save flag.
              layout.json      ClickGUI panel positions, open sections, scroll.
            saved/             Things the mod records for you.
              stash.json       Stash Overlay contents.
              tracking/        Pity counters, lifetime loot (one file per account).
            custom/            Your own files.
              bags/            Custom bag pictures.
            data/
              overrides/       Put a shared data table here to override it locally.
              cache/           Downloaded shared data tables (safe to delete).
            """;

    private ModPaths() {}

    /** Call this first thing on start. It moves the old flat layout into the folders above and writes README.txt. */
    public static void migrate() {
        if (Files.isDirectory(ROOT) && !Files.exists(SETTINGS)) {
            // The old data/ folder held the overrides directly, so move it out of the way before data/ becomes the parent folder.
            Path oldOverrides = ROOT.resolve("data");
            Path parked = ROOT.resolve("data-overrides-old");
            if (Files.isDirectory(oldOverrides)) move(oldOverrides, parked);
            move(ROOT.resolve("meta.json"), META);
            move(ROOT.resolve("layout.json"), LAYOUT);
            move(ROOT.resolve("profiles"), PROFILES);
            move(ROOT.resolve("stash.json"), STASH);
            move(ROOT.resolve("tracking"), TRACKING);
            move(ROOT.resolve("bags"), BAGS);
            move(ROOT.resolve("sounds"), SOUNDS);
            move(ROOT.resolve("data-cache"), DATA_CACHE);
            move(parked, DATA_OVERRIDES);
        }
        try {
            Files.createDirectories(SETTINGS);
            Path readme = ROOT.resolve("README.txt");
            if (!Files.exists(readme) || !Files.readString(readme, StandardCharsets.UTF_8).equals(README)) {
                Files.writeString(readme, README, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Telemetric.LOGGER.warn("Could not prepare config folder {}", ROOT, e);
        }
    }

    /** Moves {@code from} to {@code to} if the old one exists and the new one doesn't. */
    public static void move(Path from, Path to) {
        if (!Files.exists(from) || Files.exists(to)) return;
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to);
            Telemetric.LOGGER.info("Moved {} -> {}", ROOT.relativize(from), ROOT.relativize(to));
        } catch (IOException e) {
            Telemetric.LOGGER.warn("Could not move {} to {}", from, to, e);
        }
    }

    /** Moves several old root-level names into {@code into}, keeping their names. */
    public static void moveAll(List<String> names, Path into) {
        for (String n : names) move(ROOT.resolve(n), into.resolve(n));
    }

}
