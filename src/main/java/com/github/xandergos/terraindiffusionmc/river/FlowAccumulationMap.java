package com.github.xandergos.terraindiffusionmc.river;

/**
 * Flow accumulation layer derived from a D8 flow-direction map.
 *
 * <p>Each low-res cell contributes one unit of runoff. The accumulation value is
 * therefore the number of upstream cells including the cell itself that drain
 * through the current cell inside the sampled window.</p>
 */
public final class FlowAccumulationMap {
    public enum DebugLayer {
        NONE("None"),
        LINEAR("Linear heatmap"),
        LOG("Log heatmap"),
        OUTLETS("Outlets/sinks"),
        CYCLES("Cycles");

        private final String label;

        DebugLayer(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static final byte FLAG_OUTLET = 1;
    private static final byte FLAG_CYCLE = 2;

    private final FlowDirectionMap flowMap;
    private final int[] accumulation;
    private final byte[] flags;
    private final DebugLayer debugLayer;
    private final byte[] debugValues;
    private final int maxAccumulation;
    private final int outletCount;
    private final int cycleCount;
    private final int processedCount;

    public FlowAccumulationMap(
            FlowDirectionMap flowMap,
            int[] accumulation,
            byte[] flags,
            DebugLayer debugLayer,
            byte[] debugValues,
            int maxAccumulation,
            int outletCount,
            int cycleCount,
            int processedCount
    ) {
        int expected = flowMap.widthCells() * flowMap.heightCells();
        requireLength("accumulation", accumulation, expected);
        requireLength("flags", flags, expected);
        if (debugValues != null) {
            if (debugLayer == null || debugLayer == DebugLayer.NONE) {
                throw new IllegalArgumentException("debugLayer must not be NONE when debugValues is present");
            }
            requireLength("debugValues", debugValues, expected);
        }

        this.flowMap = flowMap;
        this.accumulation = accumulation;
        this.flags = flags;
        this.debugLayer = debugLayer == null ? DebugLayer.NONE : debugLayer;
        this.debugValues = debugValues;
        this.maxAccumulation = Math.max(1, maxAccumulation);
        this.outletCount = outletCount;
        this.cycleCount = cycleCount;
        this.processedCount = processedCount;
    }

    public FlowDirectionMap flowMap() {
        return flowMap;
    }

    public LowResHeightmap heightmap() {
        return flowMap.heightmap();
    }

    public int originCellX() {
        return flowMap.originCellX();
    }

    public int originCellZ() {
        return flowMap.originCellZ();
    }

    public int widthCells() {
        return flowMap.widthCells();
    }

    public int heightCells() {
        return flowMap.heightCells();
    }

    public int cellSizeBlocks() {
        return flowMap.cellSizeBlocks();
    }

    public DebugLayer debugLayer() {
        return debugLayer;
    }

    public int maxAccumulation() {
        return maxAccumulation;
    }

    public int outletCount() {
        return outletCount;
    }

    public int cycleCount() {
        return cycleCount;
    }

    public int processedCount() {
        return processedCount;
    }

    public int accumulationAtLocal(int localCellX, int localCellZ) {
        return accumulation[index(localCellX, localCellZ)];
    }

    public int accumulationAtWorldCell(int worldCellX, int worldCellZ) {
        return accumulationAtLocal(worldCellX - originCellX(), worldCellZ - originCellZ());
    }

    public float debugValueAtLocal(int localCellX, int localCellZ) {
        if (debugValues == null) {
            return 0.0f;
        }
        return unsigned(debugValues[index(localCellX, localCellZ)]);
    }

    public boolean isOutletAtLocal(int localCellX, int localCellZ) {
        return (flags[index(localCellX, localCellZ)] & FLAG_OUTLET) != 0;
    }

    public boolean isCycleAtLocal(int localCellX, int localCellZ) {
        return (flags[index(localCellX, localCellZ)] & FLAG_CYCLE) != 0;
    }

    static byte outletFlag() {
        return FLAG_OUTLET;
    }

    static byte cycleFlag() {
        return FLAG_CYCLE;
    }

    private int index(int localCellX, int localCellZ) {
        if (localCellX < 0 || localCellX >= widthCells() || localCellZ < 0 || localCellZ >= heightCells()) {
            throw new IndexOutOfBoundsException("cell outside flow accumulation map: " + localCellX + ", " + localCellZ);
        }
        return localCellZ * widthCells() + localCellX;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static void requireLength(String name, int[] values, int expected) {
        if (values.length != expected) {
            throw new IllegalArgumentException(name + " length does not match dimensions");
        }
    }

    private static void requireLength(String name, byte[] values, int expected) {
        if (values.length != expected) {
            throw new IllegalArgumentException(name + " length does not match dimensions");
        }
    }
}
