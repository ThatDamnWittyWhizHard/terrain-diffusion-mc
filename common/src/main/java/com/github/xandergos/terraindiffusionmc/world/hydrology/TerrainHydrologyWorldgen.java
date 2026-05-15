package com.github.xandergos.terraindiffusionmc.world.hydrology;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.debug.TerrainBaseTile;
import com.github.xandergos.terraindiffusionmc.debug.cost.TerrainCostBuilder;
import com.github.xandergos.terraindiffusionmc.debug.cost.TerrainCostTile;
import com.github.xandergos.terraindiffusionmc.debug.flow.TerrainFlowBuilder;
import com.github.xandergos.terraindiffusionmc.debug.flow.TerrainFlowTile;
import com.github.xandergos.terraindiffusionmc.debug.river.TerrainRiverBuilder;
import com.github.xandergos.terraindiffusionmc.debug.river.TerrainRiverTile;
import com.github.xandergos.terraindiffusionmc.debug.river.vector.TerrainRiverNetwork;
import com.github.xandergos.terraindiffusionmc.debug.river.vector.TerrainRiverVectorBuilder;
import com.github.xandergos.terraindiffusionmc.debug.river.vector.TerrainRiverVectorConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side bridge between the debug hydrology pipeline and real terrain generation.
 *
 * <p>The debug package remains the source of truth for flow routing/vectorisation. This class
 * converts the filtered vector network into a cheap per-block tile used by the chunk-generation
 * mixin for post-surface water placement. It deliberately does not carve terrain.</p>
 */
public final class TerrainHydrologyWorldgen {
    private static final Logger LOG = LoggerFactory.getLogger(TerrainHydrologyWorldgen.class);

    /** Rivers/lakes below this tributary count are intentionally not materialised in the world. */
    public static final int MIN_WORLDGEN_AFFLUENTS = 5;

    private static final int FLOW_HALO_BLOCKS = 96;
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final int MAX_LAKE_WATER_DEPTH_BLOCKS = 16;
    private static final int MAX_SURFACE_SCAN_BELOW_BLOCKS = 10;
    private static final int MAX_SURFACE_SCAN_ABOVE_BLOCKS = 5;
    private static final int MAX_RIVER_BANK_RISE_BLOCKS = 3;
    private static final float COST_FULL_WIDTH_AT = 0.24F;
    private static final float COST_STOP_WIDTH_AT = 0.82F;
    private static final float MIN_RIVER_WATER_MASK = 0.16F;

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final Map<Key, HydrologyTile> CACHE = new ConcurrentHashMap<>();
    private static final Set<Long> TERRAIN_DIFFUSION_CHUNKS = ConcurrentHashMap.newKeySet();

    private TerrainHydrologyWorldgen() {
    }

    public static void clearCache() {
        CACHE.clear();
        TERRAIN_DIFFUSION_CHUNKS.clear();
    }

    public static void markTerrainDiffusionColumn(int blockX, int blockZ) {
        TERRAIN_DIFFUSION_CHUNKS.add(chunkKey(blockX >> 4, blockZ >> 4));
    }

    public static Sample sample(int blockX, int blockZ) {
        if (!LocalTerrainProvider.isInitialized()) {
            return Sample.EMPTY;
        }

        int tileSize = TerrainDiffusionConfig.tileSize();
        int tileX = Math.floorDiv(blockX, tileSize);
        int tileZ = Math.floorDiv(blockZ, tileSize);
        int blockStartX = tileX * tileSize;
        int blockStartZ = tileZ * tileSize;

        Key key = new Key(
                LocalTerrainProvider.getSeed(),
                WorldScaleManager.getCurrentScale(),
                blockStartX,
                blockStartZ,
                tileSize,
                tileSize
        );

        HydrologyTile tile;
        try {
            tile = CACHE.computeIfAbsent(key, TerrainHydrologyWorldgen::buildTile);
        } catch (RuntimeException e) {
            LOG.warn("Terrain hydrology worldgen tile failed at {}, {}", blockStartX, blockStartZ, e);
            return Sample.EMPTY;
        }

        trimCacheIfNeeded();
        return tile.sample(blockX, blockZ);
    }

    /**
     * Called from the NoiseBasedChunkGenerator mixin after the surface pass.
     */
    public static void applyToChunk(ChunkAccess chunk) {
        if (!LocalTerrainProvider.isInitialized()) {
            return;
        }

        ChunkPos pos = chunk.getPos();
        if (!TERRAIN_DIFFUSION_CHUNKS.contains(chunkKey(pos.x, pos.z))) {
            return;
        }

        int minBlockX = pos.getMinBlockX();
        int minBlockZ = pos.getMinBlockZ();
        int minY = chunk.getMinY();
        int maxY = minY + chunk.getHeight();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int dz = 0; dz < 16; dz++) {
            int z = minBlockZ + dz;
            for (int dx = 0; dx < 16; dx++) {
                int x = minBlockX + dx;
                Sample sample = sample(x, z);
                if (!sample.active()) {
                    continue;
                }

                int waterTopY = clamp((int) Math.ceil(sample.waterLevelY()), minY, maxY - 1);
                int expectedSurfaceY = clamp(sample.surfaceY(), minY, maxY - 1);
                int scanTopY = clamp(Math.max(waterTopY, expectedSurfaceY + MAX_SURFACE_SCAN_ABOVE_BLOCKS), minY, maxY - 1);
                int scanBottomY = clamp(expectedSurfaceY - MAX_SURFACE_SCAN_BELOW_BLOCKS, minY, maxY - 1);
                int actualSurfaceY = findActualSurfaceY(chunk, mutable, x, z, scanTopY, scanBottomY);
                if (actualSurfaceY == Integer.MIN_VALUE) {
                    continue;
                }

                int fillBottomY = actualSurfaceY + 1;
                if (fillBottomY > waterTopY || fillBottomY >= maxY) {
                    continue;
                }

                int fillTopY = sample.lake()
                        ? Math.min(waterTopY, fillBottomY + MAX_LAKE_WATER_DEPTH_BLOCKS)
                        : fillBottomY;

                for (int y = fillBottomY; y <= fillTopY; y++) {
                    mutable.set(x, y, z);
                    BlockState current = chunk.getBlockState(mutable);
                    if (current.isAir() || current.liquid()) {
                        chunk.setBlockState(mutable, WATER, 0);
                    }
                }
            }
        }
    }

    private static HydrologyTile buildTile(Key key) {
        int halo = TerrainRiverVectorConfig.LOCAL_TILE_VECTOR_HALO_BLOCKS;
        int expandedStartX = key.blockStartX() - halo;
        int expandedStartZ = key.blockStartZ() - halo;
        int expandedWidth = key.width() + halo * 2;
        int expandedHeight = key.height() + halo * 2;

        TerrainBaseTile expanded = LocalTerrainProvider.getInstance().fetchTerrainBaseTile(
                expandedStartZ - FLOW_HALO_BLOCKS,
                expandedStartX - FLOW_HALO_BLOCKS,
                expandedStartZ + expandedHeight + FLOW_HALO_BLOCKS,
                expandedStartX + expandedWidth + FLOW_HALO_BLOCKS
        );

        TerrainCostTile expandedCost = TerrainCostBuilder.build(expanded);
        TerrainFlowTile flow = TerrainFlowBuilder.buildCropped(
                expanded,
                expandedCost,
                expandedStartX,
                expandedStartZ,
                expandedWidth,
                expandedHeight
        );
        TerrainRiverTile river = TerrainRiverBuilder.build(flow);
        TerrainRiverNetwork network = TerrainRiverVectorBuilder.buildCropped(
                river,
                key.blockStartX(),
                key.blockStartZ(),
                key.width(),
                key.height()
        );

        return HydrologyTile.fromNetwork(
                key.blockStartX(),
                key.blockStartZ(),
                key.width(),
                key.height(),
                network,
                expandedCost
        );
    }

    private static void trimCacheIfNeeded() {
        if (CACHE.size() <= MAX_CACHE_ENTRIES) {
            return;
        }

        Iterator<Key> iterator = CACHE.keySet().iterator();
        while (iterator.hasNext() && CACHE.size() > MAX_CACHE_ENTRIES / 2) {
            iterator.next();
            iterator.remove();
        }
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX & 4294967295L) | (((long) chunkZ & 4294967295L) << 32);
    }

    private static int index(int localX, int localZ, int width) {
        return localZ * width + localX;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        if (value > 1.0F) {
            return 1.0F;
        }
        return value;
    }

    private static float smoothRange(float edge0, float edge1, float value) {
        if (edge0 == edge1) {
            return value < edge0 ? 0.0F : 1.0F;
        }
        return smooth01((value - edge0) / (edge1 - edge0));
    }

    private static int findActualSurfaceY(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos mutable,
            int x,
            int z,
            int scanTopY,
            int scanBottomY
    ) {
        for (int y = scanTopY; y >= scanBottomY; y--) {
            mutable.set(x, y, z);
            BlockState current = chunk.getBlockState(mutable);
            if (current.isAir() || current.liquid()) {
                continue;
            }

            if (y + 1 <= scanTopY) {
                mutable.set(x, y + 1, z);
                BlockState above = chunk.getBlockState(mutable);
                if (!above.isAir() && !above.liquid()) {
                    continue;
                }
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static float smooth01(float value) {
        float t = Math.max(0.0F, Math.min(1.0F, value));
        return t * t * (3.0F - 2.0F * t);
    }

    public record Sample(
            boolean active,
            boolean lake,
            int surfaceY,
            float waterLevelY,
            int affluentCount
    ) {
        public static final Sample EMPTY = new Sample(false, false, Integer.MIN_VALUE, Float.NEGATIVE_INFINITY, 0);
    }

    private record Key(long seed, int scale, int blockStartX, int blockStartZ, int width, int height) {
    }

    private static final class HydrologyTile {
        private final int blockStartX;
        private final int blockStartZ;
        private final int width;
        private final int height;
        private final boolean[] active;
        private final boolean[] lake;
        private final int[] surfaceY;
        private final float[] waterLevelY;
        private final int[] affluentCount;

        private HydrologyTile(int blockStartX, int blockStartZ, int width, int height) {
            int len = width * height;
            this.blockStartX = blockStartX;
            this.blockStartZ = blockStartZ;
            this.width = width;
            this.height = height;
            this.active = new boolean[len];
            this.lake = new boolean[len];
            this.surfaceY = new int[len];
            this.waterLevelY = new float[len];
            this.affluentCount = new int[len];
            Arrays.fill(this.surfaceY, Integer.MIN_VALUE);
            Arrays.fill(this.waterLevelY, Float.NEGATIVE_INFINITY);
        }

        private static HydrologyTile fromNetwork(
                int blockStartX,
                int blockStartZ,
                int width,
                int height,
                TerrainRiverNetwork network,
                TerrainCostTile cost
        ) {
            HydrologyTile tile = new HydrologyTile(blockStartX, blockStartZ, width, height);
            tile.rasterizeVisibleRivers(network, cost);
            tile.rasterizeVisibleLakes(network, cost);
            return tile;
        }

        private Sample sample(int blockX, int blockZ) {
            int localX = blockX - blockStartX;
            int localZ = blockZ - blockStartZ;
            if (localX < 0 || localZ < 0 || localX >= width || localZ >= height) {
                return Sample.EMPTY;
            }

            int idx = index(localX, localZ, width);
            if (!active[idx]) {
                return Sample.EMPTY;
            }

            return new Sample(true, lake[idx], surfaceY[idx], waterLevelY[idx], affluentCount[idx]);
        }

        private void rasterizeVisibleRivers(TerrainRiverNetwork network, TerrainCostTile cost) {
            for (TerrainRiverNetwork.Segment segment : network.segments()) {
                if (segment.upstreamAffluentCount() < MIN_WORLDGEN_AFFLUENTS || segment.points().size() < 2) {
                    continue;
                }

                for (int i = 0; i < segment.points().size() - 1; i++) {
                    rasterizeRiverEdge(segment.points().get(i), segment.points().get(i + 1), segment.upstreamAffluentCount(), cost);
                }
            }
        }

        private void rasterizeRiverEdge(
                TerrainRiverNetwork.Point a,
                TerrainRiverNetwork.Point b,
                int affluents,
                TerrainCostTile cost
        ) {
            float radius = Math.max(2.0F, Math.max(a.widthBlocks(), b.widthBlocks()) * 0.80F);
            int minX = clamp((int) Math.floor(Math.min(a.worldX(), b.worldX()) - radius - 1.0F) - blockStartX, 0, width - 1);
            int maxX = clamp((int) Math.ceil(Math.max(a.worldX(), b.worldX()) + radius + 1.0F) - blockStartX, 0, width - 1);
            int minZ = clamp((int) Math.floor(Math.min(a.worldZ(), b.worldZ()) - radius - 1.0F) - blockStartZ, 0, height - 1);
            int maxZ = clamp((int) Math.ceil(Math.max(a.worldZ(), b.worldZ()) + radius + 1.0F) - blockStartZ, 0, height - 1);

            double vx = b.worldX() - a.worldX();
            double vz = b.worldZ() - a.worldZ();
            double len2 = vx * vx + vz * vz;
            if (len2 <= 1.0E-8D) {
                return;
            }

            for (int z = minZ; z <= maxZ; z++) {
                double worldZ = blockStartZ + z + 0.5D;
                for (int x = minX; x <= maxX; x++) {
                    double worldX = blockStartX + x + 0.5D;
                    double t = ((worldX - a.worldX()) * vx + (worldZ - a.worldZ()) * vz) / len2;
                    t = Math.max(0.0D, Math.min(1.0D, t));

                    double centerX = a.worldX() + vx * t;
                    double centerZ = a.worldZ() + vz * t;
                    double dx = worldX - centerX;
                    double dz = worldZ - centerZ;
                    double distance = Math.sqrt(dx * dx + dz * dz);

                    int blockX = blockStartX + x;
                    int blockZ = blockStartZ + z;
                    int costX = clamp(blockX - cost.blockStartX(), 0, cost.width() - 1);
                    int costZ = clamp(blockZ - cost.blockStartZ(), 0, cost.height() - 1);

                    float finalCost = cost.totalAt(costX, costZ);
                    float valleySignal = clamp01(cost.valleyAt(costX, costZ));
                    float ridgeSignal = clamp01(cost.ridgeAt(costX, costZ));
                    float costSuitability = 1.0F - smoothRange(COST_FULL_WIDTH_AT, COST_STOP_WIDTH_AT, finalCost);
                    costSuitability = clamp01(costSuitability + valleySignal * 0.35F - ridgeSignal * 0.25F);
                    if (costSuitability <= MIN_RIVER_WATER_MASK) {
                        continue;
                    }

                    float interpolatedWidth = lerp((float) t, a.widthBlocks(), b.widthBlocks());
                    float baseHalfWidth = Math.max(1.75F, interpolatedWidth * 0.5F);
                    float costHalfWidth = baseHalfWidth * lerp(costSuitability, 0.30F, 1.35F);
                    if (distance > costHalfWidth) {
                        continue;
                    }

                    float crossMask = smooth01(1.0F - (float) (distance / costHalfWidth));
                    if (crossMask * costSuitability < MIN_RIVER_WATER_MASK) {
                        continue;
                    }

                    int terrainY = cost.surfaceYAtLocal(costX, costZ);
                    int localLowY = localMinSurfaceY(cost, costX, costZ, 2);
                    float interpolatedSurfaceY = lerp((float) t, a.surfaceY(), b.surfaceY());
                    if (terrainY > interpolatedSurfaceY + MAX_RIVER_BANK_RISE_BLOCKS
                            || terrainY > localLowY + MAX_RIVER_BANK_RISE_BLOCKS) {
                        continue;
                    }

                    writeRiverCell(x, z, terrainY, terrainY + 1.0F, affluents);
                }
            }
        }

        private void rasterizeVisibleLakes(TerrainRiverNetwork network, TerrainCostTile cost) {
            for (TerrainRiverNetwork.Lake lake : network.lakes()) {
                int lakeAffluents = lakeAffluentCount(network, lake);
                if (lakeAffluents < MIN_WORLDGEN_AFFLUENTS) {
                    continue;
                }

                for (TerrainRiverNetwork.LakeRun run : lake.runs()) {
                    if (run.worldZ() < blockStartZ || run.worldZ() >= blockStartZ + height) {
                        continue;
                    }

                    int localZ = run.worldZ() - blockStartZ;
                    int startX = clamp(run.startWorldX() - blockStartX, 0, width);
                    int endX = clamp(run.endWorldX() - blockStartX, 0, width);
                    for (int localX = startX; localX < endX; localX++) {
                        int blockX = blockStartX + localX;
                        int costX = clamp(blockX - cost.blockStartX(), 0, cost.width() - 1);
                        int costZ = clamp(run.worldZ() - cost.blockStartZ(), 0, cost.height() - 1);
                        int terrainY = cost.surfaceYAtLocal(costX, costZ);
                        if (terrainY >= run.waterLevelY()) {
                            continue;
                        }
                        writeLakeCell(localX, localZ, terrainY, run.waterLevelY(), lakeAffluents);
                    }
                }
            }
        }

        private static int lakeAffluentCount(TerrainRiverNetwork network, TerrainRiverNetwork.Lake lake) {
            int affluents = lake.inflowCount();
            for (TerrainRiverNetwork.Segment segment : network.segments()) {
                if (segment.upstreamAffluentCount() < MIN_WORLDGEN_AFFLUENTS || segment.points().isEmpty()) {
                    continue;
                }

                TerrainRiverNetwork.Point end = segment.points().get(segment.points().size() - 1);
                if (pointTouchesLake(end, lake)) {
                    affluents = Math.max(affluents, segment.upstreamAffluentCount());
                }
            }
            return affluents;
        }

        private static boolean pointTouchesLake(TerrainRiverNetwork.Point point, TerrainRiverNetwork.Lake lake) {
            int x = (int) Math.floor(point.worldX());
            int z = (int) Math.floor(point.worldZ());
            for (TerrainRiverNetwork.LakeRun run : lake.runs()) {
                if (Math.abs(run.worldZ() - z) > 1) {
                    continue;
                }
                if (x >= run.startWorldX() - 1 && x <= run.endWorldX()) {
                    return true;
                }
            }
            return false;
        }

        private void writeRiverCell(int localX, int localZ, int terrainY, float water, int affluents) {
            int idx = index(localX, localZ, width);
            if (!active[idx] || lake[idx] || affluents > affluentCount[idx]) {
                active[idx] = true;
                lake[idx] = false;
                surfaceY[idx] = terrainY;
                waterLevelY[idx] = water;
                affluentCount[idx] = affluents;
            }
        }

        private void writeLakeCell(int localX, int localZ, int terrainY, float water, int affluents) {
            int idx = index(localX, localZ, width);
            active[idx] = true;
            lake[idx] = true;
            surfaceY[idx] = Math.max(surfaceY[idx], terrainY);
            waterLevelY[idx] = Math.max(waterLevelY[idx], water);
            affluentCount[idx] = Math.max(affluentCount[idx], affluents);
        }

        private static int localMinSurfaceY(TerrainCostTile cost, int localX, int localZ, int radius) {
            int min = Integer.MAX_VALUE;
            int minX = clamp(localX - radius, 0, cost.width() - 1);
            int maxX = clamp(localX + radius, 0, cost.width() - 1);
            int minZ = clamp(localZ - radius, 0, cost.height() - 1);
            int maxZ = clamp(localZ + radius, 0, cost.height() - 1);
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    min = Math.min(min, cost.surfaceYAtLocal(x, z));
                }
            }
            return min;
        }

        private static float lerp(float t, float a, float b) {
            return a + (b - a) * t;
        }

    }
}
