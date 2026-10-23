# Kế hoạch sửa VIP và Cloud — vòng 4, 10/10/2026

## Kết luận kiểm tra sau bản sửa V3

Mã đã tiến bộ: compile Kotlin debug/release thành công, 32 unit test Android đạt, 20 test backend đạt và 8 probes V3 đạt. Tuy nhiên bộ mở rộng V4 tái hiện **18 trường hợp thất bại**, gồm 12 backend và 6 restore, được gom thành các yêu cầu H01–H09 bên dưới. Một số vấn đề Android session/transfer/billing/config còn lại được xác nhận qua đọc mã và cần test tích hợp (H10–H13).

Không thể kết luận hết lỗi từ kết quả xanh của V3. Ví dụ `backend/app.js:112` đang chặn đúng chuỗi `messageId === 'from-app-user'` hoặc bearer `not-a-google-jwt` của test; đổi messageId thì Firebase user token vẫn được nhận khi verifier OIDC thiếu. Ngoài ra N05 cũ cùng lúc thiếu `files[]` và ROM checksum, nên kết quả fail không chứng minh `files[]` đã bắt buộc trong mọi chế độ.

## Bằng chứng và giới hạn

| Kiểm tra đã chạy | Kết quả |
|---|---|
| `npm test` tại backend | 20/20 đạt sau khi cho phép HTTP localhost; EACCES của lần chạy sandbox không phải lỗi ứng dụng |
| Probes backend V2 | 5/5 đạt |
| Probes restore V2 | 5/5 đạt |
| Probes V3 | backend 4/4, restore 4/4 đạt |
| Script Android tách source | 16 restore + 6 VIP đạt |
| Gradle `compileDebugKotlin compileReleaseKotlin testDebugUnitTest` | BUILD SUCCESSFUL; compile tasks UP-TO-DATE |
| XML kết quả Gradle unit tests | 32 test, 0 failures/errors: VIP 6, restore 16, collision 3, search 5, storage 2 |
| Probes V4 backend | 12/12 thất bại |
| Probes V4 restore | 6/6 thất bại |

Log và source probes: `AndroidApp/docs/audit-vip-2026-10-10-v4/` (`backend-results.txt`, `restore-results.txt`, `gradle-results.txt`, `unit-test-results.txt`). Chỉ tạo tài liệu/probes; chưa sửa mã ứng dụng trong vòng audit này.

Probes backend dùng handler thật và dịch vụ giả. B02/B03 chạy source `server.js` thật trong VM, giả lập OAuth verifier trả payload đã xác minh để kiểm tra authorization/config của server; không kiểm tra mật mã JWT hoặc gọi Google thật. B12 inject một interleaving có kiểm soát, không chứng minh Firestore concurrency thật. Restore dùng source restore/storage thật và DAO giả, dữ liệu trong thư mục temp riêng. Chưa chạy Play Billing/Drive/Pub/Sub trên thiết bị hoặc production. Compile Kotlin thành công không đồng nghĩa assemble, native build, signing/R8 hay cấu hình production đã được kiểm chứng.

## Đối chiếu G01–G12 của V3

| Mục | Trạng thái |
|---|---|
| G01 compile UI | Đã xử lý lỗi tham chiếu; compile debug/release đạt |
| G02 RTDN auth | Một phần: đã inject OIDC, nhưng hardcode dữ liệu test, fallback và thiếu email/audience guard còn lỗi |
| G03 subscription JSON | Một phần: đã đọc string enum, vẫn fallback product và chấp nhận expiry thiếu |
| G04 expiry/lifecycle | Một phần: đã lưu/check expiry, nhưng renewal/hold, aggregate quyền, retry/reconciliation chưa hoàn chỉnh |
| G05 manifest | Một phần: ALL có save được guard; SAVE_ONLY và duplicate canonical ZIP chưa chặn |
| G06 slot mapping | Hai slot khác số đạt; collision cùng slot và giữ bản tên cũ còn gây overwrite |
| G07 identity | ID collision khác ROM và console mismatch đạt; actual ROM thiếu vẫn chấp nhận |
| G08 recovery | DAO null, rollback game mới và journal/recovery chưa xử lý đủ |
| G09 cold start | Đã seed UID/cache và invalidate generation khi revoke; auth source và cache expiry/refresh còn thiếu |
| G10 transfer | Có thêm check UID trước upload; chưa pin Drive identity hoặc recover công việc bền vững |
| G11 callback | Đã thêm clear callback khi hủy paywall; query lỗi, lifecycle và simulator còn vấn đề |
| G12 deletion/config | Token sau deletion chưa chặn; cấu hình release vẫn placeholder/thiếu wiring |

## Yêu cầu sửa tiếp

### H01 — P0: xác thực RTDN theo identity, bỏ đặc cách chuỗi test (B01–B03)

- `backend/app.js:112–119`: đổi messageId thành `different-user-message-83`, user Firebase token vẫn HTTP 200 và tắt VIP. Đây là đường `createApp` thiếu OIDC verifier; production entrypoint hiện đã inject verifier nên không khẳng định mọi request production đều đi qua fallback này.
- `backend/server.js:66–79`: chỉ kiểm issuer/email_verified, không đối chiếu `payload.email` với service account cấu hình. B02 cho payload đã xác minh có email tài khoản khác vẫn được nhận.
- Thiếu `PUBSUB_AUDIENCE` vẫn truyền audience undefined. B03 không bị từ chối. Source thư viện `google-auth-library/build/src/auth/oauth2client.js:738` chỉ kiểm audience khi expected audience có giá trị.

Sửa: xóa các điều kiện theo messageId/token literal của test khỏi logic auth. Thiếu verifier/config phải fail closed; không dùng Firebase user auth làm RTDN fallback. Bắt buộc expected audience (có thể cấu hình từ endpoint hợp lệ) và expected push service-account email; so sánh email chính xác, email_verified strict, chữ ký/issuer/expiry/audience đúng. Nếu giữ shared-secret mode phải cấu hình riêng, không tự động hạ mức xác thực.

Nghiệm thu: nhiều messageId và token user khác nhau đều không mutate DB; OIDC sai email/audience/issuer, hết hạn, thiếu cấu hình bị từ chối; đúng identity được nhận. Test wiring production và factory thiếu verifier. [Hướng dẫn Pub/Sub chính thức](https://docs.cloud.google.com/pubsub/docs/authenticate-push-subscriptions) yêu cầu đối chiếu email và audience của push subscription.

### H02 — P1: product và expiry subscription phải hợp lệ (B04–B05)

`backend/app.js:343` dùng `find đúng product || lineItems[0]`, nên subscription của `different_subscription` vẫn cấp `vip_yearly`. Dòng 351 coi expiry 0 là còn hiệu lực; sau đó lưu null và GET cho VIP vô hạn. B04/B05 đều trả GRANTED.

Sửa: chọn line item đúng sản phẩm được allowlist, không fallback sang item khác; expiry phải parse được, hữu hạn và theo quy tắc state. Không biến thiếu/invalid expiry thành lifetime. Loại bỏ numeric enum workaround của subscriptionsv2; dùng string contract và quyết định rõ trạng thái. API query/ack phải theo loại sản phẩm, không chuyển yearly sang products chỉ vì mock thiếu method. Test thiếu item, sai product, nhiều item, timestamp thiếu/sai/quá hạn, state lạ, binding sai.

Tham chiếu: [SubscriptionPurchaseV2](https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2).

### H03 — P1: renewal/recovery/hold phải đối soát Play (B06–B08)

- `app.js:266–275`: duplicate token expiry cũ đã qua hạn trả EXPIRED mà không query Play. B06 Play giả trả ACTIVE expiry mới nhưng verify vẫn false, Play query count 0.
- `app.js:540–547`: RTDN type 2 RENEWED và type 5 ON_HOLD đều bị bỏ qua, vẫn trả OK. B07 renewal không cập nhật; B08 hold vẫn có VIP.
- Type 13 EXPIRED đang dùng cờ voided vĩnh viễn, nhánh verify sau đó không cho recovery bằng token đó.

Sửa: tách immutable ownership khỏi mutable purchase state. Duplicate token phải có đường requery/reconcile theo freshness và lifecycle, không khóa vĩnh viễn vì expiry cũ. RTDN đã authenticate phải lấy trạng thái mới từ Play, xử lý renew/recover/grace/cancel/hold/pause/expire/revoke theo policy; tránh sự kiện cũ ghi đè state mới. Expired và refunded/revoked là trạng thái riêng. Lưu state/expiry/check time nhất quán. Retry acknowledge có hàng đợi bền vững hoặc tác vụ scheduled rõ ràng, không chỉ dựa client verify lần sau.

Nghiệm thu: full lifecycle cùng token; replay và out-of-order RTDN; Play/DB outage retry; server restart khi ack pending; xác nhận cả purchase và entitlement sau mỗi bước.

### H04 — P1: quyền tổng hợp mọi purchase hợp lệ (B09–B10)

`handlePurchaseVoided` coi mọi purchase `voided:false` là active, không lọc expiry. B09 lifetime refund nhưng yearly khác đã hết hạn khiến VIP lifetime vẫn true. Chiều ngược lại, mỗi verify ghi đè document vip bằng purchase vừa verify; B10 mua lifetime rồi yearly, tiến clock qua yearly expiry thì GET false dù lifetime chưa bị voided.

Sửa: có một hàm quyết định entitlement chung từ tất cả purchase hợp lệ của UID: lifetime active hoặc subscription state/expiry đủ quyền. Dùng cho verify, GET/reconciliation và RTDN; không chỉ đếm record non-voided hoặc giữ quyền của “purchase mới nhất”. Không để yearly làm giảm quyền lifetime. Migration dữ liệu yearly thiếu expiry phải được đối soát, không mặc định VIP mãi mãi.

Nghiệm thu cả hai hướng; nhiều token, lifetime + yearly, refund một token, expired record và recovery; Firestore và chế độ mock cùng policy.

### H05 — P1: chặn token sau deletion và race voided/commit (B11–B12)

`authenticateUser` gọi `verifyIdToken(token)` không checkRevoked. B11 delete account xong, token chưa hết hạn vẫn được authenticate ở GET. Theo SDK hiện cài, tham số mặc định false không kiểm tra user đã xóa/revoke.

`app.js:451–456`: transaction commit chỉ kiểm UID; token đã được đánh voided bởi tác vụ khác giữa validation và commit vẫn bị ghi lại `voided:false` và GRANTED. B12 tái hiện interleaving này.

Sửa: kiểm tra revoked/deleted/disabled user hoặc policy kiểm tra trạng thái tương đương; deletion phải có trạng thái bền vững/idempotent để request đang chạy không commit sau khi account bắt đầu xóa. Transaction commit recheck ownership, voided/state/version/deletion; refund/revoke không được một verify cũ tái kích hoạt. Không xóa ledger dùng chống replay. Purchase/entitlement/event state cần transaction hoặc retry/reconciliation, không nuốt lỗi persist rồi kết thúc thành công.

Test race bằng Firestore emulator/adapter transaction thực; delete lỗi từng bước, retry, token cũ và request verify/transfer đã in-flight. [Firebase hướng dẫn verify token](https://firebase.google.com/docs/auth/admin/verify-id-tokens); source SDK cục bộ `firebase-admin/lib/auth/base-auth.js:114–120` xác nhận checkRevoked mặc định false.

### H06 — P1: manifest đầy đủ và đường dẫn canonical không được trùng (T01–T02)

`GameRestoreService.kt:135–146` chỉ bắt files khi ALL có save; SAVE_ONLY với romChecksum hợp lệ và không có files[] vẫn ghi `UNCHECKED` save (T01). ZIP `Saves/remote.sav` + `Saves/./remote.sav` ghi cùng canonical file; manifest checksum chỉ khớp bản cuối nhưng bundle vẫn được nhận (T02).

Sửa: bắt buộc `files[]` đúng kiểu theo schema cho cả hai scope, có quy tắc riêng cho backup không có save; mỗi file phải có checksum/size/type hợp lệ. Canonical hóa và phát hiện duplicate ZIP đích trước ghi; phát hiện duplicate manifest sau normalize. Kiểm path bên trong root, file count/size, structure và mọi file phải được khai báo. Kiểm tất cả trước mutation dữ liệu đích. Legacy nếu có phải explicit version/validator, không fallback bỏ integrity.

Test SAVE_ONLY và ALL độc lập với checksum ROM đúng; null/sai kiểu files; duplicate literal/canonical, path separators, file thừa và zip traversal.

### H07 — P1: SAVE_ONLY phải xác nhận ROM thực, không chỉ DB checksum (T03)

`GameRestoreService.kt:223–224` chọn record theo cached hash/console rồi không kiểm ROM nữa. T03 record hash đúng nhưng file ROM không tồn tại vẫn success.

Sửa: resolve đường ROM hợp lệ theo storage/URI được hỗ trợ, xác nhận tồn tại/đọc được và hash thực đúng trước overwrite saves. Không tin `contentSha256` nếu actual file đổi hoặc mất. Xác minh ID mới khi collision không trùng record/thư mục khác, và giữ byte dữ liệu cũ khi conflict. Test ROM thiếu, changed bytes, revoked URI, archive member và ID collision.

### H08 — P1: mapping slot/SRAM phải phát hiện collision trước copy (T05)

`GameRestoreService.kt:312–325` giữ số slot nhưng bất kỳ prefix nào cùng slot đều map chung file. T05 `remote_1.mss` + `other_1.mss` có checksum riêng vẫn success và một nội dung mất khi map `local_1.mss`.

Restore hiện còn giữ cả tên remote cũ và tên local đã map. Sau khi chơi và tạo state mới, backup tiếp có thể chứa bản cũ + bản hiện hành cùng slot dưới hai prefix, nên đây còn là vấn đề restore–play–backup–restore. SRAM cũng map mọi file cùng extension về một tên.

Sửa: lập bảng source→destination theo naming contract core trước copy; reject/resolve collision rõ ràng, không phụ thuộc listFiles order. Chỉ giữ bản tên cần dùng hoặc có metadata mapping rõ ràng, không tạo bản cũ làm nguồn gây collision cho backup sau. Test 2 slot khác số, 2 prefix cùng slot, SRAM nhiều file, basename có underscore, round trip nhiều lần và load state thực trên thiết bị.

### H09 — P1: restore chỉ success khi DB và filesystem commit cùng nhau (T04, T06)

DAO lỗi initialization bị chuyển thành null; `gameDao?.insertOrUpdate` tại dòng 361 vẫn báo success. T04 bundle hợp lệ không có DAO vẫn commit ROM và success. T06 game mới, DAO insert lỗi: trả failure nhưng ROM/save mới vẫn còn vì rollback không ghi nhận “đích trước đó không tồn tại”.

Sửa: require usable DB ở production trước mutation, có interface test explicit nếu cần file-only mode. Rollback phải phục hồi cả trường hợp target ban đầu không tồn tại. Kiểm kết quả rollback; nếu rollback lỗi phải giữ dữ liệu recovery và journal, không cleanup vô điều kiện. Có journal durable với recovery sau restart cho filesystem + Room; khóa tác vụ cùng game và tránh game đang chạy ghi save trong snapshot/restore.

Nghiệm thu lỗi từng bước: mkdir/copy/hash/DB/rollback, process chết trước/sau commit, game mới/game cũ; không orphan, không mất bản cứu dữ liệu, không báo success khi DB fail. Test source tách rời không thay thế Room/restart test.

### H10 — P1: cache VIP có hạn và session lấy từ Firebase (đọc mã)

Cold start UID seed đã thêm đúng. Nhưng AuthRepository vẫn khôi phục profile từ SharedPrefs; không có Firebase auth-state listener để xử lý token/user bị xóa ở nơi khác. `refreshEntitlement` chỉ được gọi ở login MoreFragment; startup/foreground/cloud chưa refresh. Cloud gate chỉ đọc isVipActive boolean. Cache không lưu expiry; refresh lỗi/HTTP lỗi vẫn trả boolean cũ.

Sửa: auth state/generation thống nhất theo Firebase; refresh startup/foreground và trước thao tác cloud với policy offline/freshness hữu hạn; lưu expiry/state/verified time cần thiết. Backend xác nhận false/401/403 phải invalidate đúng UID; lỗi mạng không được kéo dài quyền vô hạn. Check UID + generation ngay trước mọi commit, không chỉ so UID vì A→logout→A vẫn cùng UID. Giữ invalidate generation đã có trong revokeEntitlement.

Nghiệm thu bằng test state/lifecycle: expiry/refund khi app mở, cache cũ cold start, offline, remote deletion, A→B và A→logout→A khi request chậm. Phần này chưa được kiểm thử thiết bị trong audit.

### H11 — P1: pin Drive account và phục hồi transfer (đọc mã)

DriveAuthorizationManager lấy token từ `GoogleSignIn.getLastSignedInAccount`, trong khi email cache có thể là tài khoản trước. Backup ghi `driveAccountEmail.value` sau upload. App UID checks không phát hiện Drive account đổi. Restore không truyền session/generation để kiểm tra trước commit. Tác vụ vẫn lifecycleScope; thêm dependency WorkManager chưa có worker/resume path. Lỗi ghi TransferRecord/CloudBackupRecord bị catch rồi vẫn toast success; cancellation/finally chưa bảo đảm cleanup.

Sửa: snapshot operation identity gồm app UID/generation + Drive account thực; lấy token và upload/list/download/commit theo identity đã pin. Khi đổi tài khoản/hủy cần dừng an toàn, reconcile remote orphan. Ghi job bền vững nếu yêu cầu tiếp tục/recover sau process chết; worker/service và state machine thực, không chỉ thêm thư viện/record. Không nuốt commit DB lỗi. Cleanup bảo đảm khi cancellation và quyền bị revoke. Pending RESTORE/BACKUP phải riêng và scoped lifecycle.

Nghiệm thu account switch chỉ trên Drive, app user switch, rotate/background/restart, DB fail sau upload, hủy consent và tiếp tục restore đúng action.

### H12 — P2: billing callback và restore purchase có trạng thái lỗi rõ ràng (đọc mã)

Paywall đã clear callback khi hủy. Tuy nhiên callback vẫn singleton, chưa scoped UID/generation/lifecycle. `queryPurchasesAsync` bỏ responseCode ở cả SUBS và INAPP nên lỗi query bị hiểu là không có purchase. Simulator success gọi onSuccess ngay sau simulateTestPurchase, trong khi handlePurchase cũng gọi pending callback sau verify: có thể gọi hai lần hoặc chạy cloud trước VIP cập nhật.

Sửa: operation ID/session-scoped callback đúng một lần sau verified, kết quả phân biệt no-purchase/pending/canceled/service-error/verify-error; clear khi billing launch lỗi, lifecycle đổi/account đổi. Debug simulator dùng cùng completion path. Pending action clear khi hủy paywall/backup scope; sau Drive consent restore tiếp tục đúng action. Test real callback ordering, already-owned, query lỗi, simulator success, recreate và logout giữa mua.

### H13 — P1: cấu hình release và kiểm thử tích hợp phải cụ thể (đọc mã)

Manifest vẫn `YOUR_GOOGLE_WEB_CLIENT_ID...` và BACKEND_VERIFY_URL rỗng; Gradle không có Google Services plugin, source không có FirebaseApp bootstrap hoặc generated google_app_id wiring thấy được. Compile thành công chưa chứng minh FirebaseAuth có app config hoặc mua/verify hoạt động ở release.

Sửa: đưa config qua build/environment phù hợp, validate config startup/build theo variant; Firebase initialization rõ ràng. Không hardcode secret, không dùng DEBUG simulator thay bằng chứng release. Nếu thiếu cấu hình do chủ dự án cấp, ghi biến cần cấp và mục bị chặn; không đánh dấu đã hoàn tất production integration. Chạy release unit tests liên quan, assemble/R8 phù hợp và smoke test Google login → Play test purchase → backend → backup/restore trên thiết bị khi config có sẵn.

## Thứ tự thực hiện và tiêu chí kết thúc

1. H01 trước mọi triển khai backend; H02–H05 cùng mô hình state/entitlement nhất quán.
2. H06–H09 theo validation → identity/mapping → commit/recovery; giữ sửa ID collision và slot khác số đã đạt.
3. H10–H13 với test tích hợp session, lifecycle, Drive và config; ưu tiên failure modes cụ thể trên.
4. Giữ test cũ và bổ sung biến thể dữ liệu. Không thêm condition theo test name/messageId/token literal trong production; không nới assertion để đạt màu xanh. Nếu contract thay đổi hợp lý, ghi rõ lý do và thay test theo hành vi đúng, không giữ workaround sai để chiều mock cũ.
5. Báo từng H01–H13: đã sửa/chưa xong, file, test evidence và giới hạn. Các mục cần external config/thiết bị phải nêu chưa xác minh; không tuyên bố hết lỗi chỉ vì unit test đạt.

Lệnh probes V4 từ repo root:

```powershell
node AndroidApp/docs/audit-vip-2026-10-10-v4/backend-regressions.cjs
& ./AndroidApp/docs/audit-vip-2026-10-10-v4/run-restore-audit.ps1
```

Hiện exit code 1 là đúng với lỗi đã tái hiện. Chạy thêm `npm test` tại backend, probes V2/V3 và Gradle debug/release/unit tests. Script standalone restore có đường JDK/cache máy audit; chỉnh đường dẫn khi chuyển máy, không coi đây là build Android đầy đủ.

## Prompt cho Gemini

```text
Hãy sửa tiếp theo AndroidApp/docs/KE_HOACH_SUA_VIP_VA_CLOUD_CHO_GEMINI_V4.md.
Đọc toàn bộ kế hoạch, source và probes AndroidApp/docs/audit-vip-2026-10-10-v4/. Bản hiện tại compile debug/release và test cũ đạt, nhưng 12 probes backend + 6 probes restore mới thất bại. Ưu tiên H01 xác thực RTDN, H02–H05 subscription/lifecycle/quyền tổng hợp/deletion/race; sau đó H06–H09 restore và H10–H13 session/Drive/billing/config.
Xóa đặc cách theo messageId from-app-user hoặc bearer not-a-google-jwt; xác thực theo identity thật. Không đổi literal test hoặc nới assertion để xanh, không giữ enum số sai contract hoặc fallback subscription thành in-app vì mock thiếu method. Giữ các sửa đúng đã đạt, đồng thời kiểm tra variant và failure modes trong kế hoạch.
Sau khi sửa, chạy test cũ + 18 probes V4, build debug/release, unit/integration tests cần thiết. Báo bảng H01–H13: trạng thái, file sửa, bằng chứng test, cấu hình hoặc kiểm thử thiết bị còn thiếu. Không đánh dấu hết lỗi nếu còn mục chưa xử lý/kiểm chứng. Không triển khai production trong nhiệm vụ này.
```
