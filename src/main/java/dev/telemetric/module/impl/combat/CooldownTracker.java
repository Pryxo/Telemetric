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
 * Shared cooldown timers for the combat features: one per vanilla cooldown group, driven by the server's cooldown
 * packets ({@link CombatAdapter#drainCooldowns()}) and kept in wall-clock time.
 * <p>
 * Vanilla forgets cooldowns when the client gets a new player (dungeon entry, respawn) but Telos keeps them running
 * server-side, so these survive world changes. Exceptions: items in {@link TelosItemRules#resetsOnWorldChange}, and a
 * realm or hub switch (the Tab list's "Server" changes), which clears everything like the server does.
 * <p>
 * Ticked every client tick on Telos even if no feature is on, so one enabled mid-cooldown shows it right away.
 */
public final class CooldownTracker {

    /**
     * One running cooldown.
     *
     * @param info the item that uses the group, or null until it's found on the player
     */
    public record Cooldown(String group, ItemInfo info, long startMs, long endMs) {
        /** The fraction still to wait: 1 means just started, 0 means ready. */
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
    /** If a re-sent cooldown ends within this of the running one, the original start is kept so the bar stays continuous. */
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

    public Cooldown get(String group) {
        return group == null ? null : active.get(group);
    }

    /** The most recently started running cooldown of an item of {@code kind}, or null. */
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
        if (event.ticks() <= 0) { // the server cleared it early, so it's ready now
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
        if (now == null || now.isBlank()) return; // the Tab list isn't filled yet, so keep the last known server
        if (server != null && !server.equals(now)) active.clear(); // a realm or hub switch, where the server resets cooldowns
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
