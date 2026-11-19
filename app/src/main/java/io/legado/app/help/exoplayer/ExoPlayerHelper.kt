package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.gson.reflect.TypeToken
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import io.legado.app.utils.externalCache
import okhttp3.CacheControl
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.TimeUnit


@Suppress("unused")
@SuppressLint("UnsafeOptInUsageError")
object ExoPlayerHelper {

    private const val SPLIT_TAG = "\uD83D\uDEA7"

    // Audio-only playback: bounded buffer footprint for low-memory Android devices.
    internal const val AUDIO_PLAYER_TARGET_BUFFER_BYTES = 8 * 1024 * 1024
    private const val AUDIO_PLAYER_MIN_BUFFER_MS = 10_000
    private const val AUDIO_PLAYER_MAX_BUFFER_MS = 30_000
    private const val AUDIO_PLAYER_BUFFER_FOR_PLAYBACK_MS = 1_000
    private const val AUDIO_PLAYER_BUFFER_AFTER_REBUFFER_MS = 2_000

    private val mapType by lazy {
        object : TypeToken<Map<String, String>>() {}.type
    }

    fun createMediaItem(url: String, headers: Map<String, String>): MediaItem {
        val formatUrl = url + SPLIT_TAG + GSON.toJson(headers, mapType)
        val builder = MediaItem.Builder().setUri(formatUrl)
        // SPLIT_TAG 破坏了 URI 后缀，DefaultMediaSourceFactory 无法据扩展名推断类型
        val lastPathSegment = Uri.parse(url).lastPathSegment.orEmpty()
        if (lastPathSegment.endsWith(".mpd", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_MPD)
        } else if (url.contains(".m3u8", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        return builder.build()
    }

    fun createHttpExoPlayer(context: Context): ExoPlayer {
        return ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder()
                // 仅供有声书/网络音频播放：避免低内存设备因异常媒体/长缓冲造成过量分配。
                .setTargetBufferBytes(AUDIO_PLAYER_TARGET_BUFFER_BYTES)
                .setBufferDurationsMs(
                    AUDIO_PLAYER_MIN_BUFFER_MS,
                    AUDIO_PLAYER_MAX_BUFFER_MS,
                    AUDIO_PLAYER_BUFFER_FOR_PLAYBACK_MS,
                    AUDIO_PLAYER_BUFFER_AFTER_REBUFFER_MS,
                )
                .build()

        ).setMediaSourceFactory(
            DefaultMediaSourceFactory(context)
                .setDataSourceFactory(resolvingDataSource)
                .setLiveTargetOffsetMs(5000)
        ).build()
    }




    internal val resolvingDataSource: ResolvingDataSource.Factory by lazy {
        // DefaultDataSource 按已剥离 SPLIT_TAG 的 URI scheme 分流：
        // file/无 scheme -> FileDataSource，content/asset/rawresource/android.resource/data/udp
        // 走各自本地数据源；其余（含 http/https，以及缺少 RTMP 扩展时的 rtmp）
        // 回落到 CacheDataSource -> OkHttpDataSource。
        ResolvingDataSource.Factory(DefaultDataSource.Factory(appCtx, cacheDataSourceFactory)) {
            var res = it

            if (it.uri.toString().contains(SPLIT_TAG)) {
                val urls = it.uri.toString().split(SPLIT_TAG)
                val url = urls[0]
                res = res.withUri(Uri.parse(url))
                try {
                    val headers: Map<String, String> = GSON.fromJson(urls[1], mapType)
                    okhttpDataFactory.setDefaultRequestProperties(headers)
                } catch (_: Exception) {
                }
            }

            res

        }
    }


    /**
     * 支持缓存的DataSource.Factory
     */
    private val cacheDataSourceFactory by lazy {
        //使用自定义的CacheDataSource以支持设置UA
        return@lazy CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(okhttpDataFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(cache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
    }

    /**
     * Okhttp DataSource.Factory
     */
    internal val okhttpDataFactory by lazy {
        val client = okHttpClient.newBuilder()
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
        OkHttpDataSource.Factory(client)
            .setCacheControl(CacheControl.Builder().maxAge(1, TimeUnit.DAYS).build())
    }

    /**
     * Exoplayer 内置的缓存
     */
    internal val cache: Cache by lazy {
        val databaseProvider = StandaloneDatabaseProvider(appCtx)
        return@lazy SimpleCache(
            //Exoplayer的缓存路径
            File(appCtx.externalCache, "exoplayer"),
            //100M的缓存
            LeastRecentlyUsedCacheEvictor((100 * 1024 * 1024).toLong()),
            //记录缓存的数据库
            databaseProvider
        )
    }

    /**
     * 通过kotlin扩展函数+反射实现CacheDataSource.Factory设置默认请求头
     * 需要添加混淆规则 -keepclassmembers class com.google.android.exoplayer2.upstream.cache.CacheDataSource$Factory{upstreamDataSourceFactory;}
     * @param headers
     * @return
     */
//    private fun CacheDataSource.Factory.setDefaultRequestProperties(headers: Map<String, String> = mapOf()): CacheDataSource.Factory {
//        val declaredField = this.javaClass.getDeclaredField("upstreamDataSourceFactory")
//        declaredField.isAccessible = true
//        val df = declaredField[this] as DataSource.Factory
//        if (df is OkHttpDataSource.Factory) {
//            df.setDefaultRequestProperties(headers)
//        }
//        return this
//    }

}
