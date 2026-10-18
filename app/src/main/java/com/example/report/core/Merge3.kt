package com.example.report.core

/**
 * Line-based three-way merge: carries changes between [base] (auto text the user started
 * from) and [theirs] (new auto text) into [mine] (the user's edited text).
 * Regions the user changed win; regions only the data changed are updated.
 */
object Merge3 {
    /** [countAware]: the same line changed on both sides only in its "×N" → counts are added up. */
    fun merge(base: String, mine: String, theirs: String, countAware: Boolean = false): String {
        if (base == theirs) return mine
        if (base == mine) return theirs
        val b = base.split("\n")
        val m = mine.split("\n")
        val t = theirs.split("\n")
        val bm = lcsMap(b, m)
        val bt = lcsMap(b, t)
        val out = ArrayList<String>()
        var pb = 0; var pm = 0; var pt = 0
        var i = 0
        while (true) {
            // next base line kept in both
            while (i < b.size && (bm[i] < 0 || bt[i] < 0)) i++
            val nb = i
            val nm = if (i < b.size) bm[i] else m.size
            val nt = if (i < b.size) bt[i] else t.size
            val cb = b.subList(pb, nb)
            val cm = m.subList(pm, nm)
            val ct = t.subList(pt, nt)
            val summed = if (countAware) sumCounts(cb, cm, ct) else null
            when {
                summed != null -> out += summed
                cm == cb -> out.addAll(ct)
                ct == cb -> out.addAll(cm)
                cm == ct -> out.addAll(cm)
                else -> { out.addAll(cm); for (x in ct) if (x !in cm && x !in cb) out.add(x) }
            }
            if (i >= b.size) break
            out.add(m[nm])
            pb = nb + 1; pm = nm + 1; pt = nt + 1
            i++
        }
        return out.joinToString("\n")
    }

    /**
     * Flight edited on two devices from the same [base]: each field takes the side that changed it;
     * the comment is merged line by line (both sides' new lines kept, "×N" counts added up).
     */
    fun mergeFlight(base: com.example.report.data.Flight?, hub: com.example.report.data.Flight, cli: com.example.report.data.Flight): com.example.report.data.Flight {
        val b = base ?: hub.copy(c = "", lt = null, mgrs = "", x = emptyMap(), rep = null)
        fun <T> pick(bv: T, hv: T, cv: T): T = if (cv != bv) cv else hv
        val x = LinkedHashMap(hub.x)
        for (k in (b.x.keys + cli.x.keys)) {
            val bv = b.x[k]; val cv = cli.x[k]
            if (cv != bv) { if (cv == null) x.remove(k) else x[k] = cv }
        }
        return hub.copy(
            c = merge(b.c, hub.c, cli.c, countAware = true),
            lt = if (base == null) (cli.lt ?: hub.lt) else pick(b.lt, hub.lt, cli.lt),
            mgrs = if (base == null) cli.mgrs.ifEmpty { hub.mgrs } else pick(b.mgrs, hub.mgrs, cli.mgrs),
            rep = pick(b.rep, hub.rep, cli.rep),
            x = x,
            u = maxOf(hub.u, cli.u),
        )
    }

    private val CNT = Regex("\\s*[×xX](\\d+)\\s*$")
    private fun cnt(s: String) = CNT.find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    private fun bare(s: String) = CNT.replace(s, "").trimEnd()

    /** One line each side, same text apart from "×N", both sides changed it → base + both increments. */
    private fun sumCounts(b: List<String>, m: List<String>, t: List<String>): String? {
        if (b.size != 1 || m.size != 1 || t.size != 1) return null
        if (m[0] == b[0] || t[0] == b[0]) return null
        val k = bare(b[0])
        if (bare(m[0]) != k || bare(t[0]) != k) return null
        val n = cnt(m[0]) + cnt(t[0]) - cnt(b[0])
        return if (n > 1) "$k ×$n" else k
    }

    /** For each line of [a]: index of the matching line in [c] by LCS, or -1. */
    private fun lcsMap(a: List<String>, c: List<String>): IntArray {
        val n = a.size; val k = c.size
        val dp = Array(n + 1) { IntArray(k + 1) }
        for (x in n - 1 downTo 0) for (y in k - 1 downTo 0)
            dp[x][y] = if (a[x] == c[y]) dp[x + 1][y + 1] + 1 else maxOf(dp[x + 1][y], dp[x][y + 1])
        val map = IntArray(n) { -1 }
        var x = 0; var y = 0
        while (x < n && y < k) {
            when {
                a[x] == c[y] -> { map[x] = y; x++; y++ }
                dp[x + 1][y] >= dp[x][y + 1] -> x++
                else -> y++
            }
        }
        return map
    }
}
