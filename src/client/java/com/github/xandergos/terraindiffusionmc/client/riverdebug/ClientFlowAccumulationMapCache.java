package com.github.xandergos.terraindiffusionmc.client.riverdebug;

import com.github.xandergos.terraindiffusionmc.river.FlowAccumulationMap;
import com.github.xandergos.terraindiffusionmc.river.FlowAccumulationMapBuilder;
import com.github.xandergos.terraindiffusionmc.river.FlowDirectionMap;
import net.minecraft.client.MinecraftClient;

import java.util.concurrent.atomic.AtomicReference;

/** Lightweight client cache for flow accumulation derived from the current flow-direction map. */
public final class ClientFlowAccumulationMapCache {
    private static final AtomicReference<FlowAccumulationMap> CURRENT = new AtomicReference<>();
    private static volatile FlowDirectionMap sourceFlowMap;
    private static volatile RiverDebugOverlayState.AccumulationLayer sourceLayer = RiverDebugOverlayState.AccumulationLayer.NONE;
    private static volatile String status = "idle";

    private ClientFlowAccumulationMapCache() {
    }

    public static void tick(MinecraftClient client) {
        RiverDebugOverlayState.AccumulationLayer requestedLayer = RiverDebugOverlayState.accumulationLayer();
        if (!RiverDebugOverlayState.needsFlowAccumulationMap()) {
            invalidate();
            return;
        }
        if (client.world == null || client.player == null) {
            return;
        }

        FlowDirectionMap flowMap = ClientFlowDirectionMapCache.current();
        if (flowMap == null) {
            status = "waiting for flow direction";
            CURRENT.set(null);
            sourceFlowMap = null;
            sourceLayer = RiverDebugOverlayState.AccumulationLayer.NONE;
            return;
        }

        FlowAccumulationMap accumulationMap = CURRENT.get();
        if (flowMap == sourceFlowMap && requestedLayer == sourceLayer && accumulationMap != null) {
            status = readyStatus(accumulationMap);
            return;
        }

        FlowAccumulationMap.DebugLayer debugLayer = switch (requestedLayer) {
            case LINEAR -> FlowAccumulationMap.DebugLayer.LINEAR;
            case LOG -> FlowAccumulationMap.DebugLayer.LOG;
            case SINKS, WINDOW_OUTLETS, TERMINALS -> FlowAccumulationMap.DebugLayer.OUTLETS;
            case CYCLES -> FlowAccumulationMap.DebugLayer.CYCLES;
            case NONE -> FlowAccumulationMap.DebugLayer.NONE;
        };

        accumulationMap = FlowAccumulationMapBuilder.buildForDebug(flowMap, debugLayer);
        CURRENT.set(accumulationMap);
        sourceFlowMap = flowMap;
        sourceLayer = requestedLayer;
        status = readyStatus(accumulationMap);
    }

    public static void invalidate() {
        CURRENT.set(null);
        sourceFlowMap = null;
        sourceLayer = RiverDebugOverlayState.AccumulationLayer.NONE;
        status = "idle";
        ClientRiverCellMapCache.invalidate();
    }

    public static FlowAccumulationMap current() {
        return CURRENT.get();
    }

    public static String status() {
        return status;
    }

    private static String readyStatus(FlowAccumulationMap accumulationMap) {
        return "ready: max=" + accumulationMap.maxAccumulation()
                + ", outlets=" + accumulationMap.outletCount()
                + ", cycles=" + accumulationMap.cycleCount()
                + ", debug=" + accumulationMap.debugLayer().label();
    }
}
