package dev.telemetric.stash;

import java.util.Map;
import java.util.TreeMap;

/** All saved pages of one stash (one player, one {@link StashMode}). Pages start at 1. */
public final class StashBook {

    private final TreeMap<Integer, StashPage> pages = new TreeMap<>();
    /** The real number of pages (from scan, arrow edges, conversions). 0 means unknown, unless {@link #knownEmpty}. */
    private int pageCount;
    /** True if Telos has no pages in this mode (it refused a switch here, or the last page got converted away). */
    private boolean knownEmpty;
    private final TreeMap<Integer, String> names = new TreeMap<>();

    public StashPage page(int index) { return pages.get(index); }

    public Map<Integer, StashPage> pages() { return pages; }

    public void put(int index, StashPage page) {
        if (index < 1) return;
        if (pageCount > 0 && index > pageCount) return;
        pages.put(index, page);
    }

    public int pageCount() { return pageCount; }

    /** Custom page names set by the player (kept even past the page count). */
    public Map<Integer, String> names() { return names; }

    /** The page's custom name, or {@code null}. */
    public String name(int index) { return names.get(index); }

    /** Sets a page's custom name, or clears it if the name is blank. */
    public void setName(int index, String name) {
        if (index < 1) return;
        if (name == null || name.isBlank()) names.remove(index);
        else names.put(index, name.strip());
    }

    /** Sets the real page count and drops anything saved past it. */
    public void setPageCount(int count) {
        pageCount = Math.max(0, count);
        if (pageCount > 0) {
            knownEmpty = false;
            pages.tailMap(pageCount, false).clear();
        }
    }

    public boolean isKnownEmpty() { return knownEmpty; }

    /** This mode has no pages at all (saved copies are dropped, names kept). */
    public void markEmpty() {
        pageCount = 0;
        pages.clear();
        knownEmpty = true;
    }

    /** The mode was just seen with a page in it, so it's not empty anymore (the count is unknown again unless set). */
    public void markNotEmpty() { knownEmpty = false; }

    /** The count for display: "18", "0" (known empty) or "?" (unknown). */
    public String countLabel() {
        if (pageCount > 0) return Integer.toString(pageCount);
        return knownEmpty ? "0" : "?";
    }

    /** Page {@code index} left this book (converted to the other mode), so later pages and their names move up one. */
    public void removePage(int index) {
        if (index < 1) return;
        pages.remove(index);
        names.remove(index);
        shiftDown(pages, index);
        shiftDown(names, index);
        if (pageCount == 1) markEmpty();
        else if (pageCount > 0) pageCount--;
    }

    /** A converted page arrived. Telos adds it as a new, empty last page (only tracked if the count is known; 0 becomes 1). */
    public void appendEmptyPage(long savedAt) {
        if (pageCount == 0 && !knownEmpty) return;
        knownEmpty = false;
        pageCount++;
        pages.put(pageCount, new StashPage(new String[StashPage.SLOTS], savedAt));
    }

    private static <V> void shiftDown(TreeMap<Integer, V> map, int removed) {
        TreeMap<Integer, V> tail = new TreeMap<>(map.tailMap(removed, false));
        map.keySet().removeAll(tail.keySet());
        tail.forEach((k, v) -> map.put(k - 1, v));
    }

    /** How many pages to show: the real count if known, otherwise the highest saved page. */
    public int displayCount() {
        if (pageCount > 0) return pageCount;
        return pages.isEmpty() ? 0 : pages.lastKey();
    }
}
