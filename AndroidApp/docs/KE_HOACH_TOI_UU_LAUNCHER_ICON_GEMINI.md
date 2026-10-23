# Kế hoạch tối ưu launcher icon cho Gemini / Antigravity

Ngày lập: 02/10/2026. Dự án: T-Emulator Android.

Mục tiêu: icon rõ, cân đối và đồng nhất trên launcher **vuông bo góc** và **tròn**. Gemini thực hiện thay đổi tài nguyên icon, tạo ảnh so sánh và kiểm chứng trên Android. Tài liệu này là kế hoạch giao việc; icon ứng dụng chưa được sửa.

## 1. Hiện trạng đã kiểm tra

Các đường dẫn trong tài liệu tính từ thư mục gốc `F:/AI/T-Emulator`.

| Thành phần | Hiện trạng |
| --- | --- |
| `AndroidApp/app/src/main/AndroidManifest.xml` | Có `android:icon="@mipmap/ic_launcher"` và `android:roundIcon="@mipmap/ic_launcher_round"` |
| `AndroidApp/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` và `ic_launcher_round.xml` trong cùng thư mục | Hai adaptive icon dùng cùng foreground và background |
| `AndroidApp/app/src/main/res/values/colors.xml` | Nền `ic_launcher_background` là `#0C76FE` |
| `AndroidApp/app/src/main/res/mipmap-*/ic_launcher_foreground.png` | Chứa cả nền xanh bo góc, chữ EMULATOR, tay cầm, bóng dài và phần trắng ở góc; chưa phải lớp tay cầm riêng |
| `AndroidApp/app/build.gradle.kts` | `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36` |
| `AndroidApp/app/src/main/play_store_512.png` | Có ảnh 512 × 512; là tài nguyên riêng cần giữ ngoài phạm vi thay đổi launcher lần này |
| Nguồn thiết kế | Chưa tìm thấy SVG hoặc script sinh icon trong `AndroidApp`; cần tạo nguồn có thể chỉnh sửa nếu vẽ lại |

Các file foreground hiện có:

| Density | Canvas foreground | Hình chữ nhật có alpha khác 0 | Bitmap thường / tròn |
| --- | --- | --- | --- |
| mdpi | 108 × 108 px | 78 × 78 px | 48 × 48 px |
| hdpi | 162 × 162 px | 117 × 117 px | 72 × 72 px |
| xhdpi | 216 × 216 px | 156 × 156 px | 96 × 96 px |
| xxhdpi | 324 × 324 px | 233 × 233 px | 144 × 144 px |
| xxxhdpi | 432 × 432 px | 311 × 311 px | 192 × 192 px |

Đã kiểm tra alpha: toàn bộ hình chữ nhật nêu trên là đục; alpha chỉ có 0 hoặc 255. Khi xem ảnh, vùng trắng lấp các góc ngoài ô xanh bo tròn. Đây là vấn đề cần xử lý trước: launcher có thể cắt thêm một lần, để lộ mảng trắng hoặc tạo cảm giác icon nhỏ nằm trong một icon lớn. Kích thước hình chữ nhật này đo toàn bộ ô nền, không phải riêng tay cầm.

Workspace hiện không có Git repository ở thư mục gốc. Gemini cần kiểm tra lại môi trường trước khi dùng branch/commit; không coi việc tạo commit là điều kiện hoàn thành thay đổi icon.

## 2. Phương án thiết kế đề xuất

- Giữ nhận diện hiện tại: nền xanh `#0C76FE`, tay cầm trắng, D-pad tối và bốn nút màu.
- Foreground chỉ chứa tay cầm với vùng ngoài trong suốt. Tách hoàn toàn nền xanh, mảng trắng ở góc và đường bo của ô cũ.
- Đề xuất bỏ chữ **EMULATOR** khỏi launcher để tăng diện tích tay cầm và độ rõ ở kích thước nhỏ. Đây là lựa chọn thiết kế đề xuất, không phải yêu cầu của Android. Giữ tên ứng dụng trong nhãn launcher hiện có.
- Dùng nền xanh phẳng ở vòng đầu. Bỏ bóng dài chạm mép; nếu cần tạo chiều sâu, dùng chi tiết nhẹ bên trong tay cầm.
- Dùng một bố cục tay cầm chung cho tròn và vuông bo góc. Chỉ cân chỉnh riêng bản tròn nếu ảnh kiểm chứng cho thấy lý do rõ ràng.
- Ưu tiên vẽ lại tay cầm bằng SVG nguồn và Android VectorDrawable. Giữ hình dáng và vị trí nút dễ nhận diện; tránh trace bitmap thành hàng trăm path.

Android dùng các lớp foreground/background và mặt nạ launcher để tạo hình. Canvas adaptive là **108 × 108 dp**, vùng an toàn ở giữa là **66 × 66 dp**; các lớp không nên tự mang đường bo ngoài hoặc bóng quanh biên icon. Gemini phải kiểm tra mask khi căn logo. [Android Developers: Adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

Quy tắc bố cục riêng cho dự án: bắt đầu với tay cầm rộng khoảng 60 dp, căn quanh tâm `(54,54)`, giữ đúng tỉ lệ. Chọn kích thước cuối bằng ảnh preview ở 48 px. Dùng thêm vòng tròn tâm `(54,54)`, bán kính 33 dp làm giới hạn kiểm tra thận trọng cho chi tiết quan trọng; không suy ra rằng chỉ cần bounding box nhỏ là mọi góc đều an toàn.

## 3. Các bước Gemini cần thực hiện

### Bước 1 — Lưu mốc so sánh

1. Đọc lại Manifest, adaptive XML, màu nền và các PNG; xác nhận chúng chưa thay đổi so với mục 1.
2. Lưu bản sao tài nguyên icon cũ vào `AndroidApp/docs/launcher-icon/before/` để so sánh và khôi phục. Không lưu APK hoặc cả thư mục build.
3. Tạo preview icon cũ dưới mask tròn và vuông bo góc. Ghi rõ đây là mô phỏng nếu chưa có ảnh chụp launcher thật.

### Bước 2 — Tạo nguồn và foreground

Chọn một nguồn thiết kế chính: SVG được xuất sang VectorDrawable/PNG, hoặc VectorDrawable được dùng để render PNG. Ghi cách tái tạo; không chỉnh riêng nhiều bản nguồn khiến chúng lệch nhau.

1. Tạo `AndroidApp/design/launcher-icon/controller.svg`, canvas/viewBox 108 × 108, vùng ngoài tay cầm trong suốt. Nếu môi trường không chuyển SVG tốt, tạo trực tiếp VectorDrawable và ghi rõ đó là nguồn chính.
2. Tạo `AndroidApp/app/src/main/res/drawable/ic_launcher_foreground.xml`, canvas 108 dp, viewport 108 × 108.
3. Căn tay cầm theo cảm nhận thị giác, không chỉ theo bounding box. D-pad, nút màu và tay nắm phải đọc rõ khi thu nhỏ.
4. Preview hai phương án kích thước gần nhau, ví dụ rộng 58 và 62 dp; chọn một phương án sau khi xem cả mask tròn và vuông bo góc. Hai phương án dùng để căn kích thước, không yêu cầu tạo hai bộ nhận diện mới.
5. Nếu giữ raster để bảo toàn chi tiết, xuất lại foreground RGBA ở đúng kích thước trong bảng; không phóng to PNG nhỏ hoặc xóa màu trắng/xanh bằng ngưỡng toàn ảnh vì có thể làm mất thân tay cầm hoặc nút xanh.

### Bước 3 — Nối tài nguyên adaptive

Các đường dẫn resource viết gọn ở bước này và các bước sau nằm trong `AndroidApp/app/src/main/res/`.

1. Giữ background `@color/ic_launcher_background` phủ kín canvas.
2. Khi dùng vector, đổi foreground trong **cả hai** file `mipmap-anydpi-v26/ic_launcher*.xml` sang `@drawable/ic_launcher_foreground`.
3. Giữ liên kết `android:icon` và `android:roundIcon` trong Manifest.
4. Không bo tròn sẵn foreground, không thêm tấm vuông trắng, không đóng gói hình tròn vào bên trong adaptive icon.
5. Sau khi kiểm tra reference và preview, bỏ các PNG foreground cũ nếu không còn được dùng. Giữ bản trước thay đổi ở thư mục so sánh.

Launcher áp mặt nạ tròn khi dùng `roundIcon`; không cần chèn vòng tròn vào foreground. [Android Developers: icon và roundIcon](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive#add-your-adaptive-icon-to-your-app).

### Bước 4 — Đồng bộ bitmap hiện có

Sinh lại `ic_launcher.png` và `ic_launcher_round.png` ở năm density trong bảng từ cùng nguồn tay cầm mới. Bitmap thường dùng bố cục vuông bo góc; bitmap round dùng hình tròn, cạnh khử răng cưa và vùng ngoài trong suốt. Không chèn viền trắng.

Đây là bộ bitmap đồng bộ cho các nơi dùng tài nguyên thường. Vì ứng dụng có minSdk 26, luồng launcher chính phải được xác minh bằng **adaptive XML**. Không hạ minSdk để kiểm thử Android 7.1. Image Asset Studio có thể sinh và preview bộ adaptive/legacy/round nếu phù hợp với môi trường. [Android Studio: Create app icons](https://developer.android.com/studio/write/create-app-icons).

Giữ `play_store_512.png` trong phạm vi riêng; nếu cần thay ảnh cửa hàng, lập công việc tiếp theo với tiêu chuẩn Google Play.

### Bước 5 — Kiểm chứng và bàn giao

| Kiểm tra | Cách thực hiện | Điều kiện đạt |
| --- | --- | --- |
| Lớp foreground | Xem riêng trên nền caro và kiểm tra alpha/path | Chỉ còn tay cầm; vùng ngoài trong suốt; không có mảng trắng ở góc |
| Tròn + vuông bo góc | Preview từ adaptive resources; xem ở 48, 72 và 96 px | Không cắt nút/tay nắm; không có nền lồng nhau; kích thước tay cầm cân đối |
| Mask khác | Thêm squircle và vuông để rà soát | Không phát sinh chi tiết bị cắt hoặc viền thừa |
| Tương phản | Đặt preview trên hình nền sáng, tối và nhiều chi tiết | Nhận ra tay cầm ngay; D-pad và nút không nhập vào nền |
| Bitmap thường/tròn | Kiểm tra kích thước, alpha và hình dạng của cả năm density | Đúng bảng; cùng nguồn; mép sạch khi xem ở kích thước thật |
| Tài nguyên Android | Chạy từ `AndroidApp`: `.\gradlew.bat :app:processDebugResources` | AAPT xử lý thành công; không thiếu drawable hoặc tham chiếu sai |
| Lint | `.\gradlew.bat :app:lintDebug` | Không có lỗi mới do tài nguyên icon; tách lỗi tồn tại trước |
| APK và launcher | `.\gradlew.bat :app:assembleDebug`, cài trên thiết bị/emulator sẵn có | App mở được; icon đúng ở màn hình chính và danh sách ứng dụng |

Kiểm thử launcher thật: dùng một launcher/mask tròn và một launcher/mask vuông bo góc. Nên có một môi trường API 26–32 và một môi trường API 33+ nếu sẵn có. Ghi tên launcher, API và ảnh chụp; đổi mask trong preview không thay thế bằng chứng launcher thật.

Nếu launcher còn cache icon cũ, thử gỡ shortcut rồi thêm lại hoặc khởi động lại launcher/thiết bị. Không xóa dữ liệu ứng dụng/launcher hoặc gỡ ứng dụng đang chứa game, save và cấu hình chỉ để làm mới icon. Dùng emulator kiểm thử riêng khi cần cài mới.

Gradle có thể thất bại do SDK/JDK/NDK hoặc cấu hình native hiện có. Báo chính xác lệnh, lỗi và phần đã kiểm tra; không tự nâng Gradle/AGP/SDK để mở rộng công việc. Không báo đã kiểm thử trên máy thật khi chỉ có preview.

## 4. Phần mở rộng tùy chọn: themed icon

Chỉ thực hiện sau khi bản màu đạt yêu cầu. Tạo silhouette tay cầm một màu, giữ phần khuyết cho D-pad/nút để vẫn nhận diện được. Có thể thêm `drawable/ic_launcher_monochrome.xml` và hai adaptive XML trong `mipmap-anydpi-v33/` tham chiếu lớp monochrome, giữ bản v26 cho API thấp hơn.

Themed icon dùng lớp monochrome trên Android 13+ khi launcher hỗ trợ và người dùng bật tính năng. Kiểm tra cả bật/tắt themed icons trước khi bàn giao phần này. [Android Developers: User theming](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

Đây là phần mở rộng; không làm chậm việc sửa icon tròn và vuông bo góc nếu môi trường chưa hỗ trợ kiểm chứng.

## 5. Đầu ra và tiêu chí hoàn thành

- Nguồn tay cầm có thể chỉnh sửa và tài nguyên Android cập nhật.
- Ảnh `AndroidApp/docs/launcher-icon/before-after.png`: cũ/mới × tròn/vuông bo góc, kèm mẫu ở 48 px; ghi rõ preview hay screenshot thật.
- Báo cáo `AndroidApp/docs/launcher-icon/RESULTS.md`: file đã đổi, phương án kích thước đã chọn, lệnh kiểm tra và kết quả, launcher/API đã thử, giới hạn còn lại.
- Nếu dùng script xuất PNG, lưu script và lệnh chạy trong `AndroidApp/design/launcher-icon/README.md`; nếu dùng Image Asset Studio, ghi thông số nhập/xuất để tái tạo.
- Hoàn thành phần chính khi foreground đã tách sạch, cả hai mask đẹp ở kích thước thật, bộ bitmap đồng bộ, xử lý tài nguyên thành công và có kết quả kiểm thử launcher rõ ràng. Nếu thiếu môi trường kiểm thử, ghi rõ phần chưa xác minh.

Phạm vi sửa: nguồn thiết kế, các resource icon liên quan và tài liệu kiểm chứng. Không đổi package name, phiên bản, tên ứng dụng, tính năng emulator, đăng nhập/VIP/Drive hoặc icon desktop.

## 6. Prompt giao cho Gemini trong Antigravity

```text
Hãy triển khai tối ưu launcher icon Android của T-Emulator theo tài liệu:
AndroidApp/docs/KE_HOACH_TOI_UU_LAUNCHER_ICON_GEMINI.md

Đọc và kiểm tra tài nguyên hiện tại trước khi sửa. Mục tiêu là icon tay cầm
rõ, cân đối trên launcher tròn và vuông bo góc. Giữ nhận diện nền xanh,
tay cầm trắng và nút màu. Theo phương án đề xuất, bỏ chữ EMULATOR khỏi
launcher, tách hoàn toàn tay cầm khỏi ô nền/viền trắng/đường bo cũ.

Ưu tiên nguồn chỉnh sửa được và VectorDrawable; nối cả ic_launcher và
ic_launcher_round vào cùng bộ lớp adaptive. Đồng bộ bitmap năm density.
Tạo ảnh cũ/mới dưới hai mask, kiểm tra kích thước thật và chạy các bước
xác minh trong tài liệu. Ghi kết quả thực tế trong RESULTS.md; phân biệt
preview với ảnh launcher thật và nêu rõ kiểm tra nào chưa chạy được.

Chỉ sửa các file thuộc phạm vi icon. Không xóa dữ liệu game/save để làm
mới cache, không nâng toolchain hoặc đổi tính năng khác. Phần monochrome
là tùy chọn sau khi icon màu hoàn thành. Bàn giao danh sách file thay đổi,
ảnh so sánh và kết quả kiểm tra. Không chỉ trả lời lại bằng một kế hoạch.
```
