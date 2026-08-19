// SPDX-License-Identifier: Apache-2.0
package app.dibs

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.dibs.core.domain.ledger.Ledger
import app.dibs.core.domain.settings.RecurringRules
import app.dibs.core.domain.settings.SettingsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * FR-E7: recurring expenses prompt with a local notification and are **never
 * created silently** — tapping the notification opens the editor prefilled
 * from the template, and only saving writes anything. WorkManager only, no
 * services or wakelocks (NFR-12); rules are device-local reminders (D33).
 */
class RecurringWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun settingsStore(): SettingsStore

        fun ledger(): Ledger
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val store = deps.settingsStore()
        val ledger = deps.ledger()
        val today = LocalDate.now().toEpochDay()
        val settings = store.settings.first()
        val due = RecurringRules.due(settings.recurringRules, today)
        if (due.isEmpty()) return Result.success()

        if (canNotify()) {
            ensureChannel()
            due.forEachIndexed { index, rule ->
                val description = ledger.state(rule.groupId)
                    .expenses[rule.templateExpenseId]?.fields?.description
                    ?: applicationContext.getString(R.string.recurring_fallback_title)
                notify(index, rule.groupId.value, rule.templateExpenseId.value, description)
            }
        }
        store.update {
            it.copy(recurringRules = RecurringRules.advance(it.recurringRules, due.map { r -> r.id }.toSet(), today))
        }
        return Result.success()
    }

    /** Explicit runtime-permission check: the reminder silently skips when denied — never crashes. */
    private fun canNotify(): Boolean {
        val granted = android.os.Build.VERSION.SDK_INT < NOTIF_PERMISSION_SDK ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.recurring_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    @android.annotation.SuppressLint("MissingPermission") // gated by canNotify() at the call site
    private fun notify(id: Int, groupId: String, templateId: String, description: String) {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra(EXTRA_ROUTE, "group/$groupId/expense/new?duplicateOf=$templateId")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.recurring_notification_title, description))
            .setContentText(applicationContext.getString(R.string.recurring_notification_body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_BASE_ID + id, notification)
    }

    companion object {
        const val EXTRA_ROUTE = "app.dibs.nav_route"
        private const val CHANNEL_ID = "recurring"
        private const val NOTIFICATION_BASE_ID = 1000
        private const val NOTIF_PERMISSION_SDK = 33
        private const val WORK_NAME = "recurring-check"

        /** Idempotent daily schedule; battery-friendly (Doze-compliant, edge 70). */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RecurringWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
