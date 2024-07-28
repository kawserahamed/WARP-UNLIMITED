package com.ahamed.warp.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Date
import kotlin.random.Random


class WarpViewModel : ViewModel() {
    val isActive = MutableLiveData<Boolean>()
    val timeDown = MutableLiveData<Long>(0L)
    val failCount = MutableLiveData<Int>(0)
    val successCount = MutableLiveData<Int>(0)
    val log = MutableLiveData<String>()
    private val client = OkHttpClient()
    private var requestJob: Job? = null

    fun startRequestLoop(clientId: String) {
        isActive.postValue(true)
        requestJob = viewModelScope.launch(Dispatchers.IO) {
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
                val cooldownTime = Random.nextInt(30, 51)
                log.postValue("Processing.......")
                val coolingTime = cooldownTime * 1000L
                timeDown.postValue(coolingTime)  // Use postValue instead of setValue
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

    private suspend fun makeRequest(clientId: String): Int {
        val installId = genString(22)
        val body = """
            {
              "key": "${genString(43)}=",
              "install_id": "$installId",
              "fcm_token": "$installId:APA91b${genString(134)}",
              "referrer": "$clientId",
              "warp_enabled": false,
              "tos": "${Date().toInstant().toString().substring(0, 23)}+02:00",
              "type": "Android",
              "locale": "es_ES"
            }
        """.trimIndent()

        val mediaType = "application/json; charset=UTF-8".toMediaTypeOrNull()
        val requestBody = body.toRequestBody(mediaType)
        val request =
            Request.Builder().url("https://api.cloudflareclient.com/v0a${digitString(3)}/reg")
                .post(requestBody).addHeader("Content-Type", "application/json; charset=UTF-8")
                .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().code
        }
    }

    private fun genString(stringLength: Int): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..stringLength).map { chars.random() }.joinToString("")
    }

    private fun digitString(stringLength: Int): String {
        val digits = ('0'..'9')
        return (1..stringLength).map { digits.random() }.joinToString("")
    }
}