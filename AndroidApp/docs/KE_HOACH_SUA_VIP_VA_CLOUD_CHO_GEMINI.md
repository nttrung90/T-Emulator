# Rà soát VIP và kế hoạch sửa cho Gemini

Ngày kiểm tra: 05/10/2026. Phạm vi: AndroidApp, backend xác minh giao dịch, đăng nhập, Drive, sao lưu/khôi phục và xóa tài khoản.

## 1. Kết luận và bằng chứng kiểm tra

Luồng hiện tại chưa đủ điều kiện phát hành VIP trả phí. Có đường cấp VIP giả trong source main, giao tiếp xác thực Android/backend chưa khớp, cache quyền không gắn tài khoản, và restore có thể làm hỏng dữ liệu cục bộ. Tài liệu này là kế hoạch sửa, chưa thay đổi mã ứng dụng/backend.

Kiểm tra đã thực hiện:

- Đọc luồng từ UI → auth/billing/entitlement → Drive → snapshot/restore → Room/native storage; đối chiếu kế hoạch ban đầu `KE_HOACH_GAME_SEARCH_GOOGLE_LOGIN_VIP_DRIVE.md`.
- Chạy `:app:testDebugUnitTest --offline --console=plain`: BUILD SUCCESSFUL, 14 test, 0 failure/error (VIP 2, restore 2, repository 3, search 5, storage 2). Một số bước biên dịch dùng cache; task test thực sự chạy.
- `node --check AndroidApp/backend/server.js`: đạt.
- `npm.cmd test --prefix AndroidApp/backend`: lỗi MODULE_NOT_FOUND vì package.json trỏ `node test.js` nhưng không có test.js.
- Chạy handler server.js trong Node VM với Express/Firebase/Firestore giả lập, không gọi dịch vụ thật: thiếu credentials vẫn nhận bearer và purchaseToken tự tạo rồi trả GRANTED; hai UID dùng cùng token đều có entitlement active; RTDN không có Authorization vẫn trả 200 và thu hồi entitlement. Kịch bản Firestore dùng mock Play do không cấu hình credentials, chứng minh lỗi handler/storage, không phải kiểm thử Google Play thật.
- Chưa kiểm thử E2E trên thiết bị với Play sandbox, Firebase, Drive hoặc backend triển khai. Chưa xác nhận cấu hình IAM/gateway bên ngoài source. Không diễn giải các test local là bằng chứng production hoạt động.

Số dòng dưới đây tham chiếu source tại thời điểm rà soát, có thể đổi sau khi sửa. P0 = chặn phát hành vì quyền truy cập/bảo mật; P1 = sai chức năng hoặc nguy cơ mất dữ liệu; P2 = độ đầy đủ/UX.

## 2. Các lỗi cần sửa

### F01 — P0: VIP giả và Drive giả có thể đi vào release

**Vị trí:** `MoreFragment.kt:231–250`; `billing/BillingRepository.kt:128–202`; `billing/EntitlementRepository.kt:51–79,134`; `util/EmulatorDetector.kt:43`; các nhánh mock trong `cloud/`.

**Cách tái hiện từ code:** nhấn phiên bản 5 lần mở Emulator Tools, chọn bật VIP; không có điều kiện debug ở listener. Hoặc khi Play chưa ready/không có ProductDetails, bấm mua sẽ mở simulator kể cả máy thật. Token bắt đầu `test_token_` được cấp VIP local. Thiếu URL backend cũng chỉ kiểm tra purchaseState và token không rỗng, không có xác minh chữ ký như log mô tả. `DEBUG || isEmulator()` còn cho phép mock trong release chạy emulator.

**Tác động:** bỏ qua thanh toán; người dùng có thể thấy sao lưu thành công nhưng dữ liệu chỉ nằm trong `files/emulator_cloud_backups`, không trên Drive.

**Sửa:** tách fake auth/billing/Drive thành debug source set hoặc flavor dev riêng applicationId, cache và storage riêng. Release không chứa nút/tap unlock hoặc đường activateTestVip. Billing/backend chưa sẵn sàng phải trả trạng thái unavailable, không đổi sang simulator. Migration phải vô hiệu hóa cache VIP/mock cũ cho tới khi server xác nhận.

**Nghiệm thu:** release trên máy thật và emulator, offline/thiếu Play/thiếu product/thiếu URL, nhấn phiên bản nhiều lần: không có đường cấp quyền hay backup giả. Debug vẫn test được qua dependency injection.

### F02 — P0: Backend tự bỏ xác thực khi cấu hình thiếu hoặc hỏng

**Vị trí:** `backend/server.js:18–45,64–72,166–168`.

Thiếu Firebase key thì bearer bất kỳ trở thành UID; thiếu Play key thì token bất kỳ được coi là mua thành công. Parse key lỗi chỉ log rồi tiếp tục. Kể cả Firebase đã cấu hình, thiếu Play credentials vẫn cấp VIP giả.

**Sửa:** production khởi động thất bại khi dependency xác thực/xác minh không dùng được. Hỗ trợ credential hợp lệ theo môi trường triển khai (ADC hoặc cấu hình tường minh); không tự suy ra mock từ việc thiếu key. Mock chỉ ở test harness/dev riêng và bị từ chối trong production. Validate kiểu/độ dài body; trả lỗi có cấu trúc, không lộ token hoặc lỗi nội bộ.

**Nghiệm thu:** ma trận thiếu/hỏng Firebase, thiếu/hỏng Play, thiếu cả hai đều không thể grant; purchaseToken không phải string trả 400, không làm lỗi handler.

### F03 — P1: Google login không tạo phiên Firebase; verify luôn thiếu Authorization

**Vị trí:** `auth/AuthRepository.kt:132–137,175–207`; `billing/EntitlementRepository.kt:83–103`; `backend/server.js:58–75`; `app/build.gradle.kts`; `AndroidManifest.xml`.

Android giải mã `sub` rồi tự tạo UID `goog_…`, lưu profile nhưng không đăng nhập Firebase. Request verify không có Authorization, trong khi middleware backend bắt buộc Bearer. Cấu hình Firebase Auth dependency chưa có; metadata Google client ID và backend URL cũng chưa có trong manifest hiện tại.

**Cách tái hiện:** đặt URL backend hợp lệ, mua/restore giao dịch thật → request không có header → HTTP 401. Khi không đặt URL, lại rơi vào cấp quyền local của F01.

**Sửa:** Credential Manager → Google credential → Firebase Auth; UID từ Firebase, lấy Firebase ID token còn hiệu lực cho mọi API user. Có refresh/re-auth và xử lý 401. Drive authorization không được thay thế đăng nhập app bằng profile local. Cấu hình build rõ ràng, không nhúng server secrets. Với response verify, chỉ `entitled == true` mới thành công; hiện `optBoolean("entitled", true)` chấp nhận response thiếu trường. Đóng response bằng `use`.

**Nghiệm thu:** backend nhận đúng UID từ token; token thiếu/sai/hết hạn có xử lý; restart không biến profile cache thành phiên đã xác thực; `{}`/JSON sai/401/500 không mở VIP.

### F04 — P0: Một purchaseToken có thể cấp quyền nhiều UID

**Vị trí:** `backend/server.js:129–186`; `billing/VipDialogHelper.kt:52`.

Nhánh Firestore không đọc owner hiện có, batch.set ghi đè purchases/token.uid nhưng giữ entitlement của UID trước. `verifiedTokens` chỉ được thêm ở nhánh in-memory; Set này cũng không lưu owner. Backend không so sánh `obfuscatedExternalAccountId` với UID đang đăng nhập. Android truyền UID thẳng vào trường obfuscated.

**Cách tái hiện:** cùng purchaseToken lần lượt verify dưới A và B; cả hai được grant, owner token chuyển sang B. Request đồng thời còn có race.

**Sửa:** transaction claim token với owner bất biến, khóa token dùng hash an toàn; kiểm tra account identifier do Play trả về bằng thuật toán obfuscation thống nhất với Android. Lấy order/product/state từ Play, không tin orderId client. Token đã claim UID khác trả conflict và không thay đổi quyền. Giao dịch legacy thiếu identifier cần quy trình phục hồi riêng, không mặc nhiên giao cho người gửi đầu tiên.

**Nghiệm thu:** token lặp cùng UID idempotent; UID khác/concurrent claim bị chặn; restart backend không mất ràng buộc; sản phẩm/package sai bị từ chối.

### F05 — P1: Cache VIP dùng chung tài khoản và không cập nhật khi refund

**Vị trí:** `billing/EntitlementRepository.kt:19–49,113–142`; `MoreFragment.kt:143–146`; `billing/BillingRepository.kt:268–272`; `cloud/CloudBackupDialogHelper.kt:59,126`.

Cache chỉ có boolean toàn app, không owner UID/TTL. Logout không revoke cache. Android không gọi GET /v1/entitlements. queryPurchases trả true ngay nếu cache đang true. Backup chỉ xem boolean trước dialog, không xác minh ở thời điểm upload.

**Cách tái hiện:** A có VIP → logout → B login → B vẫn VIP; hoặc backend revoke → app vẫn cho backup mãi. Response verify của A về sau khi chuyển B có thể ghi quyền vào cache chung.

**Sửa:** entitlement state theo UID (Unknown/Checking/Active/Inactive/Error); cache chỉ để hiển thị. Refresh sau login, foreground và trước tạo/bắt đầu upload; response gắn UID/session generation, bỏ response cũ. Offline/chưa xác minh giữ job chờ, không cấp mới. Đổi/logout account xóa state nhạy cảm và hủy/tạm dừng job cũ. Restore backup đã có vẫn được phép khi VIP inactive.

**Nghiệm thu:** A→B, logout giữa verify/upload, revoke khi job chờ, offline, process restart không dùng sai quyền; restore không phụ thuộc VIP.

### F06 — P0: RTDN chưa xác thực và có thể thu hồi nhầm quyền

**Vị trí:** `backend/server.js:231–258`; `backend/README.md` phần RTDN.

Route không kiểm tra bearer OIDC/audience/service account/packageName, tin event body và thu hồi ngay. Source không thể hiện biện pháp bảo vệ gateway; nếu endpoint public, request giả có token hợp lệ có thể revoke. Notification của token cũ cũng revoke entitlement hiện tại của UID dù entitlement đã chuyển sang token mua mới. Chỉ xử lý voidedPurchaseNotification, bỏ qua oneTimeProductNotification; chưa có đối soát bù sự kiện thất lạc.

**Sửa:** xác thực Pub/Sub push hoặc gateway IAM có cấu hình và test tương đương; kiểm tra audience/issuer/service-account identity/package. Dùng notification để truy vấn trạng thái Play; lưu event id/trạng thái giao dịch, xử lý trùng/lệch thứ tự. Tính quyền từ giao dịch hiện hành, không revoke mọi quyền chỉ vì token cũ. Có reconciliation và retry bền vững.

**Nghiệm thu:** unsigned/wrong audience/wrong package không thay đổi DB; event trùng/out-of-order hội tụ đúng trạng thái; refund token cũ không hủy purchase mới.

### F07 — P1: Restore purchase báo thành công trước khi verify; mua thật không tiếp tục backup

**Vị trí:** `billing/BillingRepository.kt:128–136,159,221–295`; `billing/VipDialogHelper.kt:51–67`.

queryPurchases chỉ tìm thấy PURCHASED là gọi onFinished(true), trong khi verify đang chạy bất đồng bộ. Backend reject vẫn hiện VIP active. onSuccess chỉ được gọi trong simulator, không được nối với giao dịch thật thành công. Simulator cũng gọi callback ngay sau enqueue verify, dễ quay lại paywall trước khi state đổi. ITEM_ALREADY_OWNED chỉ log; lỗi setup có thể không kết thúc callback restore. Giá fallback hardcode vẫn hiển thị khi chưa tải được sản phẩm.

**Sửa:** một state machine purchase/restore với kết quả sau server verification, trạng thái pending/cancel/unavailable/error rõ ràng; không dùng Boolean found làm entitlement. Xử lý already-owned qua query+verify, reconnect/foreground query, retry có kiểm soát. Giá chỉ lấy từ ProductDetails hợp lệ; chưa có giá thì khóa mua. Tiếp tục intent backup đúng một lần sau quyền Active, có xử lý lifecycle.

**Nghiệm thu:** verify chậm/401/500 không báo thành công; pending không grant; cancel không error giả; thành công thật tiếp tục backup; không lặp dialog/submit.

### F08 — P1: Acknowledge trước khi ghi quyền bền vững, thiếu cơ chế retry

**Vị trí:** `backend/server.js:153–186`; `billing/BillingRepository.kt:239–258`.

Backend acknowledge trước batch.commit; lỗi DB sau acknowledge làm giao dịch đã xác nhận nhưng chưa có quyền. Client còn acknowledge lần nữa với Purchase.isAcknowledged cũ. Chưa có hàng đợi retry/reconciliation để bảo đảm hoàn tất nếu app đóng hoặc mạng lỗi.

**Sửa:** backend làm chủ verify → transaction persist entitlement và trạng thái acknowledge pending → acknowledge có retry/idempotency bền vững. Nếu API ack thành công nhưng ghi trạng thái lỗi, lần chạy sau kiểm tra Play trước khi retry. Không consume sản phẩm lifetime. Client xử lý quyền theo kết quả server, không tranh quyền acknowledge.

**Nghiệm thu:** inject lỗi DB và Play tại từng bước, restart app/server, callback lặp: một owner, một quyền, cuối cùng acknowledge hoàn tất trong hạn Play.

### F09 — P1: Kết nối Drive có thể đổi tài khoản ứng dụng âm thầm

**Vị trí:** `cloud/DriveAuthorizationManager.kt:104–117,126–140`; `auth/AuthRepository.kt:193`; `cloud/CloudBackupDialogHelper.kt:56–88,139`.

handleSignInResult gọi signInWithGoogleAccount và thay phiên app bằng tài khoản Drive đã chọn, không đối chiếu UID và không reset entitlement. Upload lấy token và folder qua hai lần đọc account hiện tại; không đóng băng danh tính tác vụ. CloudBackupRecord dùng UID chụp lúc mở dialog nhưng email đọc sau upload, có thể ghép hai tài khoản.

**Sửa:** tách app auth và Drive authorization; ràng buộc Drive đúng account theo thiết kế hiện tại, nếu khác phải chuyển account qua quy trình đầy đủ có thông báo. Job lưu ownerUid + Drive identity ổn định + session generation; kiểm tra trước thao tác và commit. Không lấy identity từ global state thay đổi giữa chừng.

**Nghiệm thu:** app A/Drive B, đổi account trong chọn scope/list/download/upload, callback OAuth tới muộn đều không ghi nhầm hay hiển thị thành công sai tài khoản.

### F10 — P0: Manifest gameId có thể thoát thư mục game

**Vị trí:** `cloud/GameRestoreService.kt:76–86,104,113`; `storage/GameStorageManager.kt:13–17`.

ZIP entry được kiểm tra `..`, nhưng manifest.gameId đi thẳng vào `files/games/$gameId`. Bundle có entry hợp lệ `Saves/x.sav` và gameId `../other` sẽ ghi ra `files/other/Saves/x.sav`, ngoài vùng games dự kiến. Đây là lỗi đường dẫn manifest, test ZIP traversal hiện tại không bao phủ.

**Sửa:** validate ID theo định dạng được hỗ trợ (UUID), filename/manifest path và canonical containment ở mọi đích ghi; không tin gameId/originalFileName từ remote. Ánh xạ remote game vào local game sau bước kiểm tra identity/checksum. Từ chối bundle bất hợp lệ trước khi tạo/ghi đường dẫn đích.

**Nghiệm thu:** gọi dịch vụ restore thật với ID `../other`, absolute/separator/empty, manifest filename traversal; không có thay đổi ngoài staging và game đích được chọn.

### F11 — P1: Restore bỏ qua checksum/schema và ghi đè không rollback

**Vị trí:** `cloud/GameRestoreService.kt:77–134`; `cloud/GameSnapshotService.kt:46–78`.

schemaVersion đọc nhưng không kiểm tra; files[] với size/SHA không được verify; ROM hash sai chỉ log warning rồi copy. Save/state được overwrite tuần tự trước DB, lỗi giữa chừng để dữ liệu nửa cũ nửa mới. Snapshot ALL vẫn thành công nếu không lấy được ROM, tạo backup thiếu ROM nhưng ghi nhãn full.

**Sửa:** snapshot ALL phải có ROM hợp lệ; tạo snapshot ổn định của save/state. Restore validate toàn bộ schema, console, danh sách file/duplicate/missing/extra, size/checksum trước commit; giới hạn số file, manifest size và tổng giải nén. Dùng staging + game lock + kế hoạch commit/rollback filesystem và Room; giữ dữ liệu cũ đến khi thành công toàn bộ. Bảo đảm crash recovery vì DB transaction không tự bao trùm filesystem.

**Nghiệm thu:** hash sai, schema mới không hỗ trợ, thiếu ROM, entry trùng, zip bomb, hết đĩa/permission lỗi ở bước thứ hai, DB lỗi/process kill không làm mất save cũ. Round-trip ROM/SRAM/slot state kiểm tra nội dung và chơi/nạp state thật.

### F12 — P1: SAVE_ONLY restore tạo/ghi đè GameRecord với ROM không tồn tại

**Vị trí:** `cloud/GameRestoreService.kt:121–134`; `data/db/GameDao.kt` insertOrUpdate(REPLACE).

Restore không đọc backupType hoặc lookup game hiện có; không có ROM vẫn tạo finalPath trong thư mục internal rồi REPLACE record. Máy mới nhận game không chơi được; game đang dùng SAF có thể mất sourceUri hợp lệ khi restore chỉ save.

**Sửa:** SAVE_ONLY phải ghép đúng ROM theo content checksum, console và mapping đã xác nhận. Giữ sourceUri/tree permission/localRomPath và metadata của game hiện có. Nếu thiếu ROM, yêu cầu người dùng chọn đúng ROM hoặc giữ backup chờ, không báo restore game hoàn tất. Không ghép chỉ theo tên hay gameId remote. Nếu checksum trống phải yêu cầu xác minh bổ sung.

**Nghiệm thu:** máy mới không ROM; cùng ROM khác gameId; cùng tên khác nội dung; game SAF chưa cache ROM; restore save không phá quyền truy cập/đường dẫn và dữ liệu của game khác.

### F13 — P1: Transfer gắn lifecycle UI, lỗi mạng đầu upload có thể văng app

**Vị trí:** `cloud/CloudBackupDialogHelper.kt:111–158,255–286`; `cloud/DriveBackupRepository.kt:193–201`; `data/db/TransferRecord.kt`.

Chạy transfer trong lifecycleScope, chưa dùng WorkManager/TransferRecord dù có dependency/entity. Request tạo upload session nằm ngoài try/catch; IOException thoát tới coroutine UI không được xử lý. Không có finally bao quanh toàn tác vụ để đóng dialog/dọn staging. Upload session chỉ ở biến local, không lưu offset; đổi màn hình/process chết không resume, retry có thể tạo bản trùng. Progress dialog không hủy được.

**Sửa:** repository trả typed errors; worker bền vững dùng application context, persist owner/session/backupId/resumable URL/offset/status, unique work/idempotency, retry transient với backoff. Không bắt nuốt CancellationException. UI observe job, có cancel/retry; cleanup an toàn trong finally hoặc recovery có thời hạn. Rà soát foreground/notification theo target SDK khi triển khai worker dài.

**Nghiệm thu:** mất mạng khi tạo session và giữa upload, xoay màn hình/back, kill process, retry sau upload thành công nhưng DB lỗi: không crash, không backup trùng, không staging mồ côi vĩnh viễn, không tiếp tục bằng account khác.

### F14 — P2: Danh sách Drive thiếu trang, báo rỗng khi lỗi và nhãn sai

**Vị trí:** `cloud/DriveBackupRepository.kt:167–183,304–344`; `GamesFragment.kt:75–92`.

listBackups không lấy/duyệt nextPageToken, nên chỉ hiển thị trang đầu. HTTP/auth lỗi bị trả emptyList như không có backup. Metadata upload thật không ghi backupType, list mặc định ALL nên SAVE_ONLY bị ghi nhãn Full (mock lại có field này). getOrCreateBackupFolder có thể tạo thư mục khi truy vấn lỗi và list cũng gọi hàm tạo. Sau OAuth, GamesFragment chỉ toast, không tiếp tục hành động backup/restore đang chờ.

**Sửa:** phân trang đến hết, lọc marker backup/schema, phân biệt Empty/Error/NeedsAuthorization; chỉ tạo folder khi lookup thành công và xác nhận không tồn tại, list không tự tạo folder. Ghi backupType, hỗ trợ backup cũ thiếu metadata; lưu pending action và tiếp tục sau auth nếu game/account còn hợp lệ.

**Nghiệm thu:** nhiều trang, 401/403/429/5xx, hết quota, thư mục bị xóa, full/save-only thật và cũ; không báo dữ liệu trống giả, không tạo folder trùng do lỗi.

### F15 — P1: Nút xóa tài khoản chỉ xóa phiên; endpoint còn để lại entitlement

**Vị trí:** `MoreFragment.kt:197–208`; `backend/server.js:206–218`; `res/values*/strings.xml` privacy_policy_content.

UI chỉ signOut/disconnect/revoke local, không gọi DELETE /v1/account. Endpoint chỉ xóa users/{uid}; Firestore không xóa subcollection entitlement theo document cha. Purchase mapping vẫn còn nhưng chưa có chính sách lưu tối thiểu/phục hồi, và token phiên đã cấp không được kiểm tra thu hồi ở middleware. Nội dung privacy trong app vẫn nói không có tài khoản/không truyền dữ liệu, mâu thuẫn tính năng và tài liệu privacy mới.

**Sửa:** flow delete có xác thực, backend thực hiện idempotent xóa dữ liệu ứng dụng và Firebase user; xóa subcollections tường minh, định nghĩa retention tối thiểu cho chống replay/đối soát và chính sách khôi phục purchase sau xóa/recreate UID. Chặn token/session đã bị thu hồi hoặc tài khoản đã xóa; hủy job và xóa cache/metadata theo owner. Không xóa game/save local hoặc Drive của người dùng khi chưa có yêu cầu riêng. Chỉ báo thành công khi server xác nhận; lỗi mạng có retry. Đồng bộ mô tả UI/privacy EN/VI với hành vi thật.

**Nghiệm thu:** offline/delete retry/delete hai lần, token cũ sau delete, query subcollection, recreate account rồi restore purchase; không tuyên bố xóa thành công khi chỉ logout.

## 3. Thứ tự triển khai cho Gemini

| Giai đoạn | Công việc | Điều kiện hoàn thành |
|---|---|---|
| A — Chặn lỗi bảo mật | F01, F02, F04, F06, F10; bổ sung test regression tái hiện từng lỗi trước sửa | Release không có mock; backend từ chối xác thực giả/replay/RTDN giả; restore containment đạt |
| B — Tài khoản và thanh toán thật | F03, F05, F07, F08, F09 | Firebase UID thống nhất; mua/restore/refund/account switch chạy đúng; ack/retry bền vững |
| C — Bảo toàn dữ liệu | F11, F12 | Round-trip thật; SAVE_ONLY đúng game; lỗi/kill không mất dữ liệu |
| D — Transfer và UX | F13, F14 | Worker resume/cancel, lỗi mạng rõ ràng, list đủ trang, không duplicate |
| E — Xóa tài khoản và nghiệm thu | F15, tài liệu cấu hình, test release/E2E | Delete đúng nghĩa; policy đúng hành vi; các kịch bản tích hợp có bằng chứng |

Mỗi giai đoạn giữ thay đổi tập trung và cập nhật bảng F01–F15: đã sửa/chưa sửa, test đã chạy, giới hạn còn lại. Không sửa emulator core, tìm kiếm hay giao diện ngoài phạm vi cần cho các lỗi trên. Giữ chơi/lưu local hoạt động offline không cần tài khoản.

## 4. Yêu cầu kiểm thử và bàn giao

1. Sửa test runner backend; tách app factory/dependencies để dùng fake Play và Firestore emulator. Test ownership bằng transaction thật trên emulator, không chỉ Map. Test HTTP middleware/handler, error path và idempotency.
2. Thay test VIP chỉ kiểm tra tiền tố chuỗi và test ZIP sao chép điều kiện bằng test gọi implementation thật. Có thể tách validator/state machine thuần Kotlin; dùng Robolectric/instrumentation cho Room/filesystem/auth adapter khi cần.
3. Thêm test release-specific để xác nhận fake không khả dụng. Không dùng test giả lập mua thành công làm bằng chứng Play Billing đã được tích hợp.
4. Chạy unit tests, backend tests, build/lint phù hợp cho debug và release. Lưu command, kết quả và test report; thiếu cấu hình phải ghi chưa nghiệm thu, không tự thêm mock production.
5. Trên Play internal testing/license tester: success/pending/cancel/already-owned, reinstall/máy mới, cùng Play account nhưng app UID khác, backend chậm/mất mạng, acknowledge retry, refund và notification trùng. Kiểm tra giá lấy từ Play.
6. Trên Drive thật: full/save-only, nhiều trang, revoke scope/token hết hạn, quota, mất mạng/kill/resume/account switch. Khôi phục ROM + SRAM + state qua máy khác; xác nhận game chạy và nạp được dữ liệu.
7. Backup/restore vẫn truy cập được bản cũ khi mất VIP. Không xóa hoặc đổi schema dữ liệu người dùng âm thầm; có migration/compatibility cho cache mock và bundle v1 cũ.

Đầu vào production cần xác nhận: Firebase project và Android app, OAuth Web client/SHA fingerprints, backend HTTPS URL, Play package/product thực tế, quyền service account, Pub/Sub topic + push identity/audience, Drive OAuth consent/scope. Chỉ mô tả đầu vào cần thiết; không commit private key, purchaseToken hoặc access token vào repository/log. Không tự deploy/phát hành Play trong nhiệm vụ sửa này.

## 5. Prompt giao Gemini

```text
Hãy sửa luồng VIP và các tính năng liên quan theo tài liệu
AndroidApp/docs/KE_HOACH_SUA_VIP_VA_CLOUD_CHO_GEMINI.md.

Đọc mã hiện tại và kế hoạch trước khi thay đổi. Thực hiện giai đoạn A→E,
ưu tiên ngăn cấp VIP giả, xác thực/backend ownership và bảo toàn dữ liệu restore.
Đây là sửa implementation hiện có, không triển khai lại toàn app.

Với từng F01–F15, bổ sung regression test gọi mã thật, sửa lỗi và ghi bằng chứng.
Release không được fallback sang fake auth, VIP local hay mock Drive.
Backend là nguồn quyền; cache gắn Firebase UID chỉ phục vụ hiển thị.
Không chuyển quyền mua giữa UID tự động, không âm thầm đổi tài khoản Drive/app.
Không ghi đè save trước khi kiểm tra đầy đủ và có rollback/crash recovery.
Giữ chơi local offline và restore backup cũ khi VIP bị thu hồi.

Nếu thiếu cấu hình console, hoàn tất phần code/test độc lập, liệt kê đầu vào còn
thiếu và đánh dấu E2E chưa nghiệm thu. Không tạo test pass giả, không nhúng secrets,
không tự deploy backend hoặc phát hành Play. Bàn giao danh sách file thay đổi,
trạng thái F01–F15, lệnh test/kết quả, migration và các việc còn lại.
```

## 6. Tài liệu chính thức đối chiếu

- [Google Play Billing — bảo vệ giao dịch, verify và acknowledge phía backend](https://developer.android.com/google/play/billing/security).
- [Pub/Sub — xác thực push request, token và audience](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions).
- [Firestore — xóa document không tự xóa subcollections](https://firebase.google.com/docs/firestore/manage-data/delete-data).

Các kết luận về lỗi ở trên dựa trên source và kiểm tra local, không suy đoán rằng backend production đang được triển khai công khai hoặc đã xảy ra mất dữ liệu thực tế.
