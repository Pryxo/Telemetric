package dev.telemetric.map;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Saves one {@link VoxelChunk} with its own look palette, so a copy made in one session can be baked in another (look
 * ids are only valid for the session that handed them out). Used by the map capture. Pure Java.
 * <p>
 * Format (gzip, big endian): {@code "TMVC"}, version 1, chunkX, chunkZ, minY, height, palette size {@code n}, then
 * {@code n} looks (kind byte, emission byte, 16 top ARGB ints, 16 side ARGB ints; palette index 0 is air and not
 * written), then {@code height × 256} palette indices as shorts.
 */
public final class VoxelCodec {

    private static final int MAGIC = 0x544D5643; // "TMVC"
    private static final int VERSION = 1;

    /** A chunk read back: its palette (index 0 = air) and the palette indices. */
    public record Saved(int chunkX, int chunkZ, int minY, int height, BlockLook[] palette, short[] ids) {}

    private VoxelCodec() {}

    public static void write(VoxelChunk chunk, IntFunction<BlockLook> looks, OutputStream raw) throws IOException {
        Map<Integer, Integer> local = new HashMap<>();
        List<BlockLook> palette = new ArrayList<>();
        palette.add(BlockLook.NONE);
        short[] src = chunk.ids();
        short[] out = new short[src.length];
        for (int i = 0; i < src.length; i++) {
            int id = src[i];
            if (id == 0) continue;
            Integer l = local.get(id);
            if (l == null) {
                l = palette.size();
                local.put(id, l);
                palette.add(looks.apply(id));
            }
            out[i] = (short) (int) l;
        }
        GZIPOutputStream gz = new GZIPOutputStream(raw);
        DataOutputStream d = new DataOutputStream(new BufferedOutputStream(gz));
        d.writeInt(MAGIC);
        d.writeByte(VERSION);
        d.writeInt(chunk.chunkX);
        d.writeInt(chunk.chunkZ);
        d.writeInt(chunk.minY);
        d.writeInt(chunk.height);
        d.writeShort(palette.size() - 1);
        for (int i = 1; i < palette.size(); i++) {
            BlockLook b = palette.get(i);
            d.writeByte(b.kind());
            d.writeByte(b.emission());
            for (int c : b.top()) d.writeInt(c);
            for (int c : b.side()) d.writeInt(c);
        }
        for (short s : out) d.writeShort(s);
        d.flush();
        gz.finish();
    }

    public static Saved read(InputStream raw) throws IOException {
        DataInputStream d = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)));
        if (d.readInt() != MAGIC) throw new IOException("not a voxel chunk");
        int version = d.readUnsignedByte();
        if (version != VERSION) throw new IOException("unknown voxel chunk version " + version);
        int cx = d.readInt(), cz = d.readInt(), minY = d.readInt(), height = d.readInt();
        if (height < 0 || height > 4096) throw new IOException("bad height " + height);
        int n = d.readUnsignedShort();
        BlockLook[] palette = new BlockLook[n + 1];
        palette[0] = BlockLook.NONE;
        for (int i = 1; i <= n; i++) {
            byte kind = d.readByte();
            int emission = d.readUnsignedByte();
            int[] top = new int[16], side = new int[16];
            for (int k = 0; k < 16; k++) top[k] = d.readInt();
            for (int k = 0; k < 16; k++) side[k] = d.readInt();
            palette[i] = new BlockLook(top, side, kind, emission);
        }
        short[] ids = new short[height << 8];
        for (int i = 0; i < ids.length; i++) ids[i] = d.readShort();
        return new Saved(cx, cz, minY, height, palette, ids);
    }

    /** Just the position and height range ({@code palette} and {@code ids} are null). */
    public static Saved readHeader(InputStream raw) throws IOException {
        DataInputStream d = new DataInputStream(new GZIPInputStream(raw, 512));
        if (d.readInt() != MAGIC) throw new IOException("not a voxel chunk");
        if (d.readUnsignedByte() != VERSION) throw new IOException("unknown voxel chunk version");
        return new Saved(d.readInt(), d.readInt(), d.readInt(), d.readInt(), null, null);
    }

    /**
     * Collects looks from many saved chunks into one table (equal looks share an id), so saved chunks can go into a
     * {@link VoxelStore} together.
     */
    public static final class LookTable {
        private final Map<String, Integer> byContent = new HashMap<>();
        private final List<BlockLook> table = new ArrayList<>(List.of(BlockLook.NONE));

        /** The chunk with its palette indices turned into ids of this table. */
        public VoxelChunk add(Saved s) {
            int[] map = new int[s.palette().length];
            for (int i = 1; i < map.length; i++) map[i] = id(s.palette()[i]);
            short[] ids = new short[s.ids().length];
            for (int i = 0; i < ids.length; i++) {
                int p = s.ids()[i];
                ids[i] = p > 0 && p < map.length ? (short) map[p] : 0;
            }
            return new VoxelChunk(s.chunkX(), s.chunkZ(), s.minY(), s.height(), ids);
        }

        private int id(BlockLook b) {
            String key = b.kind() + ":" + b.emission() + ":" + Arrays.toString(b.top()) + Arrays.toString(b.side());
            Integer id = byContent.get(key);
            if (id != null) return id;
            if (table.size() >= Short.MAX_VALUE) return 0;
            id = table.size();
            table.add(b);
            byContent.put(key, id);
            return id;
        }

        public BlockLook[] toArray() {
            return table.toArray(new BlockLook[0]);
        }
    }
}
