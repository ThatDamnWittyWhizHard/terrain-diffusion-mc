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

import java.util.ArrayDeque;
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
 * mixin for post-surface water placement and shallow riverbed carving.</p>
 */
public final class TerrainHydrologyWorldgen {
    private static final Logger LOG = LoggerFactory.getLogger(TerrainHydrologyWorldgen.class);

    /** Rivers/lakes below this tributary count are intentionally not materialised in the world. */
    public static final int MIN_WORLDGEN_AFFLUENTS = 5;

    private static final int FLOW_HALO_BLOCKS = 96;
    private static final int HYDROLOGY_TILE_MULTIPLIER = 2;
    private static final int WORLDGEN_HALO_BLOCKS = TerrainRiverVectorConfig.LOCAL_TILE_VECTOR_HALO_BLOCKS;
    private static final int MAX_CACHE_ENTRIES = 4;
    private static final int MAX_RIVER_CARVE_DEPTH_BLOCKS = 2;
    private static final int MAX_LAKE_SCAN_DEPTH_BLOCKS = 96;
    private static final int MAX_SURFACE_SCAN_BELOW_BLOCKS = 10;
    private static final int MAX_SURFACE_SCAN_ABOVE_BLOCKS = 5;
    private static final int MAX_RIVER_BANK_RISE_BLOCKS = 2;
    private static final int LAKE_BASIN_MARGIN_BLOCKS = 192;
    private static final int LAKE_BASIN_DRAIN_TRACE_STEPS = 96;
    private static final int LAKE_SEAL_PASSES = 5;
    private static final int MIN_LAKE_NEIGHBORS_TO_SEAL = 5;
    private static final int LAKE_OVERFLOW_MAX_STEPS = 160;
    private static final int LAKE_OVERFLOW_RADIUS_BLOCKS = 2;
    private static final int MAX_LAKE_OVERFLOW_STEP_RISE_BLOCKS = 1;
    private static final int MAX_LAKE_OVERFLOW_STEP_DROP_BLOCKS = 5;
    private static final int MIN_RIVER_COMPONENT_CELLS = 4;
    private static final int MAX_RIVER_NEIGHBOR_STEP_BLOCKS = 2;
    private static final float COST_FULL_WIDTH_AT = 0.24F;
    private static final float COST_STOP_WIDTH_AT = 0.82F;
    private static final float MIN_RIVER_WATER_MASK = 0.16F;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final int[] DIR_X_8 = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DIR_Z_8 = {0, 1, 1, 1, 0, -1, -1, -1};
    /** D8 order used by TerrainFlowTile : N, NE, E, SE, S, SW, W, NW. */
    private static final int[] FLOW_DIR_X = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] FLOW_DIR_Z = {-1, -1, 0, 1, 1, 1, 0, -1};
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

        int tileSize = TerrainDiffusionConfig.tileSize() * HYDROLOGY_TILE_MULTIPLIER;
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

                if (sample.lake()) {
                    applyLakeWater(chunk, mutable, sample, x, z, minY, maxY);
                } else {
                    applyRiverBedAndWater(chunk, mutable, sample, x, z, minY, maxY);
                }
            }
        }
    }

    private static void applyLakeWater(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos mutable,
            Sample sample,
            int x,
            int z,
            int minY,
            int maxY
    ) {
        int waterTopY = clamp((int) Math.floor(sample.waterLevelY()), minY, maxY - 1);
        int expectedSurfaceY = clamp(sample.surfaceY(), minY, maxY - 1);
        int scanTopY = clamp(Math.max(waterTopY + 2, expectedSurfaceY + MAX_SURFACE_SCAN_ABOVE_BLOCKS), minY, maxY - 1);
        int scanBottomY = clamp(Math.min(expectedSurfaceY - MAX_SURFACE_SCAN_BELOW_BLOCKS, waterTopY - MAX_LAKE_SCAN_DEPTH_BLOCKS), minY, maxY - 1);
        int actualSurfaceY = findActualSurfaceY(chunk, mutable, x, z, scanTopY, scanBottomY);
        if (actualSurfaceY == Integer.MIN_VALUE || actualSurfaceY >= waterTopY) {
            return;
        }

        for (int y = actualSurfaceY + 1; y <= waterTopY; y++) {
            mutable.set(x, y, z);
            BlockState current = chunk.getBlockState(mutable);
            if (isAirOrFluid(current)) {
                chunk.setBlockState(mutable, WATER, 0);
            }
        }
    }

    private static void applyRiverBedAndWater(
            ChunkAccess chunk,
            BlockPos.MutableBlockPos mutable,
            Sample sample,
            int x,
            int z,
            int minY,
            int maxY
    ) {
        int expectedSurfaceY = clamp(sample.surfaceY(), minY, maxY - 1);
        int scanTopY = clamp(expectedSurfaceY + MAX_SURFACE_SCAN_ABOVE_BLOCKS, minY, maxY - 1);
        int scanBottomY = clamp(expectedSurfaceY - MAX_SURFACE_SCAN_BELOW_BLOCKS, minY, maxY - 1);
        int actualSurfaceY = findActualSurfaceY(chunk, mutable, x, z, scanTopY, scanBottomY);
        if (actualSurfaceY == Integer.MIN_VALUE) {
            return;
        }

        int maxCarvedBedY = actualSurfaceY - MAX_RIVER_CARVE_DEPTH_BLOCKS;
        int bedY = clamp(Math.max(sample.bedY(), maxCarvedBedY), minY, maxY - 1);
        int waterTopY = clamp(Math.min((int) Math.floor(sample.waterLevelY()), actualSurfaceY - 1), minY, maxY - 1);
        if (bedY >= actualSurfaceY || waterTopY <= bedY) {
            return;
        }

        for (int y = bedY + 1; y <= actualSurfaceY; y++) {
            mutable.set(x, y, z);
            if (y <= waterTopY) {
                chunk.setBlockState(mutable, WATER, 0);
            } else {
                BlockState current = chunk.getBlockState(mutable);
                if (!current.isAir()) {
                    chunk.setBlockState(mutable, AIR, 0);
                }
            }
        }
    }

    private static HydrologyTile buildTile(Key key) {
        int halo = Math.max(WORLDGEN_HALO_BLOCKS, TerrainDiffusionConfig.tileSize());
        int hydroStartX = key.blockStartX() - halo;
        int hydroStartZ = key.blockStartZ() - halo;
        int hydroWidth = key.width() + halo * 2;
        int hydroHeight = key.height() + halo * 2;

        TerrainBaseTile expanded = LocalTerrainProvider.getInstance().fetchTerrainBaseTile(
                hydroStartZ - FLOW_HALO_BLOCKS,
                hydroStartX - FLOW_HALO_BLOCKS,
                hydroStartZ + hydroHeight + FLOW_HALO_BLOCKS,
                hydroStartX + hydroWidth + FLOW_HALO_BLOCKS
        );

        TerrainCostTile expandedCost = TerrainCostBuilder.build(expanded);
        TerrainFlowTile flow = TerrainFlowBuilder.buildCropped(
                expanded,
                expandedCost,
                hydroStartX,
                hydroStartZ,
                hydroWidth,
                hydroHeight
        );
        TerrainRiverTile river = TerrainRiverBuilder.build(flow);
        TerrainRiverNetwork network = TerrainRiverVectorBuilder.buildCropped(
                river,
                hydroStartX,
                hydroStartZ,
                hydroWidth,
                hydroHeight
        );

        return HydrologyTile.fromNetwork(
                hydroStartX,
                hydroStartZ,
                hydroWidth,
                hydroHeight,
                network,
                river,
                expandedCost,
                flow
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

    private static boolean isAirOrFluid(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty();
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
            if (isAirOrFluid(current)) {
                continue;
            }

            if (y + 1 <= scanTopY) {
                mutable.set(x, y + 1, z);
                BlockState above = chunk.getBlockState(mutable);
                if (!isAirOrFluid(above)) {
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
            int bedY,
            float waterLevelY,
            int affluentCount
    ) {
        public static final Sample EMPTY = new Sample(false, false, Integer.MIN_VALUE, Integer.MIN_VALUE, Float.NEGATIVE_INFINITY, 0);
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
        private final int[] bedY;
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
            this.bedY = new int[len];
            this.waterLevelY = new float[len];
            this.affluentCount = new int[len];
            Arrays.fill(this.surfaceY, Integer.MIN_VALUE);
            Arrays.fill(this.bedY, Integer.MIN_VALUE);
            Arrays.fill(this.waterLevelY, Float.NEGATIVE_INFINITY);
        }

        private static HydrologyTile fromNetwork(
                int blockStartX,
                int blockStartZ,
                int width,
                int height,
                TerrainRiverNetwork network,
                TerrainRiverTile river,
                TerrainCostTile cost,
                TerrainFlowTile flow
        ) {
            HydrologyTile tile = new HydrologyTile(blockStartX, blockStartZ, width, height);
            tile.rasterizeVisibleRivers(network, cost);
            tile.rasterizeVisibleLakes(network, river, cost, flow);
            tile.sealLakeHoles(cost);
            tile.unifyLakeComponentLevels();
            tile.pruneTinyRiverComponents();
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

            return new Sample(true, lake[idx], surfaceY[idx], bedY[idx], waterLevelY[idx], affluentCount[idx]);
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

                    int channelSurfaceY = Math.min(terrainY, Math.max(localLowY, (int) Math.floor(interpolatedSurfaceY)));
                    int bedY = channelSurfaceY - MAX_RIVER_CARVE_DEPTH_BLOCKS;
                    float waterY = channelSurfaceY - 1.0F;
                    writeRiverCell(x, z, terrainY, bedY, waterY, affluents);
                }
            }
        }

        private void rasterizeVisibleLakes(TerrainRiverNetwork network, TerrainRiverTile river, TerrainCostTile cost, TerrainFlowTile flow) {
            for (TerrainRiverNetwork.Lake lake : network.lakes()) {
                int lakeAffluents = lakeAffluentCount(network, lake);
                if (lakeAffluents < MIN_WORLDGEN_AFFLUENTS) {
                    continue;
                }

                rasterizeLakeBasin(lake, river, cost, flow, lakeAffluents);
            }
        }

        private void rasterizeLakeBasin(
                TerrainRiverNetwork.Lake lake,
                TerrainRiverTile river,
                TerrainCostTile cost,
                TerrainFlowTile flow,
                int lakeAffluents
        ) {
            if (lake.runs().isEmpty()) {
                return;
            }

            float waterLevelY = resolvedLakeWaterTopY(lake);
            int minLocalX = width;
            int maxLocalX = -1;
            int minLocalZ = height;
            int maxLocalZ = -1;
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            boolean[] accepted = new boolean[width * height];
            boolean[] queued = new boolean[width * height];

            for (TerrainRiverNetwork.LakeRun run : lake.runs()) {
                if (run.worldZ() < blockStartZ || run.worldZ() >= blockStartZ + height) {
                    continue;
                }

                int localZ = run.worldZ() - blockStartZ;
                int startX = clamp(run.startWorldX() - blockStartX, 0, width);
                int endX = clamp(run.endWorldX() - blockStartX, 0, width);
                if (endX <= startX) {
                    continue;
                }

                minLocalX = Math.min(minLocalX, startX);
                maxLocalX = Math.max(maxLocalX, endX - 1);
                minLocalZ = Math.min(minLocalZ, localZ);
                maxLocalZ = Math.max(maxLocalZ, localZ);

                for (int localX = startX; localX < endX; localX++) {
                    int idx = index(localX, localZ, width);
                    if (!accepted[idx] && terrainBelowWater(cost, localX, localZ, waterLevelY)) {
                        accepted[idx] = true;
                        queued[idx] = true;
                        queue.add(idx);
                    }
                }
            }

            if (queue.isEmpty()) {
                return;
            }

            int basinMinX = clamp(minLocalX - LAKE_BASIN_MARGIN_BLOCKS, 0, width - 1);
            int basinMaxX = clamp(maxLocalX + LAKE_BASIN_MARGIN_BLOCKS, 0, width - 1);
            int basinMinZ = clamp(minLocalZ - LAKE_BASIN_MARGIN_BLOCKS, 0, height - 1);
            int basinMaxZ = clamp(maxLocalZ + LAKE_BASIN_MARGIN_BLOCKS, 0, height - 1);

            while (!queue.isEmpty()) {
                int idx = queue.remove();
                int localX = idx % width;
                int localZ = idx / width;
                int costX = clamp(blockStartX + localX - cost.blockStartX(), 0, cost.width() - 1);
                int costZ = clamp(blockStartZ + localZ - cost.blockStartZ(), 0, cost.height() - 1);
                int terrainY = cost.surfaceYAtLocal(costX, costZ);
                writeLakeCell(localX, localZ, terrainY, waterLevelY, lakeAffluents);

                for (int dir = 0; dir < 8; dir++) {
                    int nx = localX + DIR_X_8[dir];
                    int nz = localZ + DIR_Z_8[dir];
                    if (nx < basinMinX || nz < basinMinZ || nx > basinMaxX || nz > basinMaxZ) {
                        continue;
                    }
                    int next = index(nx, nz, width);
                    if (queued[next] || !terrainBelowWater(cost, nx, nz, waterLevelY)) {
                        continue;
                    }
                    if (!canJoinLakeBasin(river, cost, flow, nx, nz, accepted, waterLevelY)) {
                        continue;
                    }
                    accepted[next] = true;
                    queued[next] = true;
                    queue.add(next);
                }
            }

            traceLakeOverflow(lake, cost, flow, accepted, waterLevelY, lakeAffluents);
        }

        private float resolvedLakeWaterTopY(TerrainRiverNetwork.Lake lake) {
            float level = lake.waterLevelY();
            for (TerrainRiverNetwork.LakeRun run : lake.runs()) {
                level = Math.max(level, run.waterLevelY());
            }
            return (float) Math.ceil(level - 1.0E-3F);
        }

        private boolean canJoinLakeBasin(
                TerrainRiverTile river,
                TerrainCostTile cost,
                TerrainFlowTile flow,
                int localX,
                int localZ,
                boolean[] accepted,
                float waterLevelY
        ) {
            int riverX = blockStartX + localX - river.blockStartX();
            int riverZ = blockStartZ + localZ - river.blockStartZ();
            if (riverX >= 0 && riverZ >= 0 && riverX < river.width() && riverZ < river.height()) {
                if (river.isLakeAt(riverX, riverZ)) {
                    return true;
                }
            }

            if (drainsIntoAcceptedLake(flow, localX, localZ, accepted)) {
                return true;
            }

            int acceptedNeighbors = 0;
            int belowWaterNeighbors = 0;
            for (int dir = 0; dir < 8; dir++) {
                int nx = localX + DIR_X_8[dir];
                int nz = localZ + DIR_Z_8[dir];
                if (nx < 0 || nz < 0 || nx >= width || nz >= height) {
                    continue;
                }
                int idx = index(nx, nz, width);
                if (accepted[idx]) {
                    acceptedNeighbors++;
                }
                if (terrainBelowWater(cost, nx, nz, waterLevelY)) {
                    belowWaterNeighbors++;
                }
            }

            return acceptedNeighbors >= 2 || (acceptedNeighbors >= 1 && belowWaterNeighbors >= 5);
        }

        private boolean drainsIntoAcceptedLake(TerrainFlowTile flow, int localX, int localZ, boolean[] accepted) {
            int flowX = blockStartX + localX - flow.blockStartX();
            int flowZ = blockStartZ + localZ - flow.blockStartZ();

            for (int step = 0; step < LAKE_BASIN_DRAIN_TRACE_STEPS; step++) {
                int worldX = flow.blockStartX() + flowX;
                int worldZ = flow.blockStartZ() + flowZ;
                int acceptedX = worldX - blockStartX;
                int acceptedZ = worldZ - blockStartZ;
                if (acceptedX >= 0 && acceptedZ >= 0 && acceptedX < width && acceptedZ < height) {
                    if (accepted[index(acceptedX, acceptedZ, width)]) {
                        return true;
                    }
                }

                if (flowX < 0 || flowZ < 0 || flowX >= flow.width() || flowZ >= flow.height()) {
                    return false;
                }

                byte direction = flow.directionAt(flowX, flowZ);
                if (direction < 0) {
                    direction = flow.costDirectionAt(flowX, flowZ);
                }
                if (direction < 0) {
                    return false;
                }

                flowX += FLOW_DIR_X[direction];
                flowZ += FLOW_DIR_Z[direction];
            }

            return false;
        }

        private void traceLakeOverflow(
                TerrainRiverNetwork.Lake lake,
                TerrainCostTile cost,
                TerrainFlowTile flow,
                boolean[] lakeMask,
                float lakeWaterLevelY,
                int lakeAffluents
        ) {
            Outlet outlet = findLakeOutlet(lake, cost, flow, lakeMask, lakeWaterLevelY);
            if (outlet == null) {
                return;
            }

            int localX = outlet.localX();
            int localZ = outlet.localZ();
            int previousSurfaceY = outlet.previousSurfaceY();
            float previousWaterY = Math.min(lakeWaterLevelY - 1.0F, previousSurfaceY - 1.0F);

            for (int step = 0; step < LAKE_OVERFLOW_MAX_STEPS; step++) {
                if (localX < 0 || localZ < 0 || localX >= width || localZ >= height) {
                    return;
                }

                int idx = index(localX, localZ, width);
                if (lakeMask[idx]) {
                    int[] outside = firstFlowCellOutsideMask(flow, localX, localZ, lakeMask);
                    if (outside == null) {
                        return;
                    }
                    localX = outside[0];
                    localZ = outside[1];
                    continue;
                }

                int costX = clamp(blockStartX + localX - cost.blockStartX(), 0, cost.width() - 1);
                int costZ = clamp(blockStartZ + localZ - cost.blockStartZ(), 0, cost.height() - 1);
                int terrainY = cost.surfaceYAtLocal(costX, costZ);
                if (step > 0 && terrainY > previousSurfaceY + MAX_LAKE_OVERFLOW_STEP_RISE_BLOCKS) {
                    return;
                }
                if (step > 0 && previousSurfaceY - terrainY > MAX_LAKE_OVERFLOW_STEP_DROP_BLOCKS) {
                    return;
                }

                boolean connectedToExistingRiver = active[idx] && !this.lake[idx];
                int localLowY = localMinSurfaceY(cost, costX, costZ, 2);
                int channelSurfaceY = Math.min(terrainY, Math.max(localLowY, (int) Math.floor(previousWaterY + 1.0F)));
                int bedY = channelSurfaceY - MAX_RIVER_CARVE_DEPTH_BLOCKS;
                float waterY = Math.min(channelSurfaceY - 1.0F, previousWaterY);
                writeOverflowRiverDisc(localX, localZ, terrainY, bedY, waterY, lakeAffluents, cost, lakeMask);

                if (connectedToExistingRiver && step > 0) {
                    return;
                }

                previousSurfaceY = terrainY;
                previousWaterY = waterY;

                int[] next = nextFlowCell(flow, localX, localZ);
                if (next == null) {
                    next = lowestNeighbor(localX, localZ, cost, lakeMask);
                }
                if (next == null || (next[0] == localX && next[1] == localZ)) {
                    return;
                }

                localX = next[0];
                localZ = next[1];
            }
        }

        private Outlet findLakeOutlet(
                TerrainRiverNetwork.Lake lake,
                TerrainCostTile cost,
                TerrainFlowTile flow,
                boolean[] lakeMask,
                float lakeWaterLevelY
        ) {
            if (lake.outletWorldX() != Integer.MIN_VALUE && lake.outletWorldZ() != Integer.MIN_VALUE) {
                int localX = lake.outletWorldX() - blockStartX;
                int localZ = lake.outletWorldZ() - blockStartZ;
                int[] outside = firstFlowCellOutsideMask(flow, localX, localZ, lakeMask);
                if (outside != null) {
                    int previousSurfaceY = surfaceYAtHydrologyLocal(cost, localX, localZ);
                    return new Outlet(outside[0], outside[1], previousSurfaceY);
                }
            }

            Outlet best = null;
            int bestSpillY = Integer.MAX_VALUE;
            float bestAccumulation = -1.0F;
            int maxWaterTop = (int) Math.floor(lakeWaterLevelY);

            for (int idx = 0; idx < lakeMask.length; idx++) {
                if (!lakeMask[idx]) {
                    continue;
                }
                int localX = idx % width;
                int localZ = idx / width;
                int[] next = nextFlowCell(flow, localX, localZ);
                if (next == null) {
                    continue;
                }
                int nx = next[0];
                int nz = next[1];
                if (nx < 0 || nz < 0 || nx >= width || nz >= height) {
                    continue;
                }
                if (lakeMask[index(nx, nz, width)]) {
                    continue;
                }

                int surfaceY = surfaceYAtHydrologyLocal(cost, localX, localZ);
                int nextSurfaceY = surfaceYAtHydrologyLocal(cost, nx, nz);
                int spillY = Math.max(surfaceY, nextSurfaceY);
                if (spillY > maxWaterTop + 1) {
                    continue;
                }

                float accumulation = flowAccumulationAtHydrologyLocal(flow, localX, localZ);
                if (spillY < bestSpillY || (spillY == bestSpillY && accumulation > bestAccumulation)) {
                    bestSpillY = spillY;
                    bestAccumulation = accumulation;
                    best = new Outlet(nx, nz, surfaceY);
                }
            }

            return best;
        }

        private int[] firstFlowCellOutsideMask(TerrainFlowTile flow, int localX, int localZ, boolean[] mask) {
            int x = localX;
            int z = localZ;
            for (int step = 0; step < LAKE_BASIN_DRAIN_TRACE_STEPS; step++) {
                if (x < 0 || z < 0 || x >= width || z >= height) {
                    return null;
                }
                if (!mask[index(x, z, width)]) {
                    return new int[]{x, z};
                }

                int[] next = nextFlowCell(flow, x, z);
                if (next == null || (next[0] == x && next[1] == z)) {
                    return null;
                }
                x = next[0];
                z = next[1];
            }
            return null;
        }

        private int[] nextFlowCell(TerrainFlowTile flow, int localX, int localZ) {
            int flowX = blockStartX + localX - flow.blockStartX();
            int flowZ = blockStartZ + localZ - flow.blockStartZ();
            if (flowX < 0 || flowZ < 0 || flowX >= flow.width() || flowZ >= flow.height()) {
                return null;
            }

            byte direction = flow.directionAt(flowX, flowZ);
            if (direction < 0) {
                direction = flow.costDirectionAt(flowX, flowZ);
            }
            if (direction < 0) {
                return null;
            }

            int worldX = flow.blockStartX() + flowX + FLOW_DIR_X[direction];
            int worldZ = flow.blockStartZ() + flowZ + FLOW_DIR_Z[direction];
            return new int[]{worldX - blockStartX, worldZ - blockStartZ};
        }

        private int[] lowestNeighbor(int localX, int localZ, TerrainCostTile cost, boolean[] lakeMask) {
            int bestX = localX;
            int bestZ = localZ;
            int bestY = surfaceYAtHydrologyLocal(cost, localX, localZ);

            for (int dir = 0; dir < 8; dir++) {
                int nx = localX + DIR_X_8[dir];
                int nz = localZ + DIR_Z_8[dir];
                if (nx < 0 || nz < 0 || nx >= width || nz >= height || lakeMask[index(nx, nz, width)]) {
                    continue;
                }
                int y = surfaceYAtHydrologyLocal(cost, nx, nz);
                if (y < bestY) {
                    bestY = y;
                    bestX = nx;
                    bestZ = nz;
                }
            }

            return bestX == localX && bestZ == localZ ? null : new int[]{bestX, bestZ};
        }

        private void writeOverflowRiverDisc(
                int centerLocalX,
                int centerLocalZ,
                int centerTerrainY,
                int centerBedY,
                float centerWaterY,
                int affluents,
                TerrainCostTile cost,
                boolean[] lakeMask
        ) {
            for (int dz = -LAKE_OVERFLOW_RADIUS_BLOCKS; dz <= LAKE_OVERFLOW_RADIUS_BLOCKS; dz++) {
                int localZ = centerLocalZ + dz;
                if (localZ < 0 || localZ >= height) {
                    continue;
                }
                for (int dx = -LAKE_OVERFLOW_RADIUS_BLOCKS; dx <= LAKE_OVERFLOW_RADIUS_BLOCKS; dx++) {
                    int localX = centerLocalX + dx;
                    if (localX < 0 || localX >= width) {
                        continue;
                    }
                    if (dx * dx + dz * dz > LAKE_OVERFLOW_RADIUS_BLOCKS * LAKE_OVERFLOW_RADIUS_BLOCKS) {
                        continue;
                    }
                    int idx = index(localX, localZ, width);
                    if (lakeMask[idx]) {
                        continue;
                    }

                    int terrainY = surfaceYAtHydrologyLocal(cost, localX, localZ);
                    if (terrainY > centerTerrainY + MAX_RIVER_BANK_RISE_BLOCKS) {
                        continue;
                    }
                    int bedY = Math.min(centerBedY, terrainY - 1);
                    float waterY = Math.min(centerWaterY, terrainY - 1.0F);
                    writeRiverCell(localX, localZ, terrainY, bedY, waterY, affluents);
                }
            }
        }

        private int surfaceYAtHydrologyLocal(TerrainCostTile cost, int localX, int localZ) {
            int costX = clamp(blockStartX + localX - cost.blockStartX(), 0, cost.width() - 1);
            int costZ = clamp(blockStartZ + localZ - cost.blockStartZ(), 0, cost.height() - 1);
            return cost.surfaceYAtLocal(costX, costZ);
        }

        private float flowAccumulationAtHydrologyLocal(TerrainFlowTile flow, int localX, int localZ) {
            int flowX = blockStartX + localX - flow.blockStartX();
            int flowZ = blockStartZ + localZ - flow.blockStartZ();
            if (flowX < 0 || flowZ < 0 || flowX >= flow.width() || flowZ >= flow.height()) {
                return 0.0F;
            }
            return flow.accumulationFinalAt(flowX, flowZ);
        }

        private record Outlet(int localX, int localZ, int previousSurfaceY) {
        }

        private boolean terrainBelowWater(TerrainCostTile cost, int localX, int localZ, float waterLevelY) {
            int costX = clamp(blockStartX + localX - cost.blockStartX(), 0, cost.width() - 1);
            int costZ = clamp(blockStartZ + localZ - cost.blockStartZ(), 0, cost.height() - 1);
            return cost.surfaceYAtLocal(costX, costZ) < waterLevelY;
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

        private void writeRiverCell(int localX, int localZ, int terrainY, int riverBedY, float water, int affluents) {
            int idx = index(localX, localZ, width);
            if (!active[idx] || lake[idx] || affluents > affluentCount[idx] || water < waterLevelY[idx]) {
                active[idx] = true;
                lake[idx] = false;
                surfaceY[idx] = terrainY;
                bedY[idx] = riverBedY;
                waterLevelY[idx] = water;
                affluentCount[idx] = affluents;
            }
        }

        private void writeLakeCell(int localX, int localZ, int terrainY, float water, int affluents) {
            int idx = index(localX, localZ, width);
            active[idx] = true;
            lake[idx] = true;
            surfaceY[idx] = terrainY;
            bedY[idx] = terrainY;
            waterLevelY[idx] = Math.max(waterLevelY[idx], water);
            affluentCount[idx] = Math.max(affluentCount[idx], affluents);
        }

        private void sealLakeHoles(TerrainCostTile cost) {
            boolean[] toLake = new boolean[width * height];
            float[] targetWater = new float[width * height];
            int[] targetAffluents = new int[width * height];
            int[] targetSurfaceY = new int[width * height];

            for (int pass = 0; pass < LAKE_SEAL_PASSES; pass++) {
                Arrays.fill(toLake, false);
                Arrays.fill(targetWater, Float.NEGATIVE_INFINITY);
                Arrays.fill(targetAffluents, 0);
                Arrays.fill(targetSurfaceY, Integer.MIN_VALUE);
                boolean changed = false;

                for (int localZ = 1; localZ < height - 1; localZ++) {
                    for (int localX = 1; localX < width - 1; localX++) {
                        int idx = index(localX, localZ, width);
                        if (lake[idx] || active[idx]) {
                            continue;
                        }

                        int lakeNeighbors = 0;
                        float water = Float.NEGATIVE_INFINITY;
                        int affluents = 0;
                        for (int dir = 0; dir < 8; dir++) {
                            int nx = localX + DIR_X_8[dir];
                            int nz = localZ + DIR_Z_8[dir];
                            int next = index(nx, nz, width);
                            if (!lake[next]) {
                                continue;
                            }
                            lakeNeighbors++;
                            water = Math.max(water, waterLevelY[next]);
                            affluents = Math.max(affluents, affluentCount[next]);
                        }

                        if (lakeNeighbors < MIN_LAKE_NEIGHBORS_TO_SEAL) {
                            continue;
                        }
                        if (!terrainBelowWater(cost, localX, localZ, water)) {
                            continue;
                        }

                        toLake[idx] = true;
                        targetWater[idx] = water;
                        targetAffluents[idx] = affluents;
                        targetSurfaceY[idx] = surfaceYAtHydrologyLocal(cost, localX, localZ);
                        changed = true;
                    }
                }

                if (!changed) {
                    return;
                }

                for (int idx = 0; idx < toLake.length; idx++) {
                    if (!toLake[idx]) {
                        continue;
                    }
                    int localX = idx % width;
                    int localZ = idx / width;
                    writeLakeCell(localX, localZ, targetSurfaceY[idx], targetWater[idx], targetAffluents[idx]);
                }
            }
        }

        private void unifyLakeComponentLevels() {
            boolean[] visited = new boolean[width * height];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            int[] component = new int[width * height];

            for (int start = 0; start < lake.length; start++) {
                if (visited[start] || !lake[start]) {
                    continue;
                }

                int count = 0;
                float water = Float.NEGATIVE_INFINITY;
                int affluents = 0;
                visited[start] = true;
                queue.add(start);

                while (!queue.isEmpty()) {
                    int cell = queue.remove();
                    component[count++] = cell;
                    water = Math.max(water, waterLevelY[cell]);
                    affluents = Math.max(affluents, affluentCount[cell]);

                    int localX = cell % width;
                    int localZ = cell / width;
                    for (int dir = 0; dir < 8; dir++) {
                        int nx = localX + DIR_X_8[dir];
                        int nz = localZ + DIR_Z_8[dir];
                        if (nx < 0 || nz < 0 || nx >= width || nz >= height) {
                            continue;
                        }
                        int next = index(nx, nz, width);
                        if (visited[next] || !lake[next]) {
                            continue;
                        }
                        visited[next] = true;
                        queue.add(next);
                    }
                }

                for (int i = 0; i < count; i++) {
                    int cell = component[i];
                    waterLevelY[cell] = water;
                    affluentCount[cell] = Math.max(affluentCount[cell], affluents);
                }
            }
        }

        private void pruneTinyRiverComponents() {
            boolean[] visited = new boolean[width * height];
            boolean[] remove = new boolean[width * height];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            int[] component = new int[width * height];

            for (int idx = 0; idx < active.length; idx++) {
                if (visited[idx] || !active[idx] || lake[idx]) {
                    continue;
                }

                int count = 0;
                boolean touchesLake = false;
                boolean touchesBoundary = false;
                visited[idx] = true;
                queue.add(idx);

                while (!queue.isEmpty()) {
                    int cell = queue.remove();
                    component[count++] = cell;
                    int localX = cell % width;
                    int localZ = cell / width;
                    if (localX == 0 || localZ == 0 || localX == width - 1 || localZ == height - 1) {
                        touchesBoundary = true;
                    }

                    for (int dir = 0; dir < 8; dir++) {
                        int nx = localX + DIR_X_8[dir];
                        int nz = localZ + DIR_Z_8[dir];
                        if (nx < 0 || nz < 0 || nx >= width || nz >= height) {
                            continue;
                        }

                        int next = index(nx, nz, width);
                        if (!active[next]) {
                            continue;
                        }
                        if (lake[next]) {
                            touchesLake = true;
                            continue;
                        }
                        if (visited[next] || Math.abs(waterLevelY[cell] - waterLevelY[next]) > MAX_RIVER_NEIGHBOR_STEP_BLOCKS) {
                            continue;
                        }

                        visited[next] = true;
                        queue.add(next);
                    }
                }

                if (count < MIN_RIVER_COMPONENT_CELLS && !touchesLake && !touchesBoundary) {
                    for (int i = 0; i < count; i++) {
                        remove[component[i]] = true;
                    }
                }
            }

            for (int idx = 0; idx < remove.length; idx++) {
                if (!remove[idx]) {
                    continue;
                }
                active[idx] = false;
                lake[idx] = false;
                surfaceY[idx] = Integer.MIN_VALUE;
                bedY[idx] = Integer.MIN_VALUE;
                waterLevelY[idx] = Float.NEGATIVE_INFINITY;
                affluentCount[idx] = 0;
            }
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
