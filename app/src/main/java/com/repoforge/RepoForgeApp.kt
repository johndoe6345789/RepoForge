package com.repoforge

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.repoforge.data.account.AccountStore
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class RepoForgeApp : Application(), SingletonImageLoader.Factory {

    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .cache(Cache(File(cacheDir, "http"), 20L * 1024 * 1024))
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "RepoForge/${BuildConfig.VERSION_NAME}").build())
            }
            .build()
    }

    val accountStore: AccountStore by lazy { AccountStore(this) }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttp })) }
            .crossfade(true)
            .build()
}
