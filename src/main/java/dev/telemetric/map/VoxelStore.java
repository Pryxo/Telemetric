package dev.telemetric.map;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.LongConsumer;

/**
 * The block copies a map is baked from: {@link VoxelChunk}s by chunk key, plus the {@link BlockLook} table their ids
 * point into. Written on the client thread ({@code game/MapAdapter}), read by the bake thread.
 */
public final class VoxelStore {

    private final ConcurrentHashMap<Long, VoxelChunk> chunks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> changed = new ConcurrentLinkedQueue<>();
    private volatile BlockLook[] looks = {BlockLook.NONE};
    private volatile long version;

    public static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    /** Stores a chunk copy. Returns true (and queues it for {@link #drainChanged}) if it differs from the stored one. */
    public boolean put(VoxelChunk chunk) {
        long k = key(chunk.chunkX, chunk.chunkZ);
        VoxelChunk old = chunks.put(k, chunk);
        if (chunk.sameAs(old)) return false;
        changed.add(k);
        version++;
        return true;
    }

    public VoxelChunk chunk(int chunkX, int chunkZ) {
        return chunks.get(key(chunkX, chunkZ));
    }

    /** Drops a chunk copy (out of range); its tiles are marked changed. */
    public void remove(int chunkX, int chunkZ) {
        long k = key(chunkX, chunkZ);
        if (chunks.remove(k) == null) return;
        changed.add(k);
        version++;
    }

    /** Every stored chunk key (a snapshot). */
    public long[] keys() {
        return chunks.keySet().stream().mapToLong(Long::longValue).toArray();
    }

    public boolean has(int chunkX, int chunkZ) {
        return chunks.containsKey(key(chunkX, chunkZ));
    }

    public int size() {
        return chunks.size();
    }

    public long version() {
        return version;
    }

    /** Hands every chunk key changed since the last call to {@code sink}. */
    public void drainChanged(LongConsumer sink) {
        Long k;
        while ((k = changed.poll()) != null) sink.accept(k);
    }

    public void clear() {
        chunks.clear();
        changed.clear();
        version++;
    }

    /** Replaces the look table (the adapter grows it as new block states show up). */
    public void setLooks(BlockLook[] table) {
        looks = table;
    }

    public BlockLook look(int id) {
        BlockLook[] t = looks;
        return id >= 0 && id < t.length && t[id] != null ? t[id] : BlockLook.NONE;
    }

    /** A reader for one thread, with a one-chunk cache. */
    public Reader reader() {
        return new Reader();
    }

    public final class Reader {
        private int cx = Integer.MIN_VALUE, cz = Integer.MIN_VALUE;
        private VoxelChunk last;

        /** Look id at a block, 0 if unknown or air. */
        public int id(int x, int y, int z) {
            int kx = x >> 4, kz = z >> 4;
            if (kx != cx || kz != cz) {
                cx = kx;
                cz = kz;
                last = chunks.get(key(kx, kz));
            }
            return last == null ? 0 : last.id(x & 15, y, z & 15);
        }

        /** True if the chunk holding this column was copied. */
        public boolean known(int x, int z) {
            int kx = x >> 4, kz = z >> 4;
            if (kx != cx || kz != cz) {
                cx = kx;
                cz = kz;
                last = chunks.get(key(kx, kz));
            }
            return last != null;
        }

        public BlockLook look(int id) {
            return VoxelStore.this.look(id);
        }
    }
}
