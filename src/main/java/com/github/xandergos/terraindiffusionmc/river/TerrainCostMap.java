package com.github.xandergos.terraindiffusionmc.river;

/**
 * Compact terrain preference cost map for river planning.
 *
 * <p>Costs are quantized to unsigned bytes in the 0..100 range. That is enough
 * precision for routing and keeps large debug/planning windows cheap in RAM.
 * The production path stores only FINAL_COST. Debug can attach exactly one
 * additional layer at a time.
 */
public final class TerrainCostMap {
    public enum Layer {
        FINAL_COST("Final cost"),
        SLOPE_COST("Slope cost"),
        RIDGE_COST("Ridge cost"),
        VALLEY_BONUS("Valley bonus"),
        BIOME_COST("Biome cost"),
        ROCK_SOIL_COST("Rock/soil cost"),
        FORBIDDEN_COST("Forbidden cost");

        private final String label;

        Layer(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final LowResHeightmap heightmap;
    private final byte[] finalCost;
    private final Layer debugLayer;
    private final byte[] debugValues;
    private final byte minFinalCost;
    private final byte maxFinalCost;

    public TerrainCostMap(
            LowResHeightmap heightmap,
            byte[] finalCost,
            Layer debugLayer,
            byte[] debugValues
    ) {
        int expected = heightmap.widthCells() * heightmap.heightCells();
        requireLength("finalCost", finalCost, expected);
        if (debugValues != null) {
            if (debugLayer == null || debugLayer == Layer.FINAL_COST) {
                throw new IllegalArgumentException("debugLayer must name a non-final layer when debugValues is present");
            }
            requireLength("debugValues", debugValues, expected);
        }

        this.heightmap = heightmap;
        this.finalCost = finalCost;
        this.debugLayer = debugLayer;
        this.debugValues = debugValues;

        int min = 255;
        int max = 0;
        for (byte value : finalCost) {
            int unsigned = unsigned(value);
            if (unsigned < min) min = unsigned;
            if (unsigned > max) max = unsigned;
        }
        this.minFinalCost = (byte) min;
        this.maxFinalCost = (byte) max;
    }

    public LowResHeightmap heightmap() {
        return heightmap;
    }

    public int originCellX() {
        return heightmap.originCellX();
    }

    public int originCellZ() {
        return heightmap.originCellZ();
    }

    public int widthCells() {
        return heightmap.widthCells();
    }

    public int heightCells() {
        return heightmap.heightCells();
    }

    public int cellSizeBlocks() {
        return heightmap.cellSizeBlocks();
    }

    public float minFinalCost() {
        return unsigned(minFinalCost);
    }

    public float maxFinalCost() {
        return unsigned(maxFinalCost);
    }

    public boolean hasLayer(Layer layer) {
        return layer == Layer.FINAL_COST || (layer == debugLayer && debugValues != null);
    }

    public Layer debugLayer() {
        return debugLayer;
    }

    public float valueAtLocal(Layer layer, int localCellX, int localCellZ) {
        int index = index(localCellX, localCellZ);
        return switch (layer) {
            case FINAL_COST -> unsigned(finalCost[index]);
            case BIOME_COST, ROCK_SOIL_COST, FORBIDDEN_COST -> 0.0f;
            default -> layer == debugLayer && debugValues != null ? unsigned(debugValues[index]) : 0.0f;
        };
    }

    public float finalCostAtLocal(int localCellX, int localCellZ) {
        return unsigned(finalCost[index(localCellX, localCellZ)]);
    }

    public float finalCostAtWorldCell(int worldCellX, int worldCellZ) {
        return finalCostAtLocal(worldCellX - originCellX(), worldCellZ - originCellZ());
    }

    public byte finalCostByteAtLocal(int localCellX, int localCellZ) {
        return finalCost[index(localCellX, localCellZ)];
    }

    private int index(int localCellX, int localCellZ) {
        if (localCellX < 0 || localCellX >= widthCells() || localCellZ < 0 || localCellZ >= heightCells()) {
            throw new IndexOutOfBoundsException("cell outside cost map: " + localCellX + ", " + localCellZ);
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
