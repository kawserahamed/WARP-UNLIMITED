package com.ahamed.warp.viewmodel

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Clock
import java.time.Instant
import kotlin.random.Random

/**
 * Drives the registration request loop.
 *
 * All collaborators are constructor-injected with production defaults so that tests can supply a
 * local MockWebServer URL, a virtual-time dispatcher, a fixed [Clock] and a seeded [Random].
 * Because every parameter has a default, Kotlin still emits a no-argument constructor, so
 * `by activityViewModels()` keeps working unchanged.
 */
class WarpViewModel(
    private val client: OkHttpClient = OkHttpClient(),
    private val baseUrl: HttpUrl = DEFAULT_BASE_URL,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
) : ViewModel() {
    val isActive = MutableLiveData<Boolean>()
    val timeDown = MutableLiveData<Long>(0L)
    val failCount = MutableLiveData<Int>(0)
    val successCount = MutableLiveData<Int>(0)
    val log = MutableLiveData<String>()
    private var requestJob: Job? = null

    fun startRequestLoop(clientId: String) {
        isActive.postValue(true)
        requestJob = viewModelScope.launch(ioDispatcher) {
            while (isActive.value == true) {
                val responseCode = makeRequest(clientId)
                withContext(Dispatchers.Main) {
                    if (responseCode == 200) {
                        successCount.value = (successCount.value ?: 0) + 1
                        log.value =
                            "PASSED: +1GB (total: ${successCount.value}GB, failed: ${failCount.value})"
                    } else {
                        failCount.value = (failCount.value ?: 0) + 1
                        log.value = "FAILED: $responseCode"
                    }
                }
                val cooldownTime = random.nextInt(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS + 1)
                log.postValue("Processing.......")
                val coolingTime = cooldownTime * 1000L
                timeDown.postValue(coolingTime)
                delay(coolingTime)
            }
        }
    }

    fun stopRequestLoop() {
        isActive.postValue(false)
        timeDown.postValue(0)
        requestJob?.cancel()
        log.value = "Request Stopped."
    }

    /**
     * Issues a single registration request and returns the HTTP status code.
     *
     * Visible for testing so that request construction and status-code handling can be asserted
     * without driving the surrounding loop.
     */
    @VisibleForTesting
    internal suspend fun makeRequest(clientId: String): Int {
        val requestBody = buildRequestBody(clientId).toRequestBody(JSON_MEDIA_TYPE)
        val url = baseUrl.newBuilder()
            .addPathSegment("v0a${digitString(API_VERSION_DIGITS)}")
            .addPathSegment("reg")
            .build()
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", CONTENT_TYPE)
            .build()

        return withContext(ioDispatcher) {
            client.newCall(request).execute().code
        }
    }

    @VisibleForTesting
    internal fun buildRequestBody(clientId: String): String {
        val installId = genString(INSTALL_ID_LENGTH)
        // Retains the original millisecond-precision semantics of `Date().toInstant()`.
        val timestamp = Instant.ofEpochMilli(clock.millis()).toString()
        return """
            {
              "key": "${genString(KEY_LENGTH)}=",
              "install_id": "$installId",
              "fcm_token": "$installId:APA91b${genString(FCM_SUFFIX_LENGTH)}",
              "referrer": "$clientId",
              "warp_enabled": false,
              "tos": "${timestamp.substring(0, TOS_TIMESTAMP_LENGTH)}+02:00",
              "type": "Android",
              "locale": "es_ES"
            }
        """.trimIndent()
    }

    private fun genString(stringLength: Int): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..stringLength).map { chars.random(random) }.joinToString("")
    }

    private fun digitString(stringLength: Int): String {
        val digits = ('0'..'9')
        return (1..stringLength).map { digits.random(random) }.joinToString("")
    }

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://api.cloudflareclient.com/".toHttpUrl()
        const val CONTENT_TYPE = "application/json; charset=UTF-8"
        val JSON_MEDIA_TYPE = CONTENT_TYPE.toMediaTypeOrNull()

        const val INSTALL_ID_LENGTH = 22
        const val KEY_LENGTH = 43
        const val FCM_SUFFIX_LENGTH = 134
        const val API_VERSION_DIGITS = 3
        const val TOS_TIMESTAMP_LENGTH = 23
        const val MIN_COOLDOWN_SECONDS = 30
        const val MAX_COOLDOWN_SECONDS = 50
    }
}
