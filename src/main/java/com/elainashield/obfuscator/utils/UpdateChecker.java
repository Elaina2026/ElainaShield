package com.elainashield.obfuscator.utils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateChecker {

    // Đây là URL của trang web Landing Page của bạn.
    // Nếu bạn đưa web lên hosting/VPS thật, hãy đổi localhost thành tên miền của bạn.
    // Ví dụ: https://elainashield.com/api/latest-version
    private static final String API_URL = "https://update.elaina2026.io.vn/api/latest-version";

    /**
     * Khởi chạy việc kiểm tra cập nhật (chạy đồng bộ để in lên đầu dòng)
     * Timeout siêu ngắn (1.5s) để không làm đơ tool nếu đứt cáp mạng.
     * @param currentVersion Phiên bản hiện tại của file .jar
     */
    public static void checkSync(String currentVersion) {
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500); // 1.5 giây
            conn.setReadTimeout(1500);
            conn.setRequestProperty("User-Agent", "ElainaShield-Client");

            if (conn.getResponseCode() == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                // Parse JSON thủ công siêu nhẹ (để không cần cài thêm thư viện GSON)
                // Chuỗi JSON có dạng: {"version":"v1.2.0","url":"https://..."}
                String json = response.toString();
                String latestVersion = extractJsonValue(json, "version");
                if (latestVersion != null && isNewerVersion(currentVersion, latestVersion)) {
                    System.out.println("\n  \u001B[33m[!] THONG BAO CAP NHAT \u001B[0m");
                    System.out.println("  \u001B[33mDa co phien ban moi: " + latestVersion + "\u001B[0m (Ban dang dung " + currentVersion + ")");
                    System.out.println("  \u001B[33mTai xuong ban cap nhat tai: https://update.elaina2026.io.vn \u001B[0m\n");
                }
            }
        } catch (Exception ignored) {
            // Im lặng nếu mạng lỗi hoặc server tắt để không ảnh hưởng người dùng
        }
    }

    /**
     * Hàm trích xuất giá trị String từ một chuỗi JSON phẳng đơn giản.
     */
    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return null;
        startIndex += searchKey.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) return null;
        return json.substring(startIndex, endIndex);
    }

    /**
     * So sánh 2 chuỗi phiên bản (Loại bỏ chữ 'v' nếu có).
     */
    private static boolean isNewerVersion(String current, String latest) {
        String c = current.replace("v", "").trim();
        String l = latest.replace("v", "").trim();
        
        // Cố gắng cứu vãn lỗi đánh máy phiên bản (VD: "113" -> "1.1.3")
        if (!l.contains(".") && l.length() >= 3) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < l.length(); i++) {
                sb.append(l.charAt(i));
                if (i < l.length() - 1) sb.append(".");
            }
            l = sb.toString();
        }
        
        if (c.equals(l)) return false;

        try {
            // Tách các phần tử của phiên bản (vd: 1.1.4 -> [1, 1, 4])
            String[] cParts = c.split("\\.");
            String[] lParts = l.split("\\.");
            
            // Nếu format không có dấu chấm (vd: v113), so sánh số nguyên trực tiếp
            if (cParts.length == 1 && lParts.length == 1) {
                return Integer.parseInt(lParts[0]) > Integer.parseInt(cParts[0]);
            }

            int length = Math.max(cParts.length, lParts.length);
            for (int i = 0; i < length; i++) {
                int cVal = i < cParts.length ? Integer.parseInt(cParts[i]) : 0;
                int lVal = i < lParts.length ? Integer.parseInt(lParts[i]) : 0;
                if (lVal > cVal) return true;
                if (lVal < cVal) return false;
            }
            return false;
        } catch (Exception e) {
            // Nếu parse lỗi (VD: chứa chữ cái lạ), tạm dùng cách cũ: khác nhau là báo update
            return false; // Tránh báo cập nhật láo nếu không rõ
        }
    }
}
