package recloudstream.nguonc

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
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class NguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "NguonC"
    override var lang = "vi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiBase = "$mainUrl/api"

    override val mainPage = mainPageOf(
        "$apiBase/films/phim-moi-cap-nhat" to "Mới cập nhật",
        "$apiBase/films/danh-sach/phim-bo" to "Phim bộ",
        "$apiBase/films/danh-sach/phim-le" to "Phim lẻ",
        "$apiBase/films/danh-sach/hoat-hinh" to "Hoạt hình",
        "$apiBase/films/danh-sach/tv-shows" to "TV Shows",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains('?')) '&' else '?'
        val response = app.get("${request.data}${separator}page=$page")
            .parsedSafe<NguonCListResponse>()
            ?: return newHomePageResponse(request.name, emptyList(), false)

        return newHomePageResponse(
            request.name,
            response.items.mapNotNull(::toSearchResponse),
            response.paginate?.let { it.currentPage < it.totalPage } ?: response.items.isNotEmpty(),
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val response = app.get(
            "$apiBase/films/search?keyword=${query.encodeUri()}&page=$page"
        ).parsedSafe<NguonCListResponse>() ?: return null

        return newSearchResponseList(
            response.items.mapNotNull(::toSearchResponse),
            response.paginate?.let { it.currentPage < it.totalPage } ?: false,
        )
    }

    private fun toSearchResponse(item: NguonCMovie): SearchResponse? {
        val title = item.name?.takeIf(String::isNotBlank) ?: return null
        val slug = item.slug?.takeIf(String::isNotBlank) ?: return null
        val type = item.toTvType()
        val url = "$mainUrl/phim/$slug"
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, type) {
                posterUrl = item.poster()
                year = item.year
                quality = getQualityFromString(item.quality)
            }
        } else {
            newTvSeriesSearchResponse(title, url, type) {
                posterUrl = item.poster()
                year = item.year
                quality = getQualityFromString(item.quality)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return null

        val movie = app.get("$apiBase/film/${slug.encodeUri()}")
            .parsedSafe<NguonCDetailResponse>()
            ?.movie ?: return null

        val detailUrl = "$mainUrl/phim/${movie.slug ?: slug}"
        val groupedEpisodes = movie.episodes.orEmpty()
            .flatMap { server ->
                server.items.orEmpty().mapNotNull { episode ->
                    episode.embed?.takeIf(String::isNotBlank)?.let {
                        EpisodeSource(
                            key = episode.slug ?: episode.name ?: it,
                            label = episode.name ?: episode.slug ?: "Tập phim",
                            server = server.serverName ?: "Server",
                            embed = it,
                        )
                    }
                }
            }
            .groupBy(EpisodeSource::key)
            .values
            .sortedBy { sources -> sources.firstOrNull()?.label?.episodeNumber() ?: Int.MAX_VALUE }

        val common: LoadResponse.() -> Unit = {
            posterUrl = movie.poster()
            backgroundPosterUrl = movie.backdrop()
            plot = movie.description
            year = movie.year
            tags = movie.category.orEmpty().values.flatMap { group ->
                group.list.orEmpty().mapNotNull(NguonCCategoryItem::name)
            }.distinct()
            duration = movie.time?.episodeNumber()
            addActors(movie.casts?.split(',')?.map(String::trim)?.filter(String::isNotBlank))
            addImdbId(movie.imdb?.id)
            addTMDbId(movie.tmdb?.id?.toString())
        }

        val isSeries = movie.totalEpisodes?.let { it > 1 } == true || groupedEpisodes.size > 1
        return if (isSeries) {
            val episodes = groupedEpisodes.map { sources ->
                val label = sources.first().label
                newEpisode(
                    NguonCLinkPayload(
                        referer = detailUrl,
                        sources = sources.map { NguonCStreamSource(it.server, it.embed) },
                    ).toJson()
                ) {
                    name = if (label.startsWith("tập", true)) label else "Tập $label"
                    episode = label.episodeNumber()
                }
            }
            newTvSeriesLoadResponse(
                movie.name ?: movie.originalName ?: slug,
                detailUrl,
                movie.toTvType().takeUnless { it == TvType.Movie } ?: TvType.TvSeries,
                episodes,
                common,
            )
        } else {
            val sources = groupedEpisodes.firstOrNull().orEmpty()
            newMovieLoadResponse(
                movie.name ?: movie.originalName ?: slug,
                detailUrl,
                TvType.Movie,
                NguonCLinkPayload(
                    referer = detailUrl,
                    sources = sources.map { NguonCStreamSource(it.server, it.embed) },
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
        val payload = tryParseJson<NguonCLinkPayload>(data) ?: return false
        var found = false

        payload.sources.forEach { source ->
            val extracted = loadExtractor(source.url, payload.referer, subtitleCallback) { link ->
                found = true
                callback(link)
            }
            if (!extracted) {
                resolveNguonCEmbed(source, payload.referer)?.let { resolved ->
                    callback(
                        newExtractorLink(name, "$name - ${source.name}", resolved.url, resolved.type) {
                            referer = resolved.referer
                            quality = getQualityFromName(source.name)
                            headers = resolved.headers
                        }
                    )
                    found = true
                }
            }
        }
        return found
    }

    private suspend fun resolveNguonCEmbed(source: NguonCStreamSource, movieUrl: String): ResolvedStream? {
        return runCatching {
            val embedUrl = source.url
            val html = app.get(embedUrl, referer = movieUrl).text

            findMediaUrl(html)?.let { direct ->
                return@runCatching ResolvedStream(
                    URI(embedUrl).resolve(direct).toString(),
                    embedUrl,
                    direct.toLinkType(),
                    browserHeaders(embedUrl),
                )
            }

            val bootstrapJson = Regex(
                """<script[^>]+id=["']stream-bootstrap["'][^>]*>(.*?)</script>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            ).find(html)?.groupValues?.get(1)

            if (bootstrapJson != null) {
                val meta = tryParseJson<BootstrapMeta>(bootstrapJson)
                    ?: return@runCatching null
                val origin = URI(embedUrl).let { "${it.scheme}://${it.authority}" }
                val responseText = app.post(
                    meta.api,
                    json = mapOf(
                        "action" to "bootstrap",
                        "referrer" to movieUrl.take(4096),
                        "frame_origins" to listOf(URI(movieUrl).let { "${it.scheme}://${it.authority}" }),
                        "request_grant" to true,
                        "playlist_format" to "aesgcm-v2",
                        "pretty_url" to true,
                        "path_chunks" to true,
                        "bootstrap_format" to "aesgcm-v1",
                    ),
                    headers = mapOf("Origin" to origin),
                    referer = embedUrl,
                ).text

                val envelope = tryParseJson<BootstrapEnvelope>(responseText)
                    ?: return@runCatching null
                val openedText = if (envelope.format == "aesgcm-v1") {
                    decryptBootstrap(envelope, meta.api)
                } else responseText
                val opened = tryParseJson<OpenedBootstrap>(openedText)
                    ?: return@runCatching null
                val playlistUrl = opened.preissued?.playlist ?: return@runCatching null
                var playlist = app.get(playlistUrl, referer = embedUrl).text
                if (playlist.contains("#ENC-AESGCM")) {
                    val videoHash = opened.video
                        ?: Regex("[?&]hash=([0-9a-f]+)", RegexOption.IGNORE_CASE)
                            .find(embedUrl)?.groupValues?.get(1)
                        ?: return@runCatching null
                    playlist = decryptPlaylist(playlist, videoHash)
                }
                playlist = absolutizePlaylist(playlist, playlistUrl)
                val dataUrl = "data:application/vnd.apple.mpegurl;base64," +
                    base64Encode(playlist.encodeToByteArray())
                return@runCatching ResolvedStream(
                    dataUrl,
                    embedUrl,
                    ExtractorLinkType.M3U8,
                    browserHeaders(embedUrl),
                )
            }

            val obfuscated = Regex("""data-obf=["']([^"']+)["']""")
                .find(html)?.groupValues?.get(1)
                ?: return@runCatching null
            val legacy = tryParseJson<LegacyStreamData>(base64Decode(obfuscated))
                ?: return@runCatching null
            val streamUrl = URI(embedUrl).resolve("/${legacy.path}.m3u8").toString()
            ResolvedStream(
                streamUrl,
                embedUrl,
                ExtractorLinkType.M3U8,
                browserHeaders(embedUrl),
            )
        }.getOrNull()
    }

    private fun decryptBootstrap(envelope: BootstrapEnvelope, apiUrl: String): String {
        val aad = "stream-bootstrap-v1\n$apiUrl".encodeToByteArray()
        val key = MessageDigest.getInstance("SHA-256").digest(aad)
        return aesGcmDecrypt(key, envelope.iv.hexToBytes(), base64DecodeArray(envelope.data), aad)
            .decodeToString()
    }

    private fun decryptPlaylist(encrypted: String, videoHash: String): String {
        val ivHex = Regex("""#ENC-AESGCM[^\n]*iv=([0-9a-fA-F]+)""")
            .find(encrypted)?.groupValues?.get(1)
            ?: error("Missing playlist IV")
        val cipherText = encrypted.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
            ?: error("Missing encrypted playlist")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("stream-derive-v1".encodeToByteArray(), "HmacSHA256"))
        val key = mac.doFinal(videoHash.encodeToByteArray())
        return aesGcmDecrypt(key, ivHex.hexToBytes(), base64DecodeArray(cipherText)).decodeToString()
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        aad?.let(cipher::updateAAD)
        return cipher.doFinal(data)
    }

    private fun absolutizePlaylist(playlist: String, baseUrl: String): String {
        val base = URI(baseUrl)
        val uriAttribute = Regex("""URI="([^"]+)""", RegexOption.IGNORE_CASE)
        return playlist.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> line
                line.startsWith('#') -> uriAttribute.replace(line) { match ->
                    "URI=\"${base.resolve(match.groupValues[1])}\""
                }
                else -> base.resolve(line).toString()
            }
        }
    }

    private fun findMediaUrl(text: String): String? = Regex(
        """https?:\\?/\\?/[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""",
        RegexOption.IGNORE_CASE,
    ).find(text)?.value?.replace("\\/", "/")

    private fun browserHeaders(referer: String) = mapOf(
        "Referer" to referer,
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
    )

    private fun String.toLinkType() = when {
        contains(".m3u8", true) -> ExtractorLinkType.M3U8
        else -> ExtractorLinkType.VIDEO
    }

    private fun String.episodeNumber(): Int? = Regex("""\d+""").find(this)?.value?.toIntOrNull()

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

private data class ResolvedStream(
    val url: String,
    val referer: String,
    val type: ExtractorLinkType,
    val headers: Map<String, String>,
)

private data class EpisodeSource(
    val key: String,
    val label: String,
    val server: String,
    val embed: String,
)

private data class NguonCLinkPayload(
    val referer: String,
    val sources: List<NguonCStreamSource> = emptyList(),
)

private data class NguonCStreamSource(
    val name: String,
    val url: String,
)

private data class BootstrapMeta(val api: String)

private data class BootstrapEnvelope(
    val format: String? = null,
    val iv: String = "",
    val data: String = "",
)

private data class OpenedBootstrap(
    val video: String? = null,
    val preissued: PreissuedGrant? = null,
)

private data class PreissuedGrant(val playlist: String? = null)

private data class LegacyStreamData(
    @JsonProperty("sUb") val path: String,
    @JsonProperty("hD") val videoHash: String? = null,
)

private data class NguonCListResponse(
    val status: String? = null,
    val paginate: NguonCPagination? = null,
    val items: List<NguonCMovie> = emptyList(),
)

private data class NguonCPagination(
    @JsonProperty("current_page") val currentPage: Int = 1,
    @JsonProperty("total_page") val totalPage: Int = 1,
)

private data class NguonCDetailResponse(
    val status: String? = null,
    val movie: NguonCMovie? = null,
)

private data class NguonCMovie(
    val id: String? = null,
    val name: String? = null,
    @JsonProperty("original_name") val originalName: String? = null,
    val slug: String? = null,
    val year: Int? = null,
    val description: String? = null,
    @JsonProperty("total_episodes") val totalEpisodes: Int? = null,
    @JsonProperty("current_episode") val currentEpisode: String? = null,
    val time: String? = null,
    val quality: String? = null,
    val language: String? = null,
    val director: String? = null,
    val casts: String? = null,
    val category: Map<String, NguonCCategory>? = null,
    val tmdb: NguonCTmdb? = null,
    val imdb: NguonCImdb? = null,
    @JsonProperty("thumb_url") val thumbUrl: String? = null,
    @JsonProperty("thumb_url_webp") val thumbUrlWebp: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("poster_url_webp") val posterUrlWebp: String? = null,
    val episodes: List<NguonCEpisodeServer>? = null,
) {
    fun poster(): String? = posterUrlWebp.nonBlank() ?: posterUrl.nonBlank()
        ?: thumbUrlWebp.nonBlank() ?: thumbUrl.nonBlank()

    fun backdrop(): String? = thumbUrlWebp.nonBlank() ?: thumbUrl.nonBlank() ?: poster()

    fun toTvType(): TvType {
        val format = category.orEmpty().values
            .flatMap { it.list.orEmpty() }
            .mapNotNull(NguonCCategoryItem::name)
            .joinToString(" ")
            .lowercase()
        return when {
            format.contains("hoạt hình") || format.contains("anime") -> TvType.Anime
            totalEpisodes?.let { it > 1 } == true -> TvType.TvSeries
            currentEpisode.orEmpty().contains("tập", true) -> TvType.TvSeries
            format.contains("phim bộ") || format.contains("tv show") -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun String?.nonBlank(): String? = this?.takeIf(String::isNotBlank)
}

private data class NguonCCategory(
    val group: NguonCCategoryGroup? = null,
    val list: List<NguonCCategoryItem>? = null,
)

private data class NguonCCategoryGroup(val name: String? = null)
private data class NguonCCategoryItem(val name: String? = null)
private data class NguonCTmdb(val id: Int? = null, val type: String? = null, val season: Int? = null)
private data class NguonCImdb(val id: String? = null)

private data class NguonCEpisodeServer(
    @JsonProperty("server_name") val serverName: String? = null,
    val items: List<NguonCEpisode>? = null,
)

private data class NguonCEpisode(
    val name: String? = null,
    val slug: String? = null,
    val embed: String? = null,
)
