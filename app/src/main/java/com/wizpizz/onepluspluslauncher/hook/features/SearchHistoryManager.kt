package com.wizpizz.onepluspluslauncher.hook.features

import android.content.Context
import kotlin.math.ln
import kotlin.math.min

/**
 * Persists search-launch history in the launcher process.
 * Supports two ranking modes:
 *  - Recency: most recently launched app ranks highest
 *  - Frequency: most frequently launched app ranks highest
 * Both can be enabled simultaneously for a combined boost.
 */
object SearchHistoryManager {

    private const val PREFS_NAME = "opp_search_history"

    // Recency: ordered list of recently launched packages, most recent first
    private const val KEY_RECENT_ORDER = "recent_order"
    private const val MAX_RECENT = 20
    private const val RECENCY_BOOST_MAX = 60     // position 0 → 60, position 1 → 57, ...
    private const val RECENCY_BOOST_STEP = 3

    // Frequency: launch count per package
    private const val KEY_PREFIX_COUNT = "cnt_"
    private const val FREQUENCY_BOOST_MAX = 25  // 1→10, 2→13, 4→17, 8→20, 16→23, max=25

    fun recordLaunch(context: Context, packageName: String) {
        if (packageName.isEmpty()) return
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = sp.edit()

        // Update recency list
        val recentList = getRecentList(sp)
        val updatedList = (listOf(packageName) + recentList.filter { it != packageName })
            .take(MAX_RECENT)
        editor.putString(KEY_RECENT_ORDER, updatedList.joinToString(","))

        // Update frequency count
        val count = sp.getInt(KEY_PREFIX_COUNT + packageName, 0)
        editor.putInt(KEY_PREFIX_COUNT + packageName, count + 1)

        editor.apply()
    }

    /** Boost based on recency: most recently launched = highest score */
    fun getRecencyBoost(context: Context, packageName: String): Int {
        if (packageName.isEmpty()) return 0
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val index = getRecentList(sp).indexOf(packageName)
        if (index < 0) return 0
        return maxOf(0, RECENCY_BOOST_MAX - index * RECENCY_BOOST_STEP)
    }

    /** Boost based on frequency: more launches = higher score (logarithmic) */
    fun getFrequencyBoost(context: Context, packageName: String): Int {
        if (packageName.isEmpty()) return 0
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val count = sp.getInt(KEY_PREFIX_COUNT + packageName, 0)
        if (count == 0) return 0
        return min(FREQUENCY_BOOST_MAX, (10 * ln(count.toDouble() + 1) / ln(2.0)).toInt())
    }

    fun getRecentPackages(context: Context): List<String> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return getRecentList(sp)
    }

    private fun getRecentList(sp: android.content.SharedPreferences): List<String> {
        val raw = sp.getString(KEY_RECENT_ORDER, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(",").filter { it.isNotEmpty() }
    }
}
