# Hướng dẫn Cấu hình Dịch vụ Bên ngoài (Google Cloud, Play Console & Backend)

Tài liệu này cung cấp hướng dẫn từng bước để cấu hình các dịch vụ Google Cloud, Google Play Console và triển khai Backend cho tính năng Đăng nhập Google, VIP Billing và Sao lưu Google Drive.

---

## 1. Google Cloud Console

1. Truy cập [Google Cloud Console](https://console.cloud.google.com/) và tạo một dự án mới (hoặc chọn dự án Firebase đã tạo): ví dụ `temulator-prod`.
2. **Kích hoạt APIs:**
   - **Google Identity / Credential Manager**
   - **Google Drive API** (yêu cầu để người dùng lưu vào Drive cá nhân qua scope `https://www.googleapis.com/auth/drive.file`).
   - **Google Play Android Developer API** (dành cho Backend xác thực giao dịch mua hàng).
3. **Màn hình đồng ý OAuth (OAuth Consent Screen):**
   - Loại người dùng: **External**.
   - Tên ứng dụng: `T-Emulator`.
   - Email hỗ trợ người dùng và thông tin nhà phát triển.
   - Thêm phạm vi (Scope): `https://www.googleapis.com/auth/drive.file`.
4. **Tạo OAuth 2.0 Client IDs:**
   - **Android Client ID:**
     - Package name: `com.temulator.app`
     - SHA-1 Certificate Fingerprint: Thêm cả mã SHA-1 của Debug Keystore và **App Signing Key** từ Google Play Console.
   - **Web Application Client ID:**
     - Dùng làm `server_client_id` cho Credential Manager / Firebase Authentication.
     - Sao chép Client ID này (dạng `xxxxx.apps.googleusercontent.com`) và cấu hình vào `AndroidManifest.xml` hoặc SharedPreferences trong ứng dụng.

---

## 2. Google Play Console

1. **Tạo Sản phẩm / Gói thuê bao (Subscription / In-App Product):**
   - Vào **Monetize > Subscriptions** (hoặc **In-app products**).
   - Tạo sản phẩm mới với Product ID: `vip_yearly` (hoặc `vip_lifetime`).
   - Tên sản phẩm: `T-Emulator VIP (1 năm)`.
   - Mô tả: Mở khóa VIP chỉ thêm tính năng sao lưu game trên đám mây, các chức năng cốt lõi vẫn giữ nguyên.
   - Đặt giá bán: `20.000 VNĐ / năm` và kích hoạt sản phẩm (`Active`).
2. **Cấu hình Google Play Developer API cho Backend:**
   - Vào **API access** trong Google Play Console.
   - Liên kết với dự án Google Cloud ở Bước 1.
   - Tạo Service Account mới (hoặc chọn Service Account có sẵn) và cấp quyền **View financial data, orders, and cancellation survey responses** cùng quyền **Manage orders and subscriptions**.
   - Tải về tệp JSON khóa riêng tư (`Service Account Key JSON`).
3. **Cấu hình Thông báo Nhà phát triển theo Thời gian thực (RTDN - Pub/Sub):**
   - Tạo Cloud Pub/Sub Topic trong Google Cloud: `projects/temulator-prod/topics/play-billing-rtdn`.
   - Cấp quyền `Publisher` cho tài khoản dịch vụ của Google Play: `google-play-developer-notifications@system.gserviceaccount.com`.
   - Trong Google Play Console > **Monetize > Monetization setup**, nhập tên Topic vừa tạo.
   - Tạo Pub/Sub Push Subscription trỏ về endpoint của Backend: `https://<YOUR_BACKEND_DOMAIN>/v1/rtdn`.

---

## 3. Triển khai Backend (Node.js / Express / Cloud Run / Firebase Functions)

Thư mục `AndroidApp/backend/` chứa mã nguồn hoàn chỉnh của backend:
- `server.js`: Xác minh Play purchase token, chống gian lận/replay token, xử lý RTDN khi có hoàn tiền/hủy mua, xóa tài khoản người dùng theo chính sách Google Play.
- `firestore.rules`: Quy tắc bảo mật ngăn client tự ghi trạng thái VIP.

### Biến môi trường (`.env`):
```env
PORT=8080
PLAY_PACKAGE_NAME=com.temulator.app
VIP_PRODUCT_ID=vip_yearly
FIREBASE_SERVICE_ACCOUNT_KEY={"type":"service_account",...}
PLAY_SERVICE_ACCOUNT_KEY={"type":"service_account",...}
```

### Chạy kiểm thử cục bộ:
```bash
cd AndroidApp/backend
npm install
npm start
```
