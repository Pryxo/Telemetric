package dev.telemetric.map;

/**
 * Which baked pixels show. Each pixel remembers the block its ray hit; the mask gives that block an opacity (0 = hidden
 * under the clouds, 1 = fully shown, in between = soft edge). Masks are applied when a tile is composed, so changing
 * the mask never needs a new bake.
 */
@FunctionalInterface
public interface MapMask {

    MapMask ALL = (x, y, z) -> 1f;

    float alpha(int x, int y, int z);
}
