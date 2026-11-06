# Đặc tả v2.0 — Thư viện theme Google Drive cho Android TV Launcher

Ngày: 2026-11-06. Áp dụng cho fork cá nhân. Đặc tả này thay thế luồng v1.x gắn `googleDrive` vào JSON
từng theme. Hướng dẫn vận hành: [launcher/DRIVE_IMAGES.md](../launcher/DRIVE_IMAGES.md).

## 1. Mục tiêu và cấu hình

Trong Launcher Settings → Theme, theme local và các thư mục theme con của thư viện Drive được
hiển thị chung một danh sách. Người dùng chọn trực tiếp mục **gDrive-{tên thư mục}**. Danh sách Drive
từ cache hiển thị ngay; mỗi lần mở picker sẽ cập nhật nền và đổi danh sách tại chỗ nếu có thay đổi.
Không đăng nhập Google trên TV, không backend; dùng service account Viewer đã chia sẻ thư mục thư viện.

Cấu hình duy nhất cho kết nối/lịch/retention nằm trong `launcher/app/src/main/res/values/drive.xml`:

```xml
<string name="drive_theme_dir" translatable="false">THEME_LIBRARY_FOLDER_ID</string>
<integer name="drive_sync_interval">5</integer>
<bool name="drive_sync_on_launch">true</bool>
<integer name="drive_cache_retention_days">5</integer>
```

`drive_sync_interval` tính theo phút; 0 tắt định kỳ, giá trị dương dưới 15 dùng tối thiểu 15 phút của
Android JobScheduler. `drive_sync_on_launch` điều khiển sync tự động khi mở Home. Việc chọn theme chưa
có cache là yêu cầu tải ban đầu rõ ràng, vẫn thực hiện khi cờ này false. Retention là số nguyên dương,
đơn vị ngày không sử dụng. Giới hạn cache/file/MIME tiếp tục dùng các resource kỹ thuật hiện có trong
drive.xml. Không khai báo trùng resource ở XML khác.

Khóa service account vẫn ở `launcher/app/src/main/google_service_account.json`, được Git bỏ qua và
chỉ đóng gói vào assets khi build. Không đưa khóa vào theme, log hoặc manifest. APK cá nhân có thể bị
trích xuất khóa; chủ sở hữu chịu trách nhiệm giữ APK riêng và thu hồi khóa khi cần.

## 2. Cấu trúc nguồn

```text
Thư viện Drive (drive_theme_dir)/
  Theme A/
    config.json
    wallpaper.jpg
    wallpapers/
      photo-1.jpg
      captions.txt
    assets/
      subfolder/...
  Theme B/
    config.json
    wallpapers/...
```

Mỗi thư mục con là theme đầy đủ như một theme cài bằng `tools/theme.sh`. Đồng bộ đệ quy toàn bộ thư
mục: config.json, ảnh, captions.txt, tài nguyên khác và thư mục rỗng. `config.json` bắt buộc là JSON
object tối đa 64 KB, dùng chính parser/khóa theme hiện có; không tải hay áp dụng theme nếu cấu hình
bắt buộc này không hợp lệ. `wallpapers/captions.txt` là UTF-8 tối đa 256 KB, mỗi dòng
`filename<TAB>caption`; hiển thị theo `wallpaper_captions` của theme. Nội dung Drive không thể thay
cấu hình kết nối, thư viện nguồn, lịch hay retention của ứng dụng.

Tên/path phải tương đối, không có `.`/`..`, separator trong tên đơn, ký tự điều khiển hay path tuyệt đối.
Từ chối path trùng, chu trình, Google Docs/shortcut không tải như file thông thường, quá 16 cấp thư mục,
10.000 mục hoặc path dài hơn 1024 ký tự. Giới hạn file/cache vẫn được kiểm tra khi stream dữ liệu.

## 3. Chọn và áp dụng theme

1. Picker gộp theme local và gDrive; không mở menu trung gian. Cache hiện ngay, fetch nền ngoài UI
   thread và cập nhật tại chỗ chỉ khi ID/tên thay đổi, giữ lựa chọn/focus bằng ID. Mất mạng vẫn chọn cache.
2. Thư mục cache được nhận diện bằng folder ID, tên hiển thị
   được lưu riêng để đổi tên hoặc trùng tên không làm mất/ghi đè cache.
3. Chọn theme mới lưu nguồn ID, ghi nhận sử dụng, đặt lịch theo XML. Nếu chưa có snapshot hợp lệ,
   tải ban đầu ngay; Home có thể tạm hiện mặc định trong lúc tải.
4. `Config.dir` của nguồn Drive trỏ vào snapshot riêng được công bố, nên normal theme loader đọc đúng
   config.json và các file tải về. Không dùng config của theme local trước đó khi nguồn mới còn chờ.
5. Khi config/tài nguyên style thay đổi, Home nạp lại. Nếu chỉ ảnh/caption thay đổi thì cập nhật player.
6. Theme cài bằng script vẫn là local theme; bỏ hoàn toàn cách đọc `googleDrive` trong JSON từng theme.

## 4. Đồng bộ và cache

- Liệt kê đủ mọi trang của toàn cây, xác minh quyền/trạng thái thư mục trước khi thay đổi snapshot.
- So file ID, MIME và MD5; thiếu MD5 thì dùng modifiedTime + size. File mới/đổi tải bằng stream, file
  không đổi tái sử dụng. Đổi tên/xóa file/thư mục phản ánh vào snapshot mới sau listing hợp lệ.
- Tải vào snapshot tạm, kiểm tra checksum/kích thước, giải mã ảnh và kiểm tra UTF-8/JSON tương ứng.
- Chỉ công bố khi có config.json hợp lệ. Dùng manifest nguyên tử trỏ snapshot, sau đó mới dọn bản cũ.
- Lỗi listing/API/config, hủy hay đổi nguồn giữ theme đã công bố. Lỗi file tùy chọn có thể giữ bản cũ
  và đánh dấu chưa đầy đủ; không mất toàn bộ cache vì lỗi mạng.
- 401 refresh và thử lại một lần; 429/5xx/rate limit backoff hữu hạn; không lặp vô hạn khi thiếu quyền.
- Lịch dùng JobScheduler, tồn tại qua reboot. Tác vụ cũ kiểm tra nguồn hiện hành để không áp dụng nhầm.
- Nút Sync now nằm ngay dưới hàng Theme, chỉ hiện khi đang chọn theme Drive; theme local/mặc định ẩn nút này. Không mạng thì dùng cache; tổng dung lượng cache vẫn giới hạn ở drive.xml.

```text
filesDir/drive-images/<theme-folder-id>/
  manifest.json
  snapshots/<revision>/
    config.json
    wallpaper.jpg
    wallpapers/captions.txt
    assets/...
```

Chuyển A → B không xóa A. Quay lại A đọc cache ngay, chỉ tải nội dung nếu metadata đổi. Thời điểm sử
dụng ghi khi chọn/hiển thị/rời Home và trong vòng cập nhật Home hiện có; sync nền không gia hạn.
Cache nguồn đang dùng được bảo vệ. Cache nguồn không dùng chỉ được dọn sau hạn XML trong lần sync
liệt kê thành công; cache thiếu lịch sử có thời gian ân hạn. Không xóa sớm cache còn hạn để nhường chỗ;
khi đầy, sync mới có thể chưa đầy đủ.

## 5. Tiến trình và chẩn đoán

Toast dưới màn hình chỉ dành cho lần chọn theme trong Settings khi chưa có ảnh cache. Hiển thị trạng
thái chờ/đọc danh sách rồi phần trăm và số ảnh sẵn sàng/tổng ảnh. Config, caption và tài nguyên khác
không tính vào số ảnh. Chỉ báo hoàn tất sau công bố snapshot; phân biệt lỗi/chưa đầy đủ/rỗng. Đóng
phiên thông báo khi kết thúc hoặc rời Home; nhấn Home, chọn nguồn đã cache và sync nền không tạo Toast.

Log tag `TvHome.Drive` ghi stage, số lượng, mã HTTP/OAuth và exception an toàn. Không log key/token,
body tùy ý, tên file/thư mục, ID hoặc nội dung caption. Clock skew nhắc kiểm tra ngày giờ tự động.

## 6. Tiêu chí nghiệm thu

- Picker hiển thị ngay cache, gộp local/gDrive-{name}, refresh nền và cho chọn trực tiếp; giữ focus/lựa chọn
  khi cập nhật, không dựng lại danh sách nếu không thay đổi. Sync Now đúng vị trí và điều kiện ẩn/hiện.
- JSON/theme local cũ có googleDrive không kích hoạt truy cập Drive nữa.
- Tải đủ config, ảnh, captions, tài nguyên lồng nhau và thư mục rỗng; cấu hình tải về thực sự áp dụng.
- Chuyển hai theme cache/offline không lẫn nội dung, cache không đổi không tải lại; retention dùng XML.
- Thêm/sửa/xóa/đổi tên cập nhật snapshot; lỗi quyền, phân trang, file hỏng hoặc config sai không phá bản cũ.
- Nguồn/giới hạn/lịch không bị override từ config.json tải về. Đổi thư viện XML cần chọn lại nguồn mới.
- Toast đúng điều kiện, caption đúng tên ảnh, D-pad/Home hoạt động trên TV thực.
- Unit test, release build và smoke test read-only thư viện; real-TV check riêng, không thay CI/Home guard.

## 7. Fallback ảnh tĩnh cho theme Drive chỉ có bộ ảnh

Nếu wallpaper=true, wallpaper_rotation_minutes=0 và không có wallpaper.jpg, chọn ảnh đầu tiên theo
thứ tự tự nhiên trong wallpapers/ để hiển thị tĩnh. Không tự bật slideshow và không sửa dữ liệu Drive.
Nếu có wallpaper.jpg thì ưu tiên ảnh đơn khi rotation=0. wallpaper=false hoặc lựa chọn None vẫn tắt
ảnh nền. Bổ sung log ở bước chọn/giải mã ảnh để phân biệt chưa có snapshot, bị tắt và ảnh bị từ chối.
