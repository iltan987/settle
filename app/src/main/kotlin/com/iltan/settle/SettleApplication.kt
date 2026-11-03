package com.iltan.settle

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.iltan.settle.data.drive.DriveThumbnailFetcher
import com.iltan.settle.data.media.MediaThumbnailFetcher
import java.io.File

class SettleApplication : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this).also { it.start() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(MediaThumbnailFetcher.Factory())
                add(MediaThumbnailFetcher.Key())
                add(
                    DriveThumbnailFetcher.Factory(File(cacheDir, "drive_thumbnails")) {
                        graph.drive
                    }
                )
                add(DriveThumbnailFetcher.Key())
            }
            .build()
}

/** The process-wide [AppGraph], reachable from any context. */
val Context.appGraph: AppGraph
    get() = (applicationContext as SettleApplication).graph
