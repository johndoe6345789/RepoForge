package com.repoforge.data.git

import org.eclipse.jgit.lib.Config
import org.eclipse.jgit.storage.file.FileBasedConfig
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.util.FS
import org.eclipse.jgit.util.SystemReader
import java.io.File

/**
 * Configures JGit for Android. Call once at startup, before any git operation.
 *
 * - JGit registers its caches as JMX MBeans by default; Android has no `javax.management`,
 *   so that would fail with NoClassDefFoundError.
 * - JGit looks for a system-wide gitconfig by running `git`; phones have no git binary and no
 *   system config, so it gets an empty one. User config lives in the app's private storage.
 */
object JGitAndroid {
    @Volatile
    private var installed = false

    fun install(configDir: File) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            WindowCacheConfig().apply {
                setExposeStatsViaJmx(false)
                setPackedGitMMAP(false)
            }.install()
            configDir.mkdirs()
            SystemReader.setInstance(AndroidSystemReader(SystemReader.getInstance(), configDir))
            installed = true
        }
    }

    private class AndroidSystemReader(private val delegate: SystemReader, private val configDir: File) : SystemReader() {
        override fun getHostname(): String = delegate.hostname
        override fun getenv(variable: String?): String? = delegate.getenv(variable)
        override fun getProperty(key: String?): String? = delegate.getProperty(key)
        override fun getCurrentTime(): Long = delegate.currentTime
        override fun getTimezone(`when`: Long): Int = delegate.getTimezone(`when`)

        override fun openUserConfig(parent: Config?, fs: FS?): FileBasedConfig =
            FileBasedConfig(parent, File(configDir, "gitconfig"), fs)

        override fun openJGitConfig(parent: Config?, fs: FS?): FileBasedConfig =
            FileBasedConfig(parent, File(configDir, "jgitconfig"), fs)

        // A file that never exists: an empty system config without running `git`.
        override fun openSystemConfig(parent: Config?, fs: FS?): FileBasedConfig =
            FileBasedConfig(parent, File(configDir, "no-system-gitconfig"), fs)
    }
}
