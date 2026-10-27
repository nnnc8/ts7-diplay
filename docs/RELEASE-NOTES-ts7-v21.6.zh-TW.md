# DiPlay TS7 v21.6 prerelease

- 日期：2026-10-27（Asia/Taipei）
- 版本：`0.2.21-ts7-wudi81-v21.6`，versionCode `55`
- 套件：`com.shihab.diplay.ts7`
- 最低 Android API：`24`

本版修正送顯執行緒的看門狗與診斷欄位。**這些修正尚未在 TS7 實車驗證，不能據此確認畫面延遲或穩定度已改善。**

## 改了什麼

- 看門狗現在要同時符合兩個條件才會停用專用送顯執行緒：上次輸出後至少送進 3 張影格，且 2 秒沒有輸出。
- 這可避免解碼器暫時保留 1 到 2 張影格時，把 iPhone 暫停送畫面誤判成送顯執行緒卡住。若真的卡住，30 fps 下 2 秒會送進遠多於 3 張。
- TS7 專用送顯執行緒啟用時，如果取得 decoder 輸入 buffer 卡住，而且上次輸出後已有新影格送入 decoder，DiPlay 會記錄 `input_stall`、重建 decoder 並要求關鍵幀。沒有新輸入時，仍走原本的 recovery。
- 送顯執行緒的例外會記錄類別，例如 `exception:IllegalStateException`。過長的原因字串會截短。
- 音訊 Receive 行回到原有欄位。只有 `Receive: video` 會輸出 `readMs` 與 `decryptMs`。
- 非 TS7 車機的 timeout 檢查會在取得鎖前結束。既有畫面行為不變。
- 沒有改音訊播放程式或音訊參數，也沒有改解析度、影格率、`KEY_OPERATING_RATE`、`frameEndMarker` 或 `poll(5)`。
- 診斷報告在 TS7 上只附目前版本的 `diplay.log` 與 `previous.log`，最近兩份合計約不超過 1 MB。其他版本或沒有版本標頭的檔案只顯示未納入原因。
- 每份新 log 都會在第一行記錄 app 版本。log 超過 512 KB 輪替後，新檔也會寫入版本標頭；TS7 只輪替出 `previous.log`。
- 升級後第一份報告不會附上舊版 log 內容。報告仍會列出舊檔的版本，或標示版本不明。

## 診斷報告怎麼看

`Video: video stats` 的 `outputThread fallback=` 可能顯示以下原因：

- `no_output_2s`：至少 3 張新影格送入 decoder 後，2 秒沒有任何輸出。
- `input_stall`：decoder 沒有可用輸入 buffer，而且上次輸出後已有新影格送入。
- `exception:<類別>`：送顯執行緒發生該類別的例外。報告只記錄類別，不會附上例外訊息。

以上 fallback 會重建 decoder 並要求關鍵幀。報告出現原因表示程式走了恢復路徑，不代表已確認車機硬體故障。

### `readMs`

- `readMs=p50/max` 的最大值與原有 `readMaxMs` 量測同一段 socket 讀取時間。原本只有 `readMaxMs`；本版新增的是 p50。兩者的最大值格式不同：`readMaxMs` 是整數毫秒，`readMs` 顯示一位小數。
- 送端傳送偏慢時，`readMs` p50 可能接近幀間隔：30 fps 約 33 ms，16 fps 約 60 ms。
- 接收端來不及處理、形成 TCP 背壓時，資料可能已在系統緩衝區，`readMs` p50 會接近 0；此時可一併查看 `decryptMs` 或 `processMaxUs` 是否上升。
- 這些數字只提供判讀線索，單一欄位不能證明卡頓原因。

### `inputAcquireMs`

- `outputThread=off` 或 `outputThread=fallback(...)` 時，`inputAcquireMs` 也包含取出 decoder 輸出與送顯所花的時間。
- `outputThread=on` 時，這段時間不會在同一個輸入取得流程中排空輸出。比較 `on` 與 `off`／`fallback` 的數值時，請注意計時範圍不同。

## 尚未實車驗證

- 看門狗的 3 張門檻能否正確區分暫停送畫面與 decoder 卡住。
- `input_stall` 與例外 fallback 能否在 TS7 上恢復畫面。
- `readMs`、`decryptMs` 與 `inputAcquireMs` 能否在實車報告中協助定位卡頓來源。
- 診斷報告只保留本版最近兩份 log，能否讓 TS7 實車報告更容易判讀。
