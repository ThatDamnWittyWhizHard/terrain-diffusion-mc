package com.github.xandergos.terraindiffusionmc.river;

/**
 * Raster river extraction layer derived from flow accumulation.
 *
 * <p>A cell is marked as river when its upstream contributing area reaches the
 * configured accumulation threshold. This is still a raster product, not the
 * final vector river network.</p>
 */
public final class RiverCellMap {
    private static final byte FLAG_RIVER = 1;
    private static final byte FLAG_SOURCE = 2;
    private static final byte FLAG_CONFLUENCE = 4;
    private static final byte FLAG_TERMINAL = 8;

    private final FlowAccumulationMap accumulationMap;
    private final RiverExtractionParameters parameters;
    private final byte[] flags;
    private final byte[] upstreamRiverCounts;
    private final int riverCellCount;
    private final int sourceCount;
    private final int confluenceCount;
    private final int terminalCount;
    private final int maxRiverAccumulation;

    public RiverCellMap(
            FlowAccumulationMap accumulationMap,
            RiverExtractionParameters parameters,
            byte[] flags,
            byte[] upstreamRiverCounts,
            int riverCellCount,
            int sourceCount,
            int confluenceCount,
            int terminalCount,
            int maxRiverAccumulation
    ) {
        int expected = accumulationMap.widthCells() * accumulationMap.heightCells();
        requireLength("flags", flags, expected);
        requireLength("upstreamRiverCounts", upstreamRiverCounts, expected);

        this.accumulationMap = accumulationMap;
        this.parameters = parameters == null ? RiverExtractionParameters.defaults() : parameters;
        this.flags = flags;
        this.upstreamRiverCounts = upstreamRiverCounts;
        this.riverCellCount = riverCellCount;
        this.sourceCount = sourceCount;
        this.confluenceCount = confluenceCount;
        this.terminalCount = terminalCount;
        this.maxRiverAccumulation = Math.max(1, maxRiverAccumulation);
    }

    public FlowAccumulationMap accumulationMap() {
        return accumulationMap;
    }

    public FlowDirectionMap flowMap() {
        return accumulationMap.flowMap();
    }

    public LowResHeightmap heightmap() {
        return accumulationMap.heightmap();
    }

    public RiverExtractionParameters parameters() {
        return parameters;
    }

    public int originCellX() {
        return accumulationMap.originCellX();
    }

    public int originCellZ() {
        return accumulationMap.originCellZ();
    }

    public int widthCells() {
        return accumulationMap.widthCells();
    }

    public int heightCells() {
        return accumulationMap.heightCells();
    }

    public int cellSizeBlocks() {
        return accumulationMap.cellSizeBlocks();
    }

    public int riverCellCount() {
        return riverCellCount;
    }

    public int sourceCount() {
        return sourceCount;
    }

    public int confluenceCount() {
        return confluenceCount;
    }

    public int terminalCount() {
        return terminalCount;
    }

    public int maxRiverAccumulation() {
        return maxRiverAccumulation;
    }

    public int accumulationAtLocal(int localCellX, int localCellZ) {
        return accumulationMap.accumulationAtLocal(localCellX, localCellZ);
    }

    public boolean isRiverAtLocal(int localCellX, int localCellZ) {
        return hasFlag(localCellX, localCellZ, FLAG_RIVER);
    }

    public boolean isSourceAtLocal(int localCellX, int localCellZ) {
        return hasFlag(localCellX, localCellZ, FLAG_SOURCE);
    }

    public boolean isConfluenceAtLocal(int localCellX, int localCellZ) {
        return hasFlag(localCellX, localCellZ, FLAG_CONFLUENCE);
    }

    public boolean isTerminalAtLocal(int localCellX, int localCellZ) {
        return hasFlag(localCellX, localCellZ, FLAG_TERMINAL);
    }

    public int upstreamRiverCountAtLocal(int localCellX, int localCellZ) {
        return upstreamRiverCounts[index(localCellX, localCellZ)] & 0xFF;
    }

    public float riverIntensityAtLocal(int localCellX, int localCellZ) {
        int accumulation = accumulationAtLocal(localCellX, localCellZ);
        int threshold = parameters.minAccumulationCells();
        int range = Math.max(1, maxRiverAccumulation - threshold);
        return clamp01((accumulation - threshold) / (float) range);
    }

    static byte riverFlag() {
        return FLAG_RIVER;
    }

    static byte sourceFlag() {
        return FLAG_SOURCE;
    }

    static byte confluenceFlag() {
        return FLAG_CONFLUENCE;
    }

    static byte terminalFlag() {
        return FLAG_TERMINAL;
    }

    private boolean hasFlag(int localCellX, int localCellZ, byte flag) {
        return (flags[index(localCellX, localCellZ)] & flag) != 0;
    }

    private int index(int localCellX, int localCellZ) {
        if (localCellX < 0 || localCellX >= widthCells() || localCellZ < 0 || localCellZ >= heightCells()) {
            throw new IndexOutOfBoundsException("cell outside river cell map: " + localCellX + ", " + localCellZ);
        }
        return localCellZ * widthCells() + localCellX;
    }

    private static float clamp01(float value) {
        if (value < 0.0f) return 0.0f;
        if (value > 1.0f) return 1.0f;
        return value;
    }

    private static void requireLength(String name, byte[] values, int expected) {
        if (values.length != expected) {
            throw new IllegalArgumentException(name + " length does not match dimensions");
        }
    }
}
