package dev.serveroptimizer.monitor;

import java.time.Instant;

public record MetricsSnapshot(Instant time, double tps1m, double mspt, long heapUsed, long heapMax,
                              long nonHeapUsed, int players, int bedrockPlayers, int loadedChunks,
                              int entities, int groundItems, int mobs, int projectiles, int activeTasks,
                              double processCpu, double systemCpu, long gcCount, long gcTimeMs) {
    public double memoryPercent() { return heapMax <= 0 ? 0 : heapUsed * 100.0 / heapMax; }
}
