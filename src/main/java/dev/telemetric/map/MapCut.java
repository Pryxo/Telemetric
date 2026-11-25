package dev.telemetric.map;

/**
 * The highest block layer drawn per column (everything above is cut away, like a cave view). A dungeon map cuts each
 * column just above the head of whoever walks there, so ceilings go and walls show as a slice; a world map uses one
 * flat height. {@link Integer#MIN_VALUE} means the whole column is left out.
 */
public interface MapCut {

    int top(int x, int z);

    /** The highest {@link #top} anywhere: rays start there. */
    int max();

    static MapCut flat(int y) {
        return new MapCut() {
            @Override
            public int top(int x, int z) {
                return y;
            }

            @Override
            public int max() {
                return y;
            }
        };
    }
}
