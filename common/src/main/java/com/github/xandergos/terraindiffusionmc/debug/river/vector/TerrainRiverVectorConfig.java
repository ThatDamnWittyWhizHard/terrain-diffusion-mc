package com.github.xandergos.terraindiffusionmc.debug.river.vector;

public final class TerrainRiverVectorConfig {
    public static final float SIMPLIFICATION_TOLERANCE_BLOCKS = 0.45F;
    public static final float MAX_VECTOR_SEGMENT_LENGTH_BLOCKS = 5.0F;
    public static final int SMOOTHING_PASSES = 2;
    public static final float MAX_SMOOTHING_OFFSET_FRACTION_OF_WIDTH = 0.30F;
    public static final int MAX_SMOOTHING_TERRAIN_RISE_BLOCKS = 2;

    public static final int SPATIAL_INDEX_CELL_SIZE_BLOCKS = 32;
    public static final int CHUNK_QUERY_PADDING_BLOCKS = 24;
    public static final int LOCAL_TILE_VECTOR_HALO_BLOCKS = 192;

    public static final float MIN_DEPTH_BLOCKS = 0.35F;
    public static final float MAX_DEPTH_BLOCKS = 3.50F;

    private TerrainRiverVectorConfig() {
    }
}
