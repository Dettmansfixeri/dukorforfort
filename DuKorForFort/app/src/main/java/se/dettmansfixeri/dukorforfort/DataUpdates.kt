package se.dettmansfixeri.dukorforfort

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Hämtar ny hastighetsdata i bakgrunden en gång per dygn, även när appen inte är öppen.
 * Bara på wifi – eller även mobildata om det är tillåtet i inställningarna.
 * Första nedladdningen görs alltid i appen (man ska se vad som laddas ner).
 */
object DataUpdates {
    private const val NAME = "hastighetsdata"

    fun schedule(ctx: Context) {
        val net = if (DataManager.mobileData.value) NetworkType.CONNECTED else NetworkType.UNMETERED
        val req = PeriodicWorkRequestBuilder<DataUpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(net).setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(ctx.applicationContext)
            .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class DataUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        DataManager.load(ctx)
        if (DataManager.data == null) return Result.success()
        DataManager.autoUpdate(ctx)
        return Result.success()
    }
}
