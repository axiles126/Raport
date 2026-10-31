package com.example.report.core

import com.example.report.data.DroneIn

/**
 * Drone list sync: entries are merged one by one (key = takeoff-time + kind), the newer [DroneIn.u]
 * wins, and deletions travel as tombstones (key → deletion time). A device that missed an update
 * no longer overwrites the whole list. Pure — unit-tested.
 */
object DroneSync {
    const val TOMB_KEEP_MS = 30L * 24 * 3600 * 1000

    fun key(d: DroneIn) = "${d.t}:${d.kind}"

    fun mergeTombs(a: Map<String, Long>, b: Map<String, Long>, now: Long): Map<String, Long> {
        val m = HashMap(a)
        for ((k, v) in b) if ((m[k] ?: 0L) < v) m[k] = v
        return m.filterValues { now - it < TOMB_KEEP_MS }
    }

    fun merge(a: List<DroneIn>, b: List<DroneIn>, tombs: Map<String, Long>): List<DroneIn> {
        val m = LinkedHashMap<String, DroneIn>()
        for (d in a) m[key(d)] = d
        for (d in b) { val x = m[key(d)]; if (x == null || d.u > x.u) m[key(d)] = d }
        return m.values.filter { d -> tombs[key(d)]?.let { it < d.u } ?: true }.sortedBy { it.t }
    }

    /**
     * Local edit: entries that changed since [prev] get u = [now]; entries gone from the list
     * get a tombstone. Returns (stamped list, new tombstones).
     */
    fun stamp(prev: List<DroneIn>, cur: List<DroneIn>, tombs: Map<String, Long>, now: Long): Pair<List<DroneIn>, Map<String, Long>> {
        val pm = prev.associateBy { key(it) }
        val out = cur.map { d -> val p = pm[key(d)]; if (p == null || p.copy(u = 0) != d.copy(u = 0)) d.copy(u = now) else d }
        val keys = out.map { key(it) }.toSet()
        val t = HashMap(tombs)
        for (k in pm.keys) if (k !in keys) t[k] = now
        for (k in keys) t.remove(k)
        return out to t
    }
}
