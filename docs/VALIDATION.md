# 3.2.2 驗證紀錄

驗證日期：2026-11-03。

## Android 建置

- JDK 17、Android SDK 35、Build Tools 35.0.0、Gradle 8.11.1。
- `assembleDebug lintDebug`：**BUILD SUCCESSFUL**。
- Lint：**0 errors、3 warnings**。剩餘提示為固定版 AndroidX 可升級，以及 API 33 返回屬性在舊版系統會忽略；返回處理有版本判斷。
- APK 通過 `apksigner verify`。版本 3.2.2、versionCode 30202、minSdk 26、targetSdk 35。
- 簽章與此對話先前的 3.2.1 測試 APK 相同。可直接覆蓋該 APK 更新，不需解除安裝。

## 49 項瀏覽器流程回歸

使用隔離的 Chromium、412 × 892 觸控模式與 mock `OCNative`。指令：

```sh
python tests/web_regression.py --chromium /usr/bin/chromium
```

涵蓋啟動、五個導覽入口、畫面品質切換與保存、App 暫停／恢復、面板暫停背景、快捷面板返回、相機不隨跳躍上下晃動、拍照橋接與隱藏介面、PNG 角色匯入、備份下載與分享、商店搜尋／空狀態／購買與庫存、房間和角色編輯、地形碰撞等價性、主題與圖片保存，以及完整備份還原個性、技能、角色引用 ID、擺設與畫面偏好。

另檢查 320 × 720、412 × 892 與 892 × 412 的導覽邊界／橫向溢位。完整紀錄見 `web-regression-results.json`。**全部通過，無 JavaScript 執行例外。**

夜間另確認星空與螢火蟲的顯示狀態會隨時間更新，星空不再卡在白天的隱藏狀態。

## 效能量測

Linux Chromium 154.0.8037.57、412 × 892、DPR 1、觸控模式。自動行為與天氣會影響結果；以下是單次取樣，非 Android 手機實測。

| 條件 | 平均每幀 | 約當 FPS | 95 百分位幀時間 |
| --- | ---: | ---: | ---: |
| 原版，4 倍 CPU 降速，10 秒 | 123.29 ms | 8.1 | 166.7 ms |
| 3.2.2 最終整合，4 倍 CPU 降速，10 秒 | 24.01 ms | 41.6 | 50.0 ms |
| 3.2.2 最終整合，不降速，5 秒 | 16.67 ms | 60.0 | 16.8 ms |

持續繪製中的首頁元素由約 4,200 個減至約 1,350 個；啟動量測中的持續動畫由三百多個減至約 6–10 個。保留使用者素材與可互動物件，靜態背景以合併圖片顯示。

開發過程亦量測夜間、雷雨、雪天與八位居民。4 倍降速下平均約 20–35 ms／幀，重負載仍可能掉幀；這些情境取樣在最後的鏡頭／量測修正之前，僅作壓力測試紀錄。最終 FPS 不應由該表推估所有機型。

原始數據見 `performance-results.json`。可用以下指令重新量測當前版本：

```sh
python tests/performance_audit.py --chromium /usr/bin/chromium --throttle 4 --seconds 10
```

## 真機驗證限制

目前沒有 Android 真機或模擬器測試。瀏覽器檢查可驗證 HTML、操作與資料流程，不能驗證 Android 的系統選檔、分享接收、PixelCopy 實際影像、定位授權、鍵盤／導覽手勢與各機型 GPU 表現。APK 已編譯並驗證簽章，以上裝置行為仍需在手機確認。

`android-preview.png` 等圖片是手機尺寸的瀏覽器截圖，不是真機截圖。GitHub Actions 的建置與回歸工作流程已提供，尚未在使用者的 repository 執行。
