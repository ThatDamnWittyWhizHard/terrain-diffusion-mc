package com.github.xandergos.terraindiffusionmc.client.riverdebug;

/** Client-only mutable debug settings. */
public final class RiverDebugOverlayState {
    public enum CostLayer {
        NONE("OFF"),
        FINAL_COST("Final cost"),
        SLOPE_COST("Slope cost"),
        RIDGE_COST("Ridge cost"),
        VALLEY_BONUS("Valley bonus"),
        BIOME_COST("Biome cost"),
        ROCK_SOIL_COST("Rock/soil cost"),
        FORBIDDEN_COST("Forbidden cost");

        private final String label;

        CostLayer(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum FlowLayer {
        NONE("OFF"),
        ARROWS("Arrows"),
        SCORE("Decision score"),
        DROP("Selected drop"),
        SINKS("Sinks/depressions");

        private final String label;

        FlowLayer(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum AccumulationLayer {
        NONE("OFF"),
        LINEAR("Linear heatmap"),
        LOG("Log heatmap"),
        SINKS("Sinks/depressions"),
        WINDOW_OUTLETS("Window outlets"),
        TERMINALS("Sinks + outlets"),
        CYCLES("Cycles");

        private final String label;

        AccumulationLayer(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }


    public enum RiverLayer {
        NONE("OFF"),
        RIVER_CELLS("River cells"),
        CLASSIFIED("Classified"),
        SOURCES("Sources"),
        CONFLUENCES("Confluences"),
        TERMINALS("Terminals");

        private final String label;

        RiverLayer(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static boolean lowResHeightmapEnabled = false;
    private static int cellSizeBlocks = 64;
    private static int radiusCells = 8;
    private static boolean wireframeEnabled = true;
    private static CostLayer costLayer = CostLayer.NONE;
    private static FlowLayer flowLayer = FlowLayer.NONE;
    private static AccumulationLayer accumulationLayer = AccumulationLayer.NONE;
    private static RiverLayer riverLayer = RiverLayer.NONE;
    private static int riverMinAccumulationCells = 24;

    private RiverDebugOverlayState() {
    }

    public static boolean isLowResHeightmapEnabled() {
        return lowResHeightmapEnabled;
    }

    public static boolean needsLowResHeightmap() {
        return lowResHeightmapEnabled || costLayer != CostLayer.NONE || flowLayer != FlowLayer.NONE || accumulationLayer != AccumulationLayer.NONE || riverLayer != RiverLayer.NONE;
    }

    public static boolean needsTerrainCostMap() {
        return costLayer != CostLayer.NONE || flowLayer != FlowLayer.NONE || accumulationLayer != AccumulationLayer.NONE || riverLayer != RiverLayer.NONE;
    }

    public static boolean needsFlowDirectionMap() {
        return flowLayer != FlowLayer.NONE || accumulationLayer != AccumulationLayer.NONE || riverLayer != RiverLayer.NONE;
    }

    public static boolean needsFlowAccumulationMap() {
        return accumulationLayer != AccumulationLayer.NONE || riverLayer != RiverLayer.NONE;
    }

    public static void setLowResHeightmapEnabled(boolean enabled) {
        lowResHeightmapEnabled = enabled;
    }

    public static void toggleLowResHeightmap() {
        lowResHeightmapEnabled = !lowResHeightmapEnabled;
        if (!needsLowResHeightmap()) {
            ClientTerrainCostMapCache.invalidate();
            ClientFlowDirectionMapCache.invalidate();
            ClientFlowAccumulationMapCache.invalidate();
            ClientRiverCellMapCache.invalidate();
        }
    }

    public static int cellSizeBlocks() {
        return cellSizeBlocks;
    }

    public static void cycleCellSize() {
        cellSizeBlocks = switch (cellSizeBlocks) {
            case 32 -> 64;
            case 64 -> 128;
            case 128 -> 256;
            default -> 32;
        };
        invalidateDerivedMaps();
    }

    public static int radiusCells() {
        return radiusCells;
    }

    public static void addRadius(int delta) {
        radiusCells = Math.max(2, Math.min(24, radiusCells + delta));
        invalidateDerivedMaps();
    }

    public static boolean isWireframeEnabled() {
        return wireframeEnabled;
    }

    public static void toggleWireframe() {
        wireframeEnabled = !wireframeEnabled;
    }

    public static CostLayer costLayer() {
        return costLayer;
    }

    public static void cycleCostLayer() {
        CostLayer[] values = CostLayer.values();
        costLayer = values[(costLayer.ordinal() + 1) % values.length];
        ClientTerrainCostMapCache.invalidate();
        ClientFlowDirectionMapCache.invalidate();
        ClientFlowAccumulationMapCache.invalidate();
        ClientRiverCellMapCache.invalidate();
    }

    public static FlowLayer flowLayer() {
        return flowLayer;
    }

    public static void cycleFlowLayer() {
        FlowLayer[] values = FlowLayer.values();
        flowLayer = values[(flowLayer.ordinal() + 1) % values.length];
        ClientFlowDirectionMapCache.invalidate();
        ClientFlowAccumulationMapCache.invalidate();
        ClientRiverCellMapCache.invalidate();
    }

    public static AccumulationLayer accumulationLayer() {
        return accumulationLayer;
    }

    public static void cycleAccumulationLayer() {
        AccumulationLayer[] values = AccumulationLayer.values();
        accumulationLayer = values[(accumulationLayer.ordinal() + 1) % values.length];
        ClientFlowAccumulationMapCache.invalidate();
        ClientRiverCellMapCache.invalidate();
    }

    public static RiverLayer riverLayer() {
        return riverLayer;
    }

    public static void cycleRiverLayer() {
        RiverLayer[] values = RiverLayer.values();
        riverLayer = values[(riverLayer.ordinal() + 1) % values.length];
        ClientRiverCellMapCache.invalidate();
    }

    public static int riverMinAccumulationCells() {
        return riverMinAccumulationCells;
    }

    public static void cycleRiverThreshold() {
        riverMinAccumulationCells = switch (riverMinAccumulationCells) {
            case 8 -> 16;
            case 16 -> 24;
            case 24 -> 32;
            case 32 -> 48;
            case 48 -> 64;
            case 64 -> 96;
            case 96 -> 128;
            default -> 8;
        };
        ClientRiverCellMapCache.invalidate();
    }

    private static void invalidateDerivedMaps() {
        ClientTerrainCostMapCache.invalidate();
        ClientFlowDirectionMapCache.invalidate();
        ClientFlowAccumulationMapCache.invalidate();
        ClientRiverCellMapCache.invalidate();
    }
}
