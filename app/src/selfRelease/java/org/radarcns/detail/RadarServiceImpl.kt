/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.detail

import android.Manifest.permission.RECEIVE_BOOT_COMPLETED
import android.Manifest.permission.SYSTEM_ALERT_WINDOW
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration.Companion.START_AT_BOOT
import org.radarbase.android.RadarService
import org.radarbase.android.config.SingleRadarConfiguration
import org.radarbase.android.source.SourceProvider
import org.radarbase.monitor.application.ApplicationStatusProvider
import org.radarbase.passive.audio.OpenSmileAudioProvider
import org.radarbase.passive.bittium.FarosProvider
import org.radarbase.passive.empatica.E4Provider
import org.radarbase.passive.phone.PhoneBluetoothProvider
import org.radarbase.passive.phone.PhoneContactListProvider
import org.radarbase.passive.phone.PhoneLocationProvider
import org.radarbase.passive.phone.PhoneSensorProvider
import org.radarbase.passive.phone.telephony.PhoneLogProvider
import org.radarbase.passive.phone.usage.PhoneUsageProvider
import org.radarbase.passive.ppg.PhonePpgProvider
import org.radarbase.passive.weather.WeatherApiProvider
import org.radarcns.detail.UpdatesActivity.Companion.DAY
import org.radarcns.detail.UpdatesActivity.Companion.LAST_AUTO_UPDATE_CHECK_TIME_KEY
import org.radarcns.detail.UpdatesActivity.Companion.MINUTE
import org.radarcns.detail.UpdatesActivity.Companion.UPDATE_CHECK_DEFAULT_HOUR_OF_DAY
import org.radarcns.detail.UpdatesActivity.Companion.UPDATE_CHECK_DEFAULT_MINUTE
import org.radarcns.detail.UpdatesActivity.Companion.UPDATE_CHECK_INTERVAL_KEY
import org.radarcns.detail.UpdatesActivity.Companion.UPDATE_RELEASES_URL_KEY
import org.radarcns.detail.UpdatesActivity.Companion.WEEK
import java.util.*

class RadarServiceImpl : RadarService() {
    private val handler: Handler = Handler(Looper.getMainLooper())

    private var updateCheckAlarmManager: AlarmManager? = null
    private var updateCheckAlarmIntent: PendingIntent? = null

    override val plugins: List<SourceProvider<*>> = listOf(
        ApplicationStatusProvider(this),
        OpenSmileAudioProvider(this),
        E4Provider(this),
        FarosProvider(this),
        PhoneBluetoothProvider(this),
        PhoneContactListProvider(this),
        PhoneLocationProvider(this),
        PhoneSensorProvider(this),
        PhoneLogProvider(this),
        PhoneUsageProvider(this),
        PhonePpgProvider(this),
        WeatherApiProvider(this),
    )

    override val servicePermissions: List<String>
        get() = ArrayList(super.servicePermissions).apply {
            this += RECEIVE_BOOT_COMPLETED
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                this += REQUEST_IGNORE_BATTERY_OPTIMIZATIONS_COMPAT
            }
            if (configuration.latestConfig.getBoolean(START_AT_BOOT, false)) {
                this += SYSTEM_ALERT_WINDOW
            }
        }

    override fun doConfigure(config: SingleRadarConfiguration) {
        super.doConfigure(config)
        configureRunAtBoot(config, MainActivityBootStarter::class.java)

        setupUpdateCheckAlarmManager(config)
   }

    private fun setupUpdateCheckAlarmManager(config: SingleRadarConfiguration){
        val updateCheckInterval = config.getLong(UPDATE_CHECK_INTERVAL_KEY, DAY)
        val releasesUrl = config.getString(UPDATE_RELEASES_URL_KEY)
        val lastUpdateCheckTimeStamp = config.getLong(LAST_AUTO_UPDATE_CHECK_TIME_KEY, 0L)

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(
            {
                if(lastUpdateCheckTimeStamp == 0L){
                    val calendar: Calendar = Calendar.getInstance().apply {
                        timeInMillis = System.currentTimeMillis()
                        set(Calendar.HOUR_OF_DAY, UPDATE_CHECK_DEFAULT_HOUR_OF_DAY)
                        set(Calendar.MINUTE, UPDATE_CHECK_DEFAULT_MINUTE)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val newCalendar = setCalendarAfterCurrentTime(calendar, updateCheckInterval)
                    radarConfig.put(LAST_AUTO_UPDATE_CHECK_TIME_KEY, newCalendar.timeInMillis)
                    radarConfig.persistChanges()
                    createUpdateCheckAlarmManager(newCalendar, releasesUrl, updateCheckInterval)
                }else{
                    val calendar: Calendar = Calendar.getInstance().apply {
                        timeInMillis = lastUpdateCheckTimeStamp
                    }
                    val newCalendar = setCalendarAfterCurrentTime(calendar, updateCheckInterval)
                    radarConfig.put(LAST_AUTO_UPDATE_CHECK_TIME_KEY, newCalendar.timeInMillis)
                    radarConfig.persistChanges()
                    createUpdateCheckAlarmManager(newCalendar, releasesUrl, updateCheckInterval)
                }
            },
            MINUTE
        )
    }

    private fun setCalendarAfterCurrentTime(calendar: Calendar, updateCheckInterval: Long): Calendar {
        while (System.currentTimeMillis() > calendar.timeInMillis - 2 * MINUTE){
            if(updateCheckInterval == DAY) {
                calendar.add(Calendar.DATE, 1)
            } else if (updateCheckInterval == WEEK){
                calendar.add(Calendar.DATE, 7)
            }
        }
        return calendar
    }

    private fun createUpdateCheckAlarmManager(calendar: Calendar, releasesUrl: String, updateCheckInterval: Long) {
        cancelUpdateCheckAlarmManager()

        updateCheckAlarmIntent = Intent(this, UpdateAlarmReceiver::class.java).let { intent ->
            intent.putExtra(UPDATE_RELEASES_URL_KEY, releasesUrl)
            PendingIntent.getBroadcast(this, UPDATE_CHECK_INTENT_REQ_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        updateCheckAlarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        updateCheckAlarmManager!!.setRepeating(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            updateCheckInterval,
            updateCheckAlarmIntent
        )
    }

    private fun cancelUpdateCheckAlarmManager(){
        if(updateCheckAlarmManager != null && updateCheckAlarmIntent != null) {
            updateCheckAlarmManager!!.cancel(updateCheckAlarmIntent)
        }
    }

    companion object {
        const val UPDATE_CHECK_INTENT_REQ_CODE = 2879
    }
}
