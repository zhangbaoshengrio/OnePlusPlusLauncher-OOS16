package com.wizpizz.onepluspluslauncher.hook.features

import android.util.Log
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.LAUNCHER_CLASS
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.TAG

/**
 * Hooks startActivitySafely in the launcher to record app launches
 * that happen shortly after a search query was active.
 * Uses a 5-second window after the last search query to capture taps on results,
 * even if the launcher clears the query before calling startActivitySafely.
 */
object SearchLaunchTrackerHook {

    private const val SEARCH_LAUNCH_WINDOW_MS = 30_000L

    fun apply(packageParam: PackageParam) {
        packageParam.apply {
            LAUNCHER_CLASS.toClassOrNull(appClassLoader)?.method {
                name = "startActivitySafely"
                paramCount = 3
                superClass()
            }?.hook {
                before {
                    // Only record if launched within 5 seconds of the last search query
                    val elapsed = System.currentTimeMillis() - FuzzySearchHook.lastQueryTime
                    if (elapsed > SEARCH_LAUNCH_WINDOW_MS) return@before

                    val intent = args[1] as? android.content.Intent ?: return@before
                    val packageName = intent.component?.packageName
                        ?: intent.`package`
                        ?: return@before

                    val context = instance as? android.content.Context ?: return@before
                    SearchHistoryManager.recordLaunch(context, packageName)
                    Log.d(TAG, "[SearchHistory] Recorded launch: $packageName (elapsed ${elapsed}ms)")
                }
            } ?: Log.e(TAG, "[SearchHistory] Could not find startActivitySafely method")
        }
    }
}
