package recloudstream.vsmov

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class VSMovPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(VSMovProvider())
    }
}
