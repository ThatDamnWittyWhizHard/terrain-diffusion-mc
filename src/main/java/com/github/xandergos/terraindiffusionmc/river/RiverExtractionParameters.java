package com.github.xandergos.terraindiffusionmc.river;

/** Tunables for extracting raster river cells from flow accumulation. */
public record RiverExtractionParameters(int minAccumulationCells) {
    private static final int DEFAULT_MIN_ACCUMULATION_CELLS = 24;

    public RiverExtractionParameters {
        if (minAccumulationCells < 2) {
            throw new IllegalArgumentException("minAccumulationCells must be >= 2");
        }
    }

    public static RiverExtractionParameters defaults() {
        return new RiverExtractionParameters(DEFAULT_MIN_ACCUMULATION_CELLS);
    }
}
