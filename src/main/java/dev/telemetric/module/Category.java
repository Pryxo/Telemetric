package dev.telemetric.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Open registry of module categories; each one with a visible module gets a ClickGUI panel. Lower {@code order} is further left. */
public final class Category {

    private static final List<Category> VALUES = new ArrayList<>();

    public static final Category COMBAT = register("Combat", 10);
    public static final Category PLAYER = register("Player", 11);
    public static final Category WORLD = register("World", 12);
    public static final Category INVENTORY = register("UI + Tracking", 13);
    public static final Category STATS = register("Stats", 14);
    public static final Category SOCIAL = register("Social", 15);
    public static final Category MISC = register("Misc", 1000);
    // Extra categories are registered by add-ons (see TelemetricAddon), never here.

    private final String name;
    private final int order;

    private Category(String name, int order) {
        this.name = name;
        this.order = order;
    }

    public static Category register(String name, int order) {
        Objects.requireNonNull(name);
        for (Category existing : VALUES) {
            if (existing.name.equalsIgnoreCase(name)) throw new IllegalStateException("Duplicate category " + name);
        }
        Category category = new Category(name, order);
        VALUES.add(category);
        VALUES.sort(Comparator.comparingInt(Category::getOrder));
        return category;
    }

    public static List<Category> values() {
        return Collections.unmodifiableList(VALUES);
    }

    public String getName() {
        return name;
    }

    public int getOrder() {
        return order;
    }

    @Override
    public String toString() {
        return name;
    }
}
