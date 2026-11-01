# FIX20 — Chia sẻ riêng cửa sổ Chrome

## Thay đổi
- Android 14+ (API 34 trở lên): ALAD mở system MediaProjection picker ở chế độ người dùng tự chọn nội dung. Người dùng có thể chọn Chrome/app window thay vì toàn bộ màn hình.
- `DelayedScreenVideoCapture` nhận `onCapturedContentResize()` để lấy đúng kích thước cửa sổ được chọn; không còn mặc định lấy nửa kích thước màn hình điện thoại.
- Video recorder được xoay đoạn khi kích thước cửa sổ thay đổi.
- Màn hình phát trễ không ép ngang; giữ hướng hiển thị phù hợp với cửa sổ Chrome.
- Luồng âm thanh trực tiếp sang Gemini vẫn tách riêng, không chờ bộ ghi video.

## Cách thử trên Android 14+
1. Mở ALAD và bấm kết nối/chia sẻ màn hình.
2. Trong hộp thoại hệ thống, chọn chế độ chia sẻ một ứng dụng (nếu có), sau đó chọn **Chrome**. Không chọn toàn bộ màn hình.
3. Xác nhận bắt đầu chia sẻ. Mở video trong Chrome và để video phát.
4. Dùng nút toàn màn hình trên cửa sổ phụ đề ALAD để mở trình phát trễ.
5. Kiểm tra hình có đúng tỷ lệ, Chrome có tiếp tục phát và ALAD còn nhận âm thanh.

## Giới hạn quan trọng
- Chỉ Android 14/API 34 trở lên có picker chọn riêng cửa sổ ứng dụng qua `MediaProjectionConfig.createConfigForUserChoice()`. Trên Android cũ, hệ thống chỉ cung cấp chia sẻ toàn màn hình.
- Người dùng phải chọn Chrome trong hộp thoại hệ thống; ALAD không thể tự chọn hoặc bí mật ghi riêng Chrome.
- Việc chia sẻ cửa sổ không thể buộc Chrome tiếp tục phát video nền. Chrome, trang web, DRM hoặc chính sách tiết kiệm pin của hãng máy vẫn có thể tạm dừng nội dung khi Chrome mất foreground.
- Nếu video được bảo vệ DRM, hình có thể đen hoặc không ghi được.
- Đây vẫn là video-only segments. Âm thanh PCM trễ được xử lý riêng; chưa có mux AAC/H.264 và chưa đảm bảo đồng bộ khung hình/âm thanh chính xác.
- Chưa build APK hoặc thử trực tiếp trên vivo. Đây là bản mã nguồn để kiểm thử, không khẳng định đã chạy thành công.
