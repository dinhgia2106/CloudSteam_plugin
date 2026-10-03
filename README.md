# Vietnamese CloudStream providers

Hai extension CloudStream độc lập:

- **NguonC** — phim và chương trình từ API công khai của `phim.nguonc.com`.
- **VSMov** — phim và chương trình từ API công khai của `vsmov.com`.

## Build

Yêu cầu JDK 17 và Android SDK:

```powershell
.\gradlew.bat make
```

File cài đặt `.cs3` nằm trong thư mục `build` của từng module. Tạo manifest repository bằng:

```powershell
.\gradlew.bat makePluginsJson
```

## Cài thủ công

Build module cần dùng rồi chép file `.cs3` sang thiết bị và mở bằng CloudStream.

Các provider chỉ đọc API công khai và không chứa dữ liệu phim trong repository. Người dùng chịu trách nhiệm tuân thủ điều khoản của nguồn và pháp luật tại nơi sử dụng.
