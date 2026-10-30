package com.iltan.settle.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/**
 * The app's own language, independent of the phone's. Stored by the system through [LocaleManager],
 * so it also shows up in Settings > Apps > Settle > Language. Changing it recreates the activity in
 * the new language.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    TURKISH("tr");

    companion object {
        fun current(context: Context): AppLanguage {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) return SYSTEM
            return entries.firstOrNull { it.tag == locales[0].language } ?: SYSTEM
        }

        fun set(context: Context, language: AppLanguage) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        }
    }
}
