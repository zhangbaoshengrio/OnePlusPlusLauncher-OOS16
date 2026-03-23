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

    /**
     * Set to true by SwipeDownSearchRedirectHook when a swipe-down open is in progress.
     * FuzzySearchHook clears this and fires history as soon as the container is ready
     * (either in onAttachedToWindow on first open, or via the retry loop on subsequent opens).
     */
    @Volatile
    var pendingHistoryTrigger = false

    /**
     * Timestamp of the last swipe-down redirect. Used by the Workspace.requestChildFocus hook
     * to block icon focus assignment during the drawer-close animation (~1s window), which
     * would otherwise leave a gray focus box on a home screen icon.
     */
    @Volatile
    var lastRedirectTime = 0L

    /**
     * Cached list of all AppInfo objects from the last successful reflection lookup.
     * Reused when the live lookup fails (e.g. mAppsView not yet populated on a given call),
     * preventing history from falling back to the system's frequency sort.
     */
    @Volatile
    private var cachedAllAppInfos: List<*>? = null

    /**
     * Timestamp of the last successful history injection.
     * Used to re-inject cached history if the launcher fires a post-open onSearchResult
     * that would otherwise overwrite the history display with freq-sorted defaults.
     */
    @Volatile
    private var lastHistoryInjectedTime = 0L

    /**
     * Last successfully built history result list.
     * Re-injected within HISTORY_LOCK_WINDOW_MS of lastHistoryInjectedTime to prevent
     * the launcher's own post-open onSearchResult from reverting the display.
     */
    @Volatile
    private var lastHistoryResults: ArrayList<Any>? = null

    /** How long (ms) to hold the history display against launcher overwrites. */
    private const val HISTORY_LOCK_WINDOW_MS = 2000L

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
            // Inject history synchronously in showAllAppsFromIntent.after.
            // This runs on the main thread BEFORE any vsync, so the first frame of the
            // drawer animation already shows history — eliminating the all-apps flash.
            // This covers the reused-container case (onAttachedToWindow won't fire).
            HookUtils.LAUNCHER_CLASS.toClassOrNull(appClassLoader)?.method {
                name = "showAllAppsFromIntent"
                param(com.highcapable.yukihookapi.hook.type.java.BooleanType)
            }?.hook {
                after {
                    if (pendingHistoryTrigger) {
                        val container = searchContainerInstance
                        if (container != null &&
                            (container as? android.view.View)?.isAttachedToWindow == true) {
                            // Do NOT clear pendingHistoryTrigger here.
                            // onSearchResult.before will clear it only when history is
                            // actually built successfully — so Choreographer/retry can
                            // still fire if this call gets empty results (stale/null cache).
                            Log.d(TAG, "[FuzzySearch] Injecting history synchronously in showAllAppsFromIntent.after")
                            try {
                                container.current().method {
                                    name = "onSearchResult"
                                    param(String::class.java, java.util.ArrayList::class.java)
                                    superClass(true)
                                }.call(" ", java.util.ArrayList<Any>())
                            } catch (e: Throwable) {
                                Log.w(TAG, "[FuzzySearch] Sync inject in showAllAppsFromIntent.after failed: ${e.message}")
                            }
                        }
                        // If container is null/detached, onAttachedToWindow or Choreographer will handle it
                    }
                }
            }

            // Cache the container instance as soon as the view is attached to the window.
            // On first open this is the ONLY reliable moment to get the container reference.
            // If SwipeDownSearch already set pendingHistoryTrigger, fire history immediately here.
            SEARCH_CONTAINER_CLASS.toClassOrNull(appClassLoader)?.method {
                name = "onAttachedToWindow"
            }?.hook {
                after {
                    searchContainerInstance = instance
                    (instance as? android.view.View)?.context?.let { searchContext = it }
                    Log.d(TAG, "[FuzzySearch] Cached container on onAttachedToWindow")
                    if (pendingHistoryTrigger) {
                        // Do NOT clear pendingHistoryTrigger here — let onSearchResult.before
                        // clear it only when history is actually built successfully.
                        Log.d(TAG, "[FuzzySearch] Firing pending history on onAttachedToWindow")
                        try {
                            instance.current().method {
                                name = "onSearchResult"
                                param(String::class.java, java.util.ArrayList::class.java)
                                superClass(true)
                            }.call(" ", java.util.ArrayList<Any>())
                        } catch (e: Throwable) {
                            Log.e(TAG, "[FuzzySearch] Failed to trigger history on attach: ${e.message}")
                        }
                    }
                }
            }

            // Block workspace from receiving focus during the ~5s window after a
            // swipe-down redirect. On drawer close, Android calls requestFocus() on the
            // Workspace, which walks into descendants via onRequestFocusInDescendants →
            // handleFocusGainInternal, setting isFocused=true on an icon (gray box).
            // Blocking at requestFocus() stops the entire descent BEFORE any icon is touched.
            // NOTE: requestChildFocus fires AFTER handleFocusGainInternal (too late).
            //       requestFocus() fires BEFORE any descendant gets focus (correct level).
            // requestFocus returns boolean, so result = false is correct here.
            "com.android.launcher3.Workspace".toClassOrNull(appClassLoader)?.method {
                name = "requestFocus"
                superClass(true)
            }?.hook {
                before {
                    val elapsed = System.currentTimeMillis() - lastRedirectTime
                    if (elapsed < 5000L) {
                        Log.d(TAG, "[FuzzySearch] Blocked Workspace.requestFocus ${elapsed}ms after redirect")
                        result = false  // requestFocus returns boolean — correct type
                    }
                }
            }

            // Hook reset() on the search container — the launcher calls this after the ALL_APPS
            // animation ends to return to "idle" state, wiping our injected history.
            // Use a BEFORE hook and block the original within the history lock window so the
            // default sort never renders (preventing the history→default→history flash).
            SEARCH_CONTAINER_CLASS.toClassOrNull(appClassLoader)?.method {
                name = "reset"
                paramCount = 0
                superClass(true)
            }?.hook {
                before {
                    if (lastQueryTime > lastRedirectTime) return@before  // user is typing, allow reset
                    val elapsed = System.currentTimeMillis() - lastHistoryInjectedTime
                    if (elapsed >= HISTORY_LOCK_WINDOW_MS) return@before  // lock expired, allow reset
                    // Within history lock window — block reset() so default sort never renders
                    Log.d(TAG, "[FuzzySearch] Blocked reset() at ${elapsed}ms after history injection")
                    result = Unit  // Skip original void reset()
                    try {
                        instance.current().method {
                            name = "onSearchResult"
                            param(String::class.java, java.util.ArrayList::class.java)
                            superClass(true)
                        }.call("", java.util.ArrayList<Any>())
                    } catch (e: Throwable) {
                        Log.w(TAG, "[FuzzySearch] Re-inject in blocked reset() failed: ${e.message}")
                    }
                }
            } ?: Log.d(TAG, "[FuzzySearch] reset() not found on search container (OK)")

            // Clear stale container reference when the view is removed from the window.
            // This ensures the retry loop never calls onSearchResult on a dead instance.
            SEARCH_CONTAINER_CLASS.toClassOrNull(appClassLoader)?.method {
                name = "onDetachedFromWindow"
            }?.hook {
                after {
                    if (searchContainerInstance === instance) {
                        searchContainerInstance = null
                        Log.d(TAG, "[FuzzySearch] Container detached, cleared cache")
                    }
                }
            }

            SEARCH_CONTAINER_CLASS.toClassOrNull(appClassLoader)?.method {
                name = "onSearchResult"
                param(String::class.java.name, ARRAY_LIST_CLASS)
            }?.hook {
                before {
                    // Top-level try/catch: some YukiHookAPI versions silently swallow exceptions
                    // that propagate out of before/after blocks, making failures invisible.
                    // Wrapping here ensures we always log what went wrong.
                    try {
                        // Treat null query as blank (some OOS16 builds call onSearchResult(null,…)
                        // after the drawer animation ends to reset state; we want to intercept this
                        // and show history rather than falling through to the default sort).
                        val rawQuery = args[0] as? String ?: ""
                        val sanitizedQuery = sanitizeSearchQuery(rawQuery)
                        val incomingResultSize = (args[1] as? java.util.ArrayList<*>)?.size ?: -1
                        Log.d(TAG, "[SearchHistory] onSearchResult fired: rawQuery='$rawQuery' sanitized='$sanitizedQuery' incomingSize=$incomingResultSize")

                        // Cache container instance and context for use by SearchHistoryDisplayHook
                        searchContainerInstance = instance
                        (instance as? android.view.View)?.context?.let { searchContext = it }

                        val useRecency = try { prefs.getBoolean(PREF_SEARCH_HISTORY_RECENCY, true) } catch (_: Throwable) { true }
                        val useFrequency = try { prefs.getBoolean(PREF_SEARCH_HISTORY_FREQUENCY, false) } catch (_: Throwable) { false }

                        // Empty query: show recently launched apps if recency history is enabled
                        if (sanitizedQuery.isBlank()) {
                            val ctx = searchContext
                            if (useRecency && ctx != null) {
                                try {
                                    // Pass incoming AdapterItems so we can extract AppInfo directly
                                    // from args[1] — guaranteed populated by the launcher, no mAppsView
                                    // reflection needed. This eliminates the all-apps flash when
                                    // mAppsView isn't ready yet on the first call.
                                    val incomingItems = args[1] as? java.util.ArrayList<*>
                                    val historyResults = getRecentHistoryResults(instance, ctx, incomingItems)
                                    Log.d(TAG, "[SearchHistory] Empty query, history size=${historyResults.size}")
                                    if (historyResults.isNotEmpty()) {
                                        // Use a non-empty query so the original method renders results
                                        // (empty query causes the original method to clear/hide results)
                                        args[0] = " "
                                        args[1] = historyResults
                                        lastHistoryInjectedTime = System.currentTimeMillis()
                                        lastHistoryResults = historyResults
                                        // History actually built — clear the pending flag so
                                        // Choreographer/retry don't re-inject and cause stutter.
                                        pendingHistoryTrigger = false
                                        return@before
                                    }
                                } catch (e: Throwable) {
                                    Log.e(TAG, "[FuzzySearch] Error building history results: ${e.message}")
                                }
                                // History build returned empty or failed.
                                // If the launcher fires a post-open call shortly after our injection,
                                // re-inject the last cached results to prevent the freq-sort overwrite.
                                val stale = lastHistoryResults
                                val elapsed = System.currentTimeMillis() - lastHistoryInjectedTime
                                if (stale != null && stale.isNotEmpty() && elapsed < HISTORY_LOCK_WINDOW_MS) {
                                    Log.d(TAG, "[SearchHistory] Re-injecting cached history to block overwrite (${elapsed}ms ago)")
                                    args[0] = " "
                                    args[1] = stale
                                    return@before
                                }
                                Log.w(TAG, "[SearchHistory] FALLING THROUGH to default (blank query, history empty/failed, stale=${stale?.size}, elapsedSinceInject=${elapsed}ms)")
                            } else {
                                Log.w(TAG, "[SearchHistory] FALLING THROUGH to default (blank query, useRecency=$useRecency, ctx=${searchContext != null})")
                            }
                            return@before
                        }

                        // Non-empty query: record time for launch tracking, and clear history lock
                        lastQueryTime = System.currentTimeMillis()
                        lastHistoryInjectedTime = 0L  // user typed — allow normal search results
                        Log.d(TAG, "[SearchHistory] Query active: '$sanitizedQuery', time=$lastQueryTime")

                        val useFuzzySearch = try { prefs.getBoolean(PREF_USE_FUZZY_SEARCH, true) } catch (_: Throwable) { true }
                        if (!useFuzzySearch) return@before

                        try {
                            val sortedResults = performFuzzySearch(instance, sanitizedQuery, useRecency, useFrequency)
                            if (sortedResults.isNotEmpty()) {
                                args[1] = sortedResults
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "[FuzzySearch] Error during fuzzy search: ${e.message}")
                        }
                    } catch (e: Throwable) {
                        // Unhandled exception — log it, then try to recover with stale history
                        Log.e(TAG, "[SearchHistory] Unhandled exception in onSearchResult hook: ${e.javaClass.simpleName}: ${e.message}")
                        val stale = lastHistoryResults
                        val elapsed = System.currentTimeMillis() - lastHistoryInjectedTime
                        if (stale != null && stale.isNotEmpty() && elapsed < HISTORY_LOCK_WINDOW_MS) {
                            Log.d(TAG, "[SearchHistory] Recovering: re-injecting cached history after exception")
                            args[0] = " "
                            args[1] = stale
                        }
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
     *
     * @param incomingItems  The AdapterItem list the launcher just passed to onSearchResult.
     *   When non-null/non-empty (e.g. the launcher's initial "show all apps" call), we extract
     *   AppInfo directly from these items — guaranteed available, no mAppsView reflection needed.
     *   This prevents the all-apps flash when mAppsView isn't populated yet.
     */
    private fun PackageParam.getRecentHistoryResults(
        containerInstance: Any,
        context: android.content.Context,
        incomingItems: java.util.ArrayList<*>? = null
    ): ArrayList<Any> {
        val recentPackages = SearchHistoryManager.getRecentPackages(context)
        if (recentPackages.isEmpty()) return ArrayList()

        val appInfoClass = APP_INFO_CLASS.toClass(appClassLoader)
        val adapterItemClass = BASE_ADAPTER_ITEM_CLASS.toClass(appClassLoader)

        // Build packageName -> appInfo map, preferring incoming AdapterItems (most reliable)
        // because they are guaranteed to be populated by the launcher on every onSearchResult call.
        val appInfoByPackage = mutableMapOf<String, Any>()

        // Source 1: extract AppInfo directly from incoming AdapterItems (no extra reflection)
        if (!incomingItems.isNullOrEmpty()) {
            incomingItems.filterNotNull().forEach { item ->
                try {
                    val appInfo = item.current()
                        .field { name = "appInfo"; superClass(true) }.any()
                    if (appInfo != null && appInfoClass.isInstance(appInfo)) {
                        val pkg = extractPackageName(appInfoClass.cast(appInfo))
                        if (pkg.isNotEmpty()) appInfoByPackage[pkg] = appInfo
                    }
                } catch (_: Throwable) {}
            }
            Log.d(TAG, "[SearchHistory] Built appInfoByPackage from incoming items: ${appInfoByPackage.size} apps")
        }

        // Source 2 & 3: if incoming gave nothing, fall back to reflection + cached list
        if (appInfoByPackage.isEmpty()) {
            val allAppInfos: List<*> = run {
                val fresh = getAppsListFromContainer(containerInstance)?.let { getAllAppInfos(it) }
                if (fresh != null) {
                    if (fresh.size >= (cachedAllAppInfos?.size ?: 0)) cachedAllAppInfos = fresh
                    fresh
                } else {
                    Log.w(TAG, "[SearchHistory] Live app list unavailable, using cached (size=${cachedAllAppInfos?.size})")
                    cachedAllAppInfos
                }
            } ?: return ArrayList()

            allAppInfos.filterNotNull().forEach { appInfoObj ->
                if (appInfoClass.isInstance(appInfoObj)) {
                    val pkg = extractPackageName(appInfoClass.cast(appInfoObj))
                    if (pkg.isNotEmpty()) appInfoByPackage[pkg] = appInfoObj
                }
            }
        }

        // Also update cachedAllAppInfos if we got a good incoming list (so future fallbacks work).
        // Only update if the incoming list is at least as large (prevent partial results from
        // overwriting the full app list built from a previous complete onSearchResult call).
        if (!incomingItems.isNullOrEmpty() && appInfoByPackage.isNotEmpty()) {
            val freshInfos = incomingItems.filterNotNull().mapNotNull { item ->
                try {
                    val appInfo = item.current()
                        .field { name = "appInfo"; superClass(true) }.any()
                    if (appInfo != null && appInfoClass.isInstance(appInfo)) appInfo else null
                } catch (_: Throwable) { null }
            }
            if (freshInfos.size >= (cachedAllAppInfos?.size ?: 0)) cachedAllAppInfos = freshInfos
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
        // Only update cache if fresh list is at least as large (prevent a filtered subset
        // returned during an internal search from overwriting the full app list).
        val allAppInfos = getAllAppInfos(appsList)?.also {
            if (it.size >= (cachedAllAppInfos?.size ?: 0)) cachedAllAppInfos = it
        } ?: return ArrayList()
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
