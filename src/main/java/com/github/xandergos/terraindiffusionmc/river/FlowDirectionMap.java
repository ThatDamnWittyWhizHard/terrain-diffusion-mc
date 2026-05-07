package com.github.xandergos.terraindiffusionmc.river;

/**
 * Compact D8 flow-direction layer derived from a low-res heightmap and terrain cost map.
 *
 * <p>The direction byte is always stored. Debug values are optional and hold exactly
 * one diagnostic layer at a time to avoid multiplying memory use.
 */
public final class FlowDirectionMap {
    public enum DebugLayer {
        NONE("None"),
        SCORE("Decision score"),
        DROP("Selected drop");

        private final String label;

        DebugLayer(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final byte SINK = 0;
    public static final byte NORTH = 1;
    public static final byte NORTH_EAST = 2;
    public static final byte EAST = 3;
    public static final byte SOUTH_EAST = 4;
    public static final byte SOUTH = 5;
    public static final byte SOUTH_WEST = 6;
    public static final byte WEST = 7;
    public static final byte NORTH_WEST = 8;
    public static final byte EDGE = 9;

    private static final int[] DX = {0, 0, 1, 1, 1, 0, -1, -1, -1, 0};
    private static final int[] DZ = {0, -1, -1, 0, 1, 1, 1, 0, -1, 0};

    private final TerrainCostMap costMap;
    private final byte[] directions;
    private final DebugLayer debugLayer;
    private final byte[] debugValues;
    private final int directedCount;
    private final int sinkCount;
    private final int edgeCount;

    public FlowDirectionMap(
            TerrainCostMap costMap,
            byte[] directions,
            DebugLayer debugLayer,
            byte[] debugValues,
            int directedCount,
            int sinkCount,
            int edgeCount
    ) {
        int expected = costMap.widthCells() * costMap.heightCells();
        requireLength("directions", directions, expected);
        if (debugValues != null) {
            if (debugLayer == null || debugLayer == DebugLayer.NONE) {
                throw new IllegalArgumentException("debugLayer must be SCORE or DROP when debugValues is present");
            }
            requireLength("debugValues", debugValues, expected);
        }

        this.costMap = costMap;
        this.directions = directions;
        this.debugLayer = debugLayer == null ? DebugLayer.NONE : debugLayer;
        this.debugValues = debugValues;
        this.directedCount = directedCount;
        this.sinkCount = sinkCount;
        this.edgeCount = edgeCount;
    }

    public TerrainCostMap costMap() {
        return costMap;
    }

    public LowResHeightmap heightmap() {
        return costMap.heightmap();
    }

    public int originCellX() {
        return costMap.originCellX();
    }

    public int originCellZ() {
        return costMap.originCellZ();
    }

    public int widthCells() {
        return costMap.widthCells();
    }

    public int heightCells() {
        return costMap.heightCells();
    }

    public int cellSizeBlocks() {
        return costMap.cellSizeBlocks();
    }

    public DebugLayer debugLayer() {
        return debugLayer;
    }

    public int directedCount() {
        return directedCount;
    }

    public int sinkCount() {
        return sinkCount;
    }

    public int edgeCount() {
        return edgeCount;
    }

    public byte directionAtLocal(int localCellX, int localCellZ) {
        return directions[index(localCellX, localCellZ)];
    }

    public byte directionAtWorldCell(int worldCellX, int worldCellZ) {
        return directionAtLocal(worldCellX - originCellX(), worldCellZ - originCellZ());
    }

    public float debugValueAtLocal(int localCellX, int localCellZ) {
        if (debugValues == null) {
            return 0.0f;
        }
        return unsigned(debugValues[index(localCellX, localCellZ)]);
    }

    public boolean isDirectedAtLocal(int localCellX, int localCellZ) {
        return isDirected(directionAtLocal(localCellX, localCellZ));
    }

    public static boolean isDirected(byte direction) {
        return direction >= NORTH && direction <= NORTH_WEST;
    }

    public static boolean isSink(byte direction) {
        return direction == SINK;
    }

    public static boolean isEdge(byte direction) {
        return direction == EDGE;
    }

    public static int dx(byte direction) {
        return direction >= 0 && direction < DX.length ? DX[direction] : 0;
    }

    public static int dz(byte direction) {
        return direction >= 0 && direction < DZ.length ? DZ[direction] : 0;
    }

    public static byte directionForOffset(int dx, int dz) {
        if (dx == 0 && dz == -1) return NORTH;
        if (dx == 1 && dz == -1) return NORTH_EAST;
        if (dx == 1 && dz == 0) return EAST;
        if (dx == 1 && dz == 1) return SOUTH_EAST;
        if (dx == 0 && dz == 1) return SOUTH;
        if (dx == -1 && dz == 1) return SOUTH_WEST;
        if (dx == -1 && dz == 0) return WEST;
        if (dx == -1 && dz == -1) return NORTH_WEST;
        return SINK;
    }

    public static String label(byte direction) {
        return switch (direction) {
            case SINK -> "sink";
            case NORTH -> "N";
            case NORTH_EAST -> "NE";
            case EAST -> "E";
            case SOUTH_EAST -> "SE";
            case SOUTH -> "S";
            case SOUTH_WEST -> "SW";
            case WEST -> "W";
            case NORTH_WEST -> "NW";
            case EDGE -> "edge";
            default -> "?";
        };
    }

    private int index(int localCellX, int localCellZ) {
        if (localCellX < 0 || localCellX >= widthCells() || localCellZ < 0 || localCellZ >= heightCells()) {
            throw new IndexOutOfBoundsException("cell outside flow direction map: " + localCellX + ", " + localCellZ);
        }
        return localCellZ * widthCells() + localCellX;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static void requireLength(String name, byte[] values, int expected) {
        if (values.length != expected) {
            throw new IllegalArgumentException(name + " length does not match dimensions");
        }
    }
}
