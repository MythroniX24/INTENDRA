package com.interndra.util

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * ImageCacheUtil — Singleton Coil ImageLoader with aggressive caching
 * for rendered diagrams (Mermaid, screenshots) and loaded images.
 *
 * - Disk:  256 MB LRU cache (survives app restarts)
 * - Memory: 128 MB (based on 25% of available heap)
 * - Network: 30s timeout, follows redirects
 */
object ImageCacheUtil {

    private var _imageLoader: ImageLoader? = null

    /**
     * Get or create the cached [ImageLoader].
     * Call once from Application.onCreate() for best performance,
     * but safe to call multiple times (reuses same instance).
     */
    fun getImageLoader(context: Context): ImageLoader {
        val existing = _imageLoader
        if (existing != null) return existing

        val appContext = context.applicationContext
        val cacheDir = appContext.cacheDir.resolve("coil_cache")

        val loader = ImageLoader.Builder(appContext)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.25) // 25% of available heap (~128 MB typical)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir)
                    .maxSizeBytes(256L * 1024 * 1024) // 256 MB
                    .build()
            }
            .crossfade(true)
            .crossfade(300)
            .okHttpClient {
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
            }
            .build()

        _imageLoader = loader
        return loader
    }

    /** Clear both memory and disk caches */
    suspend fun clearCache(context: Context) {
        val loader = getImageLoader(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    /** Approximate disk cache size in bytes */
    fun diskCacheSize(): Long? = _imageLoader?.diskCache?.size
}
