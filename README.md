<div align="center">

# 🛡️ ElainaShield Obfuscator

*Advanced Java Bytecode Protection Engine*

[![Java 17+](https://img.shields.io/badge/Java-17+-blue.svg)](https://adoptium.net/)
[![ASM 9.7](https://img.shields.io/badge/ASM-9.7.1-orange.svg)](https://asm.ow2.io/)
[![Build Status](https://img.shields.io/badge/Build-Passing-brightgreen.svg)]()

</div>

**ElainaShield** là một công cụ Obfuscate Java Bytecode cực mạnh được xây dựng dựa trên thư viện ASM. Công cụ này được thiết kế để bảo vệ các tệp `.jar` (đặc biệt là các Plugin Minecraft như Bukkit/BungeeCord) khỏi việc bị dịch ngược (decompile), phân tích tĩnh, hoặc đánh cắp mã nguồn.

---

## ✨ Tính Năng Nổi Bật (Features)

ElainaShield tích hợp sẵn hàng loạt các kĩ thuật làm rối mã nguồn tối tân nhất:

1. 🎭 **Name Mangling (Đổi Tên Chống Dịch Ngược)**
   - Xóa bỏ mọi ý nghĩa của tên Class, Method, Field.
   - Hỗ trợ nhiều kiểu đổi tên: `MIXED` (Ký tự lẫn lộn), `INVISIBLE` (Ký tự tàng hình/Unicode trống), `EXTREME` (Dài và không thể đọc).
   - Tự động nhận diện và **không đụng chạm** tới các hàm gốc của JDK, các class chính của Bukkit/BungeeCord, hoặc các hàm Event Handler để đảm bảo Plugin vẫn hoạt động bình thường 100%.
   - **Xử lý Kế thừa (Inheritance):** Đồng bộ hóa hoàn hảo tên hàm/field giữa Class cha và các Class con.

2. 🌀 **Control Flow Flattening (Làm Phẳng Luồng Điều Khiển)**
   - Phá vỡ cấu trúc logic `if/else`, `for/while` gốc của lập trình viên.
   - Gói toàn bộ logic vào các khối lệnh `switch-case` khổng lồ nằm trong vòng lặp vô tận, khiến các công cụ Decompiler như CFR, Fernflower, Procyon bị "treo" hoặc trả về kết quả rối rắm không thể đọc hiểu.

3. 🗑️ **Junk Code Injection (Bơm Mã Rác Aggressive)**
   - Thêm hàng ngàn Class, Method, và Field giả mạo không bao giờ được gọi.
   - Bơm các chỉ thị Bytecode vô nghĩa vào giữa các khối lệnh thật (Inline Junk).
   - Chế độ `--aggressive` cho phép thổi phồng dung lượng file lên gấp **4x đến 7x lần**, vắt kiệt tài nguyên của các công cụ dịch ngược và đánh lạc hướng các nhà phân tích.

4. 🔒 **String & Number Encryption (Mã Hoá Chuỗi & Số)**
   - Các chuỗi văn bản (String literals) bị ẩn giấu bằng phép toán XOR và Base64, đồng thời tự động Cache kết quả để không làm giảm hiệu năng (Lag) khi chạy.
   - Các hằng số (Integers, Longs, Floats) được thay thế bằng những phép tính Bitwise/Toán học phức tạp.

5. ⚡ **InvokeDynamic (Ẩn Lời Gọi Hàm)**
   - Xóa bỏ các lệnh gọi hàm truyền thống (`INVOKEVIRTUAL`, `INVOKESTATIC`, v.v.).
   - Ủy quyền toàn bộ việc gọi hàm và truy xuất Field cho Bootstrap Method tại thời điểm Runtime thông qua `invokedynamic` (Java 7+), làm mù hoàn toàn các công cụ phân tích tĩnh.

6. 📦 **Safe Outlining (Trích Xuất Mã)**
   - Chẻ nhỏ các đoạn code dài thành nhiều hàm con rải rác khắp nơi, làm mất ngữ cảnh logic ban đầu.

---

## 🚀 Yêu Cầu Hệ Thống (Requirements)

- **Môi trường chạy Tool (ElainaShield):** Yêu cầu cài đặt **Java 17** trở lên.
- **Môi trường chạy Tệp sau khi Obfuscate:** Yêu cầu tối thiểu **Java 8** (do sử dụng InvokeDynamic và Base64).

---

## 💻 Hướng Dẫn Sử Dụng (Usage)

Cú pháp lệnh cơ bản:

```bash
java -jar elaina-shield-1.0.0.jar <input.jar> <output.jar> [options]
```

### Các Tham Số (Options)

| Tham Số | Mô tả |
| :--- | :--- |
| `--keep-main` | Bỏ qua việc đổi tên class chính (Main Class) được khai báo trong `MANIFEST.MF`. Rất hữu ích cho các file JAR chạy trực tiếp. |
| `--aggressive` | Kích hoạt chế độ **Siêu Rác (Super Junk)**. Bơm cực nhiều mã rác và làm phình to dung lượng file gấp nhiều lần. Khuyến nghị cho ai muốn giấu code triệt để. |
| `--libraries <path>` | Chỉ định đường dẫn tới thư mục chứa các tệp `.jar` thư viện (Dependencies). Rất quan trọng để tool có thể nhận diện tính kế thừa chính xác. |
| `--name-style <style>`| Định dạng chữ dùng để đổi tên. Hỗ trợ: `MIXED`, `INVISIBLE`, `EXTREME`, `CHINESE`. Mặc định là `MIXED`. |
| `--disable-name` | Tắt chức năng Name Mangling. |
| `--disable-flow` | Tắt chức năng Control Flow Flattening. |
| `--disable-junk` | Tắt chức năng Junk Code Injection. |
| `--disable-string` | Tắt chức năng Mã hoá Chuỗi (String Encryption). |
| `--disable-indy` | Tắt chức năng Ẩn lời gọi hàm (InvokeDynamic). |

### Ví dụ (Examples)

**1. Bảo vệ một Plugin Minecraft cơ bản:**
```bash
java -jar elaina-shield.jar MyPlugin.jar MyPlugin-Protected.jar --keep-main
```

**2. Kích hoạt mức độ bảo vệ Cao Nhất (Phình to file):**
```bash
java -jar elaina-shield.jar App.jar App-Obf.jar --aggressive --keep-main
```

**3. Obfuscate với thư viện kèm theo (Đề xuất để không lỗi NoSuchMethodError):**
```bash
java -jar elaina-shield.jar NexOrder.jar NexOrder-Shield.jar --aggressive --libraries ./target/libs
```

---

## ⚠️ Lưu ý Quan Trọng

- **Tính Tương Thích:** Do tính năng `InvokeDynamic` và `StringEncryption` (dùng `java.util.Base64`), mã nguồn sau khi Obfuscate yêu cầu môi trường chạy tối thiểu là **Java 8**. Công cụ Obfuscator yêu cầu **Java 17** để thực thi.
- **Hiệu Năng:** Chế độ Control Flow Flattening và InvokeDynamic có thể làm giảm hiệu năng thực thi từ `2%` đến `5%`. Nếu ứng dụng của bạn yêu cầu xử lý Real-time cực kỳ khắt khe, hãy cân nhắc tắt Flow Flattening.
- **Thư Viện Phụ Thuộc (Dependencies):** ElainaShield sẽ tự động bỏ qua (không đổi tên) các package phổ biến như `org.slf4j`, `com.google.gson`, v.v... Tuy nhiên, nếu Plugin của bạn kế thừa các class từ một thư viện thứ 3 dị biệt, hãy chắc chắn truyền thư mục thư viện đó qua tham số `--libraries` để ElainaShield không vô tình đổi tên các Override Method.

---

<div align="center">
<i>Được phát triển để bảo vệ chất xám của Lập trình viên Java! 🛡️</i>
</div>
