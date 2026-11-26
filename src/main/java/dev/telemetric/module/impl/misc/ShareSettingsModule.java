package dev.telemetric.module.impl.misc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.telemetric.Telemetric;
import dev.telemetric.module.Category;
import dev.telemetric.module.Module;
import dev.telemetric.setting.Setting;
import dev.telemetric.setting.impl.ActionSetting;
import dev.telemetric.setting.impl.MultiChoiceSetting;
import dev.telemetric.setting.impl.TextSetting;
import dev.telemetric.input.InputUtil;
import dev.telemetric.ui.notification.Toasts;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * UI-only: shares the settings of chosen modules as a single copy-paste code.
 * <p>
 * Generate packs the ticked modules' state ({@code Module#toJson}, without the personal keybind) into
 * {@code TLM1.<base64url(deflate(json))>} and copies it. Apply reads a pasted code and overwrites only the
 * modules that are in the code, and within them only the settings in the code. Every other module stays as it is.
 * Like any change, it gets saved with the active profile (auto-save) or on the next manual save.
 */
public final class ShareSettingsModule extends Module {

    private static final String PREFIX = "TLM1.";
    private static final int FORMAT = 1;
    /** The max size of an inflated code, so pasted garbage can't expand without limit. */
    private static final int MAX_JSON_BYTES = 4 * 1024 * 1024;

    private List<String> moduleNames;

    public final MultiChoiceSetting shared = add(new MultiChoiceSetting(
            "Modules", "Modules whose settings go into the share code", this::shareableNames).searchable());

    public final ActionSetting selectAll = add(new ActionSetting(
            "Select All", "Tick every module", () -> shared.set(new java.util.LinkedHashSet<>(shareableNames()))));

    public final ActionSetting generate = add(new ActionSetting(
            "Generate Code", "Copy a share code for the ticked modules to the clipboard", this::generateCode));

    public final TextSetting code = add(new TextSetting(
            "Paste Code", "Paste a share code here (Ctrl+V), then press Enter or Apply", "", 1 << 20)
            .onSubmit(s -> applyCode())
            .notPersistent());

    public final ActionSetting apply = add(new ActionSetting(
            "Apply Code", "Overwrite only the modules contained in the pasted code", this::applyCode));

    public ShareSettingsModule() {
        super("Share Settings", "Share chosen modules' settings with a code; applying one only changes what it contains",
                Category.MISC, false);
    }

    /** Every module that has something to share (an enabled state or persistent settings), in GUI order. */
    private List<String> shareableNames() {
        if (moduleNames == null) {
            List<String> names = new ArrayList<>();
            for (Module m : Telemetric.get().modules().getModules()) {
                if (m == this || !m.isVisibleInGui()) continue;
                boolean hasSettings = m.getSettings().stream().anyMatch(Setting::isPersistent);
                if (m.isToggleable() || hasSettings) names.add(m.getName());
            }
            moduleNames = names;
        }
        return moduleNames;
    }


    private void generateCode() {
        List<Module> list = new ArrayList<>();
        for (String name : shared.get()) Telemetric.get().modules().find(name).ifPresent(list::add);
        copyCode(list);
    }

    /** Copies a share code for {@code list} to the clipboard (keybinds left out). Shared with the Settings window. */
    public static boolean copyCode(List<Module> list) {
        JsonObject modules = new JsonObject();
        for (Module module : list) {
            if (module instanceof ShareSettingsModule) continue;
            JsonObject json = module.toJson();
            json.remove("bind"); // keybinds are personal
            modules.add(module.getName(), json);
        }
        if (modules.isEmpty()) {
            Toasts.error("Tick at least one module first");
            return false;
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.add("modules", modules);
        String out = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(root.toString()));
        InputUtil.setClipboard(out);
        Toasts.success("Copied share code (" + modules.size() + (modules.size() == 1 ? " module)" : " modules)"));
        return true;
    }


    private void applyCode() {
        if (applyCode(code.get(), null)) code.set("");
    }

    /**
     * Applies a share code. With {@code only} set, just that module's entry is applied (Settings window import).
     * Returns true when at least one module was applied.
     */
    public static boolean applyCode(String raw, Module only) {
        String text = raw == null ? "" : raw.replaceAll("\\s+", "");
        if (text.isEmpty()) {
            Toasts.error(only == null ? "Paste a share code first" : "Clipboard has no share code");
            return false;
        }
        JsonObject modules;
        try {
            modules = decode(text);
            dev.telemetric.config.ConfigManager.mergeNametag(modules); // codes from before the Nametag merge
        } catch (IllegalArgumentException e) {
            Toasts.error("Invalid share code");
            return false;
        }
        int applied = 0;
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : modules.entrySet()) {
            if (only != null && !only.getName().equals(entry.getKey())) continue;
            Optional<Module> module = Telemetric.get().modules().find(entry.getKey());
            if (module.isEmpty() || module.get() instanceof ShareSettingsModule || !entry.getValue().isJsonObject()) {
                missing.add(entry.getKey());
                continue;
            }
            JsonObject json = entry.getValue().getAsJsonObject().deepCopy();
            json.remove("bind");
            try {
                module.get().fromJson(json); // only the keys present in the code get written
                applied++;
            } catch (RuntimeException e) {
                missing.add(entry.getKey());
            }
        }
        if (applied == 0) {
            Toasts.error(only == null ? "Nothing to apply from this code" : "Code has no settings for " + only.getName());
            return false;
        }
        String msg = "Applied " + applied + (applied == 1 ? " module" : " modules");
        if (!missing.isEmpty()) msg += " (" + missing.size() + " unknown skipped)";
        Toasts.success(msg);
        return true;
    }

    /** Turns a code into the {@code modules} object. Throws {@link IllegalArgumentException} for anything malformed. */
    private static JsonObject decode(String text) {
        if (!text.startsWith(PREFIX)) throw new IllegalArgumentException("prefix");
        byte[] packed = Base64.getUrlDecoder().decode(text.substring(PREFIX.length()));
        String json = inflate(packed);
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("json", e);
        }
        if (!root.isJsonObject()) throw new IllegalArgumentException("root");
        JsonElement modules = root.getAsJsonObject().get("modules");
        if (modules == null || !modules.isJsonObject()) throw new IllegalArgumentException("modules");
        return modules.getAsJsonObject();
    }

    private static byte[] deflate(String json) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(json.getBytes(StandardCharsets.UTF_8));
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static String inflate(byte[] packed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(packed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new IllegalArgumentException("truncated");
                }
                out.write(buf, 0, n);
                if (out.size() > MAX_JSON_BYTES) throw new IllegalArgumentException("too large");
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("deflate", e);
        } finally {
            inflater.end();
        }
    }
}
