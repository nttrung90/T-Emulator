# Round5: Billing lifecycle, release configuration, durable acknowledgement

User cho phép Codex điều khiển OpenCode/Gemini sửa/auditlặp trongworkspace F:/AI/T-Emulator. Sửa source/test/docs, không deploy/publish/đổiversion/ghi secrets. Giữ audit assertions, không báo lại phần trước chưa chứng minh. Đọc trạng thái code hiện tại sauRound3B/Round4 trước chỉnh, giữ APIhelpers session/coordinator mới.

## 1. Billing callback và query errors

Round3 đã bỏ callback onSuccess trực tiếp trong simulated success và thêm callback fallback; không cần sửa lại phần đã đúng. Nhưng pendingPurchaseCallback vẫn barefunction global, chưa scope actionID+appUID/generation+Activitylifecycle. launchBillingFlow false còn giữcallback; simulator error/cancel/negativebutton chưaclearpending; queryPurchases chỉboolean, mộtquery lỗi/mộtqueryempty cóthể báo khôngsởhữu, startConnectionfailure chưacompletion.

Capture immutable purchaseaction/session/lifecycle khi launch; verify chạychođúngrequestUID/generation, callback chỉconsume đúngactionđãcaptured, khôngconsume callback mới từverify cũ. Exactlyonce vàonlyafter server/debugauthoritativeverify; clear khi launchfailure/error/cancel/activitydestroy/paywallcancel. Dialog dismiss do user cancel cầnclear; dismiss do positive launchingPlayflow khônghủypurchasecallbackvôích. Debugsimulator pending/error/cancel giữstateđúng vàkhôngcompletefake; fallbackactivateTestVip phải currentcapturedsession, khônggrantuserkhác sauđổiphiên. Callback/UItoastSuccess chỉsauverified, không trướcasyncverify.

Thay queryPurchases boolean bằng resulttyped (Verified/NotOwned/Pending/Error hoặcAPI tươngđương), nối tấtcảcallers VipDialogHelper/MoreFragment/ITEM_ALREADY_OWNED. ChỉNotOwned khi cảSUBS vàINAPPqueries thànhcông, empty/khônggói; mộtquerylỗivàmộtempty phảiError. Nếu mộtqueryvalidverifiedVIPcóthểsuccess nhưngkhông che error liênquan; backendverifydeny/error/phipeExpired distingushrõ theoAPI. Connectionfailure phảiinvoke completion errorđúngonce, khôngtreo. Khônglookup kếtquảpurchase queryđãfailure. Callbacksequence/dispatcheratomic safe. Clear obsolete ProductDetails khi refresh/queryfails đểtránhlaunch staleoffers.

Tách actualproduction PendingPurchaseAction/QueryResult reducer đểtestdeterministic nếu khôngmockBillingSDK; repo phải dùnghelpers đó, khôngcopylogictrongtest. Tests: actionA->sessionB, sessionABA, requestold->callbacknew, cancel/destroy/launchfailure, simulatorcallbackonce, queryoneerror+oneempty, connectionfailure/completiononce, verifiedthenresume đúngpendingfeature.

## 2. Firebase/OAuth/backend config wiring và validation

Manifest cũ cóYOUR_GOOGLE_WEB_CLIENT_ID vàbackendempty; chưaFirebaseApp bootstrap/options/google-servicesplugin. CompilebundlekhôngchứngminhGooglelogin. Tạo Gradleproperties/env publicconfig (FirebaseapplicationId/apiKey/projectId, GoogleWebClientID, backendHTTPSURL) hoặcoptionalgoogle-services.json đúngwiring; không yêu cầutảipluginonline khiofflinecachekhôngcó, FirebaseOptions bootstrap programmaticcachedSDK đủ. Applicationstartup khởitạoFirebaseApp trướcAuthRepository; manifest/buildconfig metadata từcấuhìnhactual, khôngliteralplaceholder.

Releasevalidate task phát hiệnmissing/invalidconfig bằngtênkey khônginvalues; distributionbundle/assembleRelease task phải failclear khi thiếuconfig, compile/tests vẫnchạy được unconfigured local vàruntime auth/cloudfailclosed cóthôngbáo rõ. Debugtest/mockworkflowchỉDEBUG. Khôngbịa credentials, khôngnhúng serviceaccountprivatekey/backendsecret trongAPK. getServerClientId/getBackendURL validationblank/placeholder/HTTPS; profile signout giữconfigkeys đúng; publicprefs override ởrelease cầnvalidate nhưcấuhìnhbuild.

Thêm docs setupenvnames/config vàcáchvalidate/runLicenseTester/Drive E2E, chờprojectownerconfig thực. Sourcewiring/validation phảihoànthành; chưadevice/OAuthproductionactual thìbáoUNVERIFIED/E2Epending, khôngnóiruntimeGoogleđãpass.

## 3. Durable backend acknowledge retry ngoài clientrequest

Hiện ackfailed chỉretry nếuclientgọi verifyagain; appthoát làjobmất vàpurchasecóthểauto-refund. Persist pending acktask cùngpurchasecommit vớiowner/tokenHash/product/status/attempt/nextAttemptAt, encrypted purchaseToken hoặc vaultreference private cóđủđểSDKack ngoàirequest. Khônglog rawtoken. Configencryptionkeyprivate serverenv validate/keyrotationstrategy; khônghardcodefallbackkey ởproduction. Testonly injectedkey/vault acceptable, notifyCodexnếuauditfixtures cầninjectconfig (khôngđổiassertions). Khôngmất purchasegrant khiacktemporarilyfails, nhưngpendingjobphảidurable trước positive response.

Protected retry endpoint/jobrunner usable ngoài app (CloudScheduler/OIDC tươngđương). Authenticate expectedissuer/audience/trustedserviceaccount; missingverifier/config failclosed; userFirebaseBearer khôngđượccalljob. Boundedbatch+backoff, lease/idempotency concurrency; persistfail trảretry5xx. RequeryPlay/ownership/voided/tombstone trướcack, no grant từjobs khiaccountdeleted/refunded/unrelatedproduct. MissingackAPImustthrow chứ khôngundefinedđượccoi success. Markacked onlysauPlayacksuccess, retryauthSDKtemporaryerror. Mộtjobbadkhôngstalltoànbatch; permanentinvalid/deleted states terminal, giữrecords/chẩnđoán sanitized. Khôngcoi429/5xx làpermanent. Timing/windowconfigbounded phùhợpPlayackdeadline, docsSchedulerfrequency/backoff/alerts.

Server entrypoint wiresrunner/OIDC/encryption. DocsCloudSchedulerinvocation/envsetup đủconcrete đểownertriểnkhai; khôngdeployjob thậttrongtask. Testsimulatedserverrestart cùngdurablerecords nhưngkhôngclientverifyretry vẫnackthànhcông; duplicateworker exactlyonce/lease, revoked/deletedjobnongrant, encryptedtokennotplaintext/log, failedackpersistretry, unauthorizedendpointdeny. In-memorymocks chỉexplicit nonproductiontests, strictproductiondeps khôngfallback.

## Validation / kết quả

Giữ backend C01-C21, oldV2/V3/V4 regressions, allnewproductiontests/backendnpmtest exit0, policy/session/transfer/restoretests vàS01-S04/T01-T06. Gradle debug/releasecompile + testDebugUnitTest --offline. Validate release config task missingconfig phảiexpectedfailclear, testfixturevalidconfig phảipass khôngdùngproductionsecrets; khôngphát hànhactualbundle. Reportactualfiles/tests/limits vàmọiphầncònchưa làm đểCodexaudit tiếp. Khônggọi tấtcảhoànthành dựa trêncompilemộtmình.

Purchase preflight: never launch real Play charge if Firebase/backend verification config is missing or invalid; show actionable configuration error before billing. Debug simulator may remain DEBUG-only. Test actual preflight helper plus consumer wiring. Avoid charging user then discovering backend URL absent after payment.
Auth source audit: signOut currently invalidates session then suspends credential cleanup before Firebase.signOut. AuthStateListener may observe old Firebase user during this interval. Prevent stale listener from restoring user/VIP while logout in progress, and fence overlapping login attempts without logging out a newer user. Add deterministic production state reducer/operation tests if this race is confirmed in current source.

C22 deterministic actual Express handler audit added in docs/audit-vip-2026-10-11-v5/backend-ack-audit.cjs: delete products.acknowledge from injected publisher, verify valid purchase. Current code still marks acknowledgementState=1 because acknowledgePlayPurchase returns undefined without API. Preserve entitlement but keep ackState0/retry when API unavailable; never use products acknowledge fallback for subscriptions if subscription endpoint missing. Keep C22 assertions.
