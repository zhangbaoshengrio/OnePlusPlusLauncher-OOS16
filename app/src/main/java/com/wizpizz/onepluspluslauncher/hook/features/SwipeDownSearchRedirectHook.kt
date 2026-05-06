package com.wizpizz.onepluspluslauncher.hook.features

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.type.android.BundleClass
import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.PREF_AUTO_FOCUS_SWIPE_DOWN_REDIRECT
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.PREF_SWIPE_DOWN_SEARCH_REDIRECT
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.TAG

/**
 * Hook to redirect swipe down global search to app drawer
 */
object SwipeDownSearchRedirectHook {

    fun apply(packageParam: PackageParam) {
        packageParam.apply {
            "com.android.launcher.touch.WorkspacePullDownDetectController".toClassOrNull(appClassLoader)?.method {
                name = "showSearchBar"
                paramCount = 5
                returnType = BooleanType
            }?.hook {
                before {
                    if (prefs.getBoolean(PREF_SWIPE_DOWN_SEARCH_REDIRECT, true)) {
                        Log.d(TAG, "[SwipeDownSearch] Intercepting showSearchBar, redirecting to app drawer")

                        HookUtils.setRedirectInProgress(true)

                        val launcher = args[0]
                        val success = launcher?.let { openAppDrawer(it) }

                        if (success == true) {
                            cleanupPullDownAnimation(instance, launcher)
                            HookUtils.setRedirectInProgress(false)

                            if (prefs.getBoolean(PREF_AUTO_FOCUS_SWIPE_DOWN_REDIRECT, true)) {
                                val launcherRef = launcher
                                val loader = appClassLoader
                                if (launcherRef != null && loader != null) {
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        HookUtils.focusSearchInput(launcherRef, loader)
                                    }, 300L)
                                }
                            }

                            result = false
                        } else {
                            Log.d(TAG, "[SwipeDownSearch] Failed to open app drawer, allowing original behavior")
                            HookUtils.setRedirectInProgress(false)
                        }
                    } else {
                        Log.d(TAG, "[SwipeDownSearch] Feature disabled, allowing original behavior")
                    }
                }
            } ?: Log.e(TAG, "[SwipeDownSearch] Failed to find WorkspacePullDownDetectController.showSearchBar method")
        }
    }

    private fun PackageParam.cleanupPullDownAnimation(controllerInstance: Any, launcherInstance: Any) {
        try {
            val pullDownAnimator = controllerInstance.current().method {
                name = "getPullDownAnimator"
                superClass()
            }.call()

            if (pullDownAnimator != null) {
                try {
                    pullDownAnimator.current().method {
                        name = "destroyAnimAsHomeGesture"
                        superClass()
                    }.call()
                    Log.d(TAG, "[SwipeDownSearch] Successfully called destroyAnimAsHomeGesture")
                } catch (e: Throwable) {
                    pullDownAnimator.current().method {
                        name = "destroyAnimatorIfNeeded"
                        superClass()
                    }.call()
                }
            }

            try {
                val blurController = launcherInstance.current().method {
                    name = "getBlurScrimWindowController"
                    superClass()
                }.call()

                if (blurController != null) {
                    try {
                        blurController.current().method {
                            name = "blurBackground"
                            param(IntType)
                            superClass()
                        }.call(0)
                    } catch (e: Throwable) {}

                    try {
                        blurController.current().method {
                            name = "detach"
                            superClass()
                        }.call()
                    } catch (e: Throwable) {}
                }
            } catch (e: Throwable) {}
        } catch (e: Throwable) {
            Log.e(TAG, "[SwipeDownSearch] Error during animation cleanup: ${e.message}")
        }
    }

    private fun PackageParam.openAppDrawer(launcherInstance: Any): Boolean {
        return try {
            try {
                launcherInstance.current().method {
                    name = "showAllAppsFromIntent"
                    param(BooleanType)
                }.call(true)
                Log.d(TAG, "[SwipeDownSearch] Called showAllAppsFromIntent successfully")
                true
            } catch (e: Throwable) {
                try {
                    val launcherContext = launcherInstance as? android.content.Context ?: return false
                    "com.android.launcher3.taskbar.TaskbarUtils".toClass(appClassLoader).method {
                        name = "showAllApps"
                        param(launcherContext.javaClass)
                        modifiers { isStatic }
                    }.get().call(launcherContext)
                    Log.d(TAG, "[SwipeDownSearch] Called TaskbarUtils.showAllApps successfully")
                    true
                } catch (e2: Throwable) {
                    Log.e(TAG, "[SwipeDownSearch] TaskbarUtils.showAllApps also failed: ${e2.message}")
                    false
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "[SwipeDownSearch] Error opening app drawer: ${e.message}")
            false
        }
    }
}
