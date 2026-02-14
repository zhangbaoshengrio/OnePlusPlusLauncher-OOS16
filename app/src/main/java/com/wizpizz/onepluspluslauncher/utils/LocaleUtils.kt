package com.wizpizz.onepluspluslauncher.utils

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleUtils {
    const val PREF_UI_LANGUAGE = "ui_language"
    const val PREFS_NAME = "ui_prefs"

    fun wrapContext(base: Context, language: String?): Context {
        if (language.isNullOrBlank()) return base
        val locale = Locale(language)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
