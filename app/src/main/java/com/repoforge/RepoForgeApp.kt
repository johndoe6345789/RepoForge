package com.repoforge

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.repoforge.data.AppSettings
import android.os.Build
import android.os.Environment
import com.repoforge.data.account.AccountStore
import com.repoforge.data.ci.ArtifactFiles
import com.repoforge.data.git.CloneStore
import com.repoforge.data.git.GitService
import com.repoforge.data.git.JGitAndroid
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

    val settings: AppSettings by lazy { AppSettings(this) }

    val clones: CloneStore by lazy { CloneStore(this).also { it.prune() } }

    val git = GitService()

    val artifacts: ArtifactFiles by lazy { ArtifactFiles(this) }

    override fun onCreate() {
        super.onCreate()
        JGitAndroid.install(File(filesDir, "git-config"))
    }

    /** Where new clones go: app storage by default, or Documents/RepoForge when enabled and allowed. */
    fun cloneRoot(): File {
        if (settings.sharedClones.value && hasAllFilesAccess()) {
            @Suppress("DEPRECATION")
            return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "RepoForge")
        }
        return getExternalFilesDir("repos") ?: File(filesDir, "repos")
    }

    fun hasAllFilesAccess(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Private working copies used to update pull request branches. */
    fun mergeWorkspaceRoot(): File = File(noBackupFilesDir, "merge-workspaces")

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttp })) }
            .crossfade(true)
            .build()
}
