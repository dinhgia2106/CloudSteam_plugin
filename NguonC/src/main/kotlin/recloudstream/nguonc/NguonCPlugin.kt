package recloudstream.nguonc

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NguonCPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(NguonCProvider())
    }
}
