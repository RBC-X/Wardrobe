package com.personal.wardrobe.data

import android.content.Context
import androidx.work.*
import com.personal.wardrobe.WardrobeApplication

class SyncWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters){
    override suspend fun doWork():Result {
        val repository=(applicationContext as WardrobeApplication).repository
        if(!repository.connection.paired)return Result.failure()
        return try{repository.sync();Result.success()}catch(error:Exception){if(error is kotlinx.coroutines.CancellationException)throw error;if(error is ApiException&&error.status in listOf(401,403,422))Result.failure()else if(runAttemptCount<5)Result.retry()else Result.failure()}
    }
    companion object{fun queue(context:Context){WorkManager.getInstance(context).enqueueUniqueWork("wardrobe-refresh",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())}}
}
