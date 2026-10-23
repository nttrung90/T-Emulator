package com.temulator.app.billing

data class VipCacheRecord(
    val isVip: Boolean,
    val productId: String?,
    val isLifetime: Boolean,
    val expiryTimeMillis: Long?,
    val verifiedAt: Long
)

object VipCachePolicy {
    const val DEFAULT_TTL_MILLIS: Long = 24 * 60 * 60 * 1000L // 24 hours

    /**
     * Strictly verifies whether a cached VIP entitlement record is valid.
     * Rules:
     * 1. Record must not be null and isVip must be true.
     * 2. verifiedAt must be positive (> 0).
     * 3. now >= verifiedAt (clock rollback / future timestamp check).
     * 4. now - verifiedAt < ttlMillis (TTL age check).
     * 5. If isLifetime == true: valid if within TTL.
     * 6. If not lifetime (yearly subscription):
     *    - expiryTimeMillis must be non-null and > 0 (legacy missing metadata denied).
     *    - now < expiryTimeMillis (must not be expired, even if age is within TTL).
     */
    fun isValid(
        record: VipCacheRecord?,
        now: Long = System.currentTimeMillis(),
        ttlMillis: Long = DEFAULT_TTL_MILLIS
    ): Boolean {
        if (record == null || !record.isVip) return false
        if (record.verifiedAt <= 0L) return false

        // Clock rollback check: verifiedAt in the future is invalid
        if (now < record.verifiedAt) return false

        val age = now - record.verifiedAt
        if (age < 0L || age >= ttlMillis) return false

        return if (record.isLifetime) {
            true
        } else {
            val expiry = record.expiryTimeMillis
            expiry != null && expiry > 0L && now < expiry
        }
    }
}
