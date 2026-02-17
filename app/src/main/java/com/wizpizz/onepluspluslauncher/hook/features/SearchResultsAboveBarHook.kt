package com.wizpizz.onepluspluslauncher.hook.features

import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.PREF_LIMIT_TWO_ROWS_SEARCH
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.TAG

object SearchResultsAboveBarHook {

    private const val SEARCH_CONTAINER_CLASS =
        "com.android.launcher3.allapps.search.LauncherTaskbarAppsSearchContainerLayout"
    private const val ARRAY_LIST_CLASS = "java.util.ArrayList"

    private const val MAX_RESULTS = 8 // 2 rows × 4 columns

    fun apply(packageParam: PackageParam) {
        packageParam.apply {
            val enabled = prefs.getBoolean(PREF_LIMIT_TWO_ROWS_SEARCH, true)
            if (!enabled) return@apply

            SEARCH_CONTAINER_CLASS.toClassOrNull(appClassLoader)?.method {
                name = "onSearchResult"
                param(String::class.java.name, ARRAY_LIST_CLASS)
            }?.hook {
                before {
                    // Limit results to 8 (2 rows)
                    val query = args[0] as? String ?: return@before
                    if (query.isBlank()) return@before

                    val results = args[1] as? ArrayList<*> ?: return@before
                    if (results.size > MAX_RESULTS) {
                        val trimmed = ArrayList(results.subList(0, MAX_RESULTS))
                        args[1] = trimmed
                        Log.d(TAG, "[SearchAboveBar] Trimmed results from ${results.size} to $MAX_RESULTS")
                    }
                }

                after {
                    val query = args[0] as? String ?: ""
                    val containerInstance = instance

                    try {
                        // Get AppsView via mAppsView field
                        val appsView = containerInstance.javaClass.field {
                            name = "mAppsView"; superClass(true)
                        }.get(containerInstance).any() ?: run {
                            Log.e(TAG, "[SearchAboveBar] mAppsView is null")
                            return@after
                        }

                        // Get the search RecyclerView
                        val searchRv = appsView.current().method {
                            name = "getActiveSearchRecyclerView"; superClass()
                        }.call() as? View

                        // Get the search bar view (SearchUiManager)
                        val searchBar = appsView.current().method {
                            name = "getSearchUiManager"; superClass()
                        }.call() as? View

                        if (searchRv == null || searchBar == null) {
                            Log.e(TAG, "[SearchAboveBar] searchRv=$searchRv, searchBar=$searchBar")
                            return@after
                        }

                        if (query.isBlank()) {
                            // Reset layout when search is cleared
                            searchRv.translationY = 0f
                            Log.d(TAG, "[SearchAboveBar] Search cleared, reset translationY")
                            return@after
                        }

                        // Post to ensure layout is complete before measuring
                        searchRv.post {
                            try {
                                // Disable clipping on parent views so the translated view is visible
                                disableClipping(searchRv)

                                val searchBarHeight = searchBar.height
                                val rvHeight = searchRv.height

                                if (searchBarHeight > 0 && rvHeight > 0) {
                                    // Move RecyclerView above the search bar
                                    val offset = -(searchBarHeight + rvHeight).toFloat()
                                    searchRv.translationY = offset
                                    Log.d(TAG, "[SearchAboveBar] Moved RV above bar: translationY=$offset (barH=$searchBarHeight, rvH=$rvHeight)")
                                } else {
                                    // Heights not ready yet, try again after a short delay
                                    searchRv.postDelayed({
                                        try {
                                            val barH = searchBar.height
                                            val rvH = searchRv.height
                                            if (barH > 0 && rvH > 0) {
                                                val offset = -(barH + rvH).toFloat()
                                                searchRv.translationY = offset
                                                Log.d(TAG, "[SearchAboveBar] Delayed move: translationY=$offset (barH=$barH, rvH=$rvH)")
                                            }
                                        } catch (e: Throwable) {
                                            Log.e(TAG, "[SearchAboveBar] Delayed move failed: ${e.message}")
                                        }
                                    }, 100L)
                                }
                            } catch (e: Throwable) {
                                Log.e(TAG, "[SearchAboveBar] Post layout failed: ${e.message}")
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "[SearchAboveBar] Error: ${e.message}", e)
                    }
                }
            } ?: Log.e(TAG, "[SearchAboveBar] Could not find onSearchResult method")
        }
    }

    /**
     * Disable clipping on the view and its parent chain so the translated view remains visible.
     */
    private fun disableClipping(view: View) {
        var current: View? = view
        while (current != null) {
            if (current is ViewGroup) {
                current.clipChildren = false
                current.clipToPadding = false
            }
            current = current.parent as? View
        }
    }
}
