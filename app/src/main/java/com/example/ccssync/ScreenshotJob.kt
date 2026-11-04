package com.example.ccssync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore

/**
 * Runs when a new image is saved, even if the app is closed. Android wakes it
 * up; it checks for new CCS trip screenshots, then re-arms itself.
 */
class ScreenshotJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                Sync.scanNew(applicationContext, notify = true)
            } catch (e: Exception) {
                Prefs.addLog(applicationContext, "Background check failed: ${e.message}")
            }
            jobFinished(params, false)
            schedule(applicationContext)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        schedule(applicationContext)
        return false
    }

    companion object {
        private const val JOB_ID = 1001

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, ScreenshotJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                .setTriggerContentUpdateDelay(3000)
                .setTriggerContentMaxDelay(15000)
                .build()
            js.schedule(job)
        }
    }
}

/** Re-arms the screenshot watcher after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ScreenshotJob.schedule(context)
    }
}
