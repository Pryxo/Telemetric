package dev.telemetric.module.impl.combat;

import dev.telemetric.Telemetric;
import dev.telemetric.game.CombatAdapter;
import dev.telemetric.game.CombatAdapter.CooldownEvent;
import dev.telemetric.game.CombatAdapter.ItemInfo;
import dev.telemetric.game.CombatAdapter.Kind;
import dev.telemetric.game.DungeonAdapter;
import dev.telemetric.game.ServerInfoAdapter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared cooldown state for every combat feature: one timer per vanilla cooldown group, started / cleared by the
 * server's cooldown packets ({@link CombatAdapter#drainCooldowns()}) and kept in wall-clock time.
 * <p>
 * Vanilla forgets cooldowns whenever the client gets a new player (dungeon entry, respawn); Telos keeps them
 * running server-side, so these timers survive world changes — except items listed in
 * {@link TelosItemRules#resetsOnWorldChange}. A realm / hub switch (the Tab list's "Server" changes) resets all
 * of them, like the server does. Features read timers ({@link #get}, {@link #latest}, {@link #active}) and get
 * {@link #addListener ready events}.
 * <p>
 * Ticked from the entrypoint every client tick on Telos, whether or not a feature is on, so a feature switched on
 * mid-cooldown shows it right away. Idle cost: a few map lookups.
 */
public final class CooldownTracker {

    /**
     * One running cooldown.
     *
     * @param info item that uses the group, or null until it is found on the player
     */
    public record Cooldown(String group, ItemInfo info, long startMs, long endMs) {
        /** Fraction still to wait: 1 = just started, 0 = ready. */
        public float remaining(long now) {
            long total = endMs - startMs;
            return total <= 0 ? 0f : Math.clamp((endMs - now) / (float) total, 0f, 1f);
        }

        public boolean is(Kind kind) {
            return info != null && info.kind() == kind;
        }
    }

    /** Called on the client thread when a resolved cooldown ends. */
    @FunctionalInterface
    public interface ReadyListener {
        void ready(Cooldown cooldown);
    }

    private static final CooldownTracker INSTANCE = new CooldownTracker();
    private static final long MS_PER_TICK = 50;
    /** A re-sent cooldown ending within this of the running one keeps the original start (bar stays continuous). */
    private static final long RESYNC_MS = 1000;

    private final Map<String, Cooldown> active = new LinkedHashMap<>();
    private final List<ReadyListener> listeners = new CopyOnWriteArrayList<>();
    private Object levelToken;
    private String server;

    private CooldownTracker() {}

    public static CooldownTracker get() {
        return INSTANCE;
    }

    public void addListener(ReadyListener listener) {
        listeners.add(listener);
    }

    /** Called every client tick (on Telos) from the entrypoint. */
    public void tick() {
        long now = System.currentTimeMillis();
        detectResets();
        for (CooldownEvent event : CombatAdapter.drainCooldowns()) apply(event, now);
        if (active.isEmpty()) return;

        List<Cooldown> ready = null;
        for (Iterator<Map.Entry<String, Cooldown>> it = active.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Cooldown> e = it.next();
            Cooldown c = e.getValue();
            if (now >= c.endMs()) {
                it.remove();
                if (c.info() != null) (ready == null ? ready = new ArrayList<>(2) : ready).add(c);
            } else if (c.info() == null) {
                ItemInfo found = CombatAdapter.findByGroup(c.group());
                if (found != null) e.setValue(new Cooldown(c.group(), found, c.startMs(), c.endMs()));
            }
        }
        if (ready != null) for (Cooldown c : ready) fire(c);
    }

    /** Running cooldown of {@code group}, or null. */
    public Cooldown get(String group) {
        return group == null ? null : active.get(group);
    }

    /** Most recently started running cooldown of an item of {@code kind}, or null. */
    public Cooldown latest(Kind kind) {
        Cooldown best = null;
        for (Cooldown c : active.values()) {
            if (c.is(kind) && (best == null || c.startMs() >= best.startMs())) best = c;
        }
        return best;
    }

    /** All running cooldowns (read-only view, client thread only). */
    public Collection<Cooldown> active() {
        return Collections.unmodifiableCollection(active.values());
    }

    private void apply(CooldownEvent event, long now) {
        Cooldown running = active.get(event.group());
        if (event.ticks() <= 0) { // cleared early by the server = ready now
            if (running != null) {
                active.remove(event.group());
                if (running.info() != null) fire(running);
            }
            return;
        }
        long end = now + event.ticks() * MS_PER_TICK;
        if (running != null && Math.abs(running.endMs() - end) <= RESYNC_MS) {
            active.put(event.group(), new Cooldown(event.group(), running.info(), running.startMs(), end));
            return;
        }
        ItemInfo info = running != null && running.info() != null ? running.info() : CombatAdapter.findByGroup(event.group());
        active.put(event.group(), new Cooldown(event.group(), info, now, end));
    }

    private void detectResets() {
        Object token = DungeonAdapter.levelToken();
        if (token != levelToken) {
            levelToken = token;
            active.values().removeIf(c -> c.info() != null && TelosItemRules.resetsOnWorldChange(c.info().nameKey()));
        }
        String now = ServerInfoAdapter.tabStat("Server").map(ServerInfoAdapter.TabStat::value).orElse(null);
        if (now == null || now.isBlank()) return; // Tab not filled yet: keep the last known server
        if (server != null && !server.equals(now)) active.clear(); // realm / hub switch: the server resets cooldowns
        server = now;
    }

    private void fire(Cooldown cooldown) {
        for (ReadyListener l : listeners) {
            try {
                l.ready(cooldown);
            } catch (RuntimeException e) {
                Telemetric.LOGGER.error("Cooldown ready listener failed", e);
            }
        }
    }
}
