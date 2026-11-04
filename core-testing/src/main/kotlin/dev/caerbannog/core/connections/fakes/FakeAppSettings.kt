package dev.caerbannog.core.connections.fakes

import dev.caerbannog.core.streams.AppSettings
import dev.caerbannog.core.streams.DEFAULT_REFRESH
import dev.caerbannog.core.streams.RefreshInterval

/**
 * M2-04 test double for [AppSettings]: the value lives in memory only (the `settings.json` file is M2-05). A fresh
 * instance is "the app has no saved settings": the refresh interval is [DEFAULT_REFRESH].
 */
class FakeAppSettings(initial: RefreshInterval = DEFAULT_REFRESH) : AppSettings {
    @Volatile
    override var streamListRefresh: RefreshInterval = initial
}
