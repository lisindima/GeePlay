package com.lllhys.geeplay.vehicle.geely.evcc

/**
 * Aggregates logical EVCC signals by owner.
 *
 * EVCC subscriptions are replace-all, so individual consumers must never mutate the remote
 * subscription directly. Owners can replace their own demand without affecting other sessions.
 */
internal class EvccDemandRegistry {
    private val demands = LinkedHashMap<Any, Set<EvccLogicalSignal>>()

    @Synchronized
    fun replace(owner: Any, signals: Set<EvccLogicalSignal>): Set<EvccLogicalSignal> {
        if (signals.isEmpty()) demands.remove(owner) else demands[owner] = signals.toSet()
        return currentLocked()
    }

    @Synchronized
    fun release(owner: Any): Set<EvccLogicalSignal> {
        demands.remove(owner)
        return currentLocked()
    }

    @Synchronized
    fun current(): Set<EvccLogicalSignal> = currentLocked()

    @Synchronized
    fun clear() = demands.clear()

    private fun currentLocked(): Set<EvccLogicalSignal> = buildSet {
        demands.values.forEach(::addAll)
    }
}
