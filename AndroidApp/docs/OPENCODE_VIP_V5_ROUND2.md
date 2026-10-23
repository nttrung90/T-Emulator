# V5 vòng 2: sửa phần còn thiếu sau audit độc lập

Tiếp tục mã nguồn trong F:/AI/T-Emulator, cùng session Gemini. User đã cho phép Codex giao prompt và kiểm tra lặp đến khi các lỗi được giải quyết. Chỉ sửa source/test/docs trong workspace; không deploy, không publish, không đổi tài khoản hay lấy secrets. Không thay assertion của audit Codex để làm xanh. Báo cáo phải bám code và kết quả thực tế, không gọi J05-J07 hoàn tất nếu mới thêm hàm chưa nối callsite.

Codex đã chạy lại C01-C11: 11/11 pass; S01-S03: pass. Đã bổ sung S04, đang FAIL thực tế: localRomPath hợp lệ ở ngoài romDir nhưng romDir còn stale.nes thì snapshot lấy stale.nes. S03 pass tình cờ do thứ tự liệt kê filesystem, source resolveRomFile vẫn ưu tiên firstOrNull trước localRomPath.

## A. Cache VIP và phiên đăng nhập (P1, cần thực hiện trọn luồng)

EntitlementRepository init/onUserChanged vẫn lấy prefs boolean thẳng, bỏ TTL; isCachedVipValid chỉ TTL24h, không expiry và không loại đồng hồ quay lùi; verifyWithServer chỉ đọc boolean rồi saveEntitlement không lưu product/expiry. Gói yearly hết hạn vẫn dùng tối đa24h. ensureFreshEntitlement không có callsite (rg toàn main), backup gate vẫn isVipActive.value. AuthStateListener chỉ đổi authState, không đồng bộ generation entitlement; onUserChanged chỉ được gọi từ MoreFragment. A->logout->A hay Firebase tự đổi phiên có thể nhận kết quả cũ.

Sửa policy cache có metadata product/lifetime/expiry/verifiedAt, yearly luôn min(TTL, expiry), invalid legacy cache fail closed, delta thời gian >=0; cold start/onUserChanged dùng cùng policy. Backend verify trả metadata cần thiết hoặc refresh authoritative ngay sauverify trước khi lưu. ensureFresh phải phân biệt serverfalse/401/403/config/authfail/stalesession với lỗi transport được fallback cache, không được fallback userA sau đổiB. Generation ở AuthRepository trung tâm tăng khi phiên đổi kể cả logout->cùngUID; entitlement tự quan sát phiên; tránh callbacklistener/constructor vònglặp. Nối refresh startup/foreground và ngay trước backup, recheck policy/session trước upload/commit. Restore vẫn được phép khi VIP hết hạn, đừng thêm paywall restore. Viết unit policy tests thực chất expiry/TTL/revoke/clockrollback/sessionABA và callsite tests khả thi.

## B. Transfer và Drive identity (P1)

CloudBackupDialogHelper hiện chỉ so UID trước/sauupload, không pin generation hay Drive account như báo cáo. DriveAuthorizationManager.loadSavedDriveAccount dùng saved email ưu tiên actual Google account trong khi getAccessToken dùng lastSignedInAccount. CloudBackupRecord.driveAccount lấy current value lúc commit. Restore list/download/commit không ràng buộc app/Drive phiên.

Pin UID+authGeneration+Drive stable account ID/email thực+Drive generation cho từng operation. Token phải lấy từ đúng account đã pin; nếu actual account thay đổi phải abort trước sideeffect và commit, kiểm tra tại retry/chunk/list/download cũng vậy. Không dùng email cache để xác định owner. Đổi A->logout->A vẫn abort job cũ. Dismiss/cancel activity phải dọn và lưu terminal state trong NonCancellable, rethrow cancellation đúng. Không swallow initial TransferRecord persist rồi vẫn upload. DB success + completed state cần transaction. Khi upload thành công mà localcommit thất bại, lưu đủ remoteFileId và jobdata để reconcile orphan trênstartup; có thể dùng durable journal/state machine thay vì bắt buộc thêm Worker nếu chọn fail-safe recovery. Không resume job qua account khác. Migration explicit nếu đổi Room schema, không fallbackDestructiveMigration làm mất dữ liệu.

## C. Restore rollback và snapshot (P1)

GameRestoreService catch gọi restoreFromRollbackBackup mà bỏ Boolean; finally cleanupStaging luôn xóa rollback duy nhất khi khôi phục thất bại. Process chết giữa filesystemcommit/Roominsert không có journal/recovery. Restore đang can thiệp file cùnggame có thể nativeemulator đang chơi/ghi save, hai restore đồng thời không cólock.

Giữ rollback + journal bền vững cho đến khi DBcommit thành công hoặc rollback xác nhận thành công; lỗi rollback không được cleanup backup. Recovery startup thực sự được gọi, phục hồi trạng thái DB/file nhất quán nếu crash trước/sauDBcommit; journal phải ghi đường dẫn trongsandbox + targetold/newrecord khi cần. Serialize snapshot/restore theo game, reject hoặc dừnggameđangchơi bằng cơ chếgắn callsite nativegame lifecycle. cancellation không được nuốt hoặc bỏ recovery.

Snapshot: ưu tiên đúng explicit localRomPath/source identity, đừng chọn first file arbitrary. Fix S04. Copy ROM/save/state vào staging ổn định rồi hash/zip cùngbyte, vì source live hiệnhashtrướczip có thể mutate. Lockgameđangchơi cùng cơ chế ở trên để save coherent. SAVE_ONLY không cần quyềnVIPđểrestore.

## D. Billing và configuration (P1)

BillingRepository.showSimulatorDialog success gọi onSuccess ngay sau simulateTestPurchase, trong khi handlePurchase cũng gọi pendingcallback sauverify, gây callback đôi/về trướcVIPactive. simulateTestPurchase catch activateTestVip fallback không thông báo verifiedcallback chuẩn. Pending callback chưa scope UID/generation/lifecycle; clearPendingPurchase chỉ bổ sung API, phải nối dialog cancel/dismiss và lỗi launch. queryPurchases responsecodes mớilog lỗi; đừng diễn giải truyvấn lỗi là chưa sởhữu; keep errors distinguishable, callbackonce. Fix cùngtests.

Manifest còn YOUR_GOOGLE_WEB_CLIENT_ID và backendempty, chưa có FirebaseApp/options/google-services bootstrap. Releasebundle compile không chứng minh auth có thểlogin. Tạo wiring bằng Gradle properties/env hoặc google-services config optional đúng; cấu hình Firebase/Webclient/backendHTTPS có validate và lỗi rõ ràng khi thiếu, fail closed. Không tự bịacredentials/không in secret. Hướng dẫn envkeys và buildvalidation; môi trường thật chưa được cung cấp thì báo UNVERIFIED, không đánh dấu cấuhình hoànthành nhờbuild.

## E. Backend còn thiếu (P1)

Pending acknowledge mới retry khi clientgọi verifylại; userthoát app là không retry, có thể bịautorefundo Play. Cần durable ackretry endpoint/job được bảo vệ và wiring server scheduler hoặc hướngdẫncloudscheduler đầyđủ, boundedretry, requeryPlay, noAPIproof mockgrant, encrypted token/protected storage khônglograwtoken. RTDN ghi processedEvents.COMPLETED trongcatch chỉlog rồi200 kểcảpersistfail => yêu cầu retry5xx; nhận Playhint phải an toàn at-least-once. RecomputeUserEntitlement check tombstone rồiwrite ngoàitransaction cònrace xóaxảy ra giữacheck vàwrite, làm táitạo userdoc. Dùng transaction/recheck với quy tắc reads-beforewrites choFirestorereal; tests racewrite marker (không chỉmockcheck).

## Validation / deliverable

Codex bổ sung probes backend C12-C14 trong lúc lượt này chạy: C12 persist processedEvents lỗi vẫn200; C13 tombstone hiệnhữu nhưng GET fastpath vẫnVIPtrue; C14 thiếu subscriptionsv2 verifier vẫnconsume renewal200/COMPLETED. Cả3 đã FAIL thực tế. Sửa và giữ assertion, chạy toàn14backendprobes. GET phảicheck deletion trước fastpath và transactionalrecompute.

Probes mới thêm tiếp C15-C17 cũng FAIL: production OIDC thiếu PUBSUB_SERVICE_ACCOUNT_EMAIL vẫn nhận signed token từ serviceaccount bấtkỳ; duplicate verify trả entitled=true dù tombstone đãghi; recompute query bị chen deletion saucheckmarker vẫnghiVIPtrue. Fixture V5 giờ enforce reads-before-writes và retry khi documentread bị đổi, môphỏng conflicttransaction; không thayassertions để né. Tổng17backendprobes. GamesFragment Drive auth result chỉresume pendingBackupGame, mất intent ViewBackups; cần enum pendingaction BACKUP/RESTORE gắnappsession, resumerestore khi cấpquyềnthànhcông, clearcancel/đổiphiên.

Codex đọc bản sửa giữa lượt: recompute mới thêm delCheck trước set ngoàitransaction. C17đạt nhưng vẫnrace ngaygiữa delCheck vàwrite. ThêmC18 môphỏng deletion ởcommitboundary, vẫnFAIL. Fixture trướcflushtransactionwrite sẽđổi marker rồi kiểmtra readset, tươngđương conflict+retry; doc.set thường sẽrace. Phải đưa checkmarker+writeentitlement vào cùngFirestoretransaction, không thêmrechecklặpngoàitransaction. Nên querypurchaseledger trongtransaction luôn để chốngRTDNgrant/revokeout-of-order; tấtcảreads trướcwrites. Tổng18backendprobes.

Giữ C01-C11, B01-B12, oldprobes khôngregress. Chạy backend npmtest, V5backend, V5snapshot4probes; AndroidGradle debug/release compile + testDebugUnitTest. Nếu thêmclass dependency cho standaloneaudit hãy thông báo Codex; không sửa assertion. Bổ sung regression tests sourcepolicy/session/rollback/ackpersist phù hợp. Đọc code trước sửa; không nói tấtcảdone nếu cònmục chưa làm. Ưu tiên hoànthành A-C rồi D-E, nhưng thực hiện toànbộ trong lượt này nếu cóthể. Không cần buildbundle/đổiversion ngoài nhiệm vụ.
