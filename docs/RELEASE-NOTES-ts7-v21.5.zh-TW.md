# DiPlay TS7 v21.5 prerelease

- 日期：2026-10-27（Asia/Taipei）
- 版本：`0.2.21-ts7-wudi81-v21.5`，versionCode `54`
- 套件：`com.shihab.diplay.ts7`
- 最低 Android API：`24`

本版調整 TS7 的影像送顯流程，並增加診斷數據。**本版尚未在 TS7 實車驗證，不能據此確認畫面延遲已改善。**

## 改了什麼

- TS7 使用專用執行緒等候解碼器輸出，讓解碼完成的畫面可盡快送到螢幕。
- TS7 的 decoder worker 使用 Android 顯示優先序。
- 如果專用執行緒發生例外，DiPlay 會停用該執行緒，重建 decoder，並要求 iPhone 傳送關鍵幀。
- 如果已有影格送進 decoder，但 2 秒內沒有輸出，DiPlay 會執行相同的 fallback。
- 沒有 pacing 且不是 framesAtOnce 時，若已有多張解碼完成的影格等待送顯，DiPlay 只送最新一張。DiPlay 仍會解碼較舊影格，不會丟棄輸入或破壞參考影格鏈。
- fallback 後，DiPlay 使用原本由 decoder worker 輪詢輸出的方式。fallback 狀態會留在本次連線的診斷統計中。
- 非 TS7 車機維持原本的送顯行為。音訊、解析度預設、影格率、operating rate、frame end marker 與 `poll(5)` 沒有變更。

## 診斷報告怎麼看

`Video: video stats` 每 5 秒新增以下欄位：

- `outputThread=on|off|fallback(reason)`：目前是否使用專用輸出執行緒。`fallback(no_output_2s)` 表示 decoder 有待輸出的影格超過 2 秒；`fallback(exception)` 表示送顯執行緒發生例外。fallback 後該連線使用單執行緒送顯。
- `qDepth=avg/max`：每個統計窗中，等待 decoder worker 處理的輸入影格數平均值與最大值。數值持續偏高表示影像工作在 App 佇列中等待。
- `inputAcquireMs=p50/max`：decoder worker 等待輸入 buffer 的 p50 與最大毫秒數。數值偏高表示取得輸入 buffer 花較久。
- `inFlight=max`：單一統計窗中，已送進 decoder、尚未取出輸出的影格最大數量。數值持續偏高表示解碼器輸出落後。
- `drainYield=max`：一次排空已就緒輸出時處理的影格數最大值。
- `dropRender=N`：因為有更新的解碼輸出已就緒，這個統計窗跳過送顯的舊影格數。這些影格仍已解碼。

`Receive: video` 每個統計窗新增：

- `readMs=p50/max`：socket 收完訊息標頭與影像本文所花時間的 p50 與最大值。讀取時間高時，可對照接收端是否有等待。
- `decryptMs=p50/max`：解密影像本文所花時間的 p50 與最大值。此數值只量測解密，不含 socket 讀取。

請一併比較 `rx`、`shown`、`decode p50/p90`、`qDepth`、`inFlight`、`readMs` 與 `decryptMs`。單一欄位不能證明卡頓原因；實際效果要用同一台 TS7、相同路線與相同畫面操作測試。

## 尚未實車驗證

- 專用送顯執行緒是否能穩定搭配 TS7 的展訊 decoder。
- 2 秒無輸出 fallback 是否能在車機上及時恢復畫面。
- 最新影格送顯是否降低觸控後的畫面延遲。
- 新增統計欄位能否找出實車卡頓來源。
