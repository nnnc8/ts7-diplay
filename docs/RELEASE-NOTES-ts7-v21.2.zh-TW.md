# DiPlay TS7 v21.2

日期：2026-10-25（Asia/Taipei）。
版本：`0.2.21-ts7-wudi81-v21.2`，versionCode `51`。
套件：`com.shihab.diplay.ts7`，最低 Android API `24`。

音訊修正尚未實車驗證。請回傳新版診斷報告。

## 改動

- 音樂時鐘改用時間平均，避免把 Wi-Fi 封包串的高水位當成時脈漂移。播放開始、重新緩衝與丟包後會重新量測。
- TS7 音樂的播放速率最多修正 ±0.3%。停頓與重新填充期間不學習修正值。低水位不加速，也不把修正值帶到下一首。
- TS7 音樂起始緩衝至少約 900 ms。重新緩衝後增加 300 ms，最高約 2200 ms，並受 AudioTrack 實際容量限制。穩定 60 秒後降低 100 ms。
- TS7 的 AAC 即時音樂依序號與時間戳補靜音，每個缺口最多補 1 秒。遲到或重複的封包不再播放。超長缺口或時間戳重啟不補靜音。
- 通話結束時撤回 DiPlay 自己的通話模式。Android 8.1 控制連線加入 TCP 存活偵測。
- 報告保留 TEARDOWN、解碼器、通話階段、車機藍牙角色、音量、靜音與模式。設定重連記為 `by=settings`。選單開啟期間仍保存記錄。
- 新程序可報告上一個程序在連線中結束。記錄不代表已證明程序被系統殺掉。

電話、Siri 與導航提示保留原有低延遲緩衝。
本版沒有自動斷開車機藍牙。車機角色介面屬隱藏 API，尚無 TS7 韌體的可靠呼叫證據。
本版沒有加入音訊重送。程式庫沒有可靠的重送封包或控制埠參考。

## 安裝與實車比較

1. 從 v21 或 v21.1 直接覆蓋安裝，保留設定。
2. 如果仍使用舊 v0.2.48，先移除舊版。舊版 versionCode 68 高於本版 51。
3. 用 30 fps 播放音樂至少 10 分鐘，存診斷報告。
4. 改成 60 fps，用相同路線重複並存報告。
5. 在 TS7 藍牙設定關閉同支 iPhone 的電話音訊與媒體音訊，保留配對。
6. 重複測試音樂、來電掛斷與 Siri，回傳報告。

## 報告欄位

- `audio stats`：`rebuffers`、`underruns`、`playbackRate`。
- `Audio: buffer stats`：`burstPacketsMax`、`burstPeriodMs`、`missingPackets`、`lostBursts`、`lateOrDuplicate`、`silenceFrames`、`targetMs`、`trimPpm`。
- `lostBursts` 是以每串 18 個封包換算的完整串數，並非無線驅動層量測。
- `Audio: output state`：`mode`、`musicVolume`、`musicMuted`。
- `Bluetooth: car profiles`：`a2dpSink`、`hfpClient`、`carPlayPhone`、`atMs`。`unsupported` 表示韌體沒有開放角色介面，不能當成已斷線。
- `CarPlay call phase`、`Audio: mode restored requested=0`、`Microphone: start recordingState`。
- `Previous DiPlay process pid=... ended while a CarPlay session was active`。

## 上游依據

- [#993：時間平均](https://github.com/shihabal3amri/DiPlay/pull/993)
- [#1070：重新量測](https://github.com/shihabal3amri/DiPlay/pull/1070)
- [#1080：封包抵達與量測修正](https://github.com/shihabal3amri/DiPlay/pull/1080)
- [#994：撤回通話模式](https://github.com/shihabal3amri/DiPlay/pull/994)
- [#1042：Android 8.1 TCP 偵測](https://github.com/shihabal3amri/DiPlay/pull/1042)
- [#995：麥克風靜音診斷](https://github.com/shihabal3amri/DiPlay/pull/995)
