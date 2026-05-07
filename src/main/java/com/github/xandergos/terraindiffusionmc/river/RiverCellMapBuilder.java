package com.github.xandergos.terraindiffusionmc.river;

/** Extracts river cells from flow accumulation using an accumulation threshold. */
public final class RiverCellMapBuilder {
    private RiverCellMapBuilder() {
    }

    public static RiverCellMap build(FlowAccumulationMap accumulationMap) {
        return build(accumulationMap, RiverExtractionParameters.defaults());
    }

    public static RiverCellMap build(
            FlowAccumulationMap accumulationMap,
            RiverExtractionParameters parameters
    ) {
        RiverExtractionParameters effectiveParameters = parameters == null
                ? RiverExtractionParameters.defaults()
                : parameters;

        int width = accumulationMap.widthCells();
        int height = accumulationMap.heightCells();
        int count = width * height;
        FlowDirectionMap flowMap = accumulationMap.flowMap();

        byte[] flags = new byte[count];
        byte[] upstreamRiverCounts = new byte[count];

        int riverCellCount = markRiverCells(accumulationMap, effectiveParameters, flags, width, height);
        int maxRiverAccumulation = maxRiverAccumulation(accumulationMap, flags, width, height);
        writeUpstreamRiverCounts(flowMap, flags, upstreamRiverCounts, width, height);
        ClassificationStats stats = classify(accumulationMap, flowMap, flags, upstreamRiverCounts, width, height);

        return new RiverCellMap(
                accumulationMap,
                effectiveParameters,
                flags,
                upstreamRiverCounts,
                riverCellCount,
                stats.sourceCount(),
                stats.confluenceCount(),
                stats.terminalCount(),
                maxRiverAccumulation
        );
    }

    private static int markRiverCells(
            FlowAccumulationMap accumulationMap,
            RiverExtractionParameters parameters,
            byte[] flags,
            int width,
            int height
    ) {
        int count = 0;
        int threshold = parameters.minAccumulationCells();
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                if (accumulationMap.isCycleAtLocal(x, z)) {
                    continue;
                }
                if (accumulationMap.accumulationAtLocal(x, z) >= threshold) {
                    flags[index(x, z, width)] |= RiverCellMap.riverFlag();
                    count++;
                }
            }
        }
        return count;
    }

    private static int maxRiverAccumulation(
            FlowAccumulationMap accumulationMap,
            byte[] flags,
            int width,
            int height
    ) {
        int max = 1;
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int i = index(x, z, width);
                if ((flags[i] & RiverCellMap.riverFlag()) == 0) {
                    continue;
                }
                max = Math.max(max, accumulationMap.accumulationAtLocal(x, z));
            }
        }
        return max;
    }

    private static void writeUpstreamRiverCounts(
            FlowDirectionMap flowMap,
            byte[] flags,
            byte[] upstreamRiverCounts,
            int width,
            int height
    ) {
        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int i = index(x, z, width);
                if ((flags[i] & RiverCellMap.riverFlag()) == 0) {
                    continue;
                }

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
                if ((flags[downstream] & RiverCellMap.riverFlag()) == 0) {
                    continue;
                }

                int next = Math.min(255, (upstreamRiverCounts[downstream] & 0xFF) + 1);
                upstreamRiverCounts[downstream] = (byte) next;
            }
        }
    }

    private static ClassificationStats classify(
            FlowAccumulationMap accumulationMap,
            FlowDirectionMap flowMap,
            byte[] flags,
            byte[] upstreamRiverCounts,
            int width,
            int height
    ) {
        int sources = 0;
        int confluences = 0;
        int terminals = 0;

        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int i = index(x, z, width);
                if ((flags[i] & RiverCellMap.riverFlag()) == 0) {
                    continue;
                }

                int upstreamCount = upstreamRiverCounts[i] & 0xFF;
                if (upstreamCount == 0) {
                    flags[i] |= RiverCellMap.sourceFlag();
                    sources++;
                }
                if (upstreamCount >= 2) {
                    flags[i] |= RiverCellMap.confluenceFlag();
                    confluences++;
                }
                if (isTerminalRiverCell(accumulationMap, flowMap, flags, x, z, width, height)) {
                    flags[i] |= RiverCellMap.terminalFlag();
                    terminals++;
                }
            }
        }

        return new ClassificationStats(sources, confluences, terminals);
    }

    private static boolean isTerminalRiverCell(
            FlowAccumulationMap accumulationMap,
            FlowDirectionMap flowMap,
            byte[] flags,
            int x,
            int z,
            int width,
            int height
    ) {
        byte direction = flowMap.directionAtLocal(x, z);
        if (FlowDirectionMap.isSink(direction) || FlowDirectionMap.isEdge(direction)) {
            return true;
        }
        if (!FlowDirectionMap.isDirected(direction)) {
            return true;
        }

        int nx = x + FlowDirectionMap.dx(direction);
        int nz = z + FlowDirectionMap.dz(direction);
        if (!inside(nx, nz, width, height)) {
            return true;
        }

        int downstream = index(nx, nz, width);
        if ((flags[downstream] & RiverCellMap.riverFlag()) == 0) {
            return true;
        }

        return accumulationMap.isCycleAtLocal(nx, nz);
    }

    private static boolean inside(int x, int z, int width, int height) {
        return x >= 0 && z >= 0 && x < width && z < height;
    }

    private static int index(int x, int z, int width) {
        return z * width + x;
    }

    private record ClassificationStats(int sourceCount, int confluenceCount, int terminalCount) {
    }
}
