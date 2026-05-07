package com.github.xandergos.terraindiffusionmc.client.riverdebug;

import com.github.xandergos.terraindiffusionmc.river.FlowDirectionMap;
import com.github.xandergos.terraindiffusionmc.river.FlowDirectionMapBuilder;
import com.github.xandergos.terraindiffusionmc.river.TerrainCostMap;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.atomic.AtomicReference;

/** Lightweight client cache for the weighted D8 flow-direction layer. */
public final class ClientFlowDirectionMapCache {
    private static final AtomicReference<FlowDirectionMap> CURRENT = new AtomicReference<>();
    private static volatile TerrainCostMap sourceCostMap;
    private static volatile RiverDebugOverlayState.FlowLayer sourceLayer = RiverDebugOverlayState.FlowLayer.NONE;
    private static volatile String status = "idle";

    private ClientFlowDirectionMapCache() {
    }

    public static void tick(MinecraftClient client) {
        RiverDebugOverlayState.FlowLayer requestedLayer = RiverDebugOverlayState.flowLayer();
        if (!RiverDebugOverlayState.needsFlowDirectionMap()) {
            invalidate();
            return;
        }
        if (client.world == null || client.player == null) {
            return;
        }

        TerrainCostMap costMap = ClientTerrainCostMapCache.current();
        if (costMap == null) {
            status = "waiting for cost map";
            CURRENT.set(null);
            sourceCostMap = null;
            sourceLayer = RiverDebugOverlayState.FlowLayer.NONE;
            return;
        }

        FlowDirectionMap flowMap = CURRENT.get();
        if (costMap == sourceCostMap && requestedLayer == sourceLayer && flowMap != null) {
            status = readyStatus(flowMap);
            return;
        }

        FlowDirectionMap.DebugLayer debugLayer = switch (requestedLayer) {
            case SCORE -> FlowDirectionMap.DebugLayer.SCORE;
            case DROP -> FlowDirectionMap.DebugLayer.DROP;
            case NONE, ARROWS, SINKS -> FlowDirectionMap.DebugLayer.NONE;
        };

        flowMap = FlowDirectionMapBuilder.buildForDebug(costMap, debugLayer);
        CURRENT.set(flowMap);
        sourceCostMap = costMap;
        sourceLayer = requestedLayer;
        status = readyStatus(flowMap);
    }

    public static void invalidate() {
        CURRENT.set(null);
        sourceCostMap = null;
        sourceLayer = RiverDebugOverlayState.FlowLayer.NONE;
        status = "idle";
    }

    public static FlowDirectionMap current() {
        return CURRENT.get();
    }

    public static String status() {
        return status;
    }

    private static String readyStatus(FlowDirectionMap flowMap) {
        return "ready: directed=" + flowMap.directedCount()
                + ", sinks=" + flowMap.sinkCount()
                + ", edge=" + flowMap.edgeCount()
                + ", debug=" + flowMap.debugLayer().label();
    }
}
