package com.github.xandergos.terraindiffusionmc.river;

/** Builds the weighted D8 flow-direction layer used before accumulation. */
public final class FlowDirectionMapBuilder {
    private static final Decision EDGE_DECISION = new Decision(FlowDirectionMap.EDGE, Float.POSITIVE_INFINITY, 0.0f);
    private static final Decision SINK_DECISION = new Decision(FlowDirectionMap.SINK, Float.POSITIVE_INFINITY, 0.0f);

    private FlowDirectionMapBuilder() {
    }

    public static FlowDirectionMap build(TerrainCostMap costMap) {
        return build(costMap, RiverFlowParameters.defaults(), FlowDirectionMap.DebugLayer.NONE);
    }

    public static FlowDirectionMap buildForDebug(TerrainCostMap costMap, FlowDirectionMap.DebugLayer debugLayer) {
        return build(costMap, RiverFlowParameters.defaults(), debugLayer);
    }

    public static FlowDirectionMap build(
            TerrainCostMap costMap,
            RiverFlowParameters parameters,
            FlowDirectionMap.DebugLayer debugLayer
    ) {
        int width = costMap.widthCells();
        int height = costMap.heightCells();
        int count = width * height;

        byte[] directions = new byte[count];
        DecisionStats stats = writeDirections(costMap, parameters, directions);

        byte[] debugValues = null;
        FlowDirectionMap.DebugLayer storedDebugLayer = debugLayer == null ? FlowDirectionMap.DebugLayer.NONE : debugLayer;
        if (storedDebugLayer != FlowDirectionMap.DebugLayer.NONE) {
            debugValues = buildDebugValues(costMap, parameters, directions, storedDebugLayer, stats);
        }

        return new FlowDirectionMap(
                costMap,
                directions,
                storedDebugLayer,
                debugValues,
                stats.directedCount(),
                stats.sinkCount(),
                stats.edgeCount()
        );
    }

    private static DecisionStats writeDirections(TerrainCostMap costMap, RiverFlowParameters parameters, byte[] directions) {
        int width = costMap.widthCells();
        int height = costMap.heightCells();
        int directed = 0;
        int sinks = 0;
        int edges = 0;
        float minScore = Float.POSITIVE_INFINITY;
        float maxScore = Float.NEGATIVE_INFINITY;
        float maxDrop = 0.0f;

        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                Decision decision = decide(costMap, parameters, x, z);
                directions[index] = decision.direction();

                if (FlowDirectionMap.isEdge(decision.direction())) {
                    edges++;
                    continue;
                }
                if (FlowDirectionMap.isSink(decision.direction())) {
                    sinks++;
                    continue;
                }

                directed++;
                minScore = Math.min(minScore, decision.score());
                maxScore = Math.max(maxScore, decision.score());
                maxDrop = Math.max(maxDrop, decision.selectedDrop());
            }
        }

        if (directed == 0) {
            minScore = 0.0f;
            maxScore = 1.0f;
        } else if (maxScore <= minScore) {
            maxScore = minScore + 1.0f;
        }

        return new DecisionStats(directed, sinks, edges, minScore, maxScore, Math.max(1.0f, maxDrop));
    }

    private static byte[] buildDebugValues(
            TerrainCostMap costMap,
            RiverFlowParameters parameters,
            byte[] directions,
            FlowDirectionMap.DebugLayer debugLayer,
            DecisionStats stats
    ) {
        int width = costMap.widthCells();
        int height = costMap.heightCells();
        byte[] debugValues = new byte[width * height];

        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                byte direction = directions[index];
                if (!FlowDirectionMap.isDirected(direction)) {
                    debugValues[index] = debugLayer == FlowDirectionMap.DebugLayer.SCORE && FlowDirectionMap.isSink(direction)
                            ? (byte) 100
                            : 0;
                    continue;
                }

                Decision decision = decide(costMap, parameters, x, z);
                debugValues[index] = switch (debugLayer) {
                    case SCORE -> quantize100((decision.score() - stats.minScore()) / (stats.maxScore() - stats.minScore()) * 100.0f);
                    case DROP -> quantize100(decision.selectedDrop() / Math.max(1.0f, parameters.debugDropFullBlocks()) * 100.0f);
                    case NONE -> 0;
                };
            }
        }
        return debugValues;
    }

    private static Decision decide(TerrainCostMap costMap, RiverFlowParameters parameters, int x, int z) {
        int width = costMap.widthCells();
        int height = costMap.heightCells();

        // The debug window is not the global hydrology domain. Mark its border explicitly
        // instead of inventing fake sinks at the edge of the sampled rectangle.
        if (x <= 0 || z <= 0 || x >= width - 1 || z >= height - 1) {
            return EDGE_DECISION;
        }

        LowResHeightmap heightmap = costMap.heightmap();
        float centerHeight = heightmap.heightAtLocal(x, z);
        boolean hasDownhillCandidate = false;
        boolean hasNonUphillCandidate = false;

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                float deltaHeight = heightmap.heightAtLocal(x + dx, z + dz) - centerHeight;
                if (deltaHeight <= -parameters.minDownhillDropBlocks()) {
                    hasDownhillCandidate = true;
                }
                if (deltaHeight <= parameters.hardUphillLimitBlocks()) {
                    hasNonUphillCandidate = true;
                }
            }
        }

        if (!hasDownhillCandidate && !hasNonUphillCandidate) {
            return SINK_DECISION;
        }

        float bestScore = Float.POSITIVE_INFINITY;
        byte bestDirection = FlowDirectionMap.SINK;
        float bestDrop = 0.0f;

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;

                float neighborHeight = heightmap.heightAtLocal(x + dx, z + dz);
                float deltaHeight = neighborHeight - centerHeight;
                boolean isDownhill = deltaHeight <= -parameters.minDownhillDropBlocks();
                boolean isNonUphill = deltaHeight <= parameters.hardUphillLimitBlocks();

                if (hasDownhillCandidate) {
                    if (!isDownhill) continue;
                } else if (!isNonUphill) {
                    continue;
                }

                float score = scoreCandidate(costMap, parameters, x, z, dx, dz, deltaHeight);
                float drop = Math.max(0.0f, -deltaHeight);
                byte direction = FlowDirectionMap.directionForOffset(dx, dz);

                if (isBetter(score, drop, direction, bestScore, bestDrop, bestDirection)) {
                    bestScore = score;
                    bestDirection = direction;
                    bestDrop = drop;
                }
            }
        }

        return bestDirection == FlowDirectionMap.SINK
                ? SINK_DECISION
                : new Decision(bestDirection, bestScore, bestDrop);
    }

    private static float scoreCandidate(
            TerrainCostMap costMap,
            RiverFlowParameters parameters,
            int x,
            int z,
            int dx,
            int dz,
            float deltaHeight
    ) {
        float terrainCost = costMap.finalCostAtLocal(x + dx, z + dz);
        float uphill = Math.max(0.0f, deltaHeight);
        float downhill = Math.max(0.0f, -deltaHeight);
        boolean diagonal = dx != 0 && dz != 0;
        boolean flat = Math.abs(deltaHeight) < 0.0001f;

        return parameters.terrainCostWeight() * terrainCost
                + parameters.uphillPenalty() * uphill
                - parameters.downhillBonus() * downhill
                + parameters.slopePenalty() * Math.abs(deltaHeight)
                + (flat ? parameters.flatPenalty() : 0.0f)
                + (diagonal ? parameters.diagonalStepPenalty() : 0.0f);
    }

    private static boolean isBetter(
            float score,
            float drop,
            byte direction,
            float bestScore,
            float bestDrop,
            byte bestDirection
    ) {
        if (score < bestScore - 0.0001f) {
            return true;
        }
        if (Math.abs(score - bestScore) <= 0.0001f) {
            if (drop > bestDrop + 0.0001f) {
                return true;
            }
            return direction < bestDirection;
        }
        return false;
    }

    private static byte quantize100(float value) {
        int rounded = Math.round(Math.max(0.0f, Math.min(100.0f, value)));
        return (byte) rounded;
    }

    private record Decision(byte direction, float score, float selectedDrop) {
    }

    private record DecisionStats(
            int directedCount,
            int sinkCount,
            int edgeCount,
            float minScore,
            float maxScore,
            float maxDrop
    ) {
    }
}
