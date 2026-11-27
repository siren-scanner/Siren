package app.sirenscout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Different kinds of police gear whose signals rise and fall together are probably in one
 * vehicle: a body camera and an in-car router passing you at the same moment. Grouped when
 * their signal strengths correlate (Pearson r >= 0.6 over at least 8 s), and at least one of
 * them is police equipment. After RF Sentinel's patrol-vehicle detection.
 */
object PoliceCars {
    data class Group(val members: List<Detection>) {
        /** Stable while the same encounters stay grouped, to alert once. */
        val id get() = members.map { it.uid }.sorted().joinToString(",")
        val label get() = members.mapNotNull { it.signature?.label }.distinct().joinToString(" + ")
    }

    private val _groups = MutableStateFlow<List<Group>>(emptyList())
    val groups: StateFlow<List<Group>> = _groups.asStateFlow()

    private val ROLES = setOf(Category.POLICE, Category.FIRE_EMS, Category.FLEET)
    private const val BUCKET_MS = 2_000L
    private const val WINDOW_MS = 60_000L
    private const val MIN_BUCKETS = 5
    private const val MIN_SPAN_MS = 8_000L
    private const val MIN_R = 0.6

    /** When each encounter last made a police-vehicle alert. */
    private val alertedAt = HashMap<String, Long>()
    private const val REALERT_MS = 10 * 60_000L

    /**
     * Updates [groups] and returns the ones to alert on: a group alerts only if none of its members
     * alerted in the last 10 minutes, so a group that breaks up and re-forms isn't a new vehicle.
     */
    @Synchronized
    fun update(detections: Collection<Detection>, samples: (String) -> List<Detections.Sample>, now: Long): List<Group> {
        val found = find(detections, samples, now)
        _groups.value = found
        alertedAt.values.removeAll { now - it > REALERT_MS }
        val fresh = found.filter { g -> g.members.none { it.uid in alertedAt } }
        fresh.forEach { g -> g.members.forEach { alertedAt[it.uid] = now } }
        return fresh
    }

    fun find(detections: Collection<Detection>, samples: (String) -> List<Detections.Sample>, now: Long): List<Group> {
        val gear = detections.filter {
            val c = it.signature?.category
            c in ROLES && it.source != Source.RF && now - it.lastSeen < Detections.ACTIVE_WINDOW_MS
        }
        if (gear.size < 2) return emptyList()
        val series = gear.associate { d -> d.key to buckets(samples(d.key), now) }
        // Union-find over correlated pairs.
        val parent = gear.indices.toMutableList()
        fun root(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        for (i in gear.indices) for (j in i + 1 until gear.size) {
            val a = gear[i]; val b = gear[j]
            if (a.signature!!.id == b.signature!!.id) continue
            if (a.signature.category != Category.POLICE && b.signature.category != Category.POLICE) continue
            if (correlated(series.getValue(a.key), series.getValue(b.key))) parent[root(i)] = root(j)
        }
        return gear.indices.groupBy(::root).values.filter { it.size >= 2 }.map { idx -> Group(idx.map { gear[it] }) }
    }

    /** Mean signal per 2 s bucket over the last minute. */
    private fun buckets(samples: List<Detections.Sample>, now: Long): Map<Long, Double> =
        samples.filter { now - it.time <= WINDOW_MS }.groupBy { it.time / BUCKET_MS }.mapValues { (_, s) -> s.map { it.rssi }.average() }

    internal fun correlated(a: Map<Long, Double>, b: Map<Long, Double>): Boolean {
        val common = a.keys.intersect(b.keys).sorted()
        if (common.size < MIN_BUCKETS || (common.last() - common.first()) * BUCKET_MS < MIN_SPAN_MS) return false
        return pearson(common.map { a.getValue(it) }, common.map { b.getValue(it) }) >= MIN_R
    }

    internal fun pearson(x: List<Double>, y: List<Double>): Double {
        val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for (i in x.indices) { val dx = x[i] - mx; val dy = y[i] - my; sxy += dx * dy; sxx += dx * dx; syy += dy * dy }
        // Flat signals (both parked) say nothing about moving together.
        if (sxx < 1e-6 || syy < 1e-6) return 0.0
        return sxy / sqrt(sxx * syy)
    }

    @Synchronized
    fun clear() { _groups.value = emptyList(); alertedAt.clear() }
}
