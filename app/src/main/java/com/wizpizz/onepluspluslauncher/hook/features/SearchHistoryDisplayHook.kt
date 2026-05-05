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

                        val injectHistory: () -> Unit = {
                            val container = FuzzySearchHook.searchContainerInstance
                            if (container != null) {
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
                            } else {
                                Log.d(TAG, "[SearchHistory] No container cached yet")
                            }
                        }

                        val container = FuzzySearchHook.searchContainerInstance
                        if (container != null &&
                            (container as? android.view.View)?.isAttachedToWindow == true) {
                            // Container already ready — inject immediately via post() so the
                            // first frame of the drawer animation shows history, not all apps.
                            Handler(Looper.getMainLooper()).post(injectHistory)
                            Log.d(TAG, "[SearchHistory] Container ready, injecting history immediately")
                        } else {
                            // Container not yet attached (first open) — set pending flag so
                            // onAttachedToWindow hook can pick it up, and schedule a fallback.
                            FuzzySearchHook.pendingHistoryTrigger = true
                            Log.d(TAG, "[SearchHistory] Container not ready, set pendingHistoryTrigger + scheduling fallback")
                            Handler(Looper.getMainLooper()).postDelayed(injectHistory, 400L)
                        }
                    }
                }
                Log.d(TAG, "[SearchHistory] onStateSetStart hook registered OK")
            } catch (e: Throwable) {
                Log.e(TAG, "[SearchHistory] Failed to register hook: ${e.message}")
            }
        }
    }
}
