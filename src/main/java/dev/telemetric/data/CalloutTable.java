package dev.telemetric.data;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code Extractor/manual/callouts.json} — what the Callouts key needs to know about bosses and portals: the public
 * call cooldown, partial boss bars, duo bosses, phase names by HP and portal labels. Pure Java.
 */
public record CalloutTable(Cooldown cooldown, Map<String, Integer> barFrom, List<Duo> duos,
                           Map<String, List<Stage>> phases, Map<String, String> portals,
                           String unmodelledArea, String unmodelledPortal) {

    static final CalloutTable EMPTY = new CalloutTable(new Cooldown(15, 10, 10), Map.of(), List.of(), Map.of(),
            Map.of(), "", "");

    /** Public call cooldown: {@code seconds}, or {@code hpChangedSeconds} once the HP moved by {@code hpStep} %. */
    public record Cooldown(int seconds, int hpChangedSeconds, int hpStep) {}

    /** Bosses fought together and called out as one ({@code "Apostle & Hierophant"}). */
    public record Duo(String name, List<String> bosses) {}

    /** A phase that starts at {@code from} % HP. */
    public record Stage(int from, String name) {}

    static CalloutTable parse(JsonObject root) {
        JsonObject cd = JsonFields.obj(root, "cooldown");
        Cooldown cooldown = new Cooldown(JsonFields.integer(cd, "seconds", 15),
                JsonFields.integer(cd, "hpChangedSeconds", 10), JsonFields.integer(cd, "hpStep", 10));

        Map<String, Integer> barFrom = new HashMap<>();
        JsonObject bars = JsonFields.obj(root, "bars");
        if (bars != null) {
            for (String boss : bars.keySet()) {
                int from = JsonFields.integer(JsonFields.obj(bars, boss), "from", 0);
                if (from > 0 && from < 100) barFrom.put(Names.key(boss), from);
            }
        }

        List<Duo> duos = new ArrayList<>();
        for (JsonObject d : JsonFields.objects(root, "duos")) {
            List<String> bosses = JsonFields.strings(d, "bosses");
            if (bosses.size() >= 2) duos.add(new Duo(JsonFields.str(d, "name"), bosses));
        }

        Map<String, List<Stage>> phases = new HashMap<>();
        for (JsonObject p : JsonFields.objects(root, "phases")) {
            List<Stage> stages = new ArrayList<>();
            for (JsonObject s : JsonFields.objects(p, "stages")) {
                stages.add(new Stage(JsonFields.integer(s, "from", 0), JsonFields.str(s, "name")));
            }
            stages.sort((a, b) -> Integer.compare(b.from(), a.from())); // highest first
            for (String boss : JsonFields.strings(p, "bosses")) phases.put(Names.key(boss), List.copyOf(stages));
        }

        Map<String, String> portals = new HashMap<>();
        JsonFields.stringMap(root, "portals").forEach((k, v) -> portals.put(k.toUpperCase(Locale.ROOT), v));
        JsonObject un = JsonFields.obj(root, "unmodelledPortal");

        return new CalloutTable(cooldown, Map.copyOf(barFrom), List.copyOf(duos), Map.copyOf(phases),
                Map.copyOf(portals), JsonFields.str(un, "area"), JsonFields.str(un, "portal"));
    }

    /** The boss's own HP % for a bar at {@code barPercent} (partial bars rescaled), clamped to 0..100. */
    public int bossPercent(String boss, int barPercent) {
        Integer from = barFrom.get(Names.key(boss));
        if (from == null) return barPercent;
        return Math.clamp(Math.round((barPercent - from) * 100f / (100 - from)), 0, 100);
    }

    /** The duo this boss belongs to, or null. */
    public Duo duoOf(String boss) {
        String key = Names.key(boss);
        for (Duo d : duos) for (String b : d.bosses()) if (Names.key(b).equals(key)) return d;
        return null;
    }

    /** Phase name for the boss at {@code hp} %, or null when the boss has no phase table. */
    public String phase(String boss, int hp) {
        List<Stage> stages = phases.get(Names.key(boss));
        if (stages == null) return null;
        for (Stage s : stages) if (hp >= s.from()) return s.name();
        return null;
    }

    /**
     * Readable portal label for an Evoker head model name ({@code TOMB_OF_SHADOWS}): the table entry, else the known
     * dungeon with that name, else the name title-cased. Null for portals that are not called out ({@code RETURN}).
     */
    public String portalLabel(String modelName, List<String> knownPlaces) {
        String upper = modelName.toUpperCase(Locale.ROOT);
        String label = portals.get(upper);
        if (label != null) return label.isEmpty() ? null : label;
        String key = Names.placeKey(upper.replace('_', ' '));
        for (String place : knownPlaces) if (Names.placeKey(place).equals(key)) return place;
        StringBuilder b = new StringBuilder(upper.length());
        for (String word : upper.toLowerCase(Locale.ROOT).split("_")) {
            if (word.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return b.isEmpty() ? null : b.toString();
    }
}
