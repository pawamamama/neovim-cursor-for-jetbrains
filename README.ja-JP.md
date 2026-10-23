# Neovide Cursor for JetBrains — 設定可能版

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.2%2B-000000?logo=intellijidea&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
[![Stars](https://img.shields.io/github/stars/pawamamama/neovim-cursor-for-jetbrains?style=flat)](https://github.com/pawamamama/neovim-cursor-for-jetbrains/stargazers)
[![Downloads](https://img.shields.io/github/downloads/pawamamama/neovim-cursor-for-jetbrains/total?style=flat)](https://github.com/pawamamama/neovim-cursor-for-jetbrains/releases)

> **Neovide 風のトレーリングカーソル**を JetBrains IDE に。カーソルが動くと、
> フェードしながら伸びる軌跡と柔らかなグローが残ります。
>
> **対応**：IntelliJ IDEA / CLion / PyCharm など **2024.2 ~ 2026.2+**
>
> **本版の特徴**：元のエフェクトに加えて、**設定パネル + 永続化 + プリセット**を追加。
> 元版はパラメータをコード編集でしか変えられませんが、本版は IDE 内で直接調整・保存・切替できます。


**言語 / Language** — [English](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [**日本語**](README.ja-JP.md) | [한국어](README.ko-KR.md)

---

## 本版だけの機能

| 機能 | 元版 | **本版** |
|---|---|---|
| **設定パネル** | ❌ コード編集 | ✅ **スライダー 11 個 + チェックボックス 7 個** |
| **永続化** | ❌ | ✅ IDE 設定に保存 |
| **アニメーションプリセット** | ❌ | ✅ 3 個 |
| **カラープリセット** | ❌ | ✅ 5 個 |
| **カスタムプリセット** | ❌ | ✅ 最大 50 個 |
| **Tools メニュー** | 4 項目 | ✅ **8 項目** |

---

## インストール

[Releases](../../releases) から `neovide-cursor-*.jar` をダウンロードし、
`Settings → Plugins → ⚙ → Install Plugin from Disk...` で jar を選択、IDE を再起動。

設定場所：`Settings → その他の設定 → Neovide Cursor`

---

## 完全なドキュメント

- **簡体字中国語（最も詳細）** → [README.zh-CN.md](README.zh-CN.md)
- **English** → [README.md](README.md)

> 本版の UI は現在**簡体字中国語のみ**です。国際化は未対応。
> 設定項目名とパラメータ説明は技術用語のため、上記ドキュメントのパラメータ表と併用すれば問題ありません。

---

## 既知の制限

マルチカーソル非対応。フレームレートは Swing EDT の制約を受けます
（低フレームレート向けに「残像モード」を用意）。
詳細は [README.zh-CN.md の既知の欠陥](README.zh-CN.md#已知缺陷) を参照。

---

## 謝辞

[yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains) を基に開発。
コアのトレーリング物理は上流由来。MIT License。
