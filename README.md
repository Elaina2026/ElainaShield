# ⚔️ ElainaShield - Java Obfuscator

```
  ███████╗██╗      █████╗ ██╗███╗   ██╗ █████╗
  ██╔════╝██║     ██╔══██╗██║████╗  ██║██╔══██╗
  █████╗  ██║     ███████║██║██╔██╗ ██║███████║
  ██╔══╝  ██║     ██╔══██║██║██║╚██╗██║██╔══██║
  ███████╗███████╗██║  ██║██║██║ ╚████║██║  ██║
  ╚══════╝╚══════╝╚═╝  ╚═╝╚═╝╚═╝  ╚═══╝╚═╝  ╚═╝
              S H I E L D   v1.0.0
```

**Công cụ làm rối mã Java bytecode nâng cao**, được thiết kế để bảo vệ ứng dụng Java khỏi việc dịch ngược (reverse engineering). Sử dụng thư viện ASM để can thiệp trực tiếp vào bytecode ở mức thấp nhất.

---

## 📋 Mục lục

- [Yêu cầu hệ thống](#-yêu-cầu-hệ-thống)
- [Build từ source](#-build-từ-source)
- [Cách sử dụng](#-cách-sử-dụng)
- [Các tùy chọn (Options)](#-các-tùy-chọn-options)
- [Ví dụ thực tế](#-ví-dụ-thực-tế)
- [Kỹ thuật Obfuscation](#-kỹ-thuật-obfuscation)
- [Cấu trúc Project](#-cấu-trúc-project)
- [Lưu ý quan trọng](#-lưu-ý-quan-trọng)

---

## 💻 Yêu cầu hệ thống

| Thành phần | Phiên bản tối thiểu |
|------------|---------------------|
| Java JDK   | 17+                 |
| Apache Maven | 3.6+              |

Kiểm tra bằng lệnh:

```bash
java -version
mvn -version
```

---

## 🔨 Build từ source

### Bước 1: Clone hoặc mở thư mục project

```bash
cd ElainaShield
```

### Bước 2: Build fat-jar bằng Maven

```bash
mvn clean package
```

### Bước 3: File JAR sẽ nằm tại

```
target/elaina-shield-1.0.0.jar
```

Đây là **fat-jar** (đã đóng gói sẵn tất cả dependencies), chạy độc lập không cần cài thêm gì.

---

## 🚀 Cách sử dụng

### Cú pháp cơ bản

```bash
java -jar elaina-shield-1.0.0.jar <input.jar> [output.jar] [options]
```

| Tham số       | Bắt buộc | Mô tả                                                |
|---------------|----------|-------------------------------------------------------|
| `input.jar`   | ✅ Có    | File JAR cần làm rối                                  |
| `output.jar`  | ❌ Không | File JAR đầu ra (mặc định: `input-obfuscated.jar`)   |
| `options`     | ❌ Không | Các tùy chọn bổ sung (xem bên dưới)                  |

### Ví dụ nhanh nhất

```bash
# Cách 1: Chỉ cần truyền file input (output tự động tạo)
java -jar elaina-shield-1.0.0.jar myapp.jar

# Cách 2: Chỉ định tên file output
java -jar elaina-shield-1.0.0.jar myapp.jar myapp-secured.jar

# Cách 3: Bật chế độ aggressive (mạnh nhất)
java -jar elaina-shield-1.0.0.jar myapp.jar myapp-secured.jar --aggressive
```

---

## ⚙️ Các tùy chọn (Options)

| Option                | Mô tả                                                                |
|-----------------------|-----------------------------------------------------------------------|
| `--no-rename`         | **Tắt** Name Mangling (không đổi tên class/method/field)             |
| `--no-flow`           | **Tắt** Control Flow Flattening (không làm phẳng luồng điều khiển)   |
| `--no-junk`           | **Tắt** Junk Code Injection (không chèn mã rác)                      |
| `--aggressive`        | Bật chế độ **mạnh nhất** (nhiều junk hơn, flatten nhiều method hơn)   |
| `--keep-main`         | **Giữ nguyên** tên main class (không đổi tên class chứa `main()`)    |
| `--unicode-invisible` | Dùng ký tự **tàng hình** (zero-width) cho tên                        |
| `--unicode-confuse`   | Dùng ký tự **Cyrillic/Greek giả Latin** cho tên                      |
| `--seed <number>`     | Đặt seed cố định để **kết quả lặp lại** được (dùng cho debug)        |

---

## 📖 Ví dụ thực tế

### 1. Obfuscate toàn bộ (mặc định - khuyên dùng)

```bash
java -jar elaina-shield-1.0.0.jar myapp.jar output.jar
```

Bật cả 3 kỹ thuật: Name Mangling + Control Flow Flattening + Junk Code.

### 2. Chế độ Aggressive (bảo vệ tối đa)

```bash
java -jar elaina-shield-1.0.0.jar myapp.jar secured.jar --aggressive
```

- Chèn gấp 3 lần số junk method/field
- Flatten cả những method nhỏ hơn
- Chèn tối đa 5 opaque predicate block thay vì 2

### 3. Chỉ dùng Name Mangling (nhẹ nhất)

```bash
java -jar elaina-shield-1.0.0.jar myapp.jar light.jar --no-flow --no-junk
```

Chỉ đổi tên, không thay đổi logic bytecode → an toàn nhất, ít rủi ro lỗi runtime.

### 4. Chỉ dùng Control Flow + Junk (giữ tên gốc)

```bash
java -jar elaina-shield-1.0.0.jar myapp.jar obf.jar --no-rename
```

Hữu ích khi ứng dụng sử dụng reflection hoặc serialization phụ thuộc vào tên class.

### 5. Giữ tên main class (cho CLI app)

```bash
java -jar elaina-shield-1.0.0.jar myapp.jar secured.jar --keep-main
```

Main class giữ nguyên tên để manifest không bị lỗi khi chạy `java -jar`.

### 6. Dùng ký tự tàng hình (khó đọc nhất)

```bash
java -jar elaina-shield-1.0.0.jar myapp.jar ghost.jar --unicode-invisible
```

Tên class/method sẽ gần như **vô hình** khi mở bằng decompiler.

### 7. Build lại kết quả giống nhau (debug)

```bash
java -jar elaina-shield-1.0.0.jar myapp.jar test.jar --seed 12345
```

Cùng seed → cùng kết quả. Hữu ích khi debug hoặc so sánh.

---

## 🛡️ Kỹ thuật Obfuscation

### 1. Name Mangling (Làm rối tên) 🏷️

**Mục đích:** Đổi tên toàn bộ class, method, field thành ký tự vô nghĩa.

**Cách hoạt động:**
- Sử dụng ký tự **Zero-Width Unicode** (tàng hình, không hiển thị) → tên biến trông như trống rỗng
- Sử dụng ký tự **Cyrillic/Greek giả Latin** → `а` (Cyrillic) trông giống `a` (Latin) nhưng khác mã
- Kết hợp Cherokee, Greek, Latin Extended → không tool nào tự động đoán được

**Trước:**
```java
public class UserService {
    private String userName;
    public void validateUser() { ... }
}
```

**Sau:**
```java
public class ɐ\u200B\u200Cɒ {
    private String αβγδ;
    public void ꭰⱡɽ() { ... }
}
```

**Bảo vệ:**
- Không đổi tên: `<init>`, `<clinit>`, `main`, `toString`, `hashCode`, `equals`
- Không đổi tên: native methods, enum methods, interface override methods
- Không đổi tên: `serialVersionUID`

---

### 2. Control Flow Flattening (Làm phẳng luồng điều khiển) 🔀

**Mục đích:** Phá vỡ cấu trúc logic của method, biến mọi thứ thành switch-case khổng lồ.

**Cách hoạt động:**
1. Chia method thành các **basic block** (khối lệnh cơ bản)
2. Gán cho mỗi block một **state ID ngẫu nhiên** (số 32-bit)
3. **Xáo trộn** thứ tự các block
4. Bao bọc trong vòng `while(true) { switch(state) { ... } }`
5. Chuyển đổi state bằng **phép toán XOR** bị che giấu: `state = (A ^ B) + C`

**Trước:**
```java
void process(int x) {
    if (x > 0) {
        doA();
    } else {
        doB();
    }
    doC();
}
```

**Sau:**
```java
void process(int x) {
    int state = 0x7A3F2B1C;
    while (true) {
        switch (state) {
            case 0xD4E5F6A7: doB(); state = (0x1234 ^ 0x5678) + 0x9ABC; break;
            case 0xF6A78B9C: doC(); state = (0xAAAA ^ 0xBBBB) + 0xCCCC; break;
            case 0x7A3F2B1C: if(x>0) state=0xB1C2D3E4; else state=0xD4E5F6A7; break;
            case 0xB1C2D3E4: doA(); state = (0xDEAD ^ 0xBEEF) + 0x1337; break;
            case 0x00000000: return;
            default: return;
        }
    }
}
```

---

### 3. Junk Code Injection (Chèn mã rác) 🗑️

**Mục đích:** Làm phình to bytecode, đánh lừa decompiler.

**4 loại junk được chèn:**

| Loại | Mô tả |
|------|--------|
| **Junk Methods** | Method giả với logic phức tạp (toán học, vòng lặp, xử lý chuỗi, mảng, bit) |
| **Junk Fields** | Field static với giá trị rác (int, long, String, double, boolean) |
| **Opaque Predicates** | Điều kiện luôn false nhưng trông phức tạp: `if ((0 & x) != 0)` |
| **Inline Dead Code** | Đoạn code chết sau opaque predicate, không bao giờ thực thi |

**6 template junk method:**
1. 🔢 Math operations (XOR, shift, multiply)
2. 🔄 Loop computations (for loop với hash tính toán)
3. 📝 String manipulation (StringBuilder)
4. 📦 Array manipulation (tạo mảng, fill, XOR swap)
5. 🔧 Bit manipulation (shift, XOR, multiply hằng số)
6. 🌳 Nested conditions (if/else if/else nhiều tầng)

---

## 📁 Cấu trúc Project

```
ElainaShield/
├── pom.xml                          # Maven config + shade plugin
├── README.md                        # File này
└── src/main/java/com/elainashield/obfuscator/
    ├── ElainaShield.java            # Main entry point (CLI)
    ├── core/
    │   ├── ObfuscationConfig.java   # Cấu hình (toggle on/off, style, seed)
    │   ├── ObfuscationContext.java  # Context chung (mapping tên, thống kê)
    │   └── JarProcessor.java       # Pipeline xử lý JAR (đọc → transform → ghi)
    ├── transformers/
    │   ├── NameManglingTransformer.java    # [1] Đổi tên Unicode
    │   ├── ControlFlowTransformer.java    # [2] Làm phẳng luồng điều khiển
    │   └── JunkCodeTransformer.java       # [3] Chèn mã rác
    └── utils/
        └── NameGenerator.java       # Sinh tên Unicode (invisible/confusing)
```

### Pipeline xử lý

```
Input JAR
    │
    ▼
┌──────────────────┐
│  1. Đọc JAR      │  Parse .class → ClassNode (ASM Tree API)
│     + Manifest    │  Lưu resources (non-class files)
└──────────────────┘
    │
    ▼
┌──────────────────┐
│  2. Junk Code    │  Chèn method/field/inline dead code
│     Injection    │  (Trước khi rename để junk cũng bị rename)
└──────────────────┘
    │
    ▼
┌──────────────────┐
│  3. Control Flow │  Split basic blocks → Shuffle → Switch dispatcher
│     Flattening   │  XOR-obfuscated state transitions
└──────────────────┘
    │
    ▼
┌──────────────────┐
│  4. Name         │  Phase 1: Build renaming map
│     Mangling     │  Phase 2: Apply via ClassRemapper
└──────────────────┘
    │
    ▼
┌──────────────────┐
│  5. Ghi JAR      │  ClassWriter (COMPUTE_FRAMES)
│     + Manifest   │  Cập nhật Main-Class trong MANIFEST.MF
└──────────────────┘
    │
    ▼
Output JAR (obfuscated)
```

---

## ⚠️ Lưu ý quan trọng

### Nên làm ✅

- **Luôn test** file JAR đã obfuscate trước khi deploy
- **Backup** file JAR gốc trước khi obfuscate
- Bắt đầu với `--no-flow` nếu app phức tạp, rồi bật dần từng kỹ thuật
- Dùng `--keep-main` cho ứng dụng CLI cần giữ entry point
- Dùng `--seed` khi debug để có kết quả lặp lại

### Không nên ❌

- **Không obfuscate** các JAR thư viện mà ứng dụng khác depend vào (sẽ break API)
- **Không dùng** với app dùng **reflection nặng** mà không có `--no-rename`
- **Không dùng** với **Spring Boot** fat-jar (obfuscate trước khi đóng gói Spring)
- **Không obfuscate** lại file đã obfuscate (kết quả không đoán trước được)

### Các trường hợp đặc biệt

| Trường hợp | Giải pháp |
|------------|-----------|
| App dùng reflection | Dùng `--no-rename` hoặc `--keep-main` |
| App dùng serialization | Dùng `--no-rename` |
| JAR có try-catch phức tạp | Control Flow sẽ tự động skip method đó |
| JAR quá lớn (>50MB) | Tăng heap: `java -Xmx1g -jar elaina-shield-1.0.0.jar ...` |
| Method >300 instructions | Control Flow sẽ tự động skip (tránh exceed bytecode limit) |

---

## 📊 Output mẫu

Khi chạy thành công, bạn sẽ thấy output tương tự:

```
  ╔══════════════════════════════════════════════╗
  ║  [✓] Obfuscation completed successfully!    ║
  ║  [*] Time elapsed: 1523ms                   ║
  ║  [*] Output: myapp-obfuscated.jar           ║
  ╚══════════════════════════════════════════════╝

  ┌────────────────────────────────────────────┐
  │           Obfuscation Statistics           │
  ├────────────────────────────────────────────┤
  │  Classes renamed:        42                │
  │  Methods renamed:        187               │
  │  Fields renamed:         93                │
  │  Methods flattened:      28                │
  │  Junk methods injected:  126               │
  │  Junk fields injected:   84                │
  └────────────────────────────────────────────┘
  [*] Input size:  245,312 bytes
  [*] Output size: 412,876 bytes (1.7x)
```

---

## 📜 License

Dự án này chỉ dùng cho mục đích học tập và bảo vệ phần mềm hợp pháp. Không sử dụng để che giấu mã độc.
"# ElainaShield" 
