# Technical Specification — Đồng bộ ảnh Google Drive cho Android TV Launcher

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản | 1.2 |
| Ngày | 2026-11-06 |
| Nền tảng | Android TV, ứng dụng Launcher viết bằng Java (Android Studio) |
| Phạm vi | Launcher cá nhân, chỉ cài trên các TV do chủ ứng dụng quản lý |
| Trạng thái | Đề xuất triển khai |

## Cập nhật hợp đồng nguồn ảnh v1.2

Theo yêu cầu mới, `googleDrive.folderId` chỉ **thư mục gốc của theme** trên Drive.
Ứng dụng đọc `wallpaper.jpg` ở gốc và ảnh JPG/JPEG/PNG/WebP trong thư mục con `wallpapers/`
và file `wallpapers/captions.txt` (UTF-8, tối đa 256 KB) nếu có, lưu snapshot riêng trên thiết bị với đúng các đường dẫn đó. Không duyệt sâu hơn.
Cấu hình theme vẫn lấy từ theme cài bằng `tools/theme.sh`, không lấy `config.json` trên Drive.
Nếu các mục v1.1 bên dưới còn mô tả danh sách ảnh phẳng hoặc cache `FILE_ID.jpg`, hợp đồng
v1.2 này thay thế chúng. Chi tiết hiện hành và cách chọn ảnh được ghi tại
[launcher/DRIVE_IMAGES.md](../launcher/DRIVE_IMAGES.md).

### Giữ cache nhiều theme

`googleDrive.cacheRetentionDays` trong JSON của mỗi theme là số ngày không sử dụng trước khi cache
của nguồn đó có thể bị xóa; mặc định 30, nhận số nguyên dương. Chuyển theme không xóa cache nguồn cũ.
Ghi nhận lần sử dụng khi chọn/hiển thị/rời Home; đồng bộ nền không gia hạn. Nếu nhiều theme dùng chung
folderId thì giữ cache đến khi tất cả theme đó hết hạn. Quay lại theme đọc cache ngay, chỉ tải nội dung
đã đổi sau khi so metadata. Kiểm tra hết hạn khi sync liệt kê thành công; không xóa sớm cache còn hạn
để nhường dung lượng. Quy tắc này thay thế mọi mô tả thu hồi cache nguồn cũ ngay khi đổi theme bên dưới.

## 1. Mục tiêu

Launcher tự đọc và hiển thị ảnh từ **thư mục Google Drive có quyền Restricted được chỉ định bởi theme đang cài đặt**, không yêu cầu người dùng TV đăng nhập Google, không dùng backend trung gian. Chủ sở hữu chỉ cần tải thêm, sửa hoặc xóa ảnh trong thư mục để nội dung trên TV được cập nhật tại lần đồng bộ tiếp theo.

### 1.1. Trong phạm vi

- Xác thực Google Drive API bằng **Service Account** và khóa JSON đóng gói trong APK.
- Đọc `wallpaper.jpg` ở thư mục gốc và ảnh trong thư mục con `wallpapers/` của nguồn Google Drive do theme chỉ định.
- Tải và lưu cache ảnh cục bộ, hiển thị khi không có Internet.
- Làm mới danh sách ảnh theo chu kỳ và khi mở launcher theo cấu hình của theme; xử lý token hết hạn, phân trang và lỗi mạng.
- Tích hợp cơ chế cài theme hiện tại (`tools/theme.sh`) và đọc các giới hạn kỹ thuật từ `config.xml` hiện có của ứng dụng.
- Chỉ cấp quyền **Viewer** cho Service Account đối với thư mục ảnh; không công khai ảnh.

### 1.2. Ngoài phạm vi (v1)

- Đăng nhập Google trên TV; màn hình chọn tài khoản.
- Upload, sửa, xóa ảnh trên Drive từ launcher.
- Duyệt thư mục con đệ quy, Google Photos, video, Google Docs/Slides, chuyển đổi định dạng.
- Đồng bộ nhiều Google Drive hay cấp quyền riêng theo người dùng.
- Backend, giao diện quản trị web, cơ chế chia sẻ ảnh ra ngoài mạng.

## 2. Kiến trúc

```text
Chủ sở hữu Google Drive
  └─ My Drive / TVLauncherImages  [General access: Restricted]
       └─ Share: tv-launcher@PROJECT_ID.iam.gserviceaccount.com  [Viewer]
                    ▲
                    │ Google Drive API v3 qua HTTPS + OAuth2 access token
                    │
Android TV Launcher (APK cá nhân)
  ├─ assets/service-account.json  [private key; dùng chung, không đặt trong theme]
  ├─ ThemeConfigLoader  [đọc cấu hình Google Drive từ theme hiện hành]
  ├─ config.xml  [tham số kỹ thuật ứng dụng]
  ├─ DriveAuthManager  [tạo và refresh token]
  ├─ DriveImageRepository  [list/get]
  ├─ ImageSyncWorker  [đồng bộ theo lịch / khi mở ứng dụng]
  ├─ LocalImageCache  [ảnh + manifest]
  └─ Launcher UI  [đọc ảnh từ cache]
```

**Quyết định kiến trúc:** Vì APK chỉ sử dụng trên TV cá nhân, cho phép nhúng Service Account JSON trong APK để giảm độ phức tạp. Đây là sự chấp nhận rủi ro có chủ đích: tài khoản và private key có thể bị trích xuất từ APK; không được coi là cơ chế bảo vệ bí mật tuyệt đối.

## 3. Cấu hình Google Cloud và Google Drive

### 3.1. Google Cloud

1. Tạo hoặc chọn project tại <https://console.cloud.google.com/>.
2. Chọn **APIs & Services → Library → Google Drive API → Enable**.
3. Chọn **IAM & Admin → Service Accounts → Create Service Account**.
4. Ví dụ đặt tên `android-tv-launcher`.
5. Ở bước **Grant this service account access to project**, **không cấp IAM Role**; bỏ qua.
6. Ở bước **Grant users access to this service account**, bỏ qua.
7. Vào Service Account → **Keys → Add Key → Create new key → JSON**, nếu chính sách project cho phép tạo user-managed key. Tải file JSON và lưu riêng bản sao an toàn.

> Google Cloud IAM Role và quyền đối với dữ liệu Google Drive là hai lớp quyền khác nhau. Đọc thư mục được chia sẻ không đòi hỏi cấp Project Viewer/Editor/Owner cho Service Account.

### 3.2. Google Drive

1. Trong Drive của chủ sở hữu, tạo thư mục `TVLauncherImages`.
2. Share thư mục cho `client_email` trong file JSON Service Account.
3. Chọn **Viewer**; giữ **General access = Restricted**.
4. Tải `wallpaper.jpg` vào thư mục gốc và/hoặc đặt bộ ảnh JPG, JPEG, PNG, WebP trong thư mục con `wallpapers/`.
5. Lấy `folderId` từ URL `https://drive.google.com/drive/folders/FOLDER_ID`.
6. Kiểm tra bằng chính Service Account xem `files.list` và `files.get` có đọc được file. Nếu tài khoản thuộc tổ chức Google Workspace, chính sách chia sẻ bên ngoài có thể ngăn chia sẻ cho Service Account.

**Lưu ý:** Không sử dụng `Anyone with the link`, không dùng API key công khai để đọc ảnh Restricted. Quyền Viewer được thừa kế cho các file thông thường trong thư mục; một số giới hạn chia sẻ riêng có thể làm file không đọc được.

## 4. Cấu hình theo theme và cấu hình kỹ thuật ứng dụng

### 4.1. Nguyên tắc phân tách cấu hình

- **Cấu hình theo nội dung/theme**: đặt trong `themes/<theme-name>/config.json`; bao gồm `googleDrive.folderId`, `googleDrive.syncInterval`, `googleDrive.syncOnLaunch` và trạng thái bật/tắt nguồn Google Drive nếu cần.
- **Cấu hình kỹ thuật dùng chung của ứng dụng**: đặt trong **`config.xml` đang tồn tại của app**; bao gồm `cacheMaxSizeMb`, `supportedMimeTypes` và các giới hạn kỹ thuật khác. **Không** lặp lại trong JSON của theme.
- **Credentials**: `service-account.json` vẫn là tài nguyên bí mật dùng chung cho APK, không để trong từng thư mục theme và không ghi trong `config.json` hay `config.xml`.
- Tránh tạo `launcher_config.json` mới hoặc nguồn cấu hình song song với hệ thống theme hiện có.

### 4.2. Ví dụ thư mục theme

```text
project-root/
├── themes/
│   ├── google1/
│   │   ├── config.json        # Cấu hình theo theme; chứa googleDrive
│   │   └── ...                # Các tài nguyên theme hiện có
│   └── other-theme/
│       └── config.json
├── tools/
│   └── theme.sh              # Công cụ cài theme hiện có
└── app/
    └── src/main/
        ├── assets/
        │   └── service-account.json
        └── res/xml/
            └── config.xml    # Minh họa vị trí; giữ nguyên đường dẫn thực tế của app
```

Lệnh cài theme:

```bash
tools/theme.sh themes/google1
```

Lệnh trên là **entry point hiện có** của dự án. Khi triển khai, cần kiểm tra logic thực tế của `tools/theme.sh`, nơi lưu theme hiện hành và cách `config.json` được đưa vào APK. Không mặc định script đã hỗ trợ Google Drive; bổ sung để cấu hình Google Drive của theme được đưa vào nguồn cấu hình runtime mà launcher thực sự đọc. **Không** tạo cơ chế theme độc lập hoặc ghi đè `config.xml` chỉ để phục vụ tính năng này.

### 4.3. Schema `themes/google1/config.json`

**Thêm một đối tượng `googleDrive` vào `config.json` hiện có, giữ nguyên mọi thuộc tính khác của theme:**

```json
{
  "googleDrive": {
    "enabled": true,
    "folderId": "REPLACE_WITH_GOOGLE_DRIVE_FOLDER_ID",
    "syncInterval": 360,
    "syncOnLaunch": true
  }
}
```

Đây là ví dụ phần cấu hình Google Drive; khi áp dụng phải **merge** đối tượng `googleDrive` vào JSON thật, không thay thế nội dung cấu hình theme hiện hữu.

| Khóa | Kiểu | Bắt buộc | Ý nghĩa |
|---|---|---|---|
| `googleDrive.enabled` | boolean | Không | Bật nguồn ảnh Drive cho theme; mặc định `false` nếu thiếu đối tượng `googleDrive`, mặc định `true` khi đối tượng có `folderId` hợp lệ nhưng không khai báo `enabled` |
| `googleDrive.folderId` | string | Có, khi enabled | ID thư mục gốc theme trên Drive đã share Viewer; ảnh ở `wallpaper.jpg` và `wallpapers/`; không dùng URL đầy đủ |
| `googleDrive.syncInterval` | integer | Không | **Phút** giữa hai lần đồng bộ định kỳ; mặc định `360` (6 giờ). Giá trị `0` = tắt đồng bộ định kỳ; với giá trị dương, áp dụng giới hạn tối thiểu của cơ chế lịch Android đang dùng (WorkManager periodic tối thiểu 15 phút) |
| `googleDrive.syncOnLaunch` | boolean | Không | `true` = yêu cầu sync nền khi mở launcher; `false` = không tự sync lúc mở; mặc định `true` |

**Quy tắc:** `syncInterval` là số **phút** (không phải giờ hoặc mili-giây); nếu muốn dùng đơn vị khác phải thay đổi spec và code đồng bộ. Giá trị âm, sai kiểu hoặc `folderId` rỗng khi enabled là lỗi cấu hình, không được âm thầm dùng thư mục khác. Nếu `enabled=false` hoặc không có `googleDrive`, launcher không gọi Google Drive API cho theme đó.

### 4.4. Tham số kỹ thuật trong `config.xml` hiện có

Tiếp tục dùng **file `config.xml` của app đang được dự án sử dụng**, giữ nguyên cấu trúc và quy ước đặt tên hiện tại. Cần ánh xạ các cấu hình kỹ thuật sau vào cơ chế đọc XML hiện hữu (ví dụ dưới đây là **minh họa thuộc tính**, không phải yêu cầu đổi root/tag hoặc vị trí file):

```xml
<config>
    <googleDrive>
        <cacheMaxSizeMb>300</cacheMaxSizeMb>
        <supportedMimeTypes>image/jpeg,image/png,image/webp</supportedMimeTypes>
        <maxFileSizeMb>20</maxFileSizeMb>
    </googleDrive>
</config>
```

| Cấu hình kỹ thuật | Giá trị ví dụ | Công dụng |
|---|---|---|
| `cacheMaxSizeMb` | `300` | Ngưỡng dung lượng cache ảnh Google Drive trong app |
| `supportedMimeTypes` | `image/jpeg,image/png,image/webp` | Whitelist định dạng để lọc metadata và kiểm tra nội dung tải về |
| `maxFileSizeMb` | `20` | Giới hạn tải một ảnh, nếu app chưa có cấu hình tương đương |

**Lưu ý tương thích:** Chưa có nội dung `config.xml` của dự án trong yêu cầu này, nên tên tag và đường dẫn ở đoạn XML chỉ là hợp đồng logic; khi code cần **tận dụng tên khóa, cấu trúc và parser sẵn có** trước khi bổ sung trường mới. Không chuyển `cacheMaxSizeMb`, `supportedMimeTypes` hoặc `maxFileSizeMb` sang JSON theme.

### 4.5. Thứ tự nạp, ưu tiên và thay đổi theme

1. Sau lệnh `tools/theme.sh themes/google1`, bản build phải dùng **`config.json` của theme `google1`** làm nguồn duy nhất cho các tham số Google Drive theo theme.
2. Khi khởi động, app nạp cấu hình kỹ thuật từ `config.xml`, rồi nạp cấu hình Google Drive của theme được cài; validate trước khi chạy sync.
3. Các tùy chọn Google Drive trong theme **không ghi đè** các giới hạn kỹ thuật trong `config.xml`.
4. Nếu chuyển từ theme A sang B (thông qua build/cài theme hiện có), thư mục B trở thành nguồn ảnh có hiệu lực; không trộn ảnh của A vào danh sách hiển thị của B.
5. Manifest/cache phải phân vùng theo `folderId` hoặc theme ID + `folderId`; tác vụ sync cũ không được cập nhật manifest cho theme mới. Cache cũ có thể giữ đến khi thu hồi dung lượng nhưng không hiển thị nhầm.
6. Khi cùng một APK có thể thay theme lúc chạy (nếu app đã hỗ trợ), phải hủy/đánh dấu lỗi thời tác vụ sync cũ rồi lên lịch lại theo cấu hình mới. Nếu theme chỉ thay ở **build time**, việc cài bản APK mới cần tái đọc cấu hình và điều chỉnh lịch sync cũ.
7. Trường hợp cấu hình lỗi, ghi log tên theme và khóa bị lỗi (không ghi credentials); giữ cache hợp lệ của đúng folder nếu có, không tự truy cập folder ngẫu nhiên.

### 4.6. Android permissions và thư viện

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Cache ảnh nằm trong vùng riêng của app (`filesDir`/`cacheDir`), không cần cấp quyền lưu trữ bên ngoài. Dùng HTTPS và không chạy tác vụ mạng trên UI thread.

- Google Auth Library for Java để lấy token Service Account.
- HTTP client đang có trong dự án (ví dụ OkHttp) để gọi Drive API.
- WorkManager hoặc bộ lập lịch hiện có của app cho sync; không thêm cơ chế lên lịch thứ hai nếu đã có giải pháp tương đương.
- Thư viện hiển thị ảnh đang dùng của launcher; tránh bổ sung dependency trùng lặp.

## 5. Xác thực và phân quyền

### 5.1. OAuth scope

`https://www.googleapis.com/auth/drive.readonly`

Scope này cho phép đọc các đối tượng Drive mà Service Account được phép truy cập, **không tự giới hạn chỉ một thư mục**. Giới hạn quyền thực tế bằng cách chỉ share thư mục ảnh cho Service Account. Có thể tìm hiểu scope hẹp hơn nếu thiết kế thay đổi, nhưng v1 sử dụng `drive.readonly` để đọc danh sách và nội dung file đã được chia sẻ.

### 5.2. Luồng xác thực

1. Đọc `service-account.json` từ assets.
2. Nạp `ServiceAccountCredentials`, gán scope `drive.readonly`.
3. Tạo/refresh OAuth2 access token qua Google token endpoint (thư viện xử lý việc ký JWT).
4. Đính kèm `Authorization: Bearer <access_token>` khi gọi Drive API.
5. Refresh token khi hết hạn (hoặc trước hết hạn bằng thư viện); xử lý `401` bằng refresh và retry tối đa một lần.
6. Tuyệt đối không ghi private key/access token ra log.

**Không dùng:** `client_secret` của OAuth client thông thường, API key, hoặc Google Sign-In UI thay cho Service Account JSON.

## 6. API Google Drive

### 6.1. Liệt kê ảnh

```http
GET https://www.googleapis.com/drive/v3/files
Authorization: Bearer ACCESS_TOKEN
```

Query parameters (URL-encode):

```text
q='FOLDER_ID' in parents and trashed = false
fields=nextPageToken,files(id,name,mimeType,size,modifiedTime,md5Checksum)
pageSize=100
pageToken=<NEXT_PAGE_TOKEN_IF_PRESENT>
```

- Lặp `pageToken` đến khi không còn `nextPageToken`.
- Chỉ nhận file có `mimeType` trong danh sách hỗ trợ.
- Kiểm tra lại `mimeType` và kích thước khi tải; không dựa vào phần mở rộng tên file.
- Với `My Drive` thông thường không cần `supportsAllDrives`; nếu sau này đổi sang Shared Drive, bổ sung tham số tương ứng.
- Cần xử lý trường metadata tùy chọn không xuất hiện (ví dụ `md5Checksum`).

Ví dụ metadata:

```json
{
  "files": [
    {
      "id": "FILE_ID_001",
      "name": "wallpaper-01.jpg",
      "mimeType": "image/jpeg",
      "size": "1524300",
      "modifiedTime": "2026-11-05T08:00:00.000Z",
      "md5Checksum": "OPTIONAL_MD5"
    }
  ]
}
```

### 6.2. Tải ảnh gốc

```http
GET https://www.googleapis.com/drive/v3/files/FILE_ID?alt=media
Authorization: Bearer ACCESS_TOKEN
```

- Tải dạng stream trực tiếp ra file tạm; không đọc toàn bộ ảnh lớn vào RAM.
- Kiểm tra HTTP status, Content-Type, giới hạn kích thước (đề xuất tối đa 20 MB/ảnh), và giải mã ảnh hợp lệ.
- Chỉ sau khi tải hoàn tất và kiểm tra thành công mới đổi tên file tạm thành file cache chính thức.
- Không dùng URL `webContentLink` làm đường dẫn cache bền vững và không gắn access token vào URL hiển thị/log.

## 7. Quy trình đồng bộ

### 7.1. Khởi động ứng dụng

1. Đọc manifest cache nội bộ (nếu có).
2. Hiển thị ngay ảnh cache, không chờ mạng.
3. Nếu cấu hình `googleDrive.syncOnLaunch=true` của theme đang hoạt động và có mạng, kích hoạt đồng bộ nền; áp dụng cơ chế chống chạy trùng.
4. Sau khi tải thành công, cập nhật UI hoặc dữ liệu cho lần hiển thị tiếp theo, không làm gián đoạn ảnh đang xem.

### 7.2. Đồng bộ nền

1. Kiểm tra mạng khả dụng.
2. Lấy access token hợp lệ.
3. `files.list` toàn bộ các trang và tạo danh sách ảnh remote.
4. So sánh với manifest local dựa trên `fileId` và `md5Checksum` (nếu có) hoặc `modifiedTime` + `size`.
5. Chỉ tải file mới hoặc có thay đổi.
6. Kiểm tra và lưu từng file vào cache bằng ghi file nguyên tử.
7. Khi **toàn bộ thao tác liệt kê remote thành công**, cập nhật manifest; tránh diễn giải lỗi API hoặc danh sách không đầy đủ thành việc toàn bộ ảnh đã bị xóa.
8. Xóa cache của file không còn trên Drive sau khi có snapshot danh sách hợp lệ; có thể trì hoãn xóa để tránh ảnh đang sử dụng.
9. Ghi thời điểm đồng bộ và trạng thái, phát sự kiện cho UI.

Đồng bộ định kỳ theo `googleDrive.syncInterval` (**phút**) của theme hiện hành; ví dụ `360` tương ứng 6 giờ. Nếu bằng `0`, không tạo lịch sync định kỳ. Dùng WorkManager hoặc bộ lập lịch sẵn có (thời điểm thực tế có thể trễ do Android/Doze). Ngoài ra hỗ trợ nút “Sync now” nếu launcher có trang Settings. Khi đồng bộ thất bại, giữ nguyên ảnh đã cache.

### 7.3. Thứ tự hiển thị

Mặc định sắp xếp theo tên file tăng dần theo thứ tự tự nhiên nếu triển khai được (`photo-2` trước `photo-10`); tiêu chí sắp xếp là logic của launcher, không phụ thuộc thứ tự trả về từ API. Không định nghĩa slideshow hoặc thời gian đổi ảnh trong tính năng đồng bộ này.

## 8. Thiết kế cache

```text
<app filesDir>/drive-images/
  manifest.json
  FILE_ID_001.jpg
  FILE_ID_002.png
  .tmp/
```

Manifest đề xuất:

```json
{
  "schemaVersion": 1,
  "folderId": "FOLDER_ID_FROM_ACTIVE_THEME",
  "themeId": "google1",
  "lastSuccessfulSyncUtc": "2026-11-05T09:00:00Z",
  "items": [
    {
      "id": "FILE_ID_001",
      "name": "wallpaper-01.jpg",
      "mimeType": "image/jpeg",
      "modifiedTime": "2026-11-05T08:00:00Z",
      "size": 1524300,
      "cacheFile": "FILE_ID_001.jpg"
    }
  ]
}
```

- Không lưu private key, token trong manifest.
- Dùng `fileId` làm định danh ổn định, không dùng tên người dùng đặt để tránh trùng tên/path traversal.
- Bảo đảm thư mục cache không thể bị truy cập tùy ý bởi ứng dụng khác.
- Giới hạn dung lượng cache theo `config.xml`; khi vượt giới hạn ưu tiên giữ ảnh đang sử dụng, có chính sách loại bỏ rõ ràng và thông báo đồng bộ chưa đầy đủ.
- Nếu số ảnh ít hơn dung lượng cho phép, giữ toàn bộ ảnh đã đồng bộ để hỗ trợ offline.
- Phân vùng manifest theo nguồn dữ liệu (`folderId`/theme); không sử dụng snapshot của theme khác làm dữ liệu hiển thị hiện tại.

## 9. Xử lý lỗi

| Tình huống | Hành vi yêu cầu |
|---|---|
| Không có Internet | Giữ cache, bỏ qua tải mới; thử lại khi có mạng |
| HTTP 401 | Refresh token và thử lại tối đa 1 lần |
| HTTP 403 | Không retry liên tục; phân biệt thiếu quyền với quota/rate limit dựa trên body lỗi |
| HTTP 404 cho folder/file | Ghi lỗi cụ thể; giữ cache hợp lệ; không xóa hàng loạt |
| HTTP 429 hoặc 5xx | Retry với exponential backoff và jitter, giới hạn số lần |
| Timeout/đứt mạng khi tải | Xóa file `.tmp`, giữ bản cache cũ |
| Theme `config.json` thiếu `folderId` hoặc sai kiểu giá trị | Không gọi Drive API; ghi rõ lỗi theme/config; chỉ giữ cache đúng nguồn |
| Theme thay đổi folder | Hủy/loại bỏ hiệu lực sync cũ; chỉ hiển thị manifest của folder mới |
| JSON credentials hỏng/bị thu hồi | Báo lỗi cấu hình; vẫn hiển thị cache cũ |
| File không phải ảnh/ảnh hỏng | Bỏ qua file, ghi log đã ẩn thông tin nhạy cảm |
| Danh sách nhiều trang | Chỉ coi snapshot hợp lệ sau khi tải đủ mọi trang |
| Drive không có ảnh | Hiển thị trạng thái không có ảnh; chỉ dọn cache khi xác minh danh sách rỗng hợp lệ |
| Thiếu dung lượng | Dừng tải và hiển thị cache còn dùng được |

## 10. Yêu cầu phi chức năng và bảo mật

- Tất cả truy cập Google API qua HTTPS.
- Không gọi mạng trên Android main/UI thread.
- Không dùng quyền Editor để chia sẻ folder; hạn chế Service Account chỉ xem.
- Không commit `service-account.json`, không log token/key, không đưa credentials vào crash report.
- Chỉ chia sẻ thư mục ảnh, không chia sẻ toàn bộ My Drive.
- Thừa nhận rủi ro APK bị reverse engineer; nếu APK được chia sẻ hoặc bị mất kiểm soát, phải thu hồi/xóa JSON key trong Google Cloud và phát hành APK chứa key mới.
- Service Account key không tự động được bảo vệ bởi Android Keystore khi đã được nhúng vào APK. Obfuscation chỉ tăng khó khăn, không loại trừ nguy cơ trích xuất.
- Không có yêu cầu tài khoản Google hay tương tác cấp quyền từ người dùng TV.
- Việc tải định kỳ cần tránh ảnh hưởng hiệu năng launcher, đặc biệt trên TV cấu hình thấp.
- Không ghi thông tin private key trong `themes/*/config.json` hoặc `config.xml`; không commit credentials vào repository.
- Cấu hình theme không thể vượt qua các giới hạn kỹ thuật của `config.xml`.

## 11. Tiêu chí nghiệm thu

| ID | Kiểm thử | Kết quả mong đợi |
|---|---|---|
| AC-01 | Khởi chạy lần đầu, thư mục Drive Restricted đã share Viewer | Hiển thị ảnh mà không hiện Google login |
| AC-02 | Chưa cấp IAM Role trên project cho Service Account | Đọc được folder nếu Drive API đã bật và chia sẻ hợp lệ |
| AC-03 | Thêm ảnh mới vào folder | Ảnh xuất hiện sau lần sync thành công |
| AC-04 | Thay nội dung ảnh cũ | Bản cache mới thay bản cũ sau sync |
| AC-05 | Xóa ảnh trên Drive | Ảnh được loại khỏi danh sách sau khi xác nhận sync thành công |
| AC-06 | Tắt Internet và mở launcher | Ảnh cache vẫn hiển thị |
| AC-07 | Access token hết hạn | Tự refresh và tiếp tục mà không cần người dùng |
| AC-08 | Thu hồi quyền Viewer khỏi Service Account | Không tải ảnh mới; giữ cache và ghi lỗi quyền |
| AC-09 | Folder có hơn 100 file | Truy xuất đủ ảnh qua phân trang |
| AC-10 | Một ảnh lỗi trong quá trình tải | Không làm hỏng cache của ảnh khác |
| AC-11 | Chèn file PDF hoặc thư mục con khác `wallpapers/` | Không hiển thị như ảnh |
| AC-12 | Mạng đứt giữa quá trình sync | Không xóa toàn bộ manifest/cache cũ |
| AC-13 | Kiểm tra mã nguồn và log | Không có private key/token trong Git hoặc log runtime |
| AC-14 | Chạy `tools/theme.sh themes/google1` rồi build/cài APK | Launcher đọc `googleDrive` từ `themes/google1/config.json` qua luồng cấu hình theme sẵn có |
| AC-15 | Đặt `syncInterval=360` | Lịch đồng bộ cấu hình theo 360 phút, không hardcode 6 giờ |
| AC-16 | Đặt `syncInterval=0` và `syncOnLaunch=false` | Không tạo lịch định kỳ và không tự đồng bộ khi mở launcher |
| AC-17 | Đặt `syncOnLaunch=true` | Hiển thị cache trước rồi yêu cầu sync nền khi mở launcher |
| AC-18 | Thay theme A (folder A) bằng theme B (folder B) | Chỉ hiển thị/sync ảnh folder B, không lẫn cache/manifest folder A |
| AC-19 | Đổi `cacheMaxSizeMb` hoặc `supportedMimeTypes` trong `config.xml` | Áp dụng giới hạn kỹ thuật mới mà không cần sửa `themes/google1/config.json` |
| AC-20 | Kiểm tra `themes/google1/config.json` | Không chứa `cacheMaxSizeMb`, `supportedMimeTypes` hoặc credentials |
| AC-21 | Theme tắt `googleDrive.enabled=false` hoặc thiếu `googleDrive` | Không yêu cầu token/không gọi Drive API cho theme này |
| AC-22 | `folderId` rỗng hoặc `syncInterval` âm | Báo lỗi cấu hình, không sync nhầm hoặc dùng giá trị sai |

## 12. Các hạng mục triển khai

1. Thiết lập Google Cloud project, bật Drive API và tạo Service Account không có IAM role.
2. Chia sẻ folder Restricted cho `client_email` với Viewer và xác minh quyền.
3. Mở rộng `themes/<theme>/config.json` với `googleDrive.folderId`, `googleDrive.syncInterval`, `googleDrive.syncOnLaunch`; kiểm tra và cập nhật `tools/theme.sh` để cài/đóng gói cấu hình theme theo pipeline hiện tại.
4. Tận dụng `config.xml` hiện có để nạp `cacheMaxSizeMb`, `supportedMimeTypes` (và giới hạn kỹ thuật cần thiết); đặt credentials riêng trong assets APK.
5. Xây dựng `DriveAuthManager` lấy/refresh access token.
6. Xây dựng `DriveImageRepository` để list đầy đủ và download ảnh.
7. Xây dựng `LocalImageCache` và manifest, hỗ trợ thay đổi nguyên tử.
8. Xây dựng tác vụ đồng bộ nền, retry/backoff, chống chạy trùng.
9. Tích hợp nguồn ảnh cache với màn hình launcher.
10. Kiểm thử thay theme, cấu hình XML/JSON, online/offline, phân trang, thay ảnh, mất quyền, lỗi mạng và APK build release.

## 13. Tài liệu tham khảo

- Google Drive API — List files: <https://developers.google.com/workspace/drive/api/reference/rest/v3/files/list>
- Google Drive API — Search for files and folders: <https://developers.google.com/workspace/drive/api/guides/search-files>
- Google Drive API — Download and export files: <https://developers.google.com/workspace/drive/api/guides/manage-downloads>
- Google Drive API — Share files, folders, and drives: <https://developers.google.com/workspace/drive/api/guides/manage-sharing>
- Service Accounts: <https://cloud.google.com/iam/docs/service-account-overview>
- Google Auth Library for Java: <https://github.com/googleapis/google-auth-library-java>

---

**Giả định v1.2:** Mỗi theme bật Google Drive chọn **một thư mục gốc**; ảnh lấy từ `wallpaper.jpg` và bộ ảnh trong `wallpapers/`, không có đăng nhập người dùng, và APK chỉ được quản lý/cài đặt bởi chủ sở hữu. Đường dẫn/cấu trúc cụ thể của `config.xml`, các khóa theme hiện hành và cách `tools/theme.sh` triển khai phải đối chiếu với mã nguồn thật trước khi code. Nếu yêu cầu thay đổi sang phát hành công khai, cần thiết kế lại quản lý credentials, ưu tiên backend trung gian.

## Hướng dẫn dùng bản triển khai trong fork

Xem [launcher/DRIVE_IMAGES.md](../launcher/DRIVE_IMAGES.md) để build, cài theme và cấu hình folder ID.
