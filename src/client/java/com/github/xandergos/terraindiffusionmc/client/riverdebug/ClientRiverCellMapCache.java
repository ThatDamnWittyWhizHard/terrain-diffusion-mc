package com.github.xandergos.terraindiffusionmc.client.riverdebug;

import com.github.xandergos.terraindiffusionmc.river.FlowAccumulationMap;
import com.github.xandergos.terraindiffusionmc.river.RiverCellMap;
import com.github.xandergos.terraindiffusionmc.river.RiverCellMapBuilder;
import com.github.xandergos.terraindiffusionmc.river.RiverExtractionParameters;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.atomic.AtomicReference;

/** Lightweight client cache for extracted raster river cells. */
public final class ClientRiverCellMapCache {
    private static final AtomicReference<RiverCellMap> CURRENT = new AtomicReference<>();
    private static volatile FlowAccumulationMap sourceAccumulationMap;
    private static volatile int sourceThreshold;
    private static volatile RiverDebugOverlayState.RiverLayer sourceLayer = RiverDebugOverlayState.RiverLayer.NONE;
    private static volatile String status = "idle";

    private ClientRiverCellMapCache() {
    }

    public static void tick(MinecraftClient client) {
        RiverDebugOverlayState.RiverLayer requestedLayer = RiverDebugOverlayState.riverLayer();
        if (requestedLayer == RiverDebugOverlayState.RiverLayer.NONE) {
            invalidate();
            return;
        }
        if (client.world == null || client.player == null) {
            return;
        }

        FlowAccumulationMap accumulationMap = ClientFlowAccumulationMapCache.current();
        if (accumulationMap == null) {
            status = "waiting for accumulation";
            CURRENT.set(null);
            sourceAccumulationMap = null;
            sourceLayer = RiverDebugOverlayState.RiverLayer.NONE;
            sourceThreshold = 0;
            return;
        }

        int threshold = RiverDebugOverlayState.riverMinAccumulationCells();
        RiverCellMap riverMap = CURRENT.get();
        if (accumulationMap == sourceAccumulationMap
                && requestedLayer == sourceLayer
                && threshold == sourceThreshold
                && riverMap != null) {
            status = readyStatus(riverMap);
            return;
        }

        riverMap = RiverCellMapBuilder.build(
                accumulationMap,
                new RiverExtractionParameters(threshold)
        );
        CURRENT.set(riverMap);
        sourceAccumulationMap = accumulationMap;
        sourceLayer = requestedLayer;
        sourceThreshold = threshold;
        status = readyStatus(riverMap);
    }

    public static void invalidate() {
        CURRENT.set(null);
        sourceAccumulationMap = null;
        sourceLayer = RiverDebugOverlayState.RiverLayer.NONE;
        sourceThreshold = 0;
        status = "idle";
    }

    public static RiverCellMap current() {
        return CURRENT.get();
    }

    public static String status() {
        return status;
    }

    private static String readyStatus(RiverCellMap riverMap) {
        return "ready: cells=" + riverMap.riverCellCount()
                + ", sources=" + riverMap.sourceCount()
                + ", confluences=" + riverMap.confluenceCount()
                + ", terminals=" + riverMap.terminalCount()
                + ", threshold=" + riverMap.parameters().minAccumulationCells();
    }
}
