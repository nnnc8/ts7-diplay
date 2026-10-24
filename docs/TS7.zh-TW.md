# DiPlay TS7 版本說明

DiPlay TS7 是鼎威 TS7 車機專用的 DiPlay 版本。
DiPlay 是安卓車機上的無線／有線 CarPlay 接收程式。
這個版本以 wudi81 v21（上游 DiPlay 0.2.21）為基礎。
這個版本沒有沿用舊版 DiPlay TS7 v0.2.48 的修改，所有修改都重新設計。

- 安裝包名：`com.shihab.diplay.ts7`
- App 名稱：`DiPlay TS7`
- 版本名稱：`0.2.21-ts7-wudi81-v21`

## 安裝前要做的事

1. 移除舊的「DiPlay TS7」v0.2.48。
   舊版的 versionCode 是 68，新版是 49。Android 不允許用較小的 versionCode 覆蓋安裝。
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
| H.264 不重新排序輸出 | DiPlay 在影像參數（SPS）裡宣告「沒有重新排序的影格」。TS7 的解碼器 `OMX.sprd.h264.decoder` 因此一解完就輸出影格，不再暫存。如果 iPhone 送來會重新排序的影格（B 影格），DiPlay 在該次連線自動停用這個功能。 | 設定 → 進階媒體 →「H.264 不重新排序輸出（實驗性）」 |
| 影像直接輸出 | DiPlay 用 SurfaceView 顯示 CarPlay 畫面。影格直接交給系統合成，不經過 App 的 GPU 繪製。這樣大約少一個畫面更新週期（60 Hz 時約 16.7 ms），也減少 GPU 負擔。開啟時，畫面調整功能不能用。 | 設定 → 進階媒體 →「直接影片輸出（實驗性）」 |
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
   - 解碼器設定那一行有 `lowDelaySps=true`
   - `Video output mode=SURFACE`
   - `Microphone: start` 那一行有 `inputDevices=`
8. 如果報告裡有 `low-delay SPS dropped`，表示 iPhone 送了 B 影格。把這份報告交給開發者。

## 驗證狀態

- 已完成：單元測試、lint 和 APK 組建。驗證方式：`./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug`。
- 已完成：SPS 改寫後的影像仍可正常解碼。驗證方式：我用 macOS VideoToolbox 解碼 6 段 720p 測試影像（baseline、main、high，含 B 影格的兩段）。每段 60 個影格，改寫前後的解碼結果雜湊值相同，沒有錯誤。
- 未驗證：還沒有在 TS7 實機和 iPhone 上測試。延遲實際降低多少，要用上面的實車測試確認。
