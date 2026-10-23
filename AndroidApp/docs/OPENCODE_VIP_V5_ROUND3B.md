# Round3B: sửa các khoảng hở cache/auth còn thực tế sau Round3

User ủy quyền Codex điều khiển OpenCode/Gemini sửa rồi kiểm tra lặp. Source/test/docs trong F:/AI/T-Emulator; không deploy/publish, không thay assertion audit. Round3 đã tạo policy thật và nối một số callsites, C01-C20 đạt20/20; C21 mới thêm vẫn FAIL. Sửa đúng các delta sau, không báo lại toàn bộ các nhóm như đã hoàn tất.

## 1. C21: positive POST verification thiếu metadata

Codex chạy độc lập sau Round3: docs/audit-vip-2026-10-11-v5/backend-verified-round3.txt. C21 first/duplicate yearly verify cần productId/isLifetime/expiryTimeMillis. Android verifyAndAcknowledge lưu expiry chỉ từ verifyResult nhưng backend positive POST GRANTED/ALREADY_VERIFIED/reconciled vẫn chỉ `{entitled:true,status}`. Yearly đã thanh toán sẽ bị VipCachePolicy từ chối vì expiry null.

Trả authoritative effective entitlement metadata cho TẤT CẢ positivePOSTnhánh, cảduplicate/reconcile/first, lifetime/yearly. Không dùng clientPurchase làmnguồnexpiry. Nếu deletion/recompute không cònentitlement, khôngunconditionalentitledtrue. GETmetadata đãđúng quaC19/C20, giữkhôngregress. Thêm tests counterpart vào backend/test.js (hiện52tests), giữC01-C21 externalassertions.

## 2. ensureFresh đang bypass config/auth fail

EntitlementRepository.refreshEntitlement false khi thiếubackend/token, nhưng ensureFresh line315 fallbacks cache khi false. RefreshcatchmọiException coi malformedURL/JSON làtransport vàHTTP400/404 cũngfallthroughcache. Sửa decisiontyped/pureclassifier hoặc controlflow rõ: chỉ IOException/temporary5xx mới fallback policy-validcache của currentUID/generation. Missingconfig/malformedURL/authfailure/401/403/serverfalse/invalidschema/parseerror/stalesession/cancel phải deny, không trảcachetrue. ensureFresh chỉủy quyền kếtquả refresh đãxửlýfallback, không fallbackthứhai. getIdToken hiệngenericcatchreturnsnull cần phânbiệtauthinvalid với lỗi mạng; invalid Firebase user/token invalidate central session, token-networkerrorcóthểboundedcache nếupolicy cho phép. Releaseconfig backend phảiHTTPS, blank/prefixplaceholder deny. KhôngcatchCancellationException nhưnetwork.

DEBUGtestaccount+simulatedVIP+mockDrive cầnvẫn có thể thực hiện localmockworkflow khôngbackendthật, bằng branchgated DEBUG+testUID+testmetadata hợp lệ. Release khôngđượcacceptmockmetadata. Đừnglàmtestsimulator thấtbại doápdụngmissingconfigfailclosed củarelease vàoexplicitdebugmockworkflow.

## 3. Parse metadata không được mặc định lifetime

Refreshparser hiện `optString("productId","vip_lifetime")`, `optBoolean(isLifetime,product==lifetime)` =>oldAPIyearlythiếuproduct trởthànhlifetimebỏexpiry. Failclosed nếu khôngđủmetadata, hoặc xửlýlegacycóexpiry nhưyearlybounded; tuyệtđối không suy lifetime chỉ vìisViptrue/expiryabsent. Tách decoder/policy thựcsựrepo gọi đểtestJSONObjectthực: yearlyexplicit, lifetimeexplicit, missingmetadata, malformedexpiry, serverfalse. VớiverifyPOSTnewcontractthiếumeta cũngdeny hoặcrefreshGETauthoritative, khôngfake grant.

## 4. Session tests phải test production tracker, guarded apply phải atomic

New VipCachePolicyTest testAuthSessionGenerationMonotonic vàtestStaleSessionVerification chỉ tựtạoAtomicLong/hàmcapture/isCurrentngaytrongtest, khôngtestAuthRepositorylogic. Extract production AuthSessionTracker/SessionController thuầnKotlin cóupdate/invalidate/capture/isCurrent/commitIfCurrent (hoặcAPI tươngđương), AuthRepository bắtbuộcdùngnóchoFirebaseevent/login/logout. Move session/Flow/generation state sangtrackerduynhất, khônggiữcảhaiCounter/tracker. Tests gọiactualtracker: A->B, A->logout->A, sameUIDprofileupdate khôngincrementvôích, stale apply rejected, committedupdatecurrent permitted.

Hiện check authRepo.isCurrent rồi saveEntitlementMetadata/revoke/_isVipActive riêngrẽ trênIO, Firebase/sessionđổi trênMain có thểchen giữa vàstaleresponse flipVIP userB. Guard check+metadata/stateapply bằng cùnglock/sessioncontroller vớitransition; khôngholdlock quaHTTP/network/await. StaleHTTPresponse khôngghi cache hoặcstate. Cóthểserialize verify+refreshrequests bằngmutex để responseGETfalsekhởitrướcpurchasekhôngghiđègrantmới trongcùngsession. Scope state writes UID rõ: revokeA khôngflipStateFlowB. Logout phảiinvalidatengaytrước suspendexternalcredentialcleanup đểjobcũhủy; tránh authlistener từrequestcũkhôiphụcuser khilogoutđangchạy.

## 5. Expiry state và cached mocks ởrelease

PolicyisValidđúng nhưngStateFlowisVipActive saugrant chưa cóexpiry/TTLtimer; appgiữforeground quaexpiry vẫnhiệnVIPtruevàbooleangatescũ cóthểđi tiếp. Scheduleinvalidateatmin(expiry,verifiedAt+TTL) currentUID/generation, rescheduleoncachechange/sessionchange/revoke; khônggiữcallback cũflipuserB. Coldstart/sessionchange cùngpolicyđãđúng, giữ. Readerreject UID-keyed test_token_/GPA.TEST ởrelease kểcảcachemetadata mới cólifetimeTTL, migrate chỉpurgeunkeyedchưađủ. Addunitcases actual reader/sourcepolicy debugcachegating vàscheduledexpiry controller nếuextractpure clock.

## Validation

Codex bổsung test độc lập actualclasses giữa lượt: AuthSessionTracker Q01-Q05đạt (ABA,staleguard,profileupdate,atomicconcurrenttransition,explicitinvalid). VipMetadataDecoder/RefreshClassifier P09-P15 added trongrun-policy-audit.ps1. **P09 đangFAIL**: decoder lấy JSONverifiedAt(backendgiữngàyverify purchase cũ trongentdoc) làm thờiđiểmcachefreshness; lifetime mua5ngàytrước bịdeny mãi dùserverrefreshHTTP200isViptrue. Cache verifiedAt phải là thờiđiểmvừa kiểmtraauthority (`now` được truyềndecoder/apply), đừngdùngreceipt/historytimestamp đểTTL; cóthểgiữreceiptVerifiedAtseparate nếuUIcần. P10-P15decode/errorclassifykhácđạt. Totalpolicy/session/metadata20probes hiện19pass1fail; khôngsửaassertion. S05archive roundtrip mớiđượcCodexthêmchoRound4 vàvẫnFAIL, đừngbáoALLsnapshotgreenhiệnchưa5/5; cóthểđểS05 choRound4 theo scope nhưng reportđúng.

21/21backendprobes, backendnpmtest exit0 khôngunhandledPromise. Policyaudit8P01-P08giữ. Newtests phải productionfunctions/trackers chứkhôngcopymodel logic trongtest. Gradle debug/release compile + testDebugUnitTest --offline. Khi thêm purehelperAPI hãybáođểCodex viếtprobe độc lập. KhôngrerunbundleRelease/đổiversion. Sauhoànthành Codex sẽauditrồi giao Round4transfer/restore; nhữngmụcđó vàbilling/config/ackjob vẫnchưa hoànthành, khôngđánh dấuđãxong.
