package com.github.xandergos.terraindiffusionmc.river;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Samples the existing Terrain Diffusion model into a coarse global grid.
 *
 * <p>This deliberately samples in world coordinates and does not depend on chunk
 * generation state. The same world seed + same world cell always produces the
 * same value no matter which Minecraft chunk asks for it.
 */
public final class LowResHeightmapSampler {
    /** Hard cap for one dense native pipeline read used by the debug sampler. */
    private static final int MAX_NATIVE_WINDOW_SIDE = 512;
    private static final int DENSE_FAST_PATH_MAX_PIXELS = MAX_NATIVE_WINDOW_SIDE * MAX_NATIVE_WINDOW_SIDE;

    private LowResHeightmapSampler() {
    }

    /**
     * Build a low-resolution heightmap window.
     *
     * @param originCellX first world-grid cell on X
     * @param originCellZ first world-grid cell on Z
     * @param widthCells number of cells on X
     * @param heightCells number of cells on Z
     * @param cellSizeBlocks size of one low-res cell in Minecraft blocks
     */
    public static LowResHeightmap sampleCells(
            int originCellX,
            int originCellZ,
            int widthCells,
            int heightCells,
            int cellSizeBlocks
    ) throws Exception {
        if (widthCells <= 0 || heightCells <= 0) {
            throw new IllegalArgumentException("heightmap dimensions must be positive");
        }
        if (cellSizeBlocks <= 0 || (cellSizeBlocks & (cellSizeBlocks - 1)) != 0) {
            throw new IllegalArgumentException("cellSizeBlocks must be a positive power of two");
        }

        int scale = WorldScaleManager.getCurrentScale();
        NativeBounds bounds = nativeBounds(originCellX, originCellZ, widthCells, heightCells, cellSizeBlocks, scale);
        long nativePixels = (long) bounds.width() * (long) bounds.height();

        if (nativePixels <= DENSE_FAST_PATH_MAX_PIXELS) {
            return sampleDense(originCellX, originCellZ, widthCells, heightCells, cellSizeBlocks, scale, bounds);
        }
        return sampleChunked(originCellX, originCellZ, widthCells, heightCells, cellSizeBlocks, scale);
    }

    public static LowResHeightmap sampleAroundBlock(
            int centerBlockX,
            int centerBlockZ,
            int radiusCells,
            int cellSizeBlocks
    ) throws Exception {
        int centerCellX = Math.floorDiv(centerBlockX, cellSizeBlocks);
        int centerCellZ = Math.floorDiv(centerBlockZ, cellSizeBlocks);
        int diameterCells = radiusCells * 2 + 1;
        return sampleCells(centerCellX - radiusCells, centerCellZ - radiusCells,
                diameterCells, diameterCells, cellSizeBlocks);
    }

    private static LowResHeightmap sampleDense(
            int originCellX,
            int originCellZ,
            int widthCells,
            int heightCells,
            int cellSizeBlocks,
            int scale,
            NativeBounds bounds
    ) throws Exception {
        float[][] pipelineOut = LocalTerrainProvider.getPipelineData(bounds.i0(), bounds.j0(), bounds.i1(), bounds.j1(), false);
        float[] nativeElevationMeters = pipelineOut[0];
        int nativeWidth = bounds.width();
        int nativeHeight = bounds.height();

        short[] heightsY = new short[widthCells * heightCells];
        for (int localZ = 0; localZ < heightCells; localZ++) {
            int blockZ = (originCellZ + localZ) * cellSizeBlocks + cellSizeBlocks / 2;
            float nativeZ = blockZ / (float) scale;

            for (int localX = 0; localX < widthCells; localX++) {
                int blockX = (originCellX + localX) * cellSizeBlocks + cellSizeBlocks / 2;
                float nativeX = blockX / (float) scale;

                heightsY[localZ * widthCells + localX] = sampleMinecraftY(
                        nativeElevationMeters,
                        nativeWidth,
                        nativeHeight,
                        nativeX - bounds.j0(),
                        nativeZ - bounds.i0(),
                        scale
                );
            }
        }

        return new LowResHeightmap(originCellX, originCellZ, widthCells, heightCells, cellSizeBlocks, heightsY);
    }

    /**
     * Memory-safe path for large debug windows.
     *
     * <p>The previous prototype requested one dense native rectangle covering the
     * whole low-res window. With large cell sizes/radii that rectangle can be
     * hundreds of MB before the cost map even starts. This groups sample points
     * into bounded native windows and reads only those windows one at a time.
     */
    private static LowResHeightmap sampleChunked(
            int originCellX,
            int originCellZ,
            int widthCells,
            int heightCells,
            int cellSizeBlocks,
            int scale
    ) throws Exception {
        int count = widthCells * heightCells;
        short[] heightsY = new short[count];
        Map<NativeWindowKey, IntList> groups = new HashMap<>();

        for (int localZ = 0; localZ < heightCells; localZ++) {
            int blockZ = (originCellZ + localZ) * cellSizeBlocks + cellSizeBlocks / 2;
            float nativeZ = blockZ / (float) scale;
            int windowZ = Math.floorDiv((int) Math.floor(nativeZ), MAX_NATIVE_WINDOW_SIDE);

            for (int localX = 0; localX < widthCells; localX++) {
                int blockX = (originCellX + localX) * cellSizeBlocks + cellSizeBlocks / 2;
                float nativeX = blockX / (float) scale;
                int windowX = Math.floorDiv((int) Math.floor(nativeX), MAX_NATIVE_WINDOW_SIDE);

                NativeWindowKey key = new NativeWindowKey(windowZ, windowX);
                groups.computeIfAbsent(key, ignored -> new IntList()).add(localZ * widthCells + localX);
            }
        }

        for (Map.Entry<NativeWindowKey, IntList> entry : groups.entrySet()) {
            NativeWindowKey key = entry.getKey();
            int tileI0 = key.windowZ() * MAX_NATIVE_WINDOW_SIDE;
            int tileJ0 = key.windowX() * MAX_NATIVE_WINDOW_SIDE;
            int tileI1 = tileI0 + MAX_NATIVE_WINDOW_SIDE;
            int tileJ1 = tileJ0 + MAX_NATIVE_WINDOW_SIDE;

            // 2 cells of padding for bilinear sampling and safe edge clamps.
            int i0 = tileI0 - 2;
            int j0 = tileJ0 - 2;
            int i1 = tileI1 + 3;
            int j1 = tileJ1 + 3;

            float[][] pipelineOut = LocalTerrainProvider.getPipelineData(i0, j0, i1, j1, false);
            float[] nativeElevationMeters = pipelineOut[0];
            int nativeWidth = j1 - j0;
            int nativeHeight = i1 - i0;

            IntList indices = entry.getValue();
            for (int n = 0; n < indices.size(); n++) {
                int index = indices.get(n);
                int localZ = index / widthCells;
                int localX = index - localZ * widthCells;

                int blockX = (originCellX + localX) * cellSizeBlocks + cellSizeBlocks / 2;
                int blockZ = (originCellZ + localZ) * cellSizeBlocks + cellSizeBlocks / 2;
                float nativeX = blockX / (float) scale;
                float nativeZ = blockZ / (float) scale;

                heightsY[index] = sampleMinecraftY(
                        nativeElevationMeters,
                        nativeWidth,
                        nativeHeight,
                        nativeX - j0,
                        nativeZ - i0,
                        scale
                );
            }
        }

        return new LowResHeightmap(originCellX, originCellZ, widthCells, heightCells, cellSizeBlocks, heightsY);
    }

    private static NativeBounds nativeBounds(
            int originCellX,
            int originCellZ,
            int widthCells,
            int heightCells,
            int cellSizeBlocks,
            int scale
    ) {
        int firstCenterBlockX = originCellX * cellSizeBlocks + cellSizeBlocks / 2;
        int firstCenterBlockZ = originCellZ * cellSizeBlocks + cellSizeBlocks / 2;
        int lastCenterBlockX = (originCellX + widthCells - 1) * cellSizeBlocks + cellSizeBlocks / 2;
        int lastCenterBlockZ = (originCellZ + heightCells - 1) * cellSizeBlocks + cellSizeBlocks / 2;

        // Native Terrain Diffusion coordinates use i=Z, j=X.
        int nativeJ0 = floorToNative(firstCenterBlockX, scale) - 2;
        int nativeI0 = floorToNative(firstCenterBlockZ, scale) - 2;
        int nativeJ1 = ceilToNative(lastCenterBlockX, scale) + 3;
        int nativeI1 = ceilToNative(lastCenterBlockZ, scale) + 3;
        return new NativeBounds(nativeI0, nativeJ0, nativeI1, nativeJ1);
    }

    private static short sampleMinecraftY(
            float[] nativeElevationMeters,
            int nativeWidth,
            int nativeHeight,
            float nativeX,
            float nativeZ,
            int scale
    ) {
        float meters = bilinear(nativeElevationMeters, nativeWidth, nativeHeight, nativeX, nativeZ);
        int y = HeightConverter.convertToMinecraftHeight(clampToShortFloor(meters), scale);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, y));
    }

    private static int floorToNative(int blockCoord, int scale) {
        return Math.floorDiv(blockCoord, scale);
    }

    private static int ceilToNative(int blockCoord, int scale) {
        return -Math.floorDiv(-blockCoord, scale);
    }

    private static short clampToShortFloor(float value) {
        int floored = (int) Math.floor(value);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, floored));
    }

    private static float bilinear(float[] data, int width, int height, float x, float z) {
        int x0 = clamp((int) Math.floor(x), 0, width - 1);
        int z0 = clamp((int) Math.floor(z), 0, height - 1);
        int x1 = clamp(x0 + 1, 0, width - 1);
        int z1 = clamp(z0 + 1, 0, height - 1);

        float tx = clamp01(x - x0);
        float tz = clamp01(z - z0);

        float a = data[z0 * width + x0];
        float b = data[z0 * width + x1];
        float c = data[z1 * width + x0];
        float d = data[z1 * width + x1];
        float ab = a + (b - a) * tx;
        float cd = c + (d - c) * tx;
        return ab + (cd - ab) * tz;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private record NativeBounds(int i0, int j0, int i1, int j1) {
        int width() {
            return j1 - j0;
        }

        int height() {
            return i1 - i0;
        }
    }

    private record NativeWindowKey(int windowZ, int windowX) {
    }

    private static final class IntList {
        private int[] values = new int[16];
        private int size;

        void add(int value) {
            if (size == values.length) {
                int[] next = new int[values.length * 2];
                System.arraycopy(values, 0, next, 0, values.length);
                values = next;
            }
            values[size++] = value;
        }

        int get(int index) {
            if (index < 0 || index >= size) {
                throw new IndexOutOfBoundsException(index);
            }
            return values[index];
        }

        int size() {
            return size;
        }
    }
}
