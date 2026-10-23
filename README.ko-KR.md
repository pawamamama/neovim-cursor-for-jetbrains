# Neovide Cursor for JetBrains — 설정 가능 에디션

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![IntelliJ Platform](https://img.shields.io/badge/IntelliJ%20Platform-2024.2%2B-000000?logo=intellijidea&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
[![Stars](https://img.shields.io/github/stars/pawamamama/neovim-cursor-for-jetbrains?style=flat)](https://github.com/pawamamama/neovim-cursor-for-jetbrains/stargazers)
[![Downloads](https://img.shields.io/github/downloads/pawamamama/neovim-cursor-for-jetbrains/total?style=flat)](https://github.com/pawamamama/neovim-cursor-for-jetbrains/releases)

> **Neovide 스타일 트레일링 커서**를 JetBrains IDE 에. 커서가 움직일 때
> 페이드되며 늘어나는 궤적과 부드러운 글로우가 남습니다.
>
> **지원**: IntelliJ IDEA / CLion / PyCharm 등 **2024.2 ~ 2026.2+**
>
> **이 버전의 특징**: 원본 효과에 더해 **설정 패널 + 영속화 + 프리셋**을 추가했습니다.
> 원본은 코드를 수정해야 파라미터를 바꿀 수 있지만, 이 버전은 IDE 안에서 직접 조정·저장·전환할 수 있습니다.


**언어 / Language** — [English](README.md) | [简体中文](README.zh-CN.md) | [繁體中文](README.zh-TW.md) | [日本語](README.ja-JP.md) | [**한국어**](README.ko-KR.md)

---

## 이 버전만의 기능

| 기능 | 원본 | **이 버전** |
|---|---|---|
| **설정 패널** | ❌ 코드 수정 | ✅ **슬라이더 11개 + 체크박스 7개** |
| **영속화** | ❌ | ✅ IDE 설정에 저장 |
| **애니메이션 프리셋** | ❌ | ✅ 3개 |
| **색상 프리셋** | ❌ | ✅ 5개 |
| **사용자 프리셋** | ❌ | ✅ 최대 50개 |
| **Tools 메뉴** | 4개 항목 | ✅ **8개 항목** |

---

## 설치

[Releases](../../releases) 에서 `neovide-cursor-*.jar` 를 받아
`Settings → Plugins → ⚙ → Install Plugin from Disk...` 로 선택 후 IDE 재시작.

설정 위치: `Settings → 기타 설정 → Neovide Cursor`

---

## 전체 문서

- **중국어 간체 (가장 상세)** → [README.zh-CN.md](README.zh-CN.md)
- **English** → [README.md](README.md)

> 이 버전의 UI 는 현재 **중국어 간체만** 지원합니다. 국제화는 미지원.
> 설정 항목 이름과 파라미터 설명은 기술 용어이므로 위 문서의 파라미터 표와 함께 보면 문제없습니다.

---

## 알려진 제한

멀티 커서 미지원. 프레임레이트는 Swing EDT 제약을 받습니다
(저프레임레이트용으로 "잔상 모드" 제공).
자세한 내용은 [README.zh-CN.md 의 알려진 결함](README.zh-CN.md#已知缺陷) 참조.

---

## 감사의 말

[yyyyolo7a79-sketch/neovim-cursor-for-jetbrains](https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains) 기반으로 개발.
핵심 트레일링 물리는 업스트림에서 유래. MIT License.
