package org.radarcns.detail

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.radarcns.detail.UpdatesActivity.Companion.UPDATE_VERSION_NAME_KEY
import org.radarcns.detail.UpdatesActivity.Companion.UPDATE_VERSION_URL_KEY

fun getUpdatePackage(context: Context, response: String): JSONObject? {
    val updatePackage = getUpdatePackageVersionAndUrl(response)
    val currentPackageVersion = getInstalledPackageVersion(context)

    val pattern = Regex("\\d+(\\.\\d+)+")

    val rawCurrentPackageVersion = currentPackageVersion?.let { pattern.find(it)?.value }
    val rawUpdatePackageVersion = updatePackage?.getString(UPDATE_VERSION_NAME_KEY)?.let { pattern.find(it)?.value }

    if (rawCurrentPackageVersion != rawUpdatePackageVersion) {
        return updatePackage
    }
    return null
}

fun getInstalledPackageVersion(context: Context): String? {
    try {
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        Log.e("CompareVersions", "Cannot resolve version for " + context.packageName, e)
        e.printStackTrace()
    }
    return null
}

fun getUpdatePackageVersionAndUrl(response: String): JSONObject? {
    try {
        val responseArray = JSONArray(response)
        if (responseArray.length() > 0) {
            val latestRelease = responseArray[0] as JSONObject
            val tagName = latestRelease["tag_name"]
            val browserDownloadUrl =
                ((latestRelease["assets"] as JSONArray).get(0) as JSONObject)["browser_download_url"]
            val updateApk = JSONObject()
            updateApk.put(UPDATE_VERSION_URL_KEY, browserDownloadUrl)
            updateApk.put(UPDATE_VERSION_NAME_KEY, tagName)
            return updateApk
        }
    } catch (t: Throwable) {
        Log.e("CompareVersions", "Could not parse malformed JSON: \"$response\"")
    }
    return null
}

