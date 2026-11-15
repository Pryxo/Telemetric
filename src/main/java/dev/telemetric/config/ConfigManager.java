package dev.telemetric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.module.Module;
import dev.telemetric.module.ModuleManager;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Loads and saves everything to {@code .minecraft/config/telemetric/}:
 * <pre>
 * telemetric/
 * ├─ meta.json            active profile, auto-save flag
 * ├─ layout.json          ClickGUI panel positions (profile independent)
 * └─ profiles/
 *    └─ &lt;name&gt;.json      module states + setting values
 * </pre>
 * All writes are atomic (temp file + move) so a crash never leaves a half-written config.
 * See docs/config.md.
 */
public final class ConfigManager {

    public static final int FORMAT_VERSION = 1;
    public static final String DEFAULT_PROFILE = "default";
    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ModuleManager modules;
    private final GuiLayout layout = new GuiLayout();

    private final Path root;
    private final Path profilesDir;
    private final Path metaFile;
    private final Path layoutFile;

    private String activeProfile = DEFAULT_PROFILE;
    private boolean autoSave = true;

    public ConfigManager(ModuleManager modules) {
        this.modules = modules;
        this.root = FabricLoader.getInstance().getConfigDir().resolve(Telemetric.MOD_ID);
        this.profilesDir = root.resolve("profiles");
        this.metaFile = root.resolve("meta.json");
        this.layoutFile = root.resolve("layout.json");
    }

    // ================================================================== lifecycle

    public void init() {
        try {
            Files.createDirectories(profilesDir);
        } catch (IOException e) {
            Telemetric.LOGGER.error("Could not create config directory {}", profilesDir, e);
        }
        loadMeta();
        readJson(layoutFile).ifPresent(layout::fromJson);
        if (!loadProfile(activeProfile)) {
            Telemetric.LOGGER.info("Profile '{}' not found, creating it", activeProfile);
            saveProfile(activeProfile);
        }
    }

    /** Called when the ClickGUI closes. */
    public void onGuiClosed() {
        saveLayout();
        if (autoSave) saveProfile(activeProfile);
    }

    /** Called when the client shuts down. */
    public void onShutdown() {
        saveLayout();
        if (autoSave) saveProfile(activeProfile);
        saveMeta();
    }

    // ================================================================== profiles

    /**
     * Copies entries of modules that are not registered right now (e.g. an add-on that isn't installed this
     * session) from the existing profile file into {@code moduleJson}, so saving never wipes their settings.
     */
    private void keepUnloadedModules(Path file, JsonObject moduleJson) {
        if (!Files.exists(file)) return;
        readJson(file).ifPresent(old -> {
            if (!old.has("modules") || !old.get("modules").isJsonObject()) return;
            old.getAsJsonObject("modules").entrySet().forEach(e -> {
                if (!moduleJson.has(e.getKey())) moduleJson.add(e.getKey(), e.getValue());
            });
        });
    }

    public boolean saveProfile(String name) {
        String profile = sanitizeName(name);
        if (profile == null) return false;
        JsonObject json = new JsonObject();
        json.addProperty("version", FORMAT_VERSION);
        json.addProperty("modVersion", Telemetric.version());
        JsonObject moduleJson = new JsonObject();
        for (Module module : modules.getModules()) {
            try {
                moduleJson.add(module.getName(), module.toJson());
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("Failed to serialise module {}", module.getName(), e);
            }
        }
        keepUnloadedModules(profileFile(profile), moduleJson);
        json.add("modules", moduleJson);
        boolean ok = writeJson(profileFile(profile), json);
        if (ok) setActiveProfile(profile);
        return ok;
    }

    /**
     * Loads a profile. Modules missing from the file are reset to defaults so the result is exactly
     * the saved state. Returns {@code false} if the profile does not exist or is unreadable.
     */
    public boolean loadProfile(String name) {
        String profile = sanitizeName(name);
        if (profile == null) return false;
        Optional<JsonObject> read = readJson(profileFile(profile));
        if (read.isEmpty()) return false;

        JsonObject json = migrate(read.get());
        JsonObject moduleJson = json.has("modules") && json.get("modules").isJsonObject()
                ? json.getAsJsonObject("modules") : new JsonObject();

        for (Module module : modules.getModules()) {
            try {
                module.resetToDefaults();
                JsonElement element = moduleJson.get(module.getName());
                if (element != null && element.isJsonObject()) module.fromJson(element.getAsJsonObject());
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("Failed to load module {} from profile {}", module.getName(), profile, e);
            }
        }
        setActiveProfile(profile);
        return true;
    }

    public boolean deleteProfile(String name) {
        String profile = sanitizeName(name);
        if (profile == null || profile.equals(activeProfile)) return false;
        try {
            return Files.deleteIfExists(profileFile(profile));
        } catch (IOException e) {
            Telemetric.LOGGER.error("Could not delete profile {}", profile, e);
            return false;
        }
    }

    public boolean profileExists(String name) {
        String profile = sanitizeName(name);
        return profile != null && Files.isRegularFile(profileFile(profile));
    }

    /** Sorted list of profile names on disk (always contains the active profile). */
    public List<String> listProfiles() {
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(profilesDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(profilesDir, "*.json")) {
                for (Path file : stream) {
                    String fileName = file.getFileName().toString();
                    names.add(fileName.substring(0, fileName.length() - ".json".length()));
                }
            } catch (IOException e) {
                Telemetric.LOGGER.error("Could not list profiles", e);
            }
        }
        if (!names.contains(activeProfile)) names.add(activeProfile);
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** @return a valid profile name or {@code null}. */
    public static String sanitizeName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        return PROFILE_NAME.matcher(trimmed).matches() ? trimmed : null;
    }

    public static boolean isProfileChar(int codepoint) {
        return (codepoint >= 'a' && codepoint <= 'z') || (codepoint >= 'A' && codepoint <= 'Z')
                || (codepoint >= '0' && codepoint <= '9') || codepoint == '_' || codepoint == '-';
    }

    // ================================================================== meta / layout

    public void saveLayout() {
        writeJson(layoutFile, layout.toJson());
    }

    private void loadMeta() {
        readJson(metaFile).ifPresent(json -> {
            try {
                if (json.has("activeProfile")) {
                    String name = sanitizeName(json.get("activeProfile").getAsString());
                    if (name != null) activeProfile = name;
                }
                if (json.has("autoSave")) autoSave = json.get("autoSave").getAsBoolean();
            } catch (RuntimeException e) {
                Telemetric.LOGGER.warn("Malformed meta.json, using defaults", e);
            }
        });
    }

    private void saveMeta() {
        JsonObject json = new JsonObject();
        json.addProperty("version", FORMAT_VERSION);
        json.addProperty("activeProfile", activeProfile);
        json.addProperty("autoSave", autoSave);
        writeJson(metaFile, json);
    }

    private void setActiveProfile(String profile) {
        if (!profile.equals(activeProfile)) {
            activeProfile = profile;
        }
        saveMeta();
    }

    public String getActiveProfile() { return activeProfile; }
    public boolean isAutoSave() { return autoSave; }
    public GuiLayout getLayout() { return layout; }
    public Path getRoot() { return root; }

    public void setAutoSave(boolean autoSave) {
        if (this.autoSave == autoSave) return;
        this.autoSave = autoSave;
        saveMeta();
    }

    // ================================================================== migration

    /** Upgrades older profile formats in place. Add a case per format bump. */
    private JsonObject migrate(JsonObject json) {
        int version = json.has("version") ? json.get("version").getAsInt() : 1;
        // if (version < 2) { ...; version = 2; }
        json.addProperty("version", Math.max(version, FORMAT_VERSION));
        return json;
    }

    // ================================================================== io

    private Path profileFile(String profile) {
        return profilesDir.resolve(profile + ".json");
    }

    private Optional<JsonObject> readJson(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element != null && element.isJsonObject()) return Optional.of(element.getAsJsonObject());
            Telemetric.LOGGER.warn("{} is not a JSON object", file);
        } catch (IOException | RuntimeException e) {
            Telemetric.LOGGER.error("Could not read {}, backing it up", file, e);
            backupCorrupt(file);
        }
        return Optional.empty();
    }

    private boolean writeJson(Path file, JsonObject json) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(json, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            Telemetric.LOGGER.error("Could not write {}", file, e);
            return false;
        }
    }

    private void backupCorrupt(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
