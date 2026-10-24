# DiPlay TS7 版本說明

DiPlay TS7 是鼎威 TS7 車機專用的 DiPlay 版本。
DiPlay 是安卓車機上的無線／有線 CarPlay 接收程式。
這個版本以 wudi81 v21（上游 DiPlay 0.2.21）為基礎。
這個版本沒有沿用舊版 DiPlay TS7 v0.2.48 的修改，所有修改都重新設計。

- 安裝包名：`com.shihab.diplay.ts7`
- App 名稱：`DiPlay TS7`
- 版本名稱：`0.2.21-ts7-wudi81-v21.1`

## v21.1 修改（2026-10-25）

本版依第一份 TS7 實車報告修正。以下改動尚未在 TS7 實車確認。

- 影像：新增「H.264 畫面結束標記（實驗性）」。TS7 未儲存設定時，預設附加在畫面後。可改成獨立送出或關閉。設定從下次連線生效。解碼器失敗或恢復時，該解碼器會停用標記。
- 幀率：TS7 未儲存幀率時，預設改為 60 fps。既有的 30 fps 設定會保留。
- 解密：平台加密元件不可用時，先嘗試內建原生 ChaCha20-Poly1305。原生元件通過自測才啟用；不可用時仍用 BouncyCastle。
- 報告：新增 SPS 解析結果、`touch2shown`、解密實作、工作階段結束原因、麥克風傳送錯誤與首次讀取時間。

從 v21（versionCode 49）可直接覆蓋安裝本版（50），保留設定。若要比較 60 fps，請自行把幀率設成 60 fps。

## 安裝前要做的事

1. 移除舊的「DiPlay TS7」v0.2.48。
   舊版的 versionCode 是 68，本版是 50。Android 不允許用較小的 versionCode 覆蓋安裝。
   移除舊版也會清掉舊設定，TS7 預設值才會生效。
2. 移除或停用其他 DiPlay 版本，例如 `com.shihab.diplay` 和 `com.shihab.diplay.wudi81v18`。
   如果兩個 DiPlay 同時執行，兩個 App 會搶車機熱點和藍牙。
3. 安裝 APK。
4. 打開 DiPlay TS7，允許「修改系統設定」。
   DiPlay 需要這個權限才能自動開啟車機熱點。
5. 打開車機的熱點設定，把頻段設為 2.4 GHz。
   TS7 的 Wi-Fi 只支援 2.4 GHz。熱點設成 5 GHz 時無法開啟。

注意：沒有內建認證素材的 APK 不能獨立啟動 CarPlay。
如果要獨立使用，用 `assembleStandaloneDebug` 和外部認證素材組建。步驟見 [BUILD.zh-TW.md](BUILD.zh-TW.md)。

## TS7 專屬的修改

DiPlay 用以下條件判斷車機是不是 TS7：`Build.HARDWARE`、`Build.BOARD` 或 `Build.DEVICE` 含有 `sp7731e`。TS7 的這三個欄位都是 `sp7731e_1h10`。DiPlay 不只看製造商 `sprd`，因為其他 Unisoc 車機也用這個製造商名稱。
下表的預設值只在 TS7 上生效。如果使用者已經儲存過設定，DiPlay 使用使用者的設定。

| 項目 | 修改內容 | 在哪裡關閉 |
|---|---|---|
| H.264 不重新排序輸出 | DiPlay 在影像參數（SPS）裡宣告「沒有重新排序的影格」。此宣告用來嘗試減少解碼器暫存；第一份 TS7 報告仍出現約一格延遲，效果須實車確認。如果 iPhone 送來會重新排序的影格（B 影格），DiPlay 在該次連線自動停用這個功能。 | 設定 → 進階媒體 →「H.264 不重新排序輸出（實驗性）」 |
| 影像直接輸出 | DiPlay 用 SurfaceView 顯示 CarPlay 畫面。影格直接交給系統合成，不經過 App 的 GPU 繪製。理論上可省去一個畫面更新週期（60 Hz 時約 16.7 ms）；TS7 實際差異尚未量測。開啟時，畫面調整功能不能用。 | 設定 → 進階媒體 →「直接影片輸出（實驗性）」 |
| 無線音樂緩衝 | 無線連線的音樂緩衝預設 500 ms。有線連線不受影響。 | 設定 →「音樂快取（無線）」 |
| Wi-Fi Direct | Android 10 以下無法使用 Wi-Fi Direct 連線，所以設定頁不顯示這個選項。舊設定或備份裡的 Wi-Fi Direct 在 TS7 上改成「車機熱點」。 | 無 |
| 自動開啟車機熱點 | 預設開啟。開機時，如果「開機啟動」已開啟，DiPlay 在背景開啟車機熱點，最多等 8 秒。 | 設定 → 車輛熱點 →「自動開啟熱點」 |
| 熱點啟動等待時間 | 熱點顯示「開啟中」時，DiPlay 最多等到 30 秒。熱點開好以後，DiPlay 至少再等 5 秒讓網路介面就緒。 | 無 |

## 所有車機都適用的修改

- 熱點啟動：DiPlay 讀取熱點的實際狀態。熱點正在開啟時，DiPlay 不重複送出開啟要求。熱點失敗時，DiPlay 最多重送 2 次。
- 熱點頻段錯誤：如果車機熱點因為頻段無法開啟，DiPlay 顯示「已儲存的熱點頻段無法使用。請在車機設定中將熱點設為 2.4 GHz。」DiPlay 不再一直重試。
- 麥克風：Android 10 以下沒有系統 Opus 編碼器。DiPlay 在這些版本直接使用內建編碼器，不再掃描系統編碼器。
- 診斷報告：「Microphone: start」那一行多了輸入裝置清單。「Audio: ready」那一行多了系統輸出取樣率、每個緩衝的影格數和 AAC 解碼器名稱。
- 回音消除和語音濾波器：DiPlay 每個音訊樣本不再建立新物件。輸出結果和舊版逐位元相同，單元測試有比對。

## 實車測試清單

1. 冷開機車機。確認車機熱點自動開啟。
2. 讓 iPhone 連上 CarPlay。確認畫面出現。
3. 在 CarPlay 地圖上拖動畫面。觀察畫面跟手的程度。
4. 打開「設定 → 進階媒體」，關閉「H.264 不重新排序輸出」。重新連線，用同樣方式拖動地圖，比較兩者。
5. 播放音樂 10 分鐘。確認沒有斷音。
6. 打一通電話。確認對方聽得到，而且使用車機麥克風。
7. 產生診斷報告。確認報告裡有下列內容：
   - `Low-delay H.264 SPS preference: enabled`
   - 解碼器設定行有 `lowDelaySps=true|already|unparsed`，並查看 `H.264 SPS summary:` 的 `result` 與 `reason`
   - 標記啟用時，解碼器設定行有 `frameEndMarker=append|separate`
   - `Video: video stats` 的 `decode p50` 與 `touch2shown`：用同樣拖動方式比較標記開啟及關閉
   - `Receive: video` 的 `decryptImpl=native`、`decryptAvgUs` 與 `decryptMBps`：確認原生路徑是否生效
   - 手動斷線或設定重連後，有 `Session end requested reason=... by=...` 與 `teardown begin ... reason=...`
   - `Activity stopped finishing=...` 與 `Activity destroyed finishing=... changingConfigurations=...`
   - 使用 Siri／通話後，查看 `Microphone: stats` 的 `firstReadMs`；傳送失敗時還有 `lastSendError` 與 `afterStop`
   - `Video output mode=SURFACE`
   - `Microphone: start` 那一行有 `inputDevices=`
8. 如果報告裡有 `low-delay SPS dropped`，表示 iPhone 送了 B 影格。把這份報告交給開發者。

9. 在「設定 → 進階 → 進階媒體」切換畫面結束標記，重新連線後比較 `decode p50`。若出現 `H.264 frame end marker dropped:`，請保留原因。
10. 通話掛斷後再播放音樂並啟用 Siri。確認聲音及車機麥克風恢復。

## 驗證狀態

- 已完成：單元測試、lint 和 APK 組建。驗證方式：`./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug`。
- 已完成：SPS 改寫後的影像仍可正常解碼。驗證方式：我用 macOS VideoToolbox 解碼 6 段 720p 測試影像（baseline、main、high，含 B 影格的兩段）。每段 60 個影格，改寫前後的解碼結果雜湊值相同，沒有錯誤。
- 未驗證：v21.1 的實車延遲、原生解密效能、通話與斷線行為。請依實車測試清單確認。
