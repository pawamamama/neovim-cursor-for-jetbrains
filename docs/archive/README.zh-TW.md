# Neovide Cursor for JetBrains — 可設定版

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.2%2B-000000?logo=intellijidea&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
[![Stars](https://img.shields.io/github/stars/pawamamama/neovim-cursor-for-jetbrains?style=flat)](https://github.com/pawamamama/neovim-cursor-for-jetbrains/stargazers)
[![Downloads](https://img.shields.io/github/downloads/pawamamama/neovim-cursor-for-jetbrains/total?style=flat)](https://github.com/pawamamama/neovim-cursor-for-jetbrains/releases)

> 把 **Neovide 風格的拖尾游標**帶到 JetBrains IDE——游標移動時留下漸隱的拉伸拖尾，帶輝光光暈。
>
> **適用**：IntelliJ IDEA / CLion / PyCharm 等 **2024.2 ~ 2026.2+**
>
> **本版重點**：在原始效果之上，補上了**完整設定面板 + 持久化 + 預設系統**。
> 原版只能改程式碼調參數，本版可以在 IDE 裡直接調、存、切換。


**語言 / Language** — [English](README.md) | [简体中文](README.zh-CN.md) | [**繁體中文**](README.zh-TW.md) | [日本語](README.ja-JP.md) | [한국어](README.ko-KR.md)

---

## 本版獨有

| 能力 | 原版 | **本版** |
|---|---|---|
| **設定面板** | ❌ 改程式碼 | ✅ **11 個滑桿 + 7 個勾選框** |
| **持久化** | ❌ | ✅ 存進 IDE 設定 |
| **動畫預設** | ❌ | ✅ 3 個 |
| **顏色預設** | ❌ | ✅ 5 個 |
| **自訂預設** | ❌ | ✅ 上限 50 個 |
| **Tools 選單** | 4 項 | ✅ **8 項** |

---

## 安裝

從 [Releases](../../releases) 下載 `neovide-cursor-*.jar`，
然後 `Settings → Plugins → ⚙ → Install Plugin from Disk...`，選該 jar，重啟 IDE。

設定位置：`Settings → 其他設定 → Neovide 游標`

---

## 完整文件

- **简体中文（最完整）** → [README.zh-CN.md](README.zh-CN.md)
- **English** → [README.md](README.md)

> 本版介面目前為**簡體中文**，暫未做國際化。
> 設定項名稱與參數說明都是技術術語，配合上述文件的參數表不影響使用。

---

## 已知限制

不支援多游標；影格率受 Swing EDT 限制（提供「殘影模式」作為低影格率替代）。
完整清單見 [README.zh-CN.md 的「已知缺陷」](README.zh-CN.md#已知缺陷)。

---

## 致謝

基於 [yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains) 開發。
核心拖尾物理來自上游。MIT License。
