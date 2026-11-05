package com.daymark.app

import android.app.Application
import android.content.Context
import com.daymark.app.data.DaymarkDatabase
import com.daymark.app.data.DaymarkStore
import com.daymark.app.sync.CloudClient
import com.daymark.app.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DaymarkApplication : Application() {
    val localStore by lazy { DaymarkStore(DaymarkDatabase(this)) }
    val cloud by lazy { CloudClient(this) }
    val store get() = cloud.activeStore ?: localStore
    private var scheduledStore: DaymarkStore? = null
    private var currentScheduler: ReminderScheduler? = null
    val scheduler: ReminderScheduler @Synchronized get() {
        val active = store
        if (scheduledStore !== active) {
            currentScheduler?.cancelAll()
            scheduledStore?.takeIf { it !== localStore }?.close()
            scheduledStore = active
            currentScheduler = ReminderScheduler(this, active)
        }
        return currentScheduler!!
    }
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() { super.onCreate(); scheduler.createChannel() }
}
val Context.daymark: DaymarkApplication get() = applicationContext as DaymarkApplication
