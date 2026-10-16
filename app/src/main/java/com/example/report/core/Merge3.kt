package com.example.report.core

/**
 * Line-based three-way merge: carries changes between [base] (auto text the user started
 * from) and [theirs] (new auto text) into [mine] (the user's edited text).
 * Regions the user changed win; regions only the data changed are updated.
 */
object Merge3 {
    fun merge(base: String, mine: String, theirs: String): String {
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
            when {
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
