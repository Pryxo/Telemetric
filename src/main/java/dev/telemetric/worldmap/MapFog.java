package dev.telemetric.worldmap;

import dev.telemetric.game.MapAdapter;
import dev.telemetric.map.FogField;
import dev.telemetric.ui.animation.Durations;
import dev.telemetric.ui.render.Render2D;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The drifting fog banks over every unknown map area (world map, Minimap), drawn on top of the map tiles. The fog
 * ({@link FogField}) is rendered off the client thread into a low-res image pinned to the map, a few times a second,
 * and drawn smoothly stretched; panning moves the last image along until the next one is ready. One instance per map.
 */
public final class MapFog {

    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Telemetric-Fog");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger IDS = new AtomicInteger();

    /** Fog texture edge (the image never gets bigger; coarser texels instead). */
    private static final int TEX = 512;
    /** Target fog texel size in real screen pixels. */
    private static final double TEXEL_PX = 4;
    /** Extra fog around the view, as a share of its size, so panning doesn't reveal the edge before the next render. */
    private static final double MARGIN = 0.15;

    private final String textureId = "telemetric:map/fog_" + IDS.incrementAndGet();
    private volatile Result ready;
    private volatile boolean busy;
    private Result shown;
    private long startedAt;
    private Object lastKey;

    private record Result(FogField.Job job, int[] argb) {}

    /**
     * Draws the fog over the area {@code (x, y, w, h)} (caller clips). Map point {@code (u, v)} lands at screen
     * {@code (ox + u·scale, oy + v·scale)}.
     *
     * @param key anything that changes when the known area does (e.g. a texture count); a change renders right away
     */
    public void draw(Render2D r, int x, int y, int w, int h, double ox, double oy, double scale,
                     FogField.Known known, FogField.Style style, Object key) {
        if (w <= 0 || h <= 0 || scale <= 0) return;
        Result done = ready;
        if (done != null) {
            ready = null;
            MapAdapter.uploadSmooth(textureId, TEX, done.job().w(), done.job().h(), done.argb());
            shown = done;
        }
        double u0 = (x - ox) / scale, v0 = (y - oy) / scale, u1 = (x + w - ox) / scale, v1 = (y + h - oy) / scale;
        maybeStart(u0, v0, u1, v1, scale, known, style, key);
        if (shown == null) return;
        FogField.Job j = shown.job();
        r.pushTransform((float) (ox + j.u0() * scale), (float) (oy + j.v0() * scale), (float) (j.texel() * scale));
        r.texture(textureId, 0, 0, j.w(), j.h(), 0, 0, j.w(), j.h(), TEX, TEX, 0xFFFFFFFF);
        r.popTransform();
    }

    private void maybeStart(double u0, double v0, double u1, double v1, double scale, FogField.Known known,
                            FogField.Style style, Object key) {
        if (busy) return;
        long now = System.currentTimeMillis();
        int loop = Durations.loop(Durations.MAP_FOG_DRIFT_LOOP);
        boolean moving = loop > 0;
        FogField.Job last = shown == null ? null : shown.job();
        double texel = Math.pow(2, Math.round(Math.log(TEXEL_PX / Render2D.guiScale() / scale) / Math.log(2)));
        boolean covered = last != null && last.texel() == texel && u0 >= last.u0() && v0 >= last.v0()
                && u1 <= last.u0() + last.w() * texel && v1 <= last.v0() + last.h() * texel;
        boolean changed = !java.util.Objects.equals(key, lastKey) || last == null || !last.style().equals(style);
        boolean due = moving && now - startedAt >= Durations.MAP_FOG_REFRESH;
        if (covered && !changed && !due) return;

        double mu = (u1 - u0) * MARGIN, mv = (v1 - v0) * MARGIN;
        while ((u1 - u0 + 2 * mu) / texel > TEX || (v1 - v0 + 2 * mv) / texel > TEX) texel *= 2;
        double fu0 = Math.floor((u0 - mu) / texel) * texel, fv0 = Math.floor((v0 - mv) / texel) * texel;
        int fw = Math.min(TEX, (int) Math.ceil((u1 + mu - fu0) / texel) + 1);
        int fh = Math.min(TEX, (int) Math.ceil((v1 + mv - fv0) / texel) + 1);
        double seconds = moving ? (now % loop) / (double) loop * FogField.LOOP_SECONDS : 0;
        FogField.Job job = new FogField.Job(fu0, fv0, texel, fw, fh, scale, seconds, style, known);

        busy = true;
        startedAt = now;
        lastKey = key;
        THREAD.submit(() -> {
            try {
                ready = new Result(job, FogField.render(job));
            } catch (RuntimeException e) {
                dev.telemetric.Telemetric.LOGGER.error("Map fog render failed", e);
            } finally {
                busy = false;
            }
        });
    }

    /** Frees the fog texture (map closed for good). */
    public void release() {
        MapAdapter.release(textureId);
        shown = null;
        ready = null;
    }
}
