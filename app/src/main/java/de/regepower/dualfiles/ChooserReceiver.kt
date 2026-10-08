package de.regepower.dualfiles

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Prefs file: file extension → flattened ComponentName of the app picked for it. */
internal const val CHOICES_PREFS = "choices"

/** Gets the app the user picked in the system chooser (Android 5.1+ sends it as EXTRA_CHOSEN_COMPONENT). */
class ChooserReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        val chosen = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT) ?: return
        val ext = intent.data?.path?.removePrefix("/").orEmpty()
        if (ext.isEmpty()) return
        context.getSharedPreferences(CHOICES_PREFS, Context.MODE_PRIVATE)
            .edit().putString(ext, chosen.flattenToString()).apply()
    }
}
