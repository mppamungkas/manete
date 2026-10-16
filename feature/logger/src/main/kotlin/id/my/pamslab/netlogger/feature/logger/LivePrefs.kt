package id.my.pamslab.netlogger.feature.logger

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Collapsible Live sections. All start closed (decision 004, D7). */
enum class LiveSection { NEIGHBOURS, DETAILS }

/** Which Live sections are open; remembered across app starts (decision 004, D7). */
@Singleton
class LivePrefs @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val _open = MutableStateFlow(
        LiveSection.entries.filter { prefs.getBoolean(key(it), false) }.toSet(),
    )
    val open: StateFlow<Set<LiveSection>> = _open.asStateFlow()

    @SuppressLint("UseKtx") // core-ktx is not a dependency of feature:logger; not worth one for a single edit.
    fun setOpen(section: LiveSection, open: Boolean) {
        prefs.edit().putBoolean(key(section), open).apply()
        _open.value = if (open) _open.value + section else _open.value - section
    }

    private fun key(section: LiveSection) = "open_${section.name}"

    private companion object {
        const val FILE = "live"
    }
}
