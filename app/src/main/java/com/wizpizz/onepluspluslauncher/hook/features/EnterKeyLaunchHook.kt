package com.wizpizz.onepluspluslauncher.hook.features

import android.util.Log
import android.view.KeyEvent
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.factory.current
import com.highcapable.yukihookapi.hook.factory.field
import com.highcapable.yukihookapi.hook.factory.method
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.ANDROID_X_SEARCH_VIEW_CLASS
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.PREF_ENTER_KEY_LAUNCH
import com.wizpizz.onepluspluslauncher.hook.features.HookUtils.TAG

/**
 * Handles Enter key app launching for both launcher versions:
 * - System Launcher 15.4.13: AndroidX SearchView hook
 * - System Launcher 15.6.13+: OnePlus Search Bar Controller hook
 */
object EnterKeyLaunchHook {

    @Volatile
    private var lastAutoLaunchQuery: String = ""

    @Volatile
    private var lastAutoLaunchTime: Long = 0L

    fun apply(packageParam: PackageParam) {
        packageParam.apply {
            // Check if enter key launch is enabled
            val enterKeyLaunchEnabled = prefs.getBoolean(PREF_ENTER_KEY_LAUNCH, true)
            if (!enterKeyLaunchEnabled) {
                Log.d(TAG, "[EnterKeyLaunch] Feature disabled in preferences")
                return@apply
            }

            // Hook for System Launcher 15.4.13 (AndroidX SearchView)
            hookSearchView()

            // Hook for System Launcher 15.6.13+ (OnePlus Controller)
            hookOnePlusController()

            // hookAutoLaunchOnResults disabled: Enter key hook now works on OOS16,
            // auto-launch on every search update is no longer needed
            // hookAutoLaunchOnResults()
        }
    }

    /**
     * Hook AndroidX SearchView for System Launcher 15.4.13
     */
    private fun PackageParam.hookSearchView() {
        ANDROID_X_SEARCH_VIEW_CLASS.toClassOrNull(appClassLoader)?.method {
            name = "onSubmitQuery"
            emptyParam()
        }?.hook {
            before {
                val searchViewInstance = instance as? android.view.View ?: return@before

                val searchAutoComplete = instance.current().method {
                    name = "getSearchAutoComplete"
                }.call()
                val query = (searchAutoComplete as? android.widget.EditText)?.text?.toString() ?: ""

                if (query.isNotEmpty()) {
                    val launcherInstance = appClassLoader?.let {
                        HookUtils.getLauncherFromContext(
                            searchViewInstance.context,
                            it
                        )
                    }

                    if (launcherInstance != null) {
                        val success = appClassLoader?.let {
                            HookUtils.launchFirstSearchResult(
                                launcherInstance,
                                searchViewInstance,
                                it
                            )
                        }

                        if (success == true) {
                            Log.d(TAG, "[SearchView] Successfully launched first result for: '$query'")
                            resultNull()
                            return@before
                        }
                    }
                }
            }
        } ?: Log.d(TAG, "[SearchView] AndroidX SearchView not found - likely System Launcher 15.6.13+")
    }

    /**
     * Hook OnePlus Search Bar Controller for System Launcher 15.6.13+
     */
    private fun PackageParam.hookOnePlusController() {
        "com.android.launcher3.allapps.search.OplusAllAppsSearchBarController"
            .toClassOrNull(appClassLoader)?.method {
                name = "initialize"
                paramCount = 6
            }?.hook {
                after {
                    val controller = instance
                    val editTextParam = args[2] as? android.widget.EditText

                    val searchEditText = editTextParam ?: findEditTextInController(controller)

                    if (searchEditText != null) {
                        // Use postDelayed to ensure our listener is set after the system's
                        searchEditText.postDelayed({
                            searchEditText.setOnEditorActionListener { textView, actionId, keyEvent ->
                                handleEditorAction(textView, actionId, keyEvent, controller)
                            }
                        }, 200L)
                    }
                }
            } ?: Log.d(TAG, "[OnePlusController] OplusAllAppsSearchBarController not found - likely System Launcher 15.4.13")
    }

    /**
     * Find EditText field in OnePlus controller
     */
    private fun findEditTextInController(controller: Any): android.widget.EditText? {
        val fieldNames = listOf("mEditText", "mSearchInput", "mInput", "mSearchField", "editText")

        for (fieldName in fieldNames) {
            try {
                val field = controller.javaClass.field { name = fieldName; superClass(true) }
                val fieldValue = field.get(controller).any()
                if (fieldValue is android.widget.EditText) {
                    return fieldValue
                }
            } catch (e: Throwable) {
                // Try next field name
            }
        }
        return null
    }

    /**
     * Handle Enter key press in OnePlus controller.
     * Accepts common IME actions and KeyEvent-based Enter presses
     * to ensure compatibility with various IMEs (e.g. WeChat Input).
     */
    private fun PackageParam.handleEditorAction(
        textView: android.widget.TextView,
        actionId: Int,
        keyEvent: KeyEvent?,
        controller: Any
    ): Boolean {
        Log.d(TAG, "[OnePlusController] handleEditorAction: actionId=$actionId, keyEvent=$keyEvent")

        // Check if this is an Enter action:
        // - actionId 0 (IME_ACTION_UNSPECIFIED), 2 (IME_ACTION_GO), 3 (IME_ACTION_SEARCH),
        //   5 (IME_ACTION_NEXT), 6 (IME_ACTION_DONE) are common IME actions for Enter
        // - Also handle KeyEvent with KEYCODE_ENTER (some IMEs send Enter as a KeyEvent)
        val isEnterAction = actionId in intArrayOf(0, 2, 3, 5, 6)
        val isEnterKey = keyEvent != null &&
                keyEvent.keyCode == KeyEvent.KEYCODE_ENTER &&
                keyEvent.action == KeyEvent.ACTION_DOWN

        if ((isEnterAction || isEnterKey) && !android.text.TextUtils.isEmpty(textView.text)) {
            val query = textView.text.toString().trim()
            Log.d(TAG, "[OnePlusController] query='$query'")
            if (query.isNotEmpty()) {
                val launcherInstance = getLauncherInstance(textView, controller)
                Log.d(TAG, "[OnePlusController] launcherInstance=$launcherInstance")

                if (launcherInstance != null) {
                    val success = appClassLoader?.let {
                        HookUtils.launchFirstSearchResult(
                            launcherInstance,
                            textView as android.view.View,
                            it
                        )
                    }
                    Log.d(TAG, "[OnePlusController] launchFirstSearchResult success=$success")

                    if (success == true) {
                        Log.d(TAG, "[OnePlusController] Successfully launched first result for: '$query'")
                        return true
                    }
                } else {
                    Log.d(TAG, "[OnePlusController] launcherInstance is NULL")
                }
            }
        } else {
            Log.d(TAG, "[OnePlusController] Skipped: isEnterAction=$isEnterAction, isEnterKey=$isEnterKey, text='${textView.text}'")
        }
        return false
    }

    /**
     * Get launcher instance from OnePlus controller
     */
    private fun PackageParam.getLauncherInstance(
        textView: android.widget.TextView,
        controller: Any
    ): Any? {
        // Try mLauncher field
        try {
            val launcherField = controller.javaClass.field { name = "mLauncher"; superClass(true) }
            return launcherField.get(controller).any()
        } catch (e: Throwable) {
            // Try mActivityContext field
            try {
                val activityContextField = controller.javaClass.field { name = "mActivityContext"; superClass(true) }
                return activityContextField.get(controller).any()
            } catch (e2: Throwable) {
                // Fallback: traverse context
                return appClassLoader?.let {
                    HookUtils.getLauncherFromContext(textView.context,
                        it
                    )
                }
            }
        }
    }

    /**
     * Fallback: auto-launch first result after search results update
     * (OOS16+ where EditorAction may not fire)
     */
    private fun PackageParam.hookAutoLaunchOnResults() {
        "com.android.launcher3.allapps.search.LauncherTaskbarAppsSearchContainerLayout"
            .toClassOrNull(appClassLoader)?.method {
                name = "onSearchResult"
                param(String::class.java.name, "java.util.ArrayList")
            }?.hook {
                after {
                    val query = args[0] as? String ?: return@after
                    val results = args[1] as? java.util.ArrayList<*> ?: return@after
                    if (query.isBlank() || results.isEmpty()) return@after

                    val now = System.currentTimeMillis()
                    if (query == lastAutoLaunchQuery && (now - lastAutoLaunchTime) < 1200L) return@after

                    val view = instance as? android.view.View ?: return@after
                    val launcherInstance = appClassLoader?.let {
                        HookUtils.getLauncherFromContext(view.context, it)
                    } ?: return@after

                    lastAutoLaunchQuery = query
                    lastAutoLaunchTime = now

                    view.post {
                        try {
                            val success = appClassLoader?.let {
                                HookUtils.launchFirstSearchResult(launcherInstance, view, it)
                            } == true
                            if (success) {
                                Log.d(TAG, "[AutoLaunchOnResults] Launched first result for: '$query'")
                            }
                        } catch (t: Throwable) {
                            Log.d(TAG, "[AutoLaunchOnResults] Launch failed: ${t.message}")
                        }
                    }
                }
            } ?: Log.d(TAG, "[AutoLaunchOnResults] Search container not found")
    }

}
