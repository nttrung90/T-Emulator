package com.temulator.app.billing

import org.json.JSONObject
import java.io.IOException

sealed interface VipMetadataDecodeResult {
    data class Success(val record: VipCacheRecord) : VipMetadataDecodeResult
    object Inactive : VipMetadataDecodeResult
    data class Invalid(val error: String) : VipMetadataDecodeResult
}

object VipMetadataDecoder {
    /**
     * Strictly decodes backend JSON response into a validated [VipCacheRecord].
     * Never assumes lifetime if isVip is true or metadata is missing.
     */
    fun decode(json: JSONObject?, now: Long = System.currentTimeMillis()): VipMetadataDecodeResult {
        if (json == null) return VipMetadataDecodeResult.Invalid("Missing JSON body")

        val isVip = json.optBoolean("isVip", json.optBoolean("entitled", false))
        if (!isVip) return VipMetadataDecodeResult.Inactive

        val productId = json.optString("productId", "").ifEmpty { null }
        val hasExplicitLifetime = json.has("isLifetime")
        val isLifetime = if (hasExplicitLifetime) json.optBoolean("isLifetime") else (productId == "vip_lifetime")

        // Fresh cache timestamp must be local successful check 'now' (Round3C, P09)
        val verifiedAt = now

        if (isLifetime) {
            return VipMetadataDecodeResult.Success(
                VipCacheRecord(
                    isVip = true,
                    productId = productId ?: "vip_lifetime",
                    isLifetime = true,
                    expiryTimeMillis = null,
                    verifiedAt = verifiedAt
                )
            )
        }

        // Subscriptions must have valid finite future expiryTimeMillis
        if (!json.has("expiryTimeMillis") || json.isNull("expiryTimeMillis")) {
            return VipMetadataDecodeResult.Invalid("Subscription missing expiryTimeMillis")
        }

        val expiry = json.optLong("expiryTimeMillis", 0L)
        if (expiry <= 0L) {
            return VipMetadataDecodeResult.Invalid("Invalid subscription expiry timestamp: $expiry")
        }

        return VipMetadataDecodeResult.Success(
            VipCacheRecord(
                isVip = true,
                productId = productId ?: "vip_yearly",
                isLifetime = false,
                expiryTimeMillis = expiry,
                verifiedAt = verifiedAt
            )
        )
    }
}

sealed interface RefreshDecision {
    data class Grant(val record: VipCacheRecord) : RefreshDecision
    data class Deny(val reason: String) : RefreshDecision
    data class TransportFallback(val message: String) : RefreshDecision
}

object RefreshClassifier {
    /**
     * Categorizes HTTP responses and exceptions during entitlement refresh.
     * Only true network/transport IOExceptions and temporary server 5xx allow fallback to cached policy.
     * 400, 401, 403, 404, parse errors, and cancellations must strictly deny.
     */
    fun classifyResponse(
        httpCode: Int,
        bodyJson: String?,
        now: Long = System.currentTimeMillis()
    ): RefreshDecision {
        return when (httpCode) {
            200 -> {
                try {
                    val json = if (!bodyJson.isNullOrBlank()) JSONObject(bodyJson) else null
                    when (val decoded = VipMetadataDecoder.decode(json, now)) {
                        is VipMetadataDecodeResult.Success -> RefreshDecision.Grant(decoded.record)
                        VipMetadataDecodeResult.Inactive -> RefreshDecision.Deny("Server confirmed inactive")
                        is VipMetadataDecodeResult.Invalid -> RefreshDecision.Deny(decoded.error)
                    }
                } catch (e: Exception) {
                    RefreshDecision.Deny("JSON parse failure: ${e.message}")
                }
            }
            401, 403 -> RefreshDecision.Deny("Authentication failure HTTP $httpCode")
            400, 404 -> RefreshDecision.Deny("Client error HTTP $httpCode")
            in 500..599 -> RefreshDecision.TransportFallback("Temporary server error HTTP $httpCode")
            else -> RefreshDecision.Deny("Unexpected HTTP status: $httpCode")
        }
    }

    fun classifyException(e: Throwable): RefreshDecision {
        if (e is kotlinx.coroutines.CancellationException) {
            throw e
        }
        return if (e is IOException) {
            RefreshDecision.TransportFallback("Network transport error: ${e.message}")
        } else {
            RefreshDecision.Deny("Non-transport exception: ${e.message}")
        }
    }
}
