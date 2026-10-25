# DiPlay TS7 v21.3

日期：2026-10-25（Asia/Taipei）。
版本：`0.2.21-ts7-wudi81-v21.3`，versionCode `52`。
套件：`com.shihab.diplay.ts7`，最低 Android API `24`。

音訊改善尚未在 TS7 實車驗證。這次使用 v21.2 診斷報告的音訊到達統計做了合成重播；報告只有五秒彙總，不能還原完整逐包時序，模擬不等於實車結果。

## 改動

- 缺 1–3 個 AAC 音訊封包時，使用前一段 PCM 重複補上相同長度，後續封包逐步降低音量，並淡化補音兩端。若串流剛開始、還沒有可用 PCM，則改補靜音。
- 缺 4 個以上封包時仍用靜音維持時間軸，並為缺口前後加入淡出與淡入。
- 等候最多 3 個 AAC 封包處理輕微亂序。連續 8 個封包都晚到且序號連續時，視為序號重新開始並重新同步。
- TS7 音樂的總佇列上限 `L` 依自適應水位計算：`L = clamp(targetMs + 1200, 2100, 2400)` ms。起播與重新緩衝會先蓄到 L；播放中只有連續 10 秒超過 L+800 ms，才逐包丟棄舊資料追上即時播放。
- TS7 音樂每次 AudioTrack blocking write 限為約 40 ms，讓停止、清空等控制事件能更快處理。
- 成功送出方向盤下一首或上一首後，清除本機舊歌音訊佇列並重設起播狀態；播放／暫停不清空。
- 音訊診斷與選單開啟時的報告寫檔移到單一背景記錄器。
- 明確暫停或串流結束後，300 ms 沒有新封包且至少還有 100 ms PCM 時，播放尾段後結束。
- 診斷新增 `shortGapsConcealed`、`longGapsConcealed`、`repeatFrames`、`droppedForLatency` 與 `totalQueuedMs`。

非 TS7 音訊維持既有寫入分段與緩衝行為。影像程式、幀率預設與封包重傳沒有變更。

## v21.2 報告與合成模擬

- v21.2 這趟音樂掉包率為 19.70 包／千包；v21.1 那趟為約 10.33 包／千包。兩次接收條件不同，不能視為同一路線、同條件比較。
- v21.2 報告記錄 40 個缺口、共 353,280 個靜音 frames（7.36 秒）。依缺口大小估算，v21.3 會對 19 個短缺口重複補音約 32,768 frames（0.683 秒），其餘長缺口保留約 320,512 frames（6.677 秒）靜音。
- 同一模型與 20 組種子比較兩版：v21.2 重新緩衝中位數／最大值為 2／2 次；v21.3 為 1／1 次，播放中 `droppedForLatency` 中位數為 0。相對比較符合本次驗收條件。
- 模型估計的總佇列 p50 跨組中位數為 v21.2 2,094.875 ms、v21.3 2,422.895 ms。v21.2 實車報告記錄 0 次重新緩衝，模型估計 2 次；因此 p50 只列作參考，不作為本次驗收門檻。
- 程式的佇列限額 L 從 2,100 ms 起，最高 2,400 ms。單元測試確認起播與重新緩衝後的總佇列不超過 L、L 不超過 2,400 ms，正常播放水位不觸發延遲丟包。
- 模型依五秒彙總資料合成封包時序，並用流體 FIFO 估算總佇列，沒有分開計算 PCM 與壓縮佇列，也沒有完整重現 Android AudioTrack、MediaCodec 與實際起播狀態。模擬不代表實車結果。

## 安裝與實車測試

1. 若安裝舊版 DiPlay TS7 v0.2.48，須先移除舊版；其 versionCode 68 高於本版 52，Android 不允許降版覆蓋。
2. 安裝本版並使用原有設定。
3. 使用平常的幀率、同一路線播放音樂至少 10 分鐘。
4. 試一次方向盤「下一首」，確認新歌能開始播放。
5. 儲存並回傳診斷報告。這次測試不需更動車機藍牙或熱點設定。

## 診斷欄位

- `audio stats`：`rebuffers`、`underruns`、`maxWriteMs`、`queue`、`estimatedQueuedFrames`。
- `Audio: buffer stats`：`missingPackets`、`silenceFrames`、`shortGapsConcealed`、`longGapsConcealed`、`repeatFrames`、`droppedForLatency`、`totalQueuedMs`、`targetMs`。
- 切歌時的 `Audio: TS7 music queue cleared`：確認方向盤下一首是否清除舊佇列，並查看丟棄封包數。

## 模擬限制

到達資料來自五秒統計視窗，不是封包擷取。模型合成視窗內封包時間，並以流體 FIFO 近似 PCM／壓縮佇列；不含真實 Android AudioTrack、MediaCodec、blocking write、時鐘、排程與實際起播水位變化。v21.2 的約 2.56 秒總延遲是 AudioTrack 排隊中位數加壓縮佇列估算，不是端到端直接量測。請以實車報告驗證。
