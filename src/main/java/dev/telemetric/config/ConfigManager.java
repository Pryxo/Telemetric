package dev.telemetric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.module.Module;
import dev.telemetric.module.ModuleManager;
import dev.telemetric.setting.impl.KeybindSetting;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Loads and saves module settings and the GUI layout in {@code .minecraft/config/telemetric/settings/}
 * (paths come from {@link ModPaths}):
 * <pre>
 * settings/
 * ├─ meta.json            active profile, auto-save flag
 * ├─ layout.json          ClickGUI panel positions (profile independent)
 * └─ profiles/
 *    └─ &lt;name&gt;.json      module states + setting values
 * </pre>
 * Every write is atomic (temp file, then move), so a crash can't leave a half-written config behind.
 */
public final class ConfigManager {

    public static final int FORMAT_VERSION = 1;
    public static final String DEFAULT_PROFILE = "default";
    private static final Pattern PROFILE_NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");

    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ModuleManager modules;
    private final GuiLayout layout = new GuiLayout();

    private final Path profilesDir;
    private final Path metaFile;
    private final Path layoutFile;

    private String activeProfile = DEFAULT_PROFILE;
    private boolean autoSave = true;

    public ConfigManager(ModuleManager modules) {
        this.modules = modules;
        this.profilesDir = ModPaths.PROFILES;
        this.metaFile = ModPaths.META;
        this.layoutFile = ModPaths.LAYOUT;
    }

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

    /**
     * Copies the entries of modules that aren't registered right now (an add-on that isn't installed this session,
     * say) from the existing profile file into {@code moduleJson}, so saving never wipes their settings.
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
     * Loads a profile. Modules missing from the file are reset to their defaults, so the result is exactly the saved
     * state. Returns {@code false} if the profile doesn't exist or can't be read.
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

    /** Sorted list of the profile names on disk. Always includes the active profile. */
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

    /** @return a valid profile name, or {@code null} */
    public static String sanitizeName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        return PROFILE_NAME.matcher(trimmed).matches() ? trimmed : null;
    }

    public static boolean isProfileChar(int codepoint) {
        return (codepoint >= 'a' && codepoint <= 'z') || (codepoint >= 'A' && codepoint <= 'Z')
                || (codepoint >= '0' && codepoint <= '9') || codepoint == '_' || codepoint == '-';
    }

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

    public void setAutoSave(boolean autoSave) {
        if (this.autoSave == autoSave) return;
        this.autoSave = autoSave;
        saveMeta();
    }

    /** Upgrades older profile formats in place. Add a case per format bump. */
    private JsonObject migrate(JsonObject json) {
        int version = json.has("version") && json.get("version").isJsonPrimitive()
                && json.getAsJsonPrimitive("version").isNumber() ? json.get("version").getAsInt() : 1;
        renameModule(json, "Nametags", "Status Title");
        if (json.has("modules") && json.get("modules").isJsonObject()) mergeNametag(json.getAsJsonObject("modules"));
        json.addProperty("version", Math.max(version, FORMAT_VERSION));
        return json;
    }

    /** A module that was renamed keeps its saved entry (only if nothing is saved under the new name yet). */
    private static void renameModule(JsonObject json, String oldName, String newName) {
        if (!json.has("modules") || !json.get("modules").isJsonObject()) return;
        JsonObject modules = json.getAsJsonObject("modules");
        if (modules.has(oldName) && !modules.has(newName)) modules.add(newName, modules.remove(oldName));
    }

    /**
     * Status Title + Player Currency became one <b>Nametag</b> module: their saved entries are merged into a "Nametag"
     * entry (only if none is saved yet) and removed. {@code modules} maps module names to their JSON (a profile's
     * "modules" object or a decoded share code). Status Title → the "Status Title" group toggle (on if that module and
     * its Show Status were on), Player Currency → the "Player Currency" group toggle; Position / Hide Yourself are
     * renamed, Hide Own / Hide HP only count from a module that was on.
     */
    public static void mergeNametag(JsonObject modules) {
        JsonObject st = objectOf(modules.remove("Status Title"));
        JsonObject pc = objectOf(modules.remove("Player Currency"));
        if ((st == null && pc == null) || modules.has("Nametag")) return;
        boolean stOn = bool(st, "enabled", false), pcOn = bool(pc, "enabled", false);
        JsonObject stSet = settingsOf(st), pcSet = settingsOf(pc);
        JsonObject out = new JsonObject();
        out.addProperty("enabled", stOn || pcOn);
        JsonElement bind = st != null && st.has("bind") && !isNoBind(st.get("bind")) ? st.get("bind")
                : pc != null && pc.has("bind") ? pc.get("bind") : null;
        if (bind != null) out.add("bind", bind);
        JsonObject set = new JsonObject();
        for (String k : new String[]{"Top 1 Wave", "Status Theme", "Status Position", "Show Status Colors"}) {
            if (stSet.has(k)) set.add(k, stSet.get(k));
        }
        for (Map.Entry<String, JsonElement> e : pcSet.entrySet()) {
            switch (e.getKey()) {
                case "Position" -> set.add("Currency Position", e.getValue());
                case "Hide Yourself" -> set.add("Hide Own Currency", e.getValue());
                case "Hide HP" -> { }
                default -> set.add(e.getKey(), e.getValue());
            }
        }
        set.addProperty("Status Title", stOn && bool(stSet, "Show Status", true));
        set.addProperty("Player Currency", pcOn);
        set.addProperty("Hide Own", stOn && bool(stSet, "Hide Own", false));
        set.addProperty("Hide HP", stOn && bool(stSet, "Hide HP", false) || pcOn && bool(pcSet, "Hide HP", false));
        out.add("settings", set);
        modules.add("Nametag", out);
    }

    private static JsonObject objectOf(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonObject settingsOf(JsonObject module) {
        JsonObject s = module == null ? null : objectOf(module.get("settings"));
        return s == null ? new JsonObject() : s;
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        if (o == null) return fallback;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
    }

    private static boolean isNoBind(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && e.getAsInt() == KeybindSetting.NONE;
    }

    private Path profileFile(String profile) {
        return profilesDir.resolve(profile + ".json");
    }

    private Optional<JsonObject> readJson(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element != null && element.isJsonObject()) return Optional.of(element.getAsJsonObject());
            Telemetric.LOGGER.warn("{} is not a JSON object", file);
        } catch (IOException e) {
            // a read error (file locked by another program…) says nothing about the content: keep the file as it is
            Telemetric.LOGGER.error("Could not read {}", file, e);
        } catch (RuntimeException e) {
            Telemetric.LOGGER.error("Could not parse {}, backing it up", file, e);
            AtomicFiles.backupCorrupt(file);
        }
        return Optional.empty();
    }

    private boolean writeJson(Path file, JsonObject json) {
        try {
            AtomicFiles.writeString(file, gson.toJson(json));
            return true;
        } catch (IOException e) {
            Telemetric.LOGGER.error("Could not write {}", file, e);
            return false;
        }
    }
}
