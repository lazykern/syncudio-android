package io.github.zyrouge.symphony

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.zyrouge.symphony.services.AppMeta
import io.github.zyrouge.symphony.services.Permissions
import io.github.zyrouge.symphony.services.Settings
import io.github.zyrouge.symphony.services.cloud.DropboxService
import io.github.zyrouge.symphony.services.database.Database
import io.github.zyrouge.symphony.services.groove.Groove
import io.github.zyrouge.symphony.services.i18n.Translator
import io.github.zyrouge.symphony.services.radio.Radio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Symphony(application: Application) : AndroidViewModel(application) {
    interface Hooks {
        fun onSymphonyReady() {}
        fun onSymphonyDestroy() {}
        fun onSymphonyActivityReady() {}
        fun onSymphonyActivityPause() {}
        fun onSymphonyActivityDestroy() {}
    }

    val applicationContext = application.applicationContext
    var closeApp: (() -> Unit)? = null
    private var isReady = false

    val permission = Permissions(this)
    val settings = Settings(this)
    val database = Database(this)
    val groove = Groove(this)
    val radio = Radio(this)
    val translator = Translator(this)
    val dropbox = DropboxService(this)

    var t by mutableStateOf(translator.getCurrentTranslation())

    private val hooks = listOf<Hooks>(
        settings,
        groove,
        radio,
        dropbox,
        object : Hooks {
            override fun onSymphonyReady() {
                checkVersion()
                viewModelScope.launch {
                    translator.onChange { nTranslation ->
                        t = nTranslation
                    }
                }
            }
        }
    )

    internal fun emitReady() {
        if (isReady) {
            return
        }
        isReady = true
        notifyHooks { onSymphonyReady() }
    }

    internal fun emitDestroy() {
        notifyHooks { onSymphonyDestroy() }
    }

    internal fun emitActivityReady() {
        emitReady()
        notifyHooks { onSymphonyActivityReady() }
    }

    internal fun emitActivityPause() {
        notifyHooks { onSymphonyActivityPause() }
    }

    internal fun emitActivityDestroy() {
        notifyHooks { onSymphonyActivityDestroy() }
    }

    override fun onCleared() {
        super.onCleared()
        emitDestroy()
    }

    private fun notifyHooks(fn: Hooks.() -> Unit) {
        hooks.forEach { fn.invoke(it) }
    }

    private fun checkVersion() {
        if (!settings.checkForUpdates.value) {
            return
        }
        viewModelScope.launch {
            val latestVersion = withContext(Dispatchers.IO) {
                AppMeta.fetchLatestVersion()
            }
            if (latestVersion == null) {
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (settings.showUpdateToast.value && AppMeta.version != latestVersion) {
                    Toast.makeText(
                        applicationContext,
                        t.NewVersionAvailableX(latestVersion),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }
}
