package me.rerere.rikkahub.service

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.service.ProactiveMessageTriggerService
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class KliWakeRuntime(
    private val context: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        if (BuildConfig.KLI_WAKE_TOKEN.isBlank() || job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { consume() }
                delay(5_000)
            }
        }
    }

    private fun consume() {
        val request = Request.Builder()
            .url(BuildConfig.KLI_WAKE_URL)
            .header("Authorization", "Bearer ${BuildConfig.KLI_WAKE_TOKEN}")
            .header("Accept", "text/event-stream")
            .build()
        client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Kli wake HTTP ${response.code}")
                val source = response.body.source()
                var event = ""
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substringAfter(':').trim()
                        line.startsWith("data:") && event == "autonomy_wake" -> handleWake(line.substringAfter(':').trim())
                        line.isEmpty() -> event = ""
                    }
                }
            }
    }

    private fun handleWake(raw: String) {
        val item = Json.parseToJsonElement(raw).jsonObject
        val id = item.getValue("id").jsonPrimitive.content.toLong()
        val prompt = item["message"]?.jsonPrimitive?.content.orEmpty()
        val intent = Intent(context, ProactiveMessageTriggerService::class.java).apply {
            putExtra(ProactiveMessageTriggerService.EXTRA_FORCE_TRIGGER, true)
            putExtra(ProactiveMessageTriggerService.EXTRA_DEVICE_EVENT_CONTEXT, prompt)
        }
        context.startForegroundService(intent)
        acknowledge(id)
    }

    private fun acknowledge(id: Long) {
        val request = Request.Builder()
            .url(BuildConfig.KLI_WAKE_URL.removeSuffix("/events") + "/events/$id/ack")
            .header("Authorization", "Bearer ${BuildConfig.KLI_WAKE_TOKEN}")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Kli wake ack HTTP ${response.code}")
        }
    }
}
