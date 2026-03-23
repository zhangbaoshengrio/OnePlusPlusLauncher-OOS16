package com.wizpizz.onepluspluslauncher.hook.features

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.LAUNCHER_CLASS
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.LAUNCHER_STATE_CLASS
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.PREF_SEARCH_HISTORY_RECENCY
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.TAG

object SearchHistoryDisplayHook {

    fun apply(packageParam: PackageParam) {
        packageParam.apply {
            Log.d(TAG, "[SearchHistory] Registering onStateSetStart hook...")
            try {
                LAUNCHER_CLASS.toClass(appClassLoader).method {
                    name = "onStateSetStart"
                    param(LAUNCHER_STATE_CLASS.toClass(appClassLoader))
                }.hook {
                    after {
                        val targetState = args[0]
                        Log.d(TAG, "[SearchHistory] onStateSetStart fired, state=$targetState")

                        val allAppsState = try {
                            LAUNCHER_STATE_CLASS.toClass(appClassLoader)
                                .field { name = "ALL_APPS" }.get().any()
                        } catch (e: Throwable) {
                            Log.e(TAG, "[SearchHistory] Failed to get ALL_APPS: ${e.message}")
                            null
                        } ?: return@after

                        Log.d(TAG, "[SearchHistory] match=${targetState == allAppsState}")
                        if (targetState == allAppsState) {
                            HookUtils.drawerOpenTime = System.currentTimeMillis()
                        } else {
                            HookUtils.drawerCloseTime = System.currentTimeMillis()
                            return@after
                        }

                        val useRecency = prefs.getBoolean(PREF_SEARCH_HISTORY_RECENCY, true)
                        if (!useRecency) return@after

                        Handler(Looper.getMainLooper()).postDelayed({
                            val container = FuzzySearchHook.searchContainerInstance
                            if (container == null) {
                                Log.d(TAG, "[SearchHistory] No container cached yet")
                                return@postDelayed
                            }
                            try {
                                container.current().method {
                                    name = "onSearchResult"
                                    param(String::class.java, java.util.ArrayList::class.java)
                                    superClass(true)
                                }.call("", java.util.ArrayList<Any>())
                                Log.d(TAG, "[SearchHistory] Triggered history display on drawer open")
                            } catch (e: Throwable) {
                                Log.e(TAG, "[SearchHistory] Failed to trigger history: ${e.message}")
                            }
                        }, 400L)
                    }
                }
                Log.d(TAG, "[SearchHistory] onStateSetStart hook registered OK")
            } catch (e: Throwable) {
                Log.e(TAG, "[SearchHistory] Failed to register hook: ${e.message}")
            }
        }
    }
}
