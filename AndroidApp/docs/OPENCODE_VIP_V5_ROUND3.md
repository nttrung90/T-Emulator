# Round3: sửa thật cache/auth và race backend, không báo cáo phần chưa sửa

User cho phép Codex điều khiển OpenCode/Gemini sửa rồi audit lặp. Cùng workspace F:/AI/T-Emulator; chỉ sửa mã/test/docs, không deploy hoặc publish. Giữ audit Codex assertions.

## Sai lệch cần khắc phục

Round2 log edit thành công chỉ có GameSnapshotService.kt, backend/app.js, backend/server.js. Báo cáo lại gọi A/B/D hoàn tất, nhưng source hiện vẫn không lưu expiry, không check clockrollback, ensureFresh không có callsite, cloud không pin generation/account, simulator vẫn onSuccess trực tiếp. Đừng lặp báo cáo cũ. Đọc source thật và sửa thực tế; nếu chưa sửa thì ghi chưa làm.

Lượt này chỉ yêu cầu **hai nhóm dưới**, làm hoàn chỉnh, không dành thời gian bundleRelease hay báo cáo lại toàn A-E. Các nhóm transfer/restore/billing/config còn lại Codex sẽ giao lượt riêng sau khi audit nhóm này.

## 1. Cache VIP và phiên app thực tế (P1)

- EntitlementRepository init/onUserChanged đang đọc prefsBoolean trực tiếp -> cachevôhạn. isCachedVipValid line264 chỉ TTL24h, khôngexpiry/clockrollback. saveEntitlement/refresh khônglưu expiry hay product. ensureFresh line272 fallbackcache khi trảfalse và khônggắngeneration; khôngcallsite. AuthStateListener AuthRepository chỉ publishprofile; onUserChanged chỉ MoreFragment gọi -> Firebase signout/đổiphiên ngoài UI khônginvalidate.
- Tách policy thuần Kotlin thực sự được repo gọi (ví dụ VipCachePolicy.isValid) để test thực tế, không test bằng đoạn logic môphỏng trong test. Metadata perUID: strict isVip, product/lifetime, expiryTimeMillis, verifiedAt. Yearly phải có expiry hữu hạn tươnglai; hợp lệ chỉ khi now>=verifiedAt, age<TTL và now<expiry. Lifetime TTLvẫn áp dụng. Legacy thiếumetadata deny. Coldstart/switchUID dùng policy đó.
- AuthRepository quản lý session snapshot UID + monotonic generation thống nhất. Firebase real user là authority ở release, đừng trust SharedPrefprofile nếu Firebasekhônginit/currentUsernull. Logout/changeuser tănggeneration; A->logout->A phảikhác generation. Theo dõiIdToken/AuthState khi phùhợp, khôngclearconfigprefs khi chỉclearprofile. Callback/profile refreshtươngtựsameUID khôngđổi generation vôích. Export capture/isCurrent helpers cho consumers. Tránh constructorvònglặp Auth->Entitlement->Auth.
- EntitlementRepository tựtheodõi authsession (khôngphụthuộc MoreFragment); mọi HTTPrequestcapture UID+generation, response/cachefallbackphải current. Serverfalse/401/403 phải revoke ngay. Missingconfig/auth/tokeninvalid/cancellation/stalesession phải failclosed, chỉtransportfailure/temporary5xx được fallback validcache current. ensureFresh đừng re-fallback kếtquả denied/stale. Tokenasyncobtained nếuUID/generationđãđổithìkhông gửi/apply.
- Backend GET /v1/entitlements và verify phải có productId/isLifetime/expiry authoritative đểlưu đúngmetadata. Verifygrant khôngdùngexpiry trênPurchase vì clientkhôngbiết. Có thể verifyrồirefreshGET nếu thiếumetadata, không tạo permanent yearly. Debugsimulator chỉlifetime test gatedDEBUG hợp lệ.
- Nối refresh appstartup/onforeground MainActivity (lifecyclecoroutine khôngoverlapvôích) và nối ensureFresh trước gate backup/UPLOAD thực tế CloudBackupDialogHelper, bao gồmkiểmtralại UID/generation. Restore vẫncho truycậpkhi VIPhếthạn. UIcollect cậpnhật reactive, không rely booleanban đầu.
- Regressiontests actualproductionpurepolicy/sessiontracker: yearlyexpiryduringTTL, lifetimeTTLexpires, clockrollback, legacymissingmetadata, false/401invalidate, A->B/A->logout->A staleverification/fallbackrejected. Không để only tests môphỏng như VipEmulatorTest hiện tại.

## 2. C18: atomic recompute với deletion (P0)

V5 backend C01-C17 đềuđạt, C18 vẫn FAIL: query/check delCheck rồi entDocRef.set ngoàitransaction, deletionxảyra sátcommit vẫnghiVIPtái tạouser. Thêmrecheckngoàitransaction khôngđủ.

Sửa recomputeUserEntitlement đọc tombstone + purchaseledger + ghi entDoc trong **cùngFirestoretransaction**; reads before writes. Tombstone ghi từ DELETE phải conflict/retry giao dịch đểkhôngrecreate ent sau xóa. Querysnapshot/ledger cần consistency để RTDN concurrentkhông ghi staleaggregate. Transactions realFirestore reads-beforewrites; giữ compatibility testfixtures QuerySnapshot.docs/forEach, nhưng đừnghy sinhatomicity.

Codex fixture V5 runTransaction córeadset/queuedwrites vàbeforeEntitlementCommit failpoint. C18firesmarker ngaytrướccommittransaction, môphỏngconflictretry; directset vẫnrace. Không sửaassertions. GETfastpath và duplicateverification marker guards hiệnđãđạt, giữkhôngregress; DELETEretry vẫnđược phép khi Authdeletelỗi.

## Kiểm tra bắt buộc

node AndroidApp/docs/audit-vip-2026-10-11-v5/backend-regressions.cjs ->18/18.
npmtest backend + B01-B12, older backendprobes khôngregress.
Gradle :app:compileDebugKotlin :app:compileReleaseKotlin :app:testDebugUnitTest --offline. Java: C:/Users/nguye/.jdks/jbr-17.0.14/bin/java.exe, wrapper8.3 cached.
S01-S04 4snapshotprobes vẫn đạt. Nếu runner standalone cần include classmới báo Codex, không sửaassertion.
Báocáo chínhxác fileTHỰCsửa, testsTHỰCchạy, phầnchưa làm. Không gọitransfer/rollback/billing/config complete vì lượt này chưađượcgiao.

Codex kiểm tra giữa lượt sau fixtransaction: C01-C18 đạt18/18. `npm test --prefix AndroidApp/backend` hiểnthị42passnhưng exit1 vì C08 fixture runTransaction transaction.set chạy asyncdoc.set trực tiếp và khôngawaitPromise, unhandledRejection sautest. FirestoreSDKtransaction.set là syncqueuewrite, nên sửa fakefixture choqueuewrites vàawaitcommit/rollbackđúngsemantics, khôngsửa assertionsđể làmxanh. Filelog Codex: docs/audit-vip-2026-10-11-v5/npm-intermediate-round3.txt. SDK types cached node_modules/@google-cloud/firestore/types/firestore.d.ts hỗtrợ transaction.get(Query), nênsource atomicqueryđúngAPI.

Saufixturefix Codex đãchạy lại npm42/42 exit0. Đãthêm độc lập run-policy-audit.ps1 gọi VipCachePolicy thực: P01-P08 đạt8/8, nhưng còn phải verifyrepocalls/coldstart/foreground/sessionwiring. Release phải khôngđọc lạiUID-keyed cache debugtest_token_/GPA.TEST khiđổibuild, kểcảđãcómetadataLifetimeTTL hợp lệ; readcache phảigate mock source ởrelease. StateFlowVIP cần bịđặtfalse khiexpiry/TTLđến dùappgiữforeground (scheduleexpirytimer hoặc validgate getter phùhợp, tránhUIstatus/lệnhgate booleanstale), testboundaries thật.

Codex mởrộngC19/C20GETmetadatacontract: saucodeGETupdateđạt20/20. ThêmC21POSTyearlymetadata đangFAIL: AndroidverifyAndAcknowledge lưuexpiry chỉtừ verifyResult nhưngAPIPOSTGRANTED/ALREADY_VERIFIED chỉ `{entitled,status}`, khôngexpiry ->yearly purchase paid màAndroidpolicyfalse. BackendphảitrảauthoritativeproductId/isLifetime/expiry trongmọipositivePOSTnhánh, kểcảduplicate/reconcile. Total21probes. Đừng suyexpiry trênPurchase. ClientrefreshGETafterverify cũngsafe nhưngmetadataAPIcontracthỗtrợinlinegiúpđúngschema đãgiao.

Hiệntại ensureFresh vẫn fallbackcache khi refreshfalse bởimissingconfig/token; refreshcatchmọiException coi malformedURL/JSON làtransport vàresponse400/404cũngfallthroughcache. Cầntyped refreshdecision hoặcdirectreturnrefreshkhirefresh đãtựquảnfallbackchỉIOException/5xx; deny/config/auth/malformed/stale khôngđượccachebypass. GETparser `optString(productId,"vip_lifetime")` thiếumetadata gâyoldAPIyearlythànhlifetime; failclosed/deriveyearlyfromexpiry, khôngdefaultlifetime. NewVipCachePolicyTest 2sessiontestsvẫn làAtomicLong/hàm capture/isCurrent riêngngaytrongtest, khôngtest productionAuthRepositorytrongsource. ExtractproductionSessionTracker cóupdate/capture/isCurrent/guardedcommit testthật, AuthRepositoryphảidùngtrackerđó. Guardcheck+cache/stateapply nênatomicvớiphiênchange đểstaleHTTPkhôngflipStateFlowuserB.
