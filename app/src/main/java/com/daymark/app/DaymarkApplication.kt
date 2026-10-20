package com.daymark.app

import android.app.Application
import android.content.Context
import com.daymark.app.data.DaymarkDatabase
import com.daymark.app.data.DaymarkStore
import com.daymark.app.reminders.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DaymarkApplication : Application() {
    val store by lazy { DaymarkStore(DaymarkDatabase(this)) }
    val scheduler by lazy { ReminderScheduler(this, store) }
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onCreate() { super.onCreate(); scheduler.createChannel() }
}
val Context.daymark: DaymarkApplication get() = applicationContext as DaymarkApplication
