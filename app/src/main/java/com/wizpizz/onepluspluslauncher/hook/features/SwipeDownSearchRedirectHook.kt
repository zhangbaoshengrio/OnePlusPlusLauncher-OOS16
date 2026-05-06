package com.wizpizz.onepluspluslauncher.hook.features

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.DecelerateInterpolator
import com.highcapable.yukihookapi.hook.factory.current

import com.highcapable.yukihookapi.hook.factory.method
import com.highcapable.yukihookapi.hook.param.PackageParam

import com.highcapable.yukihookapi.hook.type.java.BooleanType
import com.highcapable.yukihookapi.hook.type.java.IntType
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.LAUNCHER_CLASS
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.PREF_AUTO_FOCUS_SWIPE_DOWN_REDIRECT
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.PREF_SEARCH_HISTORY_RECENCY
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
                        Log.d(TAG, "[SwipeDownSearch] Intercepting showSearchBar")

                        HookUtils.setRedirectInProgress(true)

                        val launcher = args[0]

                        // Activate system blur scrim as full-screen blur cover.
                        val blurController: Any? = try {
                            launcher?.current()?.method {
                                name = "getBlurScrimWindowController"
                                superClass()
                            }?.call()
                        } catch (_: Throwable) { null }

                        if (blurController != null) {
                            try {
                                blurController.current().method {
                                    name = "blurBackground"
                                    param(IntType)
                                    superClass()
                                }.call(100) // max blur radius
                                Log.d(TAG, "[SwipeDownSearch] Activated blur scrim (radius=100)")
                            } catch (_: Throwable) {}
                        }

                        val success = launcher?.let { openAppDrawer(it) }

                        if (success == true) {
                            FuzzySearchHook.lastRedirectTime = System.currentTimeMillis()
                            HookUtils.drawerOpenTime = System.currentTimeMillis()

                            cleanupPullDownAnimation(instance, launcher)
                            HookUtils.setRedirectInProgress(false)

                            // T=300ms: animate blur radius 100 → 0 to reveal search UI.
                            if (blurController != null) {
                                Handler(Looper.getMainLooper()).postDelayed({
                                    val fadeOut = ValueAnimator.ofFloat(1f, 0f)
                                    fadeOut.duration = 250L
                                    fadeOut.interpolator = DecelerateInterpolator()
                                    fadeOut.addUpdateListener { anim ->
                                        val fraction = anim.animatedValue as Float
                                        try {
                                            val radius = (100 * fraction).toInt()
                                            blurController.current().method {
                                                name = "blurBackground"
                                                param(IntType)
                                                superClass()
                                            }.call(radius)
                                        } catch (_: Throwable) {}
                                    }
                                    fadeOut.addListener(object : android.animation.AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: android.animation.Animator) {
                                            try {
                                                blurController.current().method {
                                                    name = "blurBackground"
                                                    param(IntType)
                                                    superClass()
                                                }.call(0)
                                            } catch (_: Throwable) {}
                                            try {
                                                blurController.current().method {
                                                    name = "detach"
                                                    superClass()
                                                }.call()
                                            } catch (_: Throwable) {}
                                            Log.d(TAG, "[SwipeDownSearch] Blur scrim cleared")
                                        }
                                    })
                                    fadeOut.start()
                                }, 300L)

                                // Safety fallback.
                                Handler(Looper.getMainLooper()).postDelayed({
                                    try {
                                        blurController.current().method {
                                            name = "blurBackground"
                                            param(IntType)
                                            superClass()
                                        }.call(0)
                                    } catch (_: Throwable) {}
                                    try {
                                        blurController.current().method {
                                            name = "detach"
                                            superClass()
                                        }.call()
                                    } catch (_: Throwable) {}
                                }, 1500L)
                            }

                            // Let system auto-focus handle keyboard naturally.
                            // No manual focusSearchInput — avoids keyboard bounce.

                            // Trigger history display.
                            if (prefs.getBoolean(PREF_SEARCH_HISTORY_RECENCY, true)) {
                                val triggerHistory = {
                                    val container = FuzzySearchHook.searchContainerInstance
                                    if (container != null) {
                                        try {
                                            container.current().method {
                                                name = "onSearchResult"
                                                param(String::class.java, java.util.ArrayList::class.java)
                                                superClass(true)
                                            }.call(" ", java.util.ArrayList<Any>())
                                            Log.d(TAG, "[SwipeDownSearch] Triggered history display")
                                        } catch (e: Throwable) {
                                            Log.e(TAG, "[SwipeDownSearch] Failed to trigger history: ${e.message}")
                                        }
                                    }
                                }
                                Handler(Looper.getMainLooper()).postDelayed({
                                    if (FuzzySearchHook.searchContainerInstance != null) {
                                        triggerHistory()
                                    } else {
                                        Handler(Looper.getMainLooper()).postDelayed(triggerHistory, 450L)
                                    }
                                }, 50L)
                            }

                            result = false
                        } else {
                            Log.d(TAG, "[SwipeDownSearch] Failed to open drawer")
                            // Clear blur on failure.
                            if (blurController != null) {
                                try {
                                    blurController.current().method {
                                        name = "blurBackground"
                                        param(IntType)
                                        superClass()
                                    }.call(0)
                                } catch (_: Throwable) {}
                                try {
                                    blurController.current().method {
                                        name = "detach"
                                        superClass()
                                    }.call()
                                } catch (_: Throwable) {}
                            }
                            HookUtils.setRedirectInProgress(false)
                        }
                    }
                }
            } ?: Log.e(TAG, "[SwipeDownSearch] Failed to find showSearchBar method")
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
                } catch (e: Throwable) {
                    pullDownAnimator.current().method {
                        name = "destroyAnimatorIfNeeded"
                        superClass()
                    }.call()
                }
            }

            // Note: blur scrim cleanup is handled by the fade-out animation,
            // not here — clearing it early would expose the flash.
        } catch (e: Throwable) {
            Log.e(TAG, "[SwipeDownSearch] Cleanup error: ${e.message}")
        }
    }

    private fun PackageParam.openAppDrawer(launcherInstance: Any): Boolean {
        return try {
            val success = try {
                launcherInstance.current().method {
                    name = "showAllAppsFromIntent"
                    param(BooleanType)
                }.call(true)
                true
            } catch (e: Throwable) {
                try {
                    val ctx = launcherInstance as? android.content.Context ?: return false
                    "com.android.launcher3.taskbar.TaskbarUtils".toClass(appClassLoader).method {
                        name = "showAllApps"
                        param(ctx.javaClass)
                        modifiers { isStatic }
                    }.get().call(ctx)
                    true
                } catch (_: Throwable) { false }
            }
            return success
        } catch (_: Throwable) { false }
    }
}
