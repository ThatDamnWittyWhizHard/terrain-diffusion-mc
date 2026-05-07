package com.github.xandergos.terraindiffusionmc.client.riverdebug;

import com.github.xandergos.terraindiffusionmc.river.LowResHeightmap;
import com.github.xandergos.terraindiffusionmc.river.LowResHeightmapSampler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Async client-side window cache for the debug overlay.
 *
 * <p>The sampler uses the global pipeline data, not generated chunks. It is still
 * expensive enough that render/tick threads must never wait on it.
 */
public final class ClientLowResHeightmapCache {
    private static final Logger LOG = LoggerFactory.getLogger(ClientLowResHeightmapCache.class);

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "terrain-diffusion-lowres-heightmap-debug");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicReference<LowResHeightmap> CURRENT = new AtomicReference<>();
    private static volatile CompletableFuture<?> pending;
    private static volatile RequestKey pendingKey;
    private static volatile RequestKey currentKey;
    private static volatile String status = "idle";

    private ClientLowResHeightmapCache() {
    }

    public static void tick(MinecraftClient client) {
        if (!RiverDebugOverlayState.needsLowResHeightmap()) {
            releaseIfIdle();
            return;
        }
        if (client.world == null || client.player == null) {
            return;
        }

        PlayerEntity player = client.player;
        int cellSize = RiverDebugOverlayState.cellSizeBlocks();
        int radius = RiverDebugOverlayState.radiusCells();
        int centerCellX = Math.floorDiv(player.getBlockX(), cellSize);
        int centerCellZ = Math.floorDiv(player.getBlockZ(), cellSize);

        // Do not rebuild the whole debug window every time the player crosses one low-res cell.
        // Keep the player comfortably inside the current window and jump the anchor in coarse steps.
        int anchorCellX = anchorCell(centerCellX, radius);
        int anchorCellZ = anchorCell(centerCellZ, radius);

        RequestKey wanted = new RequestKey(anchorCellX, anchorCellZ, radius, cellSize);
        LowResHeightmap current = CURRENT.get();
        if (current != null && wanted.equals(currentKey)) {
            status = "ready: " + current.widthCells() + "x" + current.heightCells()
                    + ", cell=" + current.cellSizeBlocks()
                    + ", y=" + current.minY() + ".." + current.maxY();
            return;
        }

        synchronized (ClientLowResHeightmapCache.class) {
            if (pending != null && !pending.isDone()) {
                pendingKey = wanted;
                status = "building latest window...";
                return;
            }
            requestLocked(wanted);
        }
    }

    public static void forceRefresh(MinecraftClient client) {
        if (client == null || client.player == null) {
            return;
        }
        int cellSize = RiverDebugOverlayState.cellSizeBlocks();
        int radius = RiverDebugOverlayState.radiusCells();
        int centerCellX = Math.floorDiv(client.player.getBlockX(), cellSize);
        int centerCellZ = Math.floorDiv(client.player.getBlockZ(), cellSize);
        RequestKey wanted = new RequestKey(anchorCell(centerCellX, radius), anchorCell(centerCellZ, radius), radius, cellSize);

        CURRENT.set(null);
        currentKey = null;
        ClientTerrainCostMapCache.invalidate();
        ClientFlowDirectionMapCache.invalidate();
        ClientFlowAccumulationMapCache.invalidate();
        ClientRiverCellMapCache.invalidate();

        synchronized (ClientLowResHeightmapCache.class) {
            if (pending != null && !pending.isDone()) {
                pendingKey = wanted;
                status = "building latest window...";
                return;
            }
            requestLocked(wanted);
        }
    }

    public static LowResHeightmap current() {
        return CURRENT.get();
    }

    public static String status() {
        return status;
    }

    private static void releaseIfIdle() {
        if (CURRENT.get() == null && pending == null) {
            status = "idle";
            return;
        }
        CURRENT.set(null);
        currentKey = null;
        pendingKey = null;
        if (pending != null && !pending.isDone()) {
            pending.cancel(false);
        }
        pending = null;
        ClientTerrainCostMapCache.invalidate();
        ClientFlowDirectionMapCache.invalidate();
        ClientFlowAccumulationMapCache.invalidate();
        ClientRiverCellMapCache.invalidate();
        status = "idle";
    }

    private static void requestLocked(RequestKey key) {
        pendingKey = key;
        status = "building...";
        pending = CompletableFuture.supplyAsync(() -> {
            try {
                return LowResHeightmapSampler.sampleAroundBlock(
                        key.centerCellX * key.cellSizeBlocks + key.cellSizeBlocks / 2,
                        key.centerCellZ * key.cellSizeBlocks + key.cellSizeBlocks / 2,
                        key.radiusCells,
                        key.cellSizeBlocks
                );
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }, EXECUTOR).whenComplete((heightmap, throwable) -> {
            synchronized (ClientLowResHeightmapCache.class) {
                pending = null;
                if (!key.equals(pendingKey)) {
                    status = "stale result discarded";
                    return;
                }
                if (throwable != null) {
                    status = "failed: " + rootMessage(throwable);
                    LOG.warn("Failed to build low-res heightmap debug window", throwable);
                    return;
                }
                CURRENT.set(heightmap);
                currentKey = key;
                ClientTerrainCostMapCache.invalidate();
                ClientFlowDirectionMapCache.invalidate();
                ClientFlowAccumulationMapCache.invalidate();
        ClientRiverCellMapCache.invalidate();
                status = "ready: " + heightmap.widthCells() + "x" + heightmap.heightCells()
                        + ", cell=" + heightmap.cellSizeBlocks()
                        + ", y=" + heightmap.minY() + ".." + heightmap.maxY();
            }
        });
    }

    private static int anchorCell(int cell, int radiusCells) {
        int step = Math.max(1, radiusCells / 2);
        return Math.floorDiv(cell, step) * step;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable t = throwable;
        while (t.getCause() != null) t = t.getCause();
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private record RequestKey(int centerCellX, int centerCellZ, int radiusCells, int cellSizeBlocks) {
    }
}
