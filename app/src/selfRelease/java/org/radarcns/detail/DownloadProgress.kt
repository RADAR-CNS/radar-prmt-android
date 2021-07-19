package org.radarcns.detail

import android.annotation.SuppressLint
import android.content.Context
import okhttp3.*
import okhttp3.Interceptor
import okio.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.lang.Exception

class DownloadProgress(context: Context, delegate: TaskDelegate) {
    private val mContext: Context = context
    private val mDelegate: TaskDelegate = delegate

    @SuppressLint("SetWorldReadable")
    @Throws(Exception::class)
    fun run(url: String) {
        val request: Request = Request.Builder()
            .url(url)
            .build()
        val progressListener: ProgressListener = object : ProgressListener {
            var firstUpdate = true
            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                if (done) {
                    logger.info("APK Download Completed")
                } else {
                    if (firstUpdate) {
                        firstUpdate = false
                        if (contentLength == -1L) {
                            logger.info("APK content-length: unknown")
                        } else {
                            logger.info("APK content-length: {}", contentLength)
                        }
                    }
                    if (contentLength != -1L) {
                        mDelegate.taskProgressResult(100 * bytesRead / contentLength)
                    }
                }
            }
        }
        val client: OkHttpClient = OkHttpClient.Builder()
            .addNetworkInterceptor(Interceptor { chain: Interceptor.Chain ->
                val originalResponse = chain.proceed(chain.request())
                originalResponse.newBuilder()
                    .body(ProgressResponseBody(originalResponse.body, progressListener))
                    .build()
            })
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val apkData = response.body?.byteStream()

            val outputFile = File(mContext.filesDir, DOWNLOADED_FILE)
            if (outputFile.exists()) {
                outputFile.delete()
            }

            outputFile.setReadable(true, false)

            if (apkData != null) {
                try {
                    mContext.openFileOutput(DOWNLOADED_FILE, Context.MODE_PRIVATE).use { output ->
                        output.write(apkData.readBytes())
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }

            mDelegate.taskCompletionResult()
        }
    }

    private class ProgressResponseBody(
        private val responseBody: ResponseBody?,
        private val progressListener: ProgressListener
    ) :
        ResponseBody() {
        private var bufferedSource: BufferedSource? = null
        override fun contentType(): MediaType? {
            return responseBody!!.contentType()
        }

        override fun contentLength(): Long {
            return responseBody!!.contentLength()
        }

        override fun source(): BufferedSource {
            if (bufferedSource == null) {
                bufferedSource = source(responseBody!!.source()).buffer()
            }
            return bufferedSource!!
        }

        private fun source(source: Source): Source {
            return object : ForwardingSource(source) {
                var totalBytesRead = 0L

                @Throws(IOException::class)
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val bytesRead = super.read(sink, byteCount)
                    // read() returns the number of bytes read, or -1 if this source is exhausted.
                    totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                    progressListener.update(
                        totalBytesRead,
                        responseBody!!.contentLength(),
                        bytesRead == -1L
                    )
                    return bytesRead
                }
            }
        }
    }

    internal interface ProgressListener {
        fun update(bytesRead: Long, contentLength: Long, done: Boolean)
    }

    interface TaskDelegate {
        fun taskCompletionResult()
        fun taskProgressResult(progress: Number)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(LoginActivityImpl::class.java)
        const val DOWNLOADED_FILE = "app-release.apk"
    }
}