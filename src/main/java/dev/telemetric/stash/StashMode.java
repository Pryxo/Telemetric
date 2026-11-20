package dev.telemetric.stash;

/** Telos keeps two separate sets of stash pages, and slot 53 of the stash switches between them. */
public enum StashMode {
    NORMAL("normal", "Normal"),
    SEASONAL("seasonal", "Seasonal");

    private final String key;
    private final String label;

    StashMode(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** The key used in {@code stash.json}. */
    public String key() { return key; }

    public String label() { return label; }

    public StashMode other() { return this == NORMAL ? SEASONAL : NORMAL; }

    public static StashMode byKey(String key) {
        for (StashMode m : values()) if (m.key.equalsIgnoreCase(key)) return m;
        return null;
    }
}
