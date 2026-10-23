package audit

import com.temulator.app.billing.VipCachePolicy
import com.temulator.app.billing.VipCacheRecord
import com.temulator.app.billing.VipMetadataDecoder
import com.temulator.app.billing.VipMetadataDecodeResult
import com.temulator.app.billing.RefreshClassifier
import com.temulator.app.billing.RefreshDecision
import com.temulator.app.auth.AuthSessionTracker
import com.temulator.app.auth.UserAccount
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.io.IOException

fun main() {
    val verified = 1_800_000_000_000L
    val ttl = 86_400_000L
    val yearly = VipCacheRecord(true, "vip_yearly", false, verified + 3_600_000L, verified)
    val lifetime = VipCacheRecord(true, "vip_lifetime", true, null, verified)
    var failed = 0
    fun probe(name: String, body: () -> Unit) {
        try { body(); println("PASS: $name") }
        catch (e: Throwable) { failed++; println("FAIL: $name: ${e.message}") }
    }
    probe("P01 yearly cache must stop exactly at expiry even before TTL") {
        check(!VipCachePolicy.isValid(yearly, verified + 3_600_000L))
    }
    probe("P02 yearly cache valid before expiry and TTL") {
        check(VipCachePolicy.isValid(yearly, verified + 1000L))
    }
    probe("P03 lifetime cache must stop exactly at TTL") {
        check(!VipCachePolicy.isValid(lifetime, verified + ttl))
    }
    probe("P04 future verification timestamp must reject clock rollback") {
        check(!VipCachePolicy.isValid(lifetime, verified - 1L))
    }
    probe("P05 legacy yearly without expiry must fail closed") {
        check(!VipCachePolicy.isValid(yearly.copy(expiryTimeMillis = null), verified + 1000L))
    }
    probe("P06 revoked record must override otherwise valid lifetime metadata") {
        check(!VipCachePolicy.isValid(lifetime.copy(isVip = false), verified + 1000L))
    }
    probe("P07 null cache and absent verification time must fail closed") {
        check(!VipCachePolicy.isValid(null, verified))
        check(!VipCachePolicy.isValid(lifetime.copy(verifiedAt = 0L), verified))
    }
    probe("P08 yearly far future expiry must still obey cache TTL") {
        check(!VipCachePolicy.isValid(yearly.copy(expiryTimeMillis = verified + 365L * ttl), verified + ttl))
    }
    val userA = UserAccount("user_A", "A", "a@example.test", null)
    val userB = UserAccount("user_B", "B", "b@example.test", null)
    probe("Q01 production tracker must reject an old A session after logout and A login") {
        val tracker = AuthSessionTracker(userA)
        val captured = tracker.captureSession()
        tracker.invalidateSession(); tracker.updateUser(userA)
        check(!tracker.isCurrent(captured))
        check(tracker.captureSession().generation > captured.generation)
    }
    probe("Q02 stale guarded apply must not execute against the new user") {
        val tracker = AuthSessionTracker(userA)
        val captured = tracker.captureSession(); tracker.updateUser(userB)
        var applied = false
        check(tracker.commitIfCurrent(captured) { applied = true; true } == null)
        check(!applied)
        check(tracker.commitIfCurrent(tracker.captureSession()) { true } == true)
    }
    probe("Q03 profile update for the same UID must preserve the request session") {
        val tracker = AuthSessionTracker(userA)
        val captured = tracker.captureSession()
        tracker.updateUser(userA.copy(displayName = "Updated A"))
        check(tracker.isCurrent(captured))
        check(tracker.captureSession().user?.displayName == "Updated A")
    }
    probe("Q04 session transition must not interleave with guarded state application") {
        val tracker = AuthSessionTracker(userA)
        val captured = tracker.captureSession()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val switching = CountDownLatch(1); val switched = CountDownLatch(1)
        val committing = Thread {
            tracker.commitIfCurrent(captured) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val changing = Thread { switching.countDown(); tracker.updateUser(userB); switched.countDown() }
        try {
            committing.start(); check(entered.await(5, TimeUnit.SECONDS))
            changing.start(); check(switching.await(5, TimeUnit.SECONDS))
            check(!switched.await(100, TimeUnit.MILLISECONDS)) { "Session changed in the middle of guarded application" }
        } finally { release.countDown(); committing.join(5000); if (changing.isAlive) changing.join(5000) }
        check(tracker.captureSession().uid == userB.uid)
        check(!tracker.isCurrent(captured))
    }
    probe("Q05 explicit invalidation must advance generation even if already signed out") {
        val tracker = AuthSessionTracker()
        val captured = tracker.captureSession()
        tracker.invalidateSession()
        check(!tracker.isCurrent(captured))
    }
    probe("P09 fresh authoritative refresh must start cache TTL now, not at an old receipt verification date") {
        val json = JSONObject().put("isVip", true).put("productId", "vip_lifetime").put("isLifetime", true)
            .put("verifiedAt", verified - 5 * ttl)
        val decoded = VipMetadataDecoder.decode(json, verified) as? VipMetadataDecodeResult.Success ?: error("Active server response must decode")
        check(VipCachePolicy.isValid(decoded.record, verified)) { "Active lifetime denied forever because backend verifiedAt is an old purchase timestamp" }
    }
    probe("P10 missing active metadata must never default to lifetime") {
        check(VipMetadataDecoder.decode(JSONObject().put("isVip", true), verified) is VipMetadataDecodeResult.Invalid)
    }
    probe("P11 legacy yearly expiry must stay bounded when product fields are absent") {
        val decoded = VipMetadataDecoder.decode(JSONObject().put("isVip", true).put("expiryTimeMillis", verified + 1000L), verified)
            as? VipMetadataDecodeResult.Success ?: error("Legacy subscription expiry should remain bounded")
        check(!decoded.record.isLifetime)
        check(!VipCachePolicy.isValid(decoded.record, verified + 1000L))
    }
    probe("P12 malformed expiry must deny rather than produce a grant") {
        val json = JSONObject().put("isVip", true).put("productId", "vip_yearly").put("isLifetime", false).put("expiryTimeMillis", "invalid")
        check(VipMetadataDecoder.decode(json, verified) is VipMetadataDecodeResult.Invalid)
    }
    probe("P13 server inactive response must override lifetime metadata") {
        check(VipMetadataDecoder.decode(JSONObject().put("isVip", false).put("productId", "vip_lifetime").put("isLifetime", true), verified) == VipMetadataDecodeResult.Inactive)
    }
    probe("P14 only temporary server failures may permit HTTP cache fallback") {
        for (code in listOf(400, 401, 403, 404)) check(RefreshClassifier.classifyResponse(code, null, verified) is RefreshDecision.Deny)
        check(RefreshClassifier.classifyResponse(503, null, verified) is RefreshDecision.TransportFallback)
        check(RefreshClassifier.classifyResponse(200, "not JSON", verified) is RefreshDecision.Deny)
    }
    probe("P15 transport classification must deny config errors and rethrow cancellation") {
        check(RefreshClassifier.classifyException(IOException("Network outage")) is RefreshDecision.TransportFallback)
        check(RefreshClassifier.classifyException(IllegalArgumentException("Invalid backend URL")) is RefreshDecision.Deny)
        val canceled = kotlinx.coroutines.CancellationException("Canceled request")
        var propagated = false
        try { RefreshClassifier.classifyException(canceled) } catch (e: kotlinx.coroutines.CancellationException) { propagated = e === canceled }
        check(propagated)
    }
    println("VIP policy/session/metadata audit: $failed failures / 20 probes")
    check(failed == 0)
}
