package com.marketdata.service.concurrency;

import java.util.Map;

public class SymbolRouter {
    private final Map<String, Integer> table;
    private final int workerCount;

    public SymbolRouter(Map<String, Integer> table, int workerCount) {
        if (workerCount <= 0) throw new IllegalArgumentException("workerCount must be positive");
        for (Integer index : table.values()) {
            if (index < 0 || index >= workerCount) {
                throw new IllegalArgumentException("Invalid worker index: " + index);
            }
        }
        this.table = Map.copyOf(table);
        this.workerCount = workerCount;
    }

    public int workerFor(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Symbol is missing, cannot route quote");
        }
        Integer workerIndex = this.table.get(symbol);
        if (workerIndex == null) {
            return Math.floorMod(symbol.hashCode(), workerCount);
        }
        return workerIndex;
    }
}
