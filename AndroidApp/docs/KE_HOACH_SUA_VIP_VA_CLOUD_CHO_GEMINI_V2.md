# Kiểm tra sau sửa VIP/Cloud — kế hoạch vòng 2 cho Gemini

Ngày kiểm tra: **09/10/2026**. Đối chiếu kế hoạch vòng 1 `KE_HOACH_SUA_VIP_VA_CLOUD_CHO_GEMINI.md` và mã hiện có. Đây là kết quả rà soát và kế hoạch, không phải bản sửa implementation. Chưa đủ điều kiện nghiệm thu VIP trả phí.

## 1. Phần đã cải thiện

- Các điểm vào simulator, test VIP, tài khoản test và mock Drive đã được chặn bằng BuildConfig.DEBUG; tap mở công cụ test trong More bị chặn ở release. Thiếu backend không còn tự cấp VIP local như trước.
- Có Firebase Auth sign-in, Authorization header cho verify, response được đóng và không còn mặc định entitled=true khi thiếu field.
- Backend có app factory, kiểm tra đầu vào/dependency, transaction kiểm tra owner ở thời điểm ghi, hash token và ghi entitlement trước acknowledge. Có 11 test chạy được.
- Android cache có khóa theo UID; logout qua More reset trạng thái VIP. Restore purchase đã chờ callback verify thay vì chỉ dựa vào tìm thấy purchase.
- Drive consent không còn tự đổi app account. Upload ghi backupType; list đã có vòng lặp nextPageToken, và thao tác list không tự tạo folder.
- Restore đã kiểm tra containment/gameId, giới hạn giải nén, kiểm tra checksum của các file có trong manifest, chặn ALL thiếu ROM và SAVE_ONLY không có record; test restore đã gọi implementation thật.
- Xóa tài khoản đã có HTTP DELETE và backend xóa subcollection entitlement. Privacy mặc định/tiếng Việt đã cập nhật.

Các cải thiện trên **không có nghĩa F01–F15 đã được đóng toàn bộ**. Ví dụ F06 vẫn vượt qua xác thực bằng bearer giả; F11 có rollback nhưng dùng sai đích; F13 mới ghi TransferRecord, chưa có worker bền vững.

## 2. Bằng chứng kiểm thử

### Bộ test hiện có

- `npm.cmd test` tại `AndroidApp/backend`: **11/11 đạt**. Lần đầu bị sandbox chặn HTTP localhost (EACCES); chạy lại có quyền localhost đạt. Đây là test local, không phải lỗi production hoặc kiểm thử Firebase/Play thật.
- Báo cáo Android có sẵn tại `app/build/test-results/testDebugUnitTest` ghi **22 test, 0 failure/error** (VIP 5, restore 7, repository 3, search 5, storage 2). Đây là báo cáo có sẵn; không tính là kết quả chạy lại thành công của lần kiểm tra này.
- Chạy lại Gradle debug/release bị chờ cache lock; lần chạy cache project riêng kết thúc với `Timeout waiting to lock journal cache .../.gradle/caches/journal-1`. Không khẳng định debug/release build đạt trong lần rà soát này. Không xóa cache/lock hoặc dừng các build không thuộc phiên rà soát để ép chạy.

### Test bổ sung có thể tái hiện

Lưu tại `AndroidApp/docs/audit-vip-2026-10-09/`. Các assertion mô tả hành vi đúng, nên **test đang đỏ là bằng chứng lỗi còn tồn tại**, không sửa assertion để hợp thức hóa lỗi.

| Probe | Kết quả thực tế | Mục sửa |
|---|---|---|
| R01 | Production RTDN nhận bearer `not-a-google-jwt`, trả 200 và đổi VIP=false | V01 |
| R02 | Request product vip_lifetime nhưng Play get dùng vip_yearly; ack lại dùng vip_lifetime | V02 |
| R03 | Hai lần verify, ack lỗi lần đầu: tổng cộng chỉ một lần gọi ack, lần sau ALREADY_VERIFIED | V03 |
| R04 | DB lỗi khi xử lý RTDN: lần đầu 500, retry cùng messageId trả 200 duplicate, VIP vẫn true | V04 |
| R05 | Firebase deleteUser lỗi vẫn trả HTTP 200 success=true | V12 |
| R06 | File save không có trong manifest.files vẫn được ghi thành công | V06 |
| R07 | SAVE_ONLY checksum khác ROM hiện có vẫn thành công nhờ fallback gameId | V07 |
| R08 | DB commit lỗi sau copy: save local vẫn NEW_SAVE; rollback đã chép OLD_SAVE sang ID remote | V05 |
| R09 | ALL đã chép ROM mới nhưng GameRecord vẫn trỏ `/missing/old.nes`, báo thành công | V08 |
| R10 | backupType không hỗ trợ vẫn thành công, không cần ROM | V06 |

**Cách chạy từ thư mục gốc repository:**

```powershell
node AndroidApp/docs/audit-vip-2026-10-09/backend-regressions.cjs
./AndroidApp/docs/audit-vip-2026-10-09/run-restore-audit.ps1
```

Backend probes gọi middleware/handler thật của app.js, inject Firebase/Play/Firestore fake, không kết nối mạng. Không chứng minh tính atomic của Firestore thật. Kotlin runner biên dịch trực tiếp GameRestoreService/GameStorageManager/GameDao/GameRecord/NativeGameMapping hiện tại bằng compiler/dependency đã có trong cache; Android API dùng mock jar, DAO giả và AppDatabase compile stub. Đã chạy được **5/5 lỗi backend và 5/5 lỗi restore**. Không thử nghiệm lên game/save thật của người dùng.

Runner PowerShell có đường dẫn Java/Gradle cache theo máy hiện tại; nếu cache khác, chỉnh các đường dẫn dependency, không sửa service hoặc kỳ vọng test. Đưa các probe vào test suite chính thức khi sửa; thay stub bằng Room/Firestore emulator ở lớp integration.

## 3. Các việc phải sửa tiếp

### V01 — P0: RTDN vẫn vượt xác thực bằng bearer bất kỳ

**Source:** `backend/app.js:83–113`; `backend/server.js` createApp.

authenticatePubSub chỉ gọi options.verifyOidcToken khi dependency tồn tại. server.js không truyền dependency này, nên có header Bearer là next(). Shared secret sai/không có cũng không ngăn đường bearer này. PackageName còn được phép vắng mặt. **R01 đã tái hiện thu hồi quyền bằng bearer giả ở cấu hình production với admin fake.** Chưa kiểm tra gateway/IAM triển khai thực tế; nhận định bảo mật ở lớp ứng dụng.

**Sửa:** bắt buộc verifier OIDC ở production (hoặc cơ chế gateway được triển khai và test thật); kiểm chữ ký, issuer, expiry, audience, email/email_verified của service account được cho phép. Thiếu verifier/config phải từ chối, không next(). Không dùng secret qua query làm cửa hậu. Require packageName đúng; notification chỉ là tín hiệu để đối soát Play, không tin nội dung rồi cập nhật quyền ngay.

**Nghiệm thu:** thiếu token, chuỗi giả, JWT sai aud/email/issuer/expiry, secret sai + bearer giả đều không chạm DB; token Pub/Sub đúng đi tiếp. R01 xanh. Test phải đi qua wiring server production, không chỉ createApp với verifier giả.

### V02 — P1: Android bán thuê bao năm, backend xác minh bằng API mua một lần

**Source:** `billing/BillingRepository.kt:46–48,115–153,263–275,343–371`; `backend/app.js:28,177–178,239–243,315`; `billing/VipDialogHelper.kt`.

Android query `vip_yearly` dưới SUBS, truyền offerToken, UI hiển thị giá/năm. Backend vẫn chỉ gọi purchases.products.get/acknowledge và đọc purchaseState kiểu one-time. Không có subscriptionsv2, expiryTime, subscriptionState hoặc xử lý subscriptionNotification. Token thuê bao thật vì vậy không được xác minh theo đúng contract; chưa E2E để xác định HTTP lỗi cụ thể từ Play.

Ngoài ra, lifetime được allowlist nhưng get dùng config.vipProductId thay vì product đã xác định. **R02 tái hiện get=vip_yearly, ack=vip_lifetime.** Không thể coi restore lifetime đã tương thích.

**Sửa:** giữ mô hình yearly đang có, hoàn thiện danh mục server `{productId, productType}` cho yearly SUBS và lifetime INAPP; không tự đổi lại lựa chọn kinh doanh. SUBS dùng subscriptionsv2.get và API acknowledge tương ứng; derive quyền từ trạng thái và expiry, xử lý grace/hold/expired/canceled còn hạn/revoke/renewal, linkedPurchaseToken và RTDN. One-time dùng đúng product ID cho cả verify/ack. Không tin productType/state từ client. Giá paywall phải nêu kỳ thanh toán/pha ưu đãi và giá gia hạn từ Play, không dùng giá hardcode khi chưa tải sản phẩm.

**Nghiệm thu:** test contract cả SUBS/INAPP, mua yearly thật, hết hạn và hủy còn hạn, restore lifetime, gói không hợp lệ. R02 xanh. Nếu chưa có cấu hình console, ghi E2E chưa nghiệm thu. Chính sách lưu/migration purchase document raw-token của phiên bản cũ sang tokenHash phải được xác định nếu backend cũ đã có dữ liệu.

### V03 — P1: ALREADY_VERIFIED bỏ qua retry ack và đối soát trạng thái

**Source:** `backend/app.js:199–232,310–333`; `billing/BillingRepository.kt:302–337`.

Purchase đã ghi vào DB được trả entitled=true ngay, không kiểm tra entitlement hiện có, không thử lại acknowledgementState=0 và không hỏi Play. Lỗi ack lần đầu chỉ log; purchaseRecord không giữ token theo cơ chế bảo mật để worker dùng lại và không có retry worker. Client vẫn tự ack thêm; nếu app tắt thì không có cơ chế bù. **R03 chứng minh lần verify thứ hai không retry.** Shortcut này còn che refund/expiry khi RTDN bị mất và có thể trả true sau entitlement đã bị xóa.

**Sửa:** idempotency không đồng nghĩa bỏ qua state machine. Persist pending acknowledgment và dữ liệu cần retry an toàn, backend worker retry/backoff trong hạn Play, reconciliation trạng thái hiện hành. Return quyền từ entitlement thực tế, không từ sự tồn tại purchase document. Chọn backend làm chủ acknowledge, loại bỏ phụ thuộc client; xử lý lỗi giữa Play ack và DB update. Rà soát transaction commit recheck voided/state, tránh request verify đang bay ghi voided=false sau revoke.

**Nghiệm thu:** ack timeout rồi retry/restart, DB update lỗi sau ack, refund/expiry không nhận RTDN, concurrent revoke/verify. R03 xanh; lỗi ack không bị bỏ quên và không tự hồi sinh quyền đã mất.

### V04 — P1: Đánh dấu RTDN đã xử lý trước khi xử lý thành công

**Source:** `backend/app.js:354–360,365–399,403–457`.

processedEvents là Set RAM; messageId được thêm trước decode và trước ghi DB. Lỗi DB trả 500 nhưng lần Pub/Sub retry bị bỏ qua. **R04: 500 → 200 duplicate trong khi VIP vẫn active.** Restart/multiple instances lại mất dedup. Update purchase rồi update entitlement không atomic; lỗi giữa hai bước gây state lệch.

**Sửa:** durable inbox/outbox hoặc transaction với trạng thái received/processing/applied; chỉ đánh dấu applied sau hoàn tất bền vững. Có retry lease, reconciliation và giới hạn retention; event thất bại vẫn được retry. Không dùng RAM Set làm nguồn chính. Bảo toàn consistency purchase/entitlement và xử lý notification trùng/lệch thứ tự theo trạng thái Play hiện hành.

**Nghiệm thu:** inject lỗi ở từng DB read/write và khởi động lại, hai instance xử lý một event, delivery trùng. R04 xanh; kết quả cuối hội tụ đúng.

### V05 — P1: Rollback sai game và không bảo toàn khi backup rollback thất bại

**Source:** `cloud/GameRestoreService.kt:113,166,178–179,257–265`; `storage/GameStorageManager.kt:183–219`.

targetGameId giữ ID trong manifest. Sau khi match checksum, localGameId có thể khác; copy ghi vào local nhưng catch rollback vào targetGameId remote. **R08 chứng minh save local bị ghi đè dù restore thất bại.** createRollbackBackup trả Boolean nhưng caller bỏ qua; nếu sao chép rollback chỉ được một phần thì vẫn commit, rồi rollback xóa cả game directory để chép bản không đầy đủ. Cuối cùng luôn cleanup staging, kể cả rollback thất bại. Chưa có journal khôi phục sau process kill/game lock.

**Sửa:** dùng duy nhất resolved target ID cho commit/rollback; xác nhận backup rollback đầy đủ trước mutation. Nếu backup thất bại, abort và giữ dữ liệu gốc. Không xóa dữ liệu đích trước khi bảo đảm bản phục hồi hợp lệ. Có transaction journal bền vững, trạng thái recovery và khóa theo game; chỉ xóa rollback sau DB/filesystem hoàn tất hoặc recovery được xác nhận. Production constructor không nuốt lỗi AppDatabase thành gameDao=null rồi báo restore thành công.

**Nghiệm thu:** remote ID khác local, DB lỗi sau ghi save, rollback thiếu dung lượng/lỗi copy, process kill từng bước, restore đồng thời. R08 xanh; save cũ giữ nguyên khi failure, không có thư mục game remote ngoài ý muốn.

### V06 — P1: Manifest chưa bắt buộc/đầy đủ; file ngoài danh sách vẫn ghi đè

**Source:** `cloud/GameRestoreService.kt:104–150,185–221`.

manifest.files có thể thiếu/null/rỗng. Chỉ các file được liệt kê mới được kiểm checksum; các file Saves/SaveStates ngoài danh sách vẫn copy. backupType bất kỳ ngoài ALL/SAVE_ONLY vượt cả hai điều kiện và có thể tạo record không ROM. Schema <=0 cũng không bị từ chối; chưa phát hiện entry trùng hoặc ràng buộc số ROM. **R06/R10 đã tái hiện.**

**Sửa:** validate schema chính xác, enum backupType, console/type, required fields; xây tập entry đã normalize, từ chối duplicate/missing/extra file. Mọi file được commit phải có manifest entry hợp lệ, size/hash đúng; ALL có đúng ROM theo schema, SAVE_ONLY không lén kèm ROM. Validate toàn bundle trước tạo rollback/commit. Bundle v1 cũ có files[] do snapshot hiện có sinh ra; không cần bỏ kiểm tra để tương thích mù quáng. Nếu có định dạng cũ khác, migration có version/validator riêng.

**Nghiệm thu:** R06/R10 xanh, manifest thiếu/rỗng, extra save, duplicate path, nhiều ROM, schema âm, type lạ bị từ chối mà không sửa dữ liệu local.

### V07 — P1: SAVE_ONLY vẫn ghép nhầm ROM; basename save chưa được ánh xạ

**Source:** `cloud/GameRestoreService.kt:153–174,203–221`; `storage/GameStorageManager.kt` getNativeMapping; `cloud/GameSnapshotService.kt`.

Nếu không tìm thấy checksum, code fallback getGameById rồi chỉ kiểm existingGame != null. Không kiểm checksum mâu thuẫn, console, ROM thực tế còn tồn tại/quyền SAF. **R07: cùng gameId nhưng checksum khác vẫn ghi save và còn cập nhật contentSha256 sai.** Match theo checksum ở game ID khác cũng không đổi tên SRAM/state theo basename ROM local; hai máy có cùng ROM nhưng tên khác có thể copy thành công mà core không nạp save đúng.

**Sửa:** ghép bằng checksum và console đã xác minh; khi thiếu/mâu thuẫn không fallback âm thầm theo ID/tên. Với SAF xác nhận khả năng đọc ROM; hash lại khi cache chưa tin cậy. Lưu native save mapping vào manifest hoặc chuyển tên theo quy tắc core một cách xác định; không đổi contentSha256 từ manifest khi chưa chứng minh đó là ROM của game đích.

**Nghiệm thu:** R07 xanh; cùng ID khác ROM, cùng hash khác console, ROM mất/quyền SAF thu hồi; cùng nội dung ROM nhưng khác tên/ID khôi phục SRAM và state rồi core nạp đúng. Không chỉ kiểm file tồn tại.

### V08 — P1: ALL restore vào game có sẵn không cập nhật đường dẫn ROM

**Source:** `cloud/GameRestoreService.kt:182–200,226–230`; `GamesFragment.kt:315–323`.

ALL tính localRomFilePath mới nhưng existingGame.copy chỉ đổi displayName/contentSha256. Record tiếp tục dùng localRomPath/sourceUri cũ; UI launch không tìm ROM theo thư mục vừa restore. **R09 đã tái hiện restore success với path cũ không tồn tại.** Giữ source URI của existingGame chỉ phù hợp SAVE_ONLY, không đủ cho ALL khi source cũ hỏng.

**Sửa:** chia chính sách record ALL/SAVE_ONLY. ALL commit đường dẫn ROM internal đã xác minh và mapping tương ứng; originalFileName/native basename đồng bộ. SAVE_ONLY giữ source hợp lệ của game đích. Nếu giữ URI gốc để provenance, tách trường đó khỏi đường dẫn dùng để chạy game.

**Nghiệm thu:** R09 xanh; restore full sau xóa file nguồn/thu hồi SAF, remote tên khác local, khởi động app lại vẫn chơi/nạp save được.

### V09 — P1: Entitlement chưa đồng bộ theo phiên, chưa kiểm quyền trước upload

**Source:** `billing/EntitlementRepository.kt:75–81,147–151,171–198,201–209`; `MoreFragment.kt:122–123,133`; `cloud/CloudBackupDialogHelper.kt:62–96,118–161`; `auth/AuthRepository.kt:73–96,108–123`.

onUserChanged/refresh chỉ gọi ở đăng nhập qua More, không foreground hoặc trước upload. Cache active vẫn được dùng làm điều kiện backup; refresh khi network/HTTP lỗi fallback cache. Response A về muộn có thể cập nhật _isVipActive sau khi đã logout/chuyển B vì không kiểm UID/session generation. Khi cold start, profile SharedPreferences được coi là Authenticated, entitlement init=false; không có hook Firebase state để nối lại refresh/query theo phiên.

**Sửa:** dùng Firebase auth-state làm nguồn phiên, migrate profile legacy/mock, không tin riêng prefs. Entitlement state chứa UID + generation + freshness; bỏ response cũ. Refresh sau auth/foreground, tại enqueue/start upload và khi retry; Unknown/error giữ job chờ, không lấy cache làm giấy phép. Không bắt CancellationException như lỗi thường. Các thao tác local/restore cũ vẫn không bị khóa bởi VIP.

**Nghiệm thu:** A verify chậm → logout → B login → response A về; revoke khi dialog scope đang mở/job chờ; app restart/offline; session profile và Firebase khác UID. Không cấp sai quyền hoặc bắt người VIP phải thao tác restore thủ công mỗi lần mở lại.

### V10 — P1: Transfer chưa bền vững, chưa gắn chặt app/Drive identity

**Source:** `cloud/CloudBackupDialogHelper.kt:118–200`; `cloud/DriveAuthorizationManager.kt:64–67,106–137`; `cloud/DriveBackupRepository.kt:214–218,240–305`; `data/db/TransferRecord.kt`.

Vẫn chạy lifecycleScope, chưa có Worker/WorkManager triển khai. Chỉ insert PREPARING và update COMPLETED; lỗi DB bị nuốt, lỗi transfer không ghi FAILED, không lưu session/offset, không resume/cancel thực sự. Coroutine cancellation có thể bỏ dở cleanup hoặc vẫn chạy I/O blocking. UID lấy trước dialog, Drive token/folder/account email lấy từ global state ở các thời điểm khác nhau. Không kiểm Drive account khớp app account, không khóa account của job; consent không đổi app session nữa là cải thiện nhưng chưa ngăn backup sai account.

**Sửa:** worker với application context, immutable ownerUid + Drive identity + generation; kiểm tra account/quyền trước mỗi phase và commit, hủy/tạm dừng khi logout/switch. Persist upload session/offset/backupId/idempotency, recovery và retry theo mã lỗi. UI observe persisted state; xử lý cancellation riêng, cleanup có thời hạn an toàn. Đừng nuốt DB error rồi báo thành công mà mất metadata/trạng thái. Nếu cho phép Drive khác app account, phải có lựa chọn rõ ràng và ràng buộc đích theo job; không tự thay chính sách hiện tại.

**Nghiệm thu:** kill process/rotate/back, mạng đứt từng phase, đổi account khi dialog mở/upload/list/restore, remote upload thành công nhưng DB lỗi, retry không tạo trùng. Transfer lỗi có trạng thái và staging recovery rõ ràng.

### V11 — P1: Mua thật chưa nối callback tiếp tục backup; lỗi Billing bị bỏ qua

**Source:** `billing/BillingRepository.kt:161–178,285–325,343–382`; `billing/VipDialogHelper.kt:52–67`; `GamesFragment.kt:75–92`.

launchVipPurchase nhận onSuccess nhưng nhánh Play thật return launchBillingFlow, không lưu/call callback sau verify. Nhánh simulator vẫn gọi callback trước khi verify bất đồng bộ xong. ITEM_ALREADY_OWNED và nhiều lỗi chỉ log; queryPurchases bỏ responseCode của cả SUBS/INAPP, dễ hiển thị 'không có gói' khi Play lỗi. Lỗi startConnection không hoàn tất callback restore. Sau consent Drive UI vẫn chỉ toast, không tiếp tục pending action.

**Sửa:** state machine có purchase/verify pending/result, UI lifecycle-aware; tiếp tục pending backup đúng một lần sau server Active và sau consent thành công. Query phân biệt error/empty/verified, already-owned đi query+verify. Giá chưa có thì hiện loading/unavailable và khóa mua; khi connection ready nhưng product query thất bại phải có retry ProductDetails thực sự.

**Nghiệm thu:** mua thật từ nút backup tiếp tục đúng game; cancel/pending/verify chậm/already-owned/service unavailable không treo hoặc báo thành công sai; callback tới sau rotate/account switch không mở dialog sai.

### V12 — P1: Xóa account còn báo thành công giả và không chặn phiên đã xóa

**Source:** `MoreFragment.kt:172–201`; `backend/app.js:62,462–492`.

Android bỏ qua request khi URL/token rỗng rồi vẫn signout và báo 'server data deleted'. Backend nuốt mọi lỗi deleteUser (không chỉ user-not-found) và trả success=true; **R05 đã tái hiện**. verifyIdToken chưa kiểm revoked/deleted user; purchase record còn owner cũ và ALREADY_VERIFIED trả true dù entitlement đã xóa. Chưa có state/tombstone để chống request đang bay tạo lại entitlement trong lúc delete.

**Sửa:** thiếu token/config không được báo server deletion xong. Backend delete workflow idempotent, phân biệt user-not-found với lỗi tạm thời/quyền hạn; durable deletion state và retry. Kiểm session revoked/user deleted phù hợp, chặn concurrent verify khi deleting/deleted. Có chính sách retained purchase chống replay và restore sau user recreation: không tự chuyển token cho UID mới, cũng không khóa người mua vĩnh viễn mà không có quy trình phục hồi. Xóa cloud metadata/transfer theo owner; giữ ROM/save/Drive file theo cam kết.

**Nghiệm thu:** R05 xanh, thiếu URL/token, Firebase timeout, partial delete retry, token cũ sau delete, concurrent verify/delete, recreate UID restore có kết quả xác định.

### V13 — P2: Lỗi tìm folder Drive vẫn bị hiển thị thành không có backup

**Source:** `cloud/DriveBackupRepository.kt:146–176,361–363`.

findBackupFolderId trả null cho cả không tìm thấy, 401/403/500, JSON lỗi và IOException. listBackups đổi null thành Success(emptyList()). Các typed errors mới chỉ có tác dụng sau bước lookup folder; người dùng có backup vẫn thấy trống khi mạng/token lỗi ở bước đầu.

**Sửa:** lookup trả Found/NotFound/NeedsAuthorization/Error; chỉ NotFound khi HTTP thành công và danh sách rỗng. Invalidate/refetch access token khi phù hợp và yêu cầu consent lại nếu cần. Filter marker app backup; mã hóa pageToken bằng URL builder. Backup cũ thiếu backupType cần phân biệt unknown hoặc đọc manifest, không mặc định full vô điều kiện.

**Nghiệm thu:** inject lỗi ngay request lookup folder, không chỉ request list children; phân trang vẫn đủ, không báo rỗng giả hoặc tạo folder trùng khi lỗi.

### V14 — P1, điều kiện cấu hình: Chưa có đường bootstrap Firebase/production config hoàn chỉnh

**Source:** `app/build.gradle.kts`; `app/src/main/AndroidManifest.xml:20–26`; `auth/AuthRepository.kt` FirebaseAuth.getInstance/getServerClientId.

Dependency Firebase Auth đã thêm nhưng chưa thấy Google Services plugin, resource google_app_id hoặc FirebaseApp.initializeApp với FirebaseOptions trong source. Manifest vẫn placeholder Web client và backend URL rỗng. Vì vậy source hiện tại không chứng minh Firebase default app được khởi tạo, đăng nhập release/verify được cấu hình. Đây là thiếu tích hợp/cấu hình trong workspace, không suy đoán console chưa tạo project.

**Sửa:** chọn bootstrap qua Google Services build integration hoặc FirebaseOptions được cung cấp rõ ràng; cấu hình theo variant/CI và validate fail-fast khi build release thiếu giá trị bắt buộc. Tài liệu đầu vào owner cung cấp: project/app IDs, OAuth web client, SHA fingerprints, backend HTTPS, catalog product/base plan, Pub/Sub identity/audience. Không commit private key hay tạo mock production để vượt thiếu cấu hình.

**Nghiệm thu:** release cấu hình thật FirebaseApp/FirebaseAuth khởi tạo được; UID/token backend khớp; thiếu config có lỗi rõ ràng trước phát hành. E2E Play/Drive thật là gate riêng, không thay bằng debug simulator.

### V15 — P2: Privacy tiếng Anh vẫn bị resource cũ ghi đè

**Source:** `res/values-en/strings.xml:195` so với `res/values/strings.xml:245`, `res/values-vi/strings.xml:245`; `MoreFragment.kt` showPrivacyPolicyDialog.

Default/VI đã cập nhật nhưng values-en còn nói không có account/không truyền dữ liệu ra ngoài. Khi chọn English, Android ưu tiên resource values-en, nên người dùng vẫn đọc chính sách cũ. Tài liệu privacy/backend còn mô tả lifetime cần đối chiếu gói yearly hiện tại.

**Sửa:** đồng bộ default/EN/VI và tài liệu với hành vi cuối đã kiểm chứng (subscription, auth, server records, Drive, delete). Không ghi 'xóa dữ liệu server' nếu V12 chưa hoàn tất.

**Nghiệm thu:** mở privacy từ UI ở cả English/Vietnamese/system locale, nội dung chính xác và không mâu thuẫn.

## 4. Thứ tự sửa và tiêu chí đóng lỗi

| Đợt | Hạng mục | Gate bắt buộc |
|---|---|---|
| A | V01, V04 | R01/R04 xanh; verifier production wiring đúng, retry RTDN không mất event |
| B | V05–V08 | R06–R10 xanh; rollback/recovery và game thật nạp được SRAM/state |
| C | V02, V03, V09, V14 | R02/R03 xanh; yearly/lifetime contract, entitlement expiry/account switch, cấu hình release |
| D | V10, V11, V13 | Worker resume/idempotency/owner isolation; purchase/consent tiếp tục đúng intent; lỗi lookup rõ ràng |
| E | V12, V15, integration | R05 xanh; deletion không báo sai; privacy đúng; debug/release/backend và E2E nghiệm thu |

- Giữ toàn bộ test cũ và tích hợp probe mới vào suite. Test backend hiện chủ yếu in-memory; thêm Firestore emulator cho transaction, concurrent claim/revoke/delete và nhiều worker.
- Test VIP hiện vẫn có case mô phỏng Boolean/Map thay vì gọi repository. Bổ sung test repository thật với controllable auth/HTTP/coroutines, có late response sau switch UID và release variant.
- Test rollback cũ làm sai checksum để fail **trước** copy, nên chưa kiểm rollback thật. Phải có lỗi **sau** copy/ở DB commit và process recovery như R08.
- Không dùng nullable DAO ở production để biến lỗi database thành success; test stub nằm riêng ngoài source main.
- Sau mỗi đợt cập nhật trạng thái V01–V15: code đã sửa, test đã chạy, bằng chứng, phần chưa nghiệm thu. Không đóng chỉ vì đã thêm comment Fxx hay có tên test tương ứng.
- Chạy lại `:app:testDebugUnitTest :app:testReleaseUnitTest`, lint/build phù hợp khi Gradle cache hết tranh chấp. Không tự xóa lock/cache hoặc kill build của người khác; không diễn giải báo cáo cũ là lần chạy mới.
- Không deploy backend/phát hành Play trong nhiệm vụ sửa. Không thay đổi emulator core/tìm kiếm/ROM người dùng ngoài phạm vi cần thiết. Local play và restore backup cũ không yêu cầu VIP active.

## 5. Prompt giao Gemini

```text
Rà soát và sửa tiếp theo:
AndroidApp/docs/KE_HOACH_SUA_VIP_VA_CLOUD_CHO_GEMINI_V2.md

Đừng coi kế hoạch vòng 1 đã hoàn thành chỉ vì test cũ xanh. Có 10 probe tái hiện
lỗi trong AndroidApp/docs/audit-vip-2026-10-09/: 5 backend, 5 restore.
Chạy chúng, tích hợp vào suite chính thức và sửa implementation để test xanh;
không xóa case, nới assertion hoặc đổi fake để che lỗi.

Ưu tiên xác thực RTDN và bảo toàn save/rollback, rồi hoàn thiện yearly SUBS +
legacy lifetime INAPP, ack retry, quyền theo UID/expiry và session generation.
Giữ mô hình yearly đang có; không tự quay về lifetime hoặc mock production.
Thực hiện thứ tự A→E, xử lý đủ V01–V15, worker resume và account deletion thật.

Test phải gọi code production với dependency được kiểm soát. Có integration
Firestore/Room và test failure sau khi đã ghi file, không chỉ trước commit.
Không sửa application code chỉ để cho phép DAO null hoặc thiếu config mà vẫn
báo thành công. Không nhúng secrets, không deploy/phát hành.

Nếu thiếu cấu hình Google/Firebase/Play/Drive, hoàn tất phần độc lập và ghi rõ
đầu vào thiếu cùng E2E chưa nghiệm thu. Bàn giao trạng thái V01–V15, danh sách
file đổi, migration, lệnh test/kết quả thật, và các giới hạn còn lại.
```

## 6. Nguồn đối chiếu

- Thuê bao phải được đối soát theo trạng thái và thời hạn qua API subscription: [Google Play subscription lifecycle](https://developer.android.com/google/play/billing/lifecycle/subscriptions), [SubscriptionPurchaseV2](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2).
- Pub/Sub push cần xác thực token và kiểm identity/audience: [Google Cloud authenticate push subscriptions](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions).

Các lỗi đã tái hiện là trên dữ liệu giả/local. Chưa có bằng chứng người dùng thật bị mất dữ liệu hoặc endpoint triển khai đang public; cần sửa source và nghiệm thu integration trước phát hành.
