package biz.cesena.packride4.data.local

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.appDataStore by preferencesDataStore("app_prefs")
