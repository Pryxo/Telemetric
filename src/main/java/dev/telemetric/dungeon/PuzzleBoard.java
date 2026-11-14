package dev.telemetric.dungeon;

import java.util.ArrayList;
import java.util.List;

/**
 * State of a dungeon's puzzle rooms this run ({@code DungeonRules#chatPuzzles}). Rooms fill the slots in the order
 * they are first named in chat; an unnamed slot is "???". Pure Java, client thread only (writes), snapshot reads.
 */
public final class PuzzleBoard {

    /** One puzzle row: room name (null = not seen yet), player attempting it (null = nobody), completed. */
    public record Puzzle(String room, String player, boolean completed) {}

    private final int slots;
    private final List<Puzzle> rooms = new ArrayList<>();
    private volatile List<Puzzle> snapshot;

    public PuzzleBoard(int slots) {
        this.slots = slots;
        publish();
    }

    /** {@code player} started {@code room}. Ignored once the room is completed. */
    public void attempt(String player, String room) {
        int i = slot(room);
        if (i < 0 || rooms.get(i).completed()) return;
        rooms.set(i, new Puzzle(rooms.get(i).room(), player, false));
        publish();
    }

    /** {@code room} was completed. */
    public void complete(String room) {
        int i = slot(room);
        if (i < 0) return;
        rooms.set(i, new Puzzle(rooms.get(i).room(), null, true));
        publish();
    }

    /** {@code room} reset (failed / timed out): nobody attempting it any more. Ignored once completed. */
    public void reset(String room) {
        int i = slot(room);
        if (i < 0 || rooms.get(i).completed()) return;
        rooms.set(i, new Puzzle(rooms.get(i).room(), null, false));
        publish();
    }

    /** All slots in order; unnamed ones have a null room. */
    public List<Puzzle> puzzles() {
        return snapshot;
    }

    /** Rooms completed this run (at most the slot count). */
    public int completed() {
        int n = 0;
        for (Puzzle p : snapshot) if (p.completed()) n++;
        return n;
    }

    /** Index of {@code room}, taking the next free slot for a new room; -1 if all slots are named by other rooms. */
    private int slot(String room) {
        for (int i = 0; i < rooms.size(); i++) if (rooms.get(i).room().equalsIgnoreCase(room)) return i;
        if (rooms.size() >= slots) return -1;
        rooms.add(new Puzzle(room, null, false));
        return rooms.size() - 1;
    }

    private void publish() {
        List<Puzzle> out = new ArrayList<>(rooms);
        while (out.size() < slots) out.add(new Puzzle(null, null, false));
        snapshot = List.copyOf(out);
    }
}
