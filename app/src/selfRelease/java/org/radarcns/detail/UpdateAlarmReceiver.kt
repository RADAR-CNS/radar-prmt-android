package org.radarcns.detail

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import org.radarcns.detail.UpdatesActivity.Companion.UPDATE_RELEASES_URL_KEY
import org.radarcns.detail.UpdatesActivity.Companion.UPDATE_VERSION_NAME_KEY
import org.radarcns.detail.UpdatesActivity.Companion.UPDATE_VERSION_URL_KEY
import java.io.IOException

class UpdateAlarmReceiver: BroadcastReceiver() {

    private var updateNotificationConfig = true

    override fun onReceive(context: Context?, intent: Intent?) {
        if(context == null) return

        val url: String? = intent?.extras?.getString(UPDATE_RELEASES_URL_KEY)
        if(url != null){
            val request = okhttp3.Request.Builder().url(url).build()
            val client = OkHttpClient()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    e.printStackTrace()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) throw IOException("Unexpected code $response")
                        val responseBody = response.body!!.string()
                        val updatePackage = getUpdatePackage(context, responseBody)

                        if (updatePackage != null && updateNotificationConfig) {
                            val updateVersionUrl = updatePackage.getString(UPDATE_VERSION_URL_KEY)
                            val updateVersionName = updatePackage.getString(UPDATE_VERSION_NAME_KEY)
                            val repeaterIntent = Intent(context, UpdatesActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                putExtra(FROM_NOTIFICATION_KEY, true)
                                putExtra(UPDATE_VERSION_NAME_KEY, updateVersionName)
                                putExtra(UPDATE_VERSION_URL_KEY, updateVersionUrl)
                            }

                            val pendingIntent = PendingIntent.getActivity(
                                context,
                                UPDATE_NOTIFICATION_INTENT_REQ_CODE,
                                repeaterIntent,
                                PendingIntent.FLAG_CANCEL_CURRENT
                            )

                            val title = context.getString(R.string.app_name)
                            val content = context.getString(R.string.update_notification_content)

                            val builder = context.let {
                                NotificationCompat.Builder(it, UPDATE_NOTIFICATION_ID)
                                    .setSmallIcon(R.drawable.ic_baseline_update_24)
                                    .setContentTitle(title)
                                    .setContentText(content)
                                    .setContentIntent(pendingIntent)
                                    .setPriority(NotificationCompat.PRIORITY_MAX)
                            }

                            createNotificationChannel(context)

                            if (builder != null) {
                                with(context.let { NotificationManagerCompat.from(it) }) {
                                    this.notify(UPDATE_NOTIFICATION_INTENT_REQ_CODE, builder.build())
                                }
                            }
                        }
                    }
                }
            })
        }


    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val descriptionText = context.getString(R.string.update_notification_content)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(
                UPDATE_NOTIFICATION_ID,
                UPDATE_NOTIFICATION_CHANNEL_NAME,
                importance
            ).apply {
                description = descriptionText
            }

            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val FROM_NOTIFICATION_KEY = "from_notification"
        const val UPDATE_NOTIFICATION_ID = "com.radarcns.detail.update"
        const val UPDATE_NOTIFICATION_CHANNEL_NAME = "update_notification"
        const val UPDATE_NOTIFICATION_INTENT_REQ_CODE = 200
    }
}
