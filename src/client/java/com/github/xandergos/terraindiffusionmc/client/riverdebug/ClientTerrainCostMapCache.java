package com.github.xandergos.terraindiffusionmc.client.riverdebug;

import com.github.xandergos.terraindiffusionmc.river.LowResHeightmap;
import com.github.xandergos.terraindiffusionmc.river.TerrainCostMap;
import com.github.xandergos.terraindiffusionmc.river.TerrainCostMapBuilder;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.atomic.AtomicReference;

/** Lightweight client cache for cost layers derived from the current low-res heightmap window. */
public final class ClientTerrainCostMapCache {
    private static final AtomicReference<TerrainCostMap> CURRENT = new AtomicReference<>();
    private static volatile LowResHeightmap sourceHeightmap;
    private static volatile RiverDebugOverlayState.CostLayer sourceLayer = RiverDebugOverlayState.CostLayer.NONE;
    private static volatile String status = "idle";

    private ClientTerrainCostMapCache() {
    }

    public static void tick(MinecraftClient client) {
        RiverDebugOverlayState.CostLayer requestedLayer = RiverDebugOverlayState.costLayer();
        if (!RiverDebugOverlayState.needsTerrainCostMap()) {
            invalidate();
            return;
        }
        if (client.world == null || client.player == null) {
            return;
        }

        LowResHeightmap heightmap = ClientLowResHeightmapCache.current();
        if (heightmap == null) {
            status = "waiting for heightmap";
            CURRENT.set(null);
            sourceHeightmap = null;
            sourceLayer = RiverDebugOverlayState.CostLayer.NONE;
            return;
        }

        TerrainCostMap costMap = CURRENT.get();
        if (heightmap == sourceHeightmap && requestedLayer == sourceLayer && costMap != null) {
            status = "ready: final=" + format(costMap.minFinalCost()) + ".." + format(costMap.maxFinalCost())
                    + ", stored=" + storedLayerName(costMap);
            return;
        }

        TerrainCostMap.Layer debugLayer = requestedLayer == RiverDebugOverlayState.CostLayer.NONE
                ? null
                : toCostMapLayer(requestedLayer);
        costMap = debugLayer == null
                ? TerrainCostMapBuilder.build(heightmap)
                : TerrainCostMapBuilder.buildForDebug(heightmap, debugLayer);
        CURRENT.set(costMap);
        sourceHeightmap = heightmap;
        sourceLayer = requestedLayer;
        status = "ready: final=" + format(costMap.minFinalCost()) + ".." + format(costMap.maxFinalCost())
                + ", stored=" + storedLayerName(costMap);
    }

    public static void invalidate() {
        CURRENT.set(null);
        sourceHeightmap = null;
        sourceLayer = RiverDebugOverlayState.CostLayer.NONE;
        status = "idle";
    }

    public static TerrainCostMap current() {
        return CURRENT.get();
    }

    public static String status() {
        return status;
    }

    private static TerrainCostMap.Layer toCostMapLayer(RiverDebugOverlayState.CostLayer layer) {
        return switch (layer) {
            case FINAL_COST -> TerrainCostMap.Layer.FINAL_COST;
            case SLOPE_COST -> TerrainCostMap.Layer.SLOPE_COST;
            case RIDGE_COST -> TerrainCostMap.Layer.RIDGE_COST;
            case VALLEY_BONUS -> TerrainCostMap.Layer.VALLEY_BONUS;
            case BIOME_COST -> TerrainCostMap.Layer.BIOME_COST;
            case ROCK_SOIL_COST -> TerrainCostMap.Layer.ROCK_SOIL_COST;
            case FORBIDDEN_COST -> TerrainCostMap.Layer.FORBIDDEN_COST;
            case NONE -> TerrainCostMap.Layer.FINAL_COST;
        };
    }

    private static String storedLayerName(TerrainCostMap costMap) {
        TerrainCostMap.Layer debugLayer = costMap.debugLayer();
        return debugLayer == null ? "final-only" : debugLayer.label();
    }

    private static String format(float value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
