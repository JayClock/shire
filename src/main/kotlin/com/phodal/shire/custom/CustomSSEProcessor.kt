package com.phodal.shire.custom

import com.aallam.openai.api.chat.ChatCompletion
import com.fasterxml.jackson.databind.ObjectMapper
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.nfeld.jsonpathkt.JsonPath
import com.nfeld.jsonpathkt.extension.read
import com.phodal.shire.llm.LlmProvider.Companion.ChatRole
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.FlowableEmitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Request
import org.jetbrains.annotations.VisibleForTesting

/**
 * The `CustomSSEProcessor` class is responsible for processing server-sent events (SSE) in a custom manner.
 * It provides functions to stream JSON and SSE data from a given `Call` instance, and exposes properties for request and response formats.
 *
 * @property hasSuccessRequest A boolean flag indicating whether the request was successful.
 * @property requestFormat A string representing the format of the request.
 * @property responseFormat A string representing the format of the response.
 * @property logger An instance of the logger for logging purposes.
 *
 * @constructor Creates an instance of `CustomSSEProcessor`.
 */
open class CustomSSEProcessor(private val project: Project) {
    open var hasSuccessRequest: Boolean = false
    private var parseFailedResponses: MutableList<String> = mutableListOf()
    open val requestFormat: String = ""
    open val responseFormat: String = ""
    private val logger = logger<CustomSSEProcessor>()


    fun streamJson(call: Call, promptText: String, messages: MutableList<Message>): Flow<String> = callbackFlow {
        call.enqueue(JSONBodyResponseCallback(responseFormat) {
            withContext(Dispatchers.IO) {
                send(it)
            }

            messages += Message(ChatRole.Assistant.roleName(), it)
            close()
        })
        awaitClose()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun streamSSE(call: Call, promptText: String, keepHistory: Boolean = false, messages: MutableList<Message>): Flow<String> {
        val sseFlowable = Flowable
            .create({ emitter: FlowableEmitter<SSE> ->
                call.enqueue(ResponseBodyCallback(emitter, true))
            }, BackpressureStrategy.BUFFER)

        try {
            var output = ""
            return callbackFlow {
                withContext(Dispatchers.IO) {
                    sseFlowable
                        .doOnError {
                            it.printStackTrace()
                            trySend(it.message ?: "Error occurs")
                            close()
                        }
                        .blockingForEach { sse ->
                            if (responseFormat.isNotEmpty()) {
                                // {"id":"cmpl-a22a0d78fcf845be98660628fe5d995b","object":"chat.completion.chunk","created":822330,"model":"moonshot-v1-8k","choices":[{"index":0,"delta":{},"finish_reason":"stop","usage":{"prompt_tokens":434,"completion_tokens":68,"total_tokens":502}}]}
                                // in some case, the response maybe not equal to our response format, so we need to ignore it
                                // {"id":"cmpl-ac26a17e","object":"chat.completion.chunk","created":1858403,"model":"yi-34b-chat","choices":[{"delta":{"role":"assistant"},"index":0}],"content":"","lastOne":false}
                                val chunk: String? = JsonPath.parse(sse!!.data)?.read(responseFormat)

                                // new JsonPath lib caught the exception, so we need to handle when it is null
                                if (chunk == null) {
                                    parseFailedResponses.add(sse.data)
                                    logger.warn("Failed to parse response.origin response is: ${sse.data}, response format: $responseFormat")
                                } else {
                                    hasSuccessRequest = true
                                    output += chunk
                                    trySend(chunk)
                                }
                            } else {
                                val completion: ChatCompletion =
                                    ObjectMapper().readValue(sse!!.data, ChatCompletion::class.java)

                                if (completion.choices.isNotEmpty()) {
                                    val content = completion.choices[0].message?.content
                                    if (content != null ) {
                                        output += content
                                        trySend(content)
                                    }
                                } else {
                                    //
                                }
                            }
                        }

                    // when stream finished, check if any response parsed succeeded
                    // if not, notice user check response format
                    if (!hasSuccessRequest) {
                        val errorMsg = """
                                        |**Failed** to parse response.please check your response format: 
                                        |**$responseFormat** origin responses is: 
                                        |- ${parseFailedResponses.joinToString("\n- ")}
                                        |""".trimMargin()

                        // TODO add refresh feature
                        // don't use trySend, it may be ignored by 'close()` op
                        send(errorMsg)
                    }

                    messages += Message(ChatRole.Assistant.roleName(), output)
                    close()
                }
                awaitClose()
            }
        } catch (e: Exception) {
            if (hasSuccessRequest) {
                logger.info("Failed to stream", e)
            } else {
                logger.error("Failed to stream", e)
            }

            return callbackFlow {
                close()
            }
        } finally {
            parseFailedResponses.clear()
        }
    }
}

@Serializable
data class Message(val role: String, val content: String)

@Serializable
data class CustomRequest(val messages: List<Message>)

@VisibleForTesting
fun Request.Builder.appendCustomHeaders(customRequestHeader: String): Request.Builder = apply {
    runCatching {
        Json.parseToJsonElement(customRequestHeader)
            .jsonObject["customHeaders"].let { customFields ->
            customFields?.jsonObject?.forEach { (key, value) ->
                header(key, value.jsonPrimitive.content)
            }
        }
    }.onFailure {
        logger<CustomRequest>().warn("Failed to parse custom request header", it)
    }
}

@VisibleForTesting
fun JsonObject.updateCustomBody(customRequest: String): JsonObject {
    return runCatching {
        buildJsonObject {
            // copy origin object
            this@updateCustomBody.forEach { u, v -> put(u, v) }

            val customRequestJson = Json.parseToJsonElement(customRequest).jsonObject
            customRequestJson["customFields"]?.let { customFields ->
                customFields.jsonObject.forEach { (key, value) ->
                    put(key, value.jsonPrimitive)
                }
            }

            // TODO clean code with magic literals
            var roleKey = "role"
            var contentKey = "content"
            customRequestJson.jsonObject["messageKeys"]?.let {
                roleKey = it.jsonObject["role"]?.jsonPrimitive?.content ?: "role"
                contentKey = it.jsonObject["content"]?.jsonPrimitive?.content ?: "content"
            }

            val messages: JsonArray = this@updateCustomBody["messages"]?.jsonArray ?: buildJsonArray { }
            this.put("messages", buildJsonArray {
                messages.forEach { message ->
                    val role: String = message.jsonObject["role"]?.jsonPrimitive?.content ?: "user"
                    val content: String = message.jsonObject["content"]?.jsonPrimitive?.content ?: ""
                    add(buildJsonObject {
                        put(roleKey, role)
                        put(contentKey, content)
                    })
                }
            })
        }
    }.getOrElse {
        logger<CustomRequest>().error("Failed to parse custom request body", it)
        this
    }
}

fun CustomRequest.updateCustomFormat(format: String): String {
    val requestContentOri = Json.encodeToString<CustomRequest>(this)
    return Json.parseToJsonElement(requestContentOri)
        .jsonObject.updateCustomBody(format).toString()
}
