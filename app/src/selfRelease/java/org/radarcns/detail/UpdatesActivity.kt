package org.radarcns.detail

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.FileProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import org.radarbase.android.RadarApplication.Companion.radarConfig
import org.radarbase.android.RadarConfiguration
import org.radarcns.detail.DownloadProgress.Companion.DOWNLOADED_FILE
import java.io.File
import java.io.IOException
import java.lang.Exception
import java.util.*

class UpdatesActivity : AppCompatActivity(), DownloadProgress.TaskDelegate {

    private lateinit var config: RadarConfiguration
    private var releasesUrl: String? = null

    private lateinit var currentVersion: TextView
    private lateinit var updateStatus: TextView

    private lateinit var updateLinearLayout: LinearLayout
    private lateinit var startDownloadingButton: Button
    private lateinit var cancelUpdateButton: Button
    private lateinit var stopNotificationButton: TextView

    private lateinit var progressBarContainerLayout: ConstraintLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressBarPercent: TextView

    private lateinit var contactTextView: TextView

    private lateinit var newVersionApkUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_updates)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar).apply {
            setTitle(R.string.updates)
        })
        supportActionBar?.apply {
            setDisplayShowHomeEnabled(true)
            setDisplayHomeAsUpEnabled(true)
        }

        contactTextView = findViewById(R.id.contact)

        config = radarConfig
        config.config.observe(this, { config ->
            val newReleaseUrl = config.getString(UPDATE_RELEASES_URL_KEY)
            if( releasesUrl != newReleaseUrl ) {
                releasesUrl = newReleaseUrl
                checkForUpdates()
            }
            val contactPhone = config.optString(CONTACT_PHONE_KEY)
            val contactEmail = config.optString(CONTACT_EMAIL_KEY)
            if (contactPhone != null && contactEmail != null) {
                contactTextView.text = this.getString(R.string.update_notification_contact, contactPhone, contactEmail)
            }
        })

        currentVersion = findViewById(R.id.current_version)
        updateStatus = findViewById(R.id.update_status)

        updateLinearLayout = findViewById(R.id.update_linearLayout)
        startDownloadingButton = findViewById(R.id.start_downloading_button)
        cancelUpdateButton = findViewById(R.id.cancel_update_button)
        stopNotificationButton = findViewById(R.id.stop_notification_button)

        progressBarContainerLayout = findViewById(R.id.progressbar_container)
        progressBar = findViewById(R.id.progressBar)
        progressBarPercent = findViewById(R.id.progressbar_percent)

        // TODO not all notifications should be canceled
        val notificationMng =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationMng.cancelAll()

        setCurrentVersion()

        if (intent.extras?.getBoolean(FROM_NOTIFICATION_KEY, false) == true){
            updateLinearLayout.visibility = View.VISIBLE
            updateStatus.text = getString(
                R.string.new_version_available,
                getString(R.string.app_name),
                intent.extras?.getString(
                    "versionName", // todo
                    ""
                )
            )
            stopNotificationButton.visibility = View.VISIBLE
        }

        startDownloadingButton.setOnClickListener {
            startDownloading()
        }

        cancelUpdateButton.setOnClickListener {
            finish()
        }

        stopNotificationButton.setOnClickListener {
            config.put(UPDATE_CHECK_INTERVAL_KEY, WEEK)
            config.persistChanges()
            finish()
        }
    }

    private fun setCurrentVersion() {
        currentVersion.text = getString(R.string.currentVersion, getInstalledPackageVersion(this))
    }

    private fun checkForUpdates() {
        val url = releasesUrl
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
                        runOnUiThread {
                            val updatePackage = getUpdatePackage(this@UpdatesActivity, responseBody)
                            if (updatePackage != null) {
                                val updateStatus: TextView = findViewById(R.id.update_status)
                                newVersionApkUrl = updatePackage.get(UPDATE_VERSION_URL_KEY) as String
                                updateStatus.text = getString(
                                    R.string.new_version_available,
                                    getString(R.string.app_name),
                                    updatePackage.get(UPDATE_VERSION_NAME_KEY)
                                )
                                updateLinearLayout.visibility = View.VISIBLE
                            } else {
                                val updateStatus: TextView = findViewById(R.id.update_status)
                                updateStatus.text = getString(
                                    R.string.new_version_not_available, getString(
                                        R.string.app_name
                                    )
                                )
                                updateLinearLayout.visibility = View.GONE
                            }
                        }
                    }
                }
            })
        }
    }

    private fun startDownloading() {
        updateLinearLayout.visibility = View.GONE
        progressBarContainerLayout.visibility = View.VISIBLE
        progressBar.progress = 0

        val thread = Thread {
            try {
                val downloader = DownloadProgress(this, this)
                downloader.run(newVersionApkUrl)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        thread.start()

    }

    private fun isInstallApkValid(): Boolean {
        val fileName = DOWNLOADED_FILE
        val fileLocation = File(filesDir, fileName)

        return isPackageNameSame(this, fileLocation.absolutePath)
    }

    private fun install() {
        val fileName = DOWNLOADED_FILE
        val fileLocation = File(filesDir, fileName)

        val uri = FileProvider.getUriForFile(
            this,
            applicationContext.packageName.toString() + PROVIDER_PATH,
            fileLocation
        )
        val install = Intent(Intent.ACTION_VIEW)
        install.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        install.setDataAndType(
            uri,
            MIME_TYPE
        )
        startActivity(install)
    }

    override fun taskCompletionResult() {
        if(isInstallApkValid()) {
            install()
            progressBarContainerLayout.visibility = View.GONE
            updateLinearLayout.visibility = View.VISIBLE
        }else{
            updateStatus.text = getString(R.string.error_in_package_name)
            progressBarContainerLayout.visibility = View.GONE
        }
    }

    override fun taskProgressResult(progress: Number) {
        progressBar.progress = progress.toInt()
    }

    companion object {
        private const val MIME_TYPE = "application/vnd.android.package-archive"
        private const val PROVIDER_PATH = ".provider"

        const val UPDATE_CHECK_INTERVAL_KEY = "update_check_period"
        const val LAST_AUTO_UPDATE_CHECK_TIME_KEY = "last_update_check_timestamp"

        const val UPDATE_VERSION_URL_KEY = "updateVersionUrl"
        const val UPDATE_VERSION_NAME_KEY = "updateVersionName"

        const val FROM_NOTIFICATION_KEY = "from_notification"

        // Time intervals
        const val MINUTE = 60 * 100L
        const val DAY = AlarmManager.INTERVAL_DAY
        const val WEEK = DAY * 7

        // Update check default time
        const val UPDATE_CHECK_DEFAULT_HOUR_OF_DAY = 11
        const val UPDATE_CHECK_DEFAULT_MINUTE = 0

        // Remote Config Keys
        const val UPDATE_RELEASES_URL_KEY = "update_releases_url"
        const val CONTACT_PHONE_KEY = "contact_phone"
        const val CONTACT_EMAIL_KEY = "contact_email"
    }
}

