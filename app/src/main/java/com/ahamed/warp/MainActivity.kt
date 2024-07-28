package com.ahamed.warp

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ahamed.warp.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
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

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient()
    private var successCount = 0
    private var failCount = 0
    private var requestJob: Job? = null
    private var isActive = false

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnStart.setOnClickListener {
            val strID = binding.etId.editableText.toString()
            val strLimit = binding.etLimit.editableText.toString()

            if (strID.isEmpty()) {
                binding.etId.error = "Can't be empty"
                return@setOnClickListener
            }

            if (strLimit.isEmpty()) {
                binding.etLimit.error = "Can't be empty"
                return@setOnClickListener
            }
            isActive = true
            startRequestLoop(strID, strLimit.toInt())
        }
        binding.btnStop.setOnClickListener {
            stopRequestLoop()
        }


    }

    private fun startRequestLoop(clientId: String, limit: Int) {
        requestJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                val responseCode = makeRequest(clientId)
                withContext(Dispatchers.Main) {
                    if (responseCode == 200) {
                        successCount++
                        binding.tvPass.text =
                            "PASSED: +1GB (total: ${successCount}GB, failed: $failCount)"
                        binding.tvLog.text =
                            "PASSED: +1GB (total: ${successCount}GB, failed: $failCount)"

                    } else {
                        failCount++
                        binding.tvPass.text =
                            "PASSED: +1GB (total: ${successCount}GB, failed: $failCount)"
                        binding.tvLog.text =
                            "PASSED: +1GB (total: ${successCount}GB, failed: $failCount)"

                        binding.tvLog.text = "FAILED: $responseCode"
                    }
                }
                val cooldownTime = Random.nextInt(30, 50 + 1)
                println("Sleep: $cooldownTime seconds.")
                binding.tvLog.text = "Sleep: $cooldownTime seconds."
                delay(cooldownTime * 1000L)
            }
        }
    }

    private fun stopRequestLoop() {
        isActive = false
        requestJob?.cancel()
        binding.tvLog.text = "Request loop stopped."
    }

    private fun genString(stringLength: Int): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..stringLength).map { chars.random() }.joinToString("")
    }

    private fun digitString(stringLength: Int): String {
        val digits = ('0'..'9')
        return (1..stringLength).map { digits.random() }.joinToString("")
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
                .addHeader("Host", "api.cloudflareclient.com").addHeader("Connection", "Keep-Alive")
                .addHeader("Accept-Encoding", "gzip").addHeader("User-Agent", "okhttp/3.12.1")
                .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().code
        }
    }

    companion object {
        const val WARP_CLIENT_ID = "8c0db723-25f4-4689-ba5d-5c5060cb9447"
    }

}