package com.wizpizz.onepluspluslauncher.hook.features

import android.util.Log
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.PREF_LIMIT_TWO_ROWS_SEARCH
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.PREF_SEARCH_HISTORY_FREQUENCY
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.PREF_SEARCH_HISTORY_RECENCY
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.PREF_USE_FUZZY_SEARCH
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.TAG
import me.xdrop.fuzzywuzzy.FuzzySearch
import kotlin.math.roundToInt


object FuzzySearchHook {

    private const val SEARCH_CONTAINER_CLASS =
        "com.android.launcher3.allapps.search.LauncherTaskbarAppsSearchContainerLayout"
    private const val BASE_ADAPTER_ITEM_CLASS =
        "com.android.launcher3.allapps.BaseAllAppsAdapter\$AdapterItem"
    private const val APP_INFO_CLASS = "com.android.launcher3.model.data.AppInfo"
    private const val ARRAY_LIST_CLASS = "java.util.ArrayList"

    private const val PREFIX_MATCH_MULTIPLIER = 1.5
    private const val SUBSTRING_MATCH_MULTIPLIER = 1.3
    private const val SUBSEQUENCE_MATCH_MULTIPLIER = 1.1

    /** Timestamp of the last non-empty search query; used by SearchLaunchTrackerHook */
    @Volatile
    var lastQueryTime = 0L

    /** Launcher context captured during onSearchResult; used for history lookups */
    @Volatile
    var searchContext: android.content.Context? = null

    /** Cached search container instance; used by SearchHistoryDisplayHook to trigger history */
    @Volatile
    var searchContainerInstance: Any? = null

    data class FuzzyMatchResult(
        val appInfo: Any,
        val score: Int,
        val appName: String,
        val appNameLower: String,
        val packageName: String,
        val historyBoost: Int
    )

    fun apply(packageParam: PackageParam) {
        packageParam.apply {
            SEARCH_CONTAINER_CLASS.toClassOrNull(appClassLoader)?.method {
                name = "onSearchResult"
                param(String::class.java.name, ARRAY_LIST_CLASS)
            }?.hook {
                before {
                    val rawQuery = args[0] as? String ?: return@before
                    val sanitizedQuery = sanitizeSearchQuery(rawQuery)

                    // Cache container instance and context for use by SearchHistoryDisplayHook
                    searchContainerInstance = instance
                    (instance as? android.view.View)?.context?.let { searchContext = it }

                    val useRecency = prefs.getBoolean(PREF_SEARCH_HISTORY_RECENCY, true)
                    val useFrequency = prefs.getBoolean(PREF_SEARCH_HISTORY_FREQUENCY, false)

                    // Empty query: show recently launched apps if recency history is enabled
                    if (sanitizedQuery.isBlank()) {
                        val ctx = searchContext
                        if (useRecency && ctx != null) {
                            try {
                                val historyResults = getRecentHistoryResults(instance, ctx)
                                Log.d(TAG, "[SearchHistory] Empty query, history size=${historyResults.size}")
                                if (historyResults.isNotEmpty()) {
                                    // Use a non-empty query so the original method renders results
                                    // (empty query causes the original method to clear/hide results)
                                    args[0] = " "
                                    args[1] = historyResults
                                    return@before
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "[FuzzySearch] Error building history results: ${e.message}")
                            }
                        }
                        return@before
                    }

                    // Non-empty query: record time for launch tracking
                    lastQueryTime = System.currentTimeMillis()
                    Log.d(TAG, "[SearchHistory] Query active: '$sanitizedQuery', time=$lastQueryTime")

                    val useFuzzySearch = prefs.getBoolean(PREF_USE_FUZZY_SEARCH, true)
                    if (!useFuzzySearch) return@before

                    try {
                        val sortedResults = performFuzzySearch(instance, sanitizedQuery, useRecency, useFrequency)
                        if (sortedResults.isNotEmpty()) {
                            args[1] = sortedResults
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "[FuzzySearch] Error during fuzzy search: ${e.message}")
                    }
                }
            } ?: Log.e(TAG, "[FuzzySearch] Could not find onSearchResult method")
        }
    }

    private fun sanitizeSearchQuery(input: String): String {
        if (input.isEmpty()) return input
        val builder = StringBuilder(input.length)
        input.forEach { ch ->
            if (ch != ' ' && ch != '\'') builder.append(ch)
        }
        return builder.toString()
    }

    /**
     * When no query is typed, return recently launched apps in recency order.
     */
    private fun PackageParam.getRecentHistoryResults(
        containerInstance: Any,
        context: android.content.Context
    ): ArrayList<Any> {
        val recentPackages = SearchHistoryManager.getRecentPackages(context)
        if (recentPackages.isEmpty()) return ArrayList()

        val appsList = getAppsListFromContainer(containerInstance) ?: return ArrayList()
        val allAppInfos = getAllAppInfos(appsList) ?: return ArrayList()

        val appInfoClass = APP_INFO_CLASS.toClass(appClassLoader)
        val adapterItemClass = BASE_ADAPTER_ITEM_CLASS.toClass(appClassLoader)

        // Build packageName -> appInfo map
        val appInfoByPackage = mutableMapOf<String, Any>()
        allAppInfos.filterNotNull().forEach { appInfoObj ->
            if (appInfoClass.isInstance(appInfoObj)) {
                val pkg = extractPackageName(appInfoClass.cast(appInfoObj))
                if (pkg.isNotEmpty()) appInfoByPackage[pkg] = appInfoObj
            }
        }

        // Return apps in recency order, up to 2 rows (8 items)
        val result = ArrayList<Any>()
        for (pkgName in recentPackages) {
            val appInfo = appInfoByPackage[pkgName] ?: continue
            try {
                val adapterItem = adapterItemClass.method {
                    name = "asApp"
                    param(appInfoClass)
                    modifiers { isStatic }
                }.get().call(appInfo)
                if (adapterItem != null) {
                    result.add(adapterItem)
                    if (result.size >= 8) break
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[FuzzySearch] Error building history item for $pkgName: ${e.message}")
            }
        }
        return result
    }

    private fun PackageParam.performFuzzySearch(
        containerInstance: Any,
        query: String,
        useRecency: Boolean,
        useFrequency: Boolean
    ): ArrayList<Any> {
        val appsList = getAppsListFromContainer(containerInstance) ?: return ArrayList()
        val allAppInfos = getAllAppInfos(appsList) ?: return ArrayList()
        val scoredResults = scoreSearchResults(allAppInfos, query, useRecency, useFrequency)
        return convertToAdapterItems(scoredResults, query)
    }

    private fun getAppsListFromContainer(containerInstance: Any): Any? {
        return try {
            val appsViewField =
                containerInstance.javaClass.field { name = "mAppsView"; superClass(true) }
            val appsViewInstance = appsViewField.get(containerInstance).any() ?: return null

            appsViewInstance.current().method { name = "getAlphabeticalAppsList"; superClass() }
                .call()
                ?: appsViewInstance.current().method { name = "getAppsList"; superClass() }.call()
                ?: appsViewInstance.current().method { name = "getApps"; superClass() }.call()
        } catch (e: Throwable) {
            Log.e(TAG, "[FuzzySearch] Failed to get apps list: ${e.message}")
            null
        }
    }

    private fun getAllAppInfos(appsList: Any): List<*>? {
        return try {
            appsList.current().method {
                name = "getApps"
                superClass(true)
            }.call() as? List<*>
        } catch (e: Throwable) {
            try {
                val allAppsStore =
                    appsList.current().method { name = "getAllAppsStore"; superClass(true) }.call()
                allAppsStore?.current()?.method { name = "getApps"; superClass(true) }
                    ?.call() as? List<*>
            } catch (e2: Throwable) {
                Log.e(TAG, "[FuzzySearch] Failed to get app infos: ${e2.message}")
                null
            }
        }
    }

    private fun PackageParam.scoreSearchResults(
        appInfos: List<*>,
        query: String,
        useRecency: Boolean,
        useFrequency: Boolean
    ): List<FuzzyMatchResult> {
        val scoredResults = ArrayList<FuzzyMatchResult>()
        val appInfoClass = APP_INFO_CLASS.toClass(appClassLoader)
        val queryLower = query.lowercase()
        val context = searchContext

        appInfos.filterNotNull().forEach { appInfoObj ->
            try {
                if (!appInfoClass.isInstance(appInfoObj)) return@forEach

                val appInfo = appInfoClass.cast(appInfoObj)
                val titleField = appInfo?.javaClass?.field { name = "title"; superClass(true) }
                val appName = titleField?.get(appInfo)?.any()?.toString() ?: ""
                val appNameLower = appName.lowercase()
                val packageName = extractPackageName(appInfo)
                val score = calculateMatchScore(appNameLower, queryLower)

                val historyBoost = if (context != null && packageName.isNotEmpty()) {
                    val recency = if (useRecency) SearchHistoryManager.getRecencyBoost(context, packageName) else 0
                    val frequency = if (useFrequency) SearchHistoryManager.getFrequencyBoost(context, packageName) else 0
                    recency + frequency
                } else 0

                appInfo?.let {
                    FuzzyMatchResult(it, score, appName, appNameLower, packageName, historyBoost)
                }?.let { scoredResults.add(it) }
            } catch (e: Throwable) {
                Log.e(TAG, "[FuzzySearch] Error processing app: ${e.message}")
            }
        }

        return scoredResults
    }

    private fun extractPackageName(appInfo: Any?): String {
        if (appInfo == null) return ""

        // Try getIntent() method first (most reliable, avoids field name differences across OOS versions)
        try {
            val intent = appInfo.current().method { name = "getIntent"; superClass() }.call()
                as? android.content.Intent
            val pkg = intent?.component?.packageName ?: intent?.`package`
            if (!pkg.isNullOrEmpty()) {
                Log.d(TAG, "[SearchHistory] extractPackageName via getIntent: $pkg")
                return pkg
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[SearchHistory] getIntent failed: ${e.message}")
        }

        // Fallback: walk the class hierarchy with standard Java reflection (no YukiHookAPI logging)
        val fieldNames = listOf("componentName", "mComponentName")
        var clazz: Class<*>? = appInfo.javaClass
        while (clazz != null) {
            for (fieldName in fieldNames) {
                try {
                    val f = clazz.getDeclaredField(fieldName)
                    f.isAccessible = true
                    val pkg = (f.get(appInfo) as? android.content.ComponentName)?.packageName
                    if (!pkg.isNullOrEmpty()) return pkg
                } catch (_: NoSuchFieldException) {}
            }
            clazz = clazz.superclass
        }

        return ""
    }

    private fun calculateMatchScore(appNameLower: String, queryLower: String): Int {
        val baseScore = try {
            FuzzySearch.weightedRatio(appNameLower, queryLower)
        } catch (t: Throwable) {
            0
        }

        val multiplier = when {
            queryLower.isEmpty() -> 1.0
            appNameLower.startsWith(queryLower) -> PREFIX_MATCH_MULTIPLIER
            appNameLower.contains(queryLower) -> SUBSTRING_MATCH_MULTIPLIER
            isSubsequence(appNameLower, queryLower) -> SUBSEQUENCE_MATCH_MULTIPLIER
            else -> 1.0
        }

        return (baseScore * multiplier).roundToInt()
    }

    private fun isSubsequence(text: String, pattern: String): Boolean {
        if (pattern.isEmpty()) return true
        var textIndex = 0
        var patternIndex = 0
        while (textIndex < text.length && patternIndex < pattern.length) {
            if (text[textIndex] == pattern[patternIndex]) {
                patternIndex++
            }
            textIndex++
        }
        return patternIndex == pattern.length
    }

    private fun PackageParam.convertToAdapterItems(
        scoredResults: List<FuzzyMatchResult>,
        query: String
    ): ArrayList<Any> {
        val queryLower = query.lowercase()

        val sortedResults = scoredResults.sortedWith(
            compareByDescending<FuzzyMatchResult> {
                when {
                    it.appNameLower == queryLower -> 3
                    it.appNameLower.startsWith(queryLower) -> 2
                    it.appNameLower.contains(queryLower) -> 1
                    else -> 0
                }
            }.thenByDescending { it.score + it.historyBoost }
        )

        val finalAdapterItems = ArrayList<Any>()
        val adapterItemClass = BASE_ADAPTER_ITEM_CLASS.toClass(appClassLoader)
        val appInfoClass = APP_INFO_CLASS.toClass(appClassLoader)

        val limitTwoRows = prefs.getBoolean(PREF_LIMIT_TWO_ROWS_SEARCH, false)
        val resultsToShow = if (limitTwoRows) sortedResults.take(8) else sortedResults

        resultsToShow.forEach { result ->
            try {
                val adapterItem = adapterItemClass.method {
                    name = "asApp"
                    param(appInfoClass)
                    modifiers { isStatic }
                }.get().call(result.appInfo)

                if (adapterItem != null) {
                    finalAdapterItems.add(adapterItem)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "[FuzzySearch] Error converting ${result.appName}: ${e.message}")
            }
        }

        return finalAdapterItems
    }
}
