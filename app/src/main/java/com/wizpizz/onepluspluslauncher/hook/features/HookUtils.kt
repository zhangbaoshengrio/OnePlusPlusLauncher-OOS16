package com.wizpizz.onepluspluslauncher.hook.features

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.factory.toClass

import java.util.ArrayList

object HookUtils {
    
    // Constants
    const val TAG = "OPPLauncherHook"

    // Preference keys
    const val PREF_USE_FUZZY_SEARCH = "use_fuzzy_search"
    const val PREF_AUTO_FOCUS_SEARCH_SWIPE = "auto_focus_search_swipe"
    const val PREF_AUTO_FOCUS_SEARCH_REDIRECT = "auto_focus_search_redirect"
    const val PREF_AUTO_FOCUS_SWIPE_DOWN_REDIRECT = "auto_focus_swipe_down_redirect"
    const val PREF_ENTER_KEY_LAUNCH = "enter_key_launch"
    const val PREF_GLOBAL_SEARCH_REDIRECT = "global_search_redirect"
    const val PREF_SWIPE_DOWN_SEARCH_REDIRECT = "swipe_down_search_redirect"
    const val PREF_LEFT_SWIPE_DISCOVER_REDIRECT = "left_swipe_discover_redirect"
    const val PREF_AUTO_FOCUS_LEFT_SWIPE_REDIRECT = "auto_focus_left_swipe_redirect"
    const val PREF_LIMIT_TWO_ROWS_SEARCH = "pref_limit_two_rows_search"
    
    // Class names
    const val LAUNCHER_CLASS = "com.android.launcher3.Launcher"
    const val LAUNCHER_STATE_CLASS = "com.android.launcher3.LauncherState"
    const val ANDROID_X_SEARCH_VIEW_CLASS = "androidx.appcompat.widget.SearchView"
    private const val ITEM_INFO_CLASS = "com.android.launcher3.model.data.ItemInfo"
    private const val VIEW_CLASS = "android.view.View"
    private const val INTENT_CLASS = "android.content.Intent"
    
    // Coordination flag to distinguish between swipe and redirect
    @Volatile
    private var isRedirectInProgress = false
    
    /**
     * Mark that a redirect is in progress (used by GlobalSearchRedirectHook)
     */
    fun setRedirectInProgress(inProgress: Boolean) {
        isRedirectInProgress = inProgress
    }
    
    /**
     * Check if a redirect is currently in progress (used by AutoFocusHook)
     */
    fun isRedirectInProgress(): Boolean = isRedirectInProgress
    
    /**
     * Extract Launcher instance from context traversal
     */
    fun getLauncherFromContext(context: Context, appClassLoader: ClassLoader): Any? {
        var currentContext: Context? = context
        val launcher3Class = LAUNCHER_CLASS.toClassOrNull(appClassLoader)
        
        while (currentContext != null) {
            if (currentContext is Activity && launcher3Class?.isInstance(currentContext) == true) {
                return currentContext
            }
            currentContext = if (currentContext is ContextWrapper) {
                currentContext.baseContext
            } else {
                null
            }
        }
        return null
    }
    
    /**
     * Focus search input in app drawer
     */
    fun focusSearchInput(launcherInstance: Any, appClassLoader: ClassLoader) {
        if (launcherInstance !is android.content.Context) return
        
        try {
            // Get AppsView
            var appsView = LAUNCHER_CLASS.toClass(appClassLoader)
                .field { name = "mAppsView" }
                .get(instance = launcherInstance)
                .any()
            
            if (appsView == null) {
                appsView = launcherInstance.current().method { name = "getAppsView" }.call()
            }
            
            if (appsView == null) {
                Log.e(TAG, "[AutoFocus] Failed to get AppsView")
                return
            }

            // Get SearchUiManager
            val searchUiManager = appsView.current().method {
                name = "getSearchUiManager"
                superClass()
            }.call()
            
            if (searchUiManager == null) {
                Log.e(TAG, "[AutoFocus] Failed to get SearchUiManager")
                return
            }

            // Try multiple approaches to get and focus search input
            var searchInputFocused = false
            var editTextRef: android.widget.EditText? = null

            // Approach 1: Try getEditText method
            try {
                val editText = searchUiManager.current().method {
                    name = "getEditText"
                    superClass()
                }.call() as? android.widget.EditText

                if (editText != null) {
                    editText.isFocusable = true
                    editText.isFocusableInTouchMode = true
                    editText.requestFocus()
                    editText.requestFocusFromTouch()
                    editText.performClick()
                    searchInputFocused = true
                    editTextRef = editText
                    Log.d(TAG, "[AutoFocus] Successfully focused search input via getEditText")
                }
            } catch (e: Throwable) {
                Log.d(TAG, "[AutoFocus] getEditText method not available")
            }

            // Approach 2: Search for EditText in searchUiManager view hierarchy
            if (!searchInputFocused && searchUiManager is android.view.ViewGroup) {
                val editText = findEditTextInViewGroup(searchUiManager)
                if (editText != null) {
                    editText.isFocusable = true
                    editText.isFocusableInTouchMode = true
                    editText.requestFocus()
                    editText.requestFocusFromTouch()
                    editText.performClick()
                    searchInputFocused = true
                    editTextRef = editText
                    Log.d(TAG, "[AutoFocus] Successfully focused search input via view traversal")
                }
            }

            // Approach 3: Search for EditText in AppsView hierarchy (fallback)
            if (!searchInputFocused && appsView is android.view.ViewGroup) {
                val editText = findEditTextInViewGroup(appsView)
                if (editText != null) {
                    editText.isFocusable = true
                    editText.isFocusableInTouchMode = true
                    editText.requestFocus()
                    editText.requestFocusFromTouch()
                    editText.performClick()
                    searchInputFocused = true
                    editTextRef = editText
                    Log.d(TAG, "[AutoFocus] Successfully focused search input via AppsView traversal")
                }
            }

            if (!searchInputFocused) {
                Log.w(TAG, "[AutoFocus] Could not focus search input - no suitable method found")
            }

            // Show keyboard (immediate)
            try {
                searchUiManager.current().method {
                    name = "showKeyboard"
                    superClass()
                }.call()
            } catch (_: Throwable) {}

            // If we have a real EditText, force IME on it (post to wait for layout)
            try {
                val et = editTextRef
                if (et != null) {
                    et.post {
                        try {
                            val imm = et.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                            imm?.restartInput(et)
                            imm?.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                            Log.d(TAG, "[AutoFocus] showSoftInput on EditText (post)")
                        } catch (_: Throwable) {}
                    }
                    et.postDelayed({
                        try {
                            val imm = et.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                            imm?.restartInput(et)
                            imm?.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                            Log.d(TAG, "[AutoFocus] showSoftInput on EditText (postDelayed=100ms)")
                        } catch (_: Throwable) {}
                    }, 100L)
                }
            } catch (_: Throwable) {}

            // Retry keyboard show after a short delay (some builds need a second tick)
            try {
                if (searchUiManager is android.view.View) {
                    val view = searchUiManager
                    val retryMs = listOf(250L, 400L, 600L)
                    for (delay in retryMs) {
                        view.postDelayed({
                            try {
                                // Re-focus if possible
                                val editText = editTextRef ?: try {
                                    searchUiManager.current().method {
                                        name = "getEditText"
                                        superClass()
                                    }.call() as? android.widget.EditText
                                } catch (_: Throwable) { null }
                                    ?: if (searchUiManager is android.view.ViewGroup) {
                                        findEditTextInViewGroup(searchUiManager)
                                    } else null
                                    ?: if (appsView is android.view.ViewGroup) {
                                        findEditTextInViewGroup(appsView)
                                    } else null

                                editText?.isFocusable = true
                                editText?.isFocusableInTouchMode = true
                                editText?.requestFocus()
                                editText?.requestFocusFromTouch()
                                editText?.performClick()

                                // Try framework showKeyboard again
                                try {
                                    searchUiManager.current().method {
                                        name = "showKeyboard"
                                        superClass()
                                    }.call()
                                } catch (_: Throwable) {}

                                // Force InputMethodManager on the EditText if possible
                                val targetView = (editText ?: view) as android.view.View
                                val imm = targetView.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                                if (editText != null) {
                                    imm?.restartInput(editText)
                                }
                                imm?.showSoftInput(targetView, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

                                // On last retry, try a stronger toggle
                                if (delay >= 1000L) {
                                    imm?.toggleSoftInput(android.view.inputmethod.InputMethodManager.SHOW_FORCED, 0)
                                }

                                Log.d(TAG, "[AutoFocus] Retried showKeyboard (delay=${delay}ms)")
                            } catch (e: Throwable) {
                                Log.d(TAG, "[AutoFocus] Retry showKeyboard failed: ${e.message}")
                            }
                        }, delay)
                    }
                }
            } catch (_: Throwable) {}

        } catch (e: Throwable) {
            Log.e(TAG, "[AutoFocus] Error during focus logic: ${e.message}")
        }
    }
    
    /**
     * Recursively search for EditText in view hierarchy
     */
    private fun findEditTextInViewGroup(viewGroup: android.view.ViewGroup): android.widget.EditText? {
        for (i in 0 until viewGroup.childCount) {
            when (val child = viewGroup.getChildAt(i)) {
                is android.widget.EditText -> return child
                is android.view.ViewGroup -> {
                    val found = findEditTextInViewGroup(child)
                    if (found != null) return found
                }
            }
        }
        return null
    }
    
    /**
     * Launch first search result
     */
    fun launchFirstSearchResult(
        launcherInstance: Any,
        searchView: android.view.View,
        appClassLoader: ClassLoader
    ): Boolean {
        return try {
            val appsView = launcherInstance.current().method {
                name = "getAppsView"; superClass()
            }.call()

            // OOS16: Click the first visible app item in the search RecyclerView
            // This ensures we launch exactly what the user sees as the first result
            try {
                val searchRv = appsView?.current()?.method { name = "getActiveSearchRecyclerView"; superClass() }?.call()

                if (searchRv != null && searchRv is android.view.ViewGroup) {
                    val childCount = searchRv.childCount
                    Log.d(TAG, "[LaunchFirst] searchRv childCount=$childCount")

                    if (childCount > 0) {
                        // Find the first child that looks like an app icon (BubbleTextView or clickable view)
                        for (i in 0 until minOf(childCount, 15)) {
                            val child = searchRv.getChildAt(i)
                            if (child == null) continue

                            // If child is a clickable view with a tag (app item), click it
                            if (child.isClickable && child.tag != null) {
                                val tag = child.tag
                                val tagClass = tag.javaClass.name
                                Log.d(TAG, "[LaunchFirst] child[$i] tag class=$tagClass")

                                // Check if tag is an ItemInfo (app info)
                                val itemInfoClass = ITEM_INFO_CLASS.toClass(appClassLoader)
                                if (itemInfoClass.isInstance(tag)) {
                                    val intent = tag.current().method { name = "getIntent"; superClass() }.call() as? android.content.Intent
                                    if (intent != null) {
                                        Log.d(TAG, "[LaunchFirst] Launching via tag intent=$intent")
                                        launcherInstance.current().method {
                                            name = "startActivitySafely"
                                            param(VIEW_CLASS.toClass(appClassLoader),
                                                    INTENT_CLASS.toClass(appClassLoader),
                                                    ITEM_INFO_CLASS.toClass(appClassLoader))
                                            superClass()
                                        }.call(child, intent, tag)
                                        return true
                                    }
                                }
                            }

                            // If child is a ViewGroup (e.g. a row of icons), search inside it
                            if (child is android.view.ViewGroup) {
                                for (j in 0 until child.childCount) {
                                    val subChild = child.getChildAt(j)
                                    if (subChild != null && subChild.isClickable && subChild.tag != null) {
                                        val tag = subChild.tag
                                        val itemInfoClass = ITEM_INFO_CLASS.toClass(appClassLoader)
                                        if (itemInfoClass.isInstance(tag)) {
                                            val intent = tag.current().method { name = "getIntent"; superClass() }.call() as? android.content.Intent
                                            if (intent != null) {
                                                Log.d(TAG, "[LaunchFirst] Launching via subChild tag intent=$intent")
                                                launcherInstance.current().method {
                                                    name = "startActivitySafely"
                                                    param(VIEW_CLASS.toClass(appClassLoader),
                                                            INTENT_CLASS.toClass(appClassLoader),
                                                            ITEM_INFO_CLASS.toClass(appClassLoader))
                                                    superClass()
                                                }.call(subChild, intent, tag)
                                                return true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.d(TAG, "[LaunchFirst] searchRv approach failed: ${e.message}")
            }

            // Fallback: Try main AlphabeticalAppsList (older launcher versions)
            val appsList = appsView?.current()?.method {
                name = "getAlphabeticalAppsList"; superClass()
            }?.call() ?: appsView?.current()?.method {
                name = "getAppsList"; superClass()
            }?.call()

            if (appsList != null) {
                var resultItems: List<*>? = null
                try {
                    resultItems = appsList.current().method { name = "getSearchResults"; superClass() }.call() as? List<*>
                } catch (_: Throwable) {}

                if (resultItems != null && resultItems.isNotEmpty()) {
                    val firstAdapterItem = resultItems[0]
                    if (firstAdapterItem != null) {
                        val itemInfoField = firstAdapterItem.javaClass.field {
                            name = "itemInfo"; superClass(true)
                        }
                        val itemInfoObject = itemInfoField.get(firstAdapterItem).any()
                        val itemInfoClass = ITEM_INFO_CLASS.toClass(appClassLoader)
                        val itemInfo = if (itemInfoClass.isInstance(itemInfoObject)) itemInfoObject else null

                        if (itemInfo != null) {
                            val foundIntent = itemInfo.current().method {
                                name = "getIntent"; superClass()
                            }.call() as? android.content.Intent

                            if (foundIntent != null) {
                                launcherInstance.current().method {
                                    name = "startActivitySafely"
                                    param(VIEW_CLASS.toClass(appClassLoader),
                                            INTENT_CLASS.toClass(appClassLoader),
                                            ITEM_INFO_CLASS.toClass(appClassLoader))
                                    superClass()
                                }.call(searchView, foundIntent, itemInfo)
                                return true
                            }
                        }
                    }
                }
            }

            Log.d(TAG, "[LaunchFirst] No launchable item found")
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Error launching first search result: ${e.message}", e)
            false
        }
    }
}

// Extension for safe class resolution
fun String.toClassOrNull(classLoader: ClassLoader): Class<*>? {
    return try {
        Class.forName(this, false, classLoader)
    } catch (e: ClassNotFoundException) {
        null
    }
} 