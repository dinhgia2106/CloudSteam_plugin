package recloudstream.vsmov

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

class VSMovProvider : MainAPI() {
    override var mainUrl = "https://vsmov.com"
    override var name = "VSMov"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiBase = "$mainUrl/api"

    override val mainPage = mainPageOf(
        "$apiBase/danh-sach/phim-moi-cap-nhat" to "Mới cập nhật",
        "$apiBase/danh-sach?type=series" to "Phim bộ",
        "$apiBase/danh-sach?type=single" to "Phim lẻ",
        "$apiBase/danh-sach?type=hoathinh" to "Hoạt hình",
        "$apiBase/danh-sach/subteam" to "Phim Subteam",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains('?')) '&' else '?'
        val response = app.get("${request.data}${separator}page=$page&limit=24")
            .parsedSafe<VSMovListResponse>()
            ?: return newHomePageResponse(request.name, emptyList(), false)

        return newHomePageResponse(
            request.name,
            response.items.mapNotNull(::toSearchResponse),
            response.pagination?.let { it.currentPage < it.totalPages } ?: response.items.isNotEmpty(),
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val response = app.get(
            "$apiBase/tim-kiem?keyword=${query.encodeUri()}&page=$page&limit=24"
        ).parsedSafe<VSMovListResponse>() ?: return null

        return newSearchResponseList(
            response.items.mapNotNull(::toSearchResponse),
            response.pagination?.let { it.currentPage < it.totalPages } ?: false,
        )
    }

    private fun toSearchResponse(movie: VSMovMovie): SearchResponse? {
        val title = movie.name?.takeIf(String::isNotBlank) ?: return null
        val slug = movie.slug?.takeIf(String::isNotBlank) ?: return null
        val type = movie.toTvType()
        val url = "$mainUrl/phim/$slug"
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, type) {
                posterUrl = movie.poster()
                year = movie.year
            }
        } else {
            newTvSeriesSearchResponse(title, url, type) {
                posterUrl = movie.poster()
                year = movie.year
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return null
        val response = app.get("$apiBase/phim/${slug.encodeUri()}")
            .parsedSafe<VSMovDetailResponse>() ?: return null
        val movie = response.movie ?: return null
        val detailUrl = "$mainUrl/phim/${movie.slug ?: slug}"

        val groupedEpisodes = response.episodes.orEmpty()
            .flatMap { server ->
                server.serverData.orEmpty().mapNotNull { episode ->
                    val urlValue = episode.linkM3u8.nonBlank() ?: episode.linkEmbed.nonBlank()
                        ?: return@mapNotNull null
                    VSMovEpisodeSource(
                        key = episode.slug ?: episode.name ?: urlValue,
                        label = episode.name ?: episode.slug ?: "Tập phim",
                        server = server.serverName.orEmpty().replace(Regex("\\s+"), " ").trim()
                            .ifBlank { "Server" },
                        url = urlValue,
                    )
                }
            }
            .groupBy(VSMovEpisodeSource::key)
            .values
            .sortedBy { it.firstOrNull()?.label?.number() ?: Int.MAX_VALUE }

        val common: LoadResponse.() -> Unit = {
            posterUrl = movie.poster()
            backgroundPosterUrl = movie.backdrop()
            plot = movie.content
            year = movie.year
            tags = movie.category.orEmpty().mapNotNull(VSMovCategory::name)
            duration = movie.time?.number()
            addActors(movie.actor)
            addImdbId(movie.imdb?.id)
            addTMDbId(movie.tmdb?.id)
        }

        val isSeries = movie.toTvType() != TvType.Movie || groupedEpisodes.size > 1
        return if (isSeries) {
            val episodes = groupedEpisodes.map { sources ->
                val label = sources.first().label
                newEpisode(
                    VSMovLinkPayload(
                        referer = detailUrl,
                        sources = sources.map { VSMovStreamSource(it.server, it.url) },
                    ).toJson()
                ) {
                    name = if (label.startsWith("tập", true)) label else "Tập $label"
                    episode = label.number()
                }
            }
            newTvSeriesLoadResponse(
                movie.name ?: movie.originName ?: slug,
                detailUrl,
                movie.toTvType().takeUnless { it == TvType.Movie } ?: TvType.TvSeries,
                episodes,
                common,
            )
        } else {
            val sources = groupedEpisodes.firstOrNull().orEmpty()
            newMovieLoadResponse(
                movie.name ?: movie.originName ?: slug,
                detailUrl,
                TvType.Movie,
                VSMovLinkPayload(
                    referer = detailUrl,
                    sources = sources.map { VSMovStreamSource(it.server, it.url) },
                ).toJson(),
                common,
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = tryParseJson<VSMovLinkPayload>(data) ?: return false
        var found = false

        payload.sources.forEach { source ->
            if (source.url.isDirectMedia()) {
                callback(source.toExtractorLink(payload.referer))
                found = true
                return@forEach
            }

            val extracted = loadExtractor(source.url, payload.referer, subtitleCallback) { link ->
                callback(link)
                found = true
            }
            if (!extracted) {
                resolveVSMovPlayer(source.url, payload.referer)?.let { streamUrl ->
                    callback(
                        newExtractorLink(name, "$name - ${source.name}", streamUrl, streamUrl.linkType()) {
                            referer = source.url
                            quality = Qualities.Unknown.value
                            headers = mapOf(
                                "Referer" to source.url,
                                "Origin" to URI(source.url).let { "${it.scheme}://${it.authority}" },
                            )
                        }
                    )
                    found = true
                }
            }
        }
        return found
    }

    private suspend fun resolveVSMovPlayer(embedUrl: String, referer: String): String? = runCatching {
        val html = app.get(
            embedUrl,
            referer = referer,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            ),
        ).text

        val signed = Regex(
            """signedMasterUrl\s*:\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
        if (signed != null) return@runCatching URI(embedUrl).resolve(signed).toString()

        val baseUrl = Regex("""const\s+baseUrl\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        val videoHash = Regex("""const\s+videoHash\s*=\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)
        if (!baseUrl.isNullOrBlank() && !videoHash.isNullOrBlank()) {
            return@runCatching "$baseUrl/stream/$videoHash/master.m3u8"
        }

        mediaRegex.find(html)?.value?.replace("\\/", "/")
    }.getOrNull()

    private suspend fun VSMovStreamSource.toExtractorLink(referer: String): ExtractorLink =
        newExtractorLink(name, "$name - ${this.name}", url, url.linkType()) {
            this.referer = referer
            quality = getQualityFromName(this@toExtractorLink.name)
            headers = mapOf("Referer" to referer)
        }

    private fun String.isDirectMedia(): Boolean =
        contains(".m3u8", true) || contains(".mp4", true) || contains(".mpd", true)

    private fun String.linkType(): ExtractorLinkType = when {
        contains(".m3u8", true) -> ExtractorLinkType.M3U8
        contains(".mpd", true) -> ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    private fun String.number(): Int? = Regex("""\d+""").find(this)?.value?.toIntOrNull()
    private fun String?.nonBlank(): String? = this?.takeIf(String::isNotBlank)

    companion object {
        private val mediaRegex = Regex(
            """https?:\\?/\\?/[^"'\\\s<>]+?\.(?:m3u8|mp4|mpd)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE,
        )
    }
}

private data class VSMovEpisodeSource(
    val key: String,
    val label: String,
    val server: String,
    val url: String,
)

private data class VSMovLinkPayload(
    val referer: String,
    val sources: List<VSMovStreamSource> = emptyList(),
)

private data class VSMovStreamSource(
    val name: String,
    val url: String,
)

private data class VSMovListResponse(
    val status: Boolean? = null,
    val items: List<VSMovMovie> = emptyList(),
    val pagination: VSMovPagination? = null,
)

private data class VSMovPagination(
    val currentPage: Int = 1,
    val totalPages: Int = 1,
)

private data class VSMovDetailResponse(
    val status: Boolean? = null,
    val movie: VSMovMovie? = null,
    val episodes: List<VSMovEpisodeServer>? = null,
)

private data class VSMovMovie(
    val name: String? = null,
    @JsonProperty("origin_name") val originName: String? = null,
    val slug: String? = null,
    val content: String? = null,
    val type: String? = null,
    @JsonProperty("poster_url") val posterUrl: Any? = null,
    @JsonProperty("thumb_url") val thumbUrl: Any? = null,
    val time: String? = null,
    @JsonProperty("episode_current") val episodeCurrent: String? = null,
    @JsonProperty("episode_total") val episodeTotal: String? = null,
    val quality: String? = null,
    val lang: String? = null,
    val year: Int? = null,
    val actor: List<String>? = null,
    val director: List<String>? = null,
    val category: List<VSMovCategory>? = null,
    val country: VSMovCategory? = null,
    val tmdb: VSMovTmdb? = null,
    val imdb: VSMovImdb? = null,
) {
    fun poster(): String? = (posterUrl as? String).nonBlank() ?: (thumbUrl as? String).nonBlank()
    fun backdrop(): String? = (thumbUrl as? String).nonBlank() ?: poster()

    fun toTvType(): TvType = when {
        type.equals("hoathinh", true) || type.equals("anime", true) -> TvType.Anime
        type.equals("series", true) || type.equals("tvshows", true) ||
            tmdb?.type.equals("tv", true) -> TvType.TvSeries
        else -> TvType.Movie
    }

    private fun String?.nonBlank(): String? = this?.takeIf(String::isNotBlank)
}

private data class VSMovCategory(
    val id: Int? = null,
    val name: String? = null,
    val slug: String? = null,
)

private data class VSMovTmdb(
    val type: String? = null,
    val id: String? = null,
    val season: Int? = null,
)

private data class VSMovImdb(val id: String? = null)

private data class VSMovEpisodeServer(
    @JsonProperty("server_name") val serverName: String? = null,
    @JsonProperty("server_data") val serverData: List<VSMovEpisode>? = null,
)

private data class VSMovEpisode(
    val name: String? = null,
    val slug: String? = null,
    val filename: String? = null,
    @JsonProperty("link_embed") val linkEmbed: String? = null,
    @JsonProperty("link_m3u8") val linkM3u8: String? = null,
)
