package com.github.xandergos.terraindiffusionmc.river;

/** Builds flow accumulation from the weighted D8 flow-direction layer. */
public final class FlowAccumulationMapBuilder {
    private FlowAccumulationMapBuilder() {
    }

    public static FlowAccumulationMap build(FlowDirectionMap flowMap) {
        return build(flowMap, FlowAccumulationMap.DebugLayer.NONE);
    }

    public static FlowAccumulationMap buildForDebug(
            FlowDirectionMap flowMap,
            FlowAccumulationMap.DebugLayer debugLayer
    ) {
        return build(flowMap, debugLayer);
    }

    public static FlowAccumulationMap build(
            FlowDirectionMap flowMap,
            FlowAccumulationMap.DebugLayer debugLayer
    ) {
        int width = flowMap.widthCells();
        int height = flowMap.heightCells();
        int count = width * height;

        int[] accumulation = new int[count];
        int[] indegree = new int[count];
        int[] queue = new int[count];
        byte[] processed = new byte[count];
        byte[] flags = new byte[count];

        for (int i = 0; i < count; i++) {
            accumulation[i] = 1;
        }

        writeIndegrees(flowMap, width, height, indegree);
        int processedCount = propagate(flowMap, width, height, accumulation, indegree, queue, processed);
        CycleStats cycleStats = markCycles(processed, flags);
        OutletStats outletStats = markOutlets(flowMap, width, height, accumulation, flags);
        int maxAccumulation = max(accumulation);

        FlowAccumulationMap.DebugLayer storedDebugLayer = debugLayer == null
                ? FlowAccumulationMap.DebugLayer.NONE
                : debugLayer;
        byte[] debugValues = storedDebugLayer == FlowAccumulationMap.DebugLayer.NONE
                ? null
                : buildDebugValues(flowMap, width, height, accumulation, flags, storedDebugLayer, maxAccumulation);

        return new FlowAccumulationMap(
                flowMap,
                accumulation,
                flags,
                storedDebugLayer,
                debugValues,
                maxAccumulation,
                outletStats.outletCount(),
                cycleStats.cycleCount(),
                processedCount
        );
    }

    private static void writeIndegrees(FlowDirectionMap flowMap, int width, int height, int[] indegree) {
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                byte direction = flowMap.directionAtLocal(x, z);
                if (!FlowDirectionMap.isDirected(direction)) {
                    continue;
                }
                int nx = x + FlowDirectionMap.dx(direction);
                int nz = z + FlowDirectionMap.dz(direction);
                if (inside(nx, nz, width, height)) {
                    indegree[index(nx, nz, width)]++;
                }
            }
        }
    }

    private static int propagate(
            FlowDirectionMap flowMap,
            int width,
            int height,
            int[] accumulation,
            int[] indegree,
            int[] queue,
            byte[] processed
    ) {
        int count = width * height;
        int head = 0;
        int tail = 0;

        for (int i = 0; i < count; i++) {
            if (indegree[i] == 0) {
                queue[tail++] = i;
            }
        }

        int processedCount = 0;
        while (head < tail) {
            int current = queue[head++];
            processed[current] = 1;
            processedCount++;

            int x = current % width;
            int z = current / width;
            byte direction = flowMap.directionAtLocal(x, z);
            if (!FlowDirectionMap.isDirected(direction)) {
                continue;
            }

            int nx = x + FlowDirectionMap.dx(direction);
            int nz = z + FlowDirectionMap.dz(direction);
            if (!inside(nx, nz, width, height)) {
                continue;
            }

            int downstream = index(nx, nz, width);
            accumulation[downstream] = saturatedAdd(accumulation[downstream], accumulation[current]);
            indegree[downstream]--;
            if (indegree[downstream] == 0) {
                queue[tail++] = downstream;
            }
        }
        return processedCount;
    }

    private static CycleStats markCycles(byte[] processed, byte[] flags) {
        int cycles = 0;
        for (int i = 0; i < processed.length; i++) {
            if (processed[i] == 0) {
                flags[i] |= FlowAccumulationMap.cycleFlag();
                cycles++;
            }
        }
        return new CycleStats(cycles);
    }

    private static OutletStats markOutlets(
            FlowDirectionMap flowMap,
            int width,
            int height,
            int[] accumulation,
            byte[] flags
    ) {
        int outlets = 0;
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = index(x, z, width);
                byte direction = flowMap.directionAtLocal(x, z);
                boolean edgeOutlet = FlowDirectionMap.isEdge(direction) && accumulation[index] > 1;
                if (edgeOutlet) {
                    flags[index] |= FlowAccumulationMap.outletFlag();
                    outlets++;
                }
            }
        }
        return new OutletStats(outlets);
    }

    private static byte[] buildDebugValues(
            FlowDirectionMap flowMap,
            int width,
            int height,
            int[] accumulation,
            byte[] flags,
            FlowAccumulationMap.DebugLayer debugLayer,
            int maxAccumulation
    ) {
        byte[] debugValues = new byte[width * height];
        double logMax = Math.log1p(Math.max(1, maxAccumulation));

        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = index(x, z, width);
                debugValues[index] = switch (debugLayer) {
                    case LINEAR -> quantize100((float) accumulation[index] / Math.max(1.0f, maxAccumulation) * 100.0f);
                    case LOG -> quantize100((float) (Math.log1p(accumulation[index]) / logMax * 100.0));
                    case OUTLETS -> outletDebugValue(flowMap, x, z, accumulation[index], flags[index]);
                    case CYCLES -> (flags[index] & FlowAccumulationMap.cycleFlag()) != 0 ? (byte) 100 : 0;
                    case NONE -> 0;
                };
            }
        }
        return debugValues;
    }

    private static byte outletDebugValue(
            FlowDirectionMap flowMap,
            int localX,
            int localZ,
            int accumulation,
            byte flag
    ) {
        byte direction = flowMap.directionAtLocal(localX, localZ);
        if ((flag & FlowAccumulationMap.cycleFlag()) != 0) {
            return (byte) 100;
        }
        if (FlowDirectionMap.isSink(direction)) {
            return (byte) 85;
        }
        if ((flag & FlowAccumulationMap.outletFlag()) != 0) {
            return (byte) Math.max(35, Math.min(100, accumulation));
        }
        return 0;
    }

    private static int max(int[] values) {
        int max = 1;
        for (int value : values) {
            if (value > max) {
                max = value;
            }
        }
        return max;
    }

    private static int saturatedAdd(int a, int b) {
        long sum = (long) a + (long) b;
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static boolean inside(int x, int z, int width, int height) {
        return x >= 0 && z >= 0 && x < width && z < height;
    }

    private static int index(int x, int z, int width) {
        return z * width + x;
    }

    private static byte quantize100(float value) {
        int rounded = Math.round(Math.max(0.0f, Math.min(100.0f, value)));
        return (byte) rounded;
    }

    private record CycleStats(int cycleCount) {
    }

    private record OutletStats(int outletCount) {
    }
}
