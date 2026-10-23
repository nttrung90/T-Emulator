# Chính sách quyền riêng tư — T-Emulator

**Ngày hiệu lực:** 02 tháng 10 năm 2026  
**Ứng dụng:** T-Emulator (`com.temulator.app`)  
**Nhà phát triển:** [Tên nhà phát triển hiển thị trên Google Play]  
**Liên hệ về quyền riêng tư:** [Địa chỉ email hỗ trợ hợp lệ]

T-Emulator tôn trọng quyền riêng tư của bạn. Chính sách này giải thích cách ứng dụng xử lý dữ liệu khi bạn sử dụng T-Emulator, bao gồm tính năng chạy game cục bộ và tính năng sao lưu đám mây Google Drive (dành cho người dùng VIP).

## 1. Tóm tắt

- **Chơi và tìm kiếm game ngoại tuyến:** Hoàn toàn miễn phí, không yêu cầu tài khoản, dữ liệu game và tiến trình chơi được lưu trữ cục bộ trên thiết bị của bạn.
- **Đăng nhập bằng Google (tùy chọn):** Sử dụng Credential Manager để xác thực và đồng bộ gói mua VIP.
- **Thanh toán Google Play Billing (tùy chọn):** Gói VIP (20.000 đồng/năm) chỉ thêm tính năng sao lưu game trên đám mây, các chức năng cốt lõi vẫn giữ nguyên và miễn phí.
- **Sao lưu đám mây Google Drive (VIP, do bạn chủ động):** ROM game và dữ liệu lưu tiến trình do bạn chọn được sao lưu trực tiếp vào **Google Drive cá nhân của chính bạn** (thư mục `T-Emulator Backups`). Nhà phát triển **không** thu thập, lưu trữ, tiếp cận hay bán các tệp game của bạn trên máy chủ của nhà phát triển.

## 2. Dữ liệu ứng dụng xử lý

### 2.1. Dữ liệu xử lý cục bộ trên thiết bị
- **Tệp trò chơi (ROM) do bạn tự chọn:** Ứng dụng đọc tệp bạn cấp quyền qua trình chọn tệp Android và sao chép vào vùng lưu trữ riêng an toàn của ứng dụng để nạp game.
- **Tiến trình chơi và trạng thái lưu:** Pin lưu SRAM và các slot save state được tạo và lưu trữ theo từng game trong vùng lưu trữ riêng của ứng dụng.
- **Cài đặt cá nhân:** Tùy chọn ngôn ngữ, âm thanh, tay cầm ảo, bộ lọc hình ảnh và mã gian lận được lưu cục bộ trên thiết bị.

### 2.2. Dữ liệu tài khoản và xác thực (khi bạn chọn Đăng nhập)
- Khi bạn chọn "Đăng nhập bằng Google", ứng dụng nhận thông tin hồ sơ cơ bản từ Google Identity Services: mã định danh người dùng (UID), địa chỉ email, tên hiển thị và ảnh đại diện.
- Mục đích: quản lý phiên đăng nhập và liên kết quyền VIP với tài khoản của bạn. Ứng dụng không chia sẻ thông tin này với bên thứ ba cho mục đích tiếp thị hoặc quảng cáo.

### 2.3. Dữ liệu giao dịch mua hàng (Google Play Billing)
- Khi bạn nâng cấp gói VIP, Google Play xử lý thanh toán. Ứng dụng nhận mã giao dịch (`orderId`) và mã xác thực mua hàng (`purchaseToken`).
- Mục đích: xác thực trạng thái đã mua VIP và cấp quyền sử dụng tính năng sao lưu đám mây.

### 2.4. Dữ liệu sao lưu trên Google Drive cá nhân
- Khi bạn kích hoạt sao lưu, ứng dụng yêu cầu quyền `https://www.googleapis.com/auth/drive.file` để chỉ truy cập các tệp và thư mục do chính T-Emulator tạo ra (`T-Emulator Backups`).
- Gói sao lưu (gồm ROM, tiến trình lưu và manifest) được truyền trực tiếp bằng kết nối mã hóa an toàn giữa thiết bị của bạn và máy chủ Google Drive của bạn. Nhà phát triển không có quyền truy cập vào các tệp khác trên Drive của bạn.

## 3. Mục đích xử lý dữ liệu

- Cung cấp tính năng giả lập trò chơi và khôi phục trạng thái chơi game.
- Xác nhận quyền sở hữu gói VIP thông qua Google Play.
- Hỗ trợ người dùng sao lưu và khôi phục tiến trình chơi game giữa các thiết bị thông qua tài khoản Google Drive cá nhân.

## 4. Quyền kiểm soát và xóa dữ liệu

- **Xóa phiên tài khoản ứng dụng:** Bạn có thể xóa phiên đăng nhập và dữ liệu bộ nhớ đệm đám mây bất kỳ lúc nào ngay trong ứng dụng tại mục **Thêm (More) > Tài khoản > Xóa tài khoản ứng dụng**.
- **Ngắt kết nối Google Drive:** Bạn có thể ngắt kết nối quyền truy cập Google Drive bất kỳ lúc nào trong tab Thêm.
- **Xóa tệp sao lưu trên Google Drive:** Bạn có thể xóa bản sao lưu trực tiếp trong ứng dụng hoặc quản lý trực tiếp trong tài khoản Google Drive cá nhân của bạn.
- **Xóa dữ liệu cục bộ:** Bạn có thể xóa từng game trong tab Game hoặc vào **Cài đặt Android > Ứng dụng > T-Emulator > Bộ nhớ > Xóa dữ liệu**.

## 5. Bảo mật dữ liệu

Dữ liệu truyền tải giữa thiết bị và Google (Google Identity, Google Play, Google Drive) luôn được mã hóa trong quá trình truyền qua giao thức HTTPS/TLS. T-Emulator áp dụng quy tắc cô lập thư mục, kiểm tra mã băm SHA-256 và bảo vệ chống tấn công duyệt thư mục (path traversal) trong quá trình nén và giải nén tệp sao lưu.

## 6. Trẻ em

T-Emulator không cố ý thu thập thông tin cá nhân từ trẻ em dưới 13 tuổi (hoặc độ tuổi tối thiểu theo luật định tại quốc gia của bạn).

## 7. Thay đổi chính sách

Chúng tôi có thể cập nhật Chính sách quyền riêng tư này khi bổ sung chức năng mới. Ngày hiệu lực ở đầu trang sẽ được điều chỉnh tương ứng khi có phiên bản mới.

## 8. Liên hệ

Nếu bạn có bất kỳ câu hỏi nào về chính sách quyền riêng tư hoặc việc xử lý dữ liệu trong T-Emulator, vui lòng liên hệ với chúng tôi qua email hỗ trợ của nhà phát triển trên trang ứng dụng Google Play.
