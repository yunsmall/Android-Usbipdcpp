package com.yunsmall.usbipdcpp

import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleHelper {
    fun setAppLocale(context: Context, languageTag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService("locale")!!
            val localeList = if (languageTag.isNullOrEmpty()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(languageTag)
            }
            localeManager.javaClass.getMethod("setApplicationLocales", LocaleList::class.java)
                .invoke(localeManager, localeList)
        } else {
            val localeList = if (languageTag.isNullOrEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(languageTag)
            }
            AppCompatDelegate.setApplicationLocales(localeList)
        }
    }

    fun getCurrentLocale(context: Context): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService("locale")!!
            val localeList = localeManager.javaClass.getMethod("getApplicationLocales")
                .invoke(localeManager) as LocaleList
            localeList.get(0) ?: Locale.getDefault()
        } else {
            AppCompatDelegate.getApplicationLocales().get(0) ?: Locale.getDefault()
        }
    }
}