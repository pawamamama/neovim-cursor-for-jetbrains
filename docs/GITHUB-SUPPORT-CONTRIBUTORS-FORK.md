# GitHub Support ticket draft — fork Contributors sidebar is empty

> 给用户的操作说明（不要整段贴给 GitHub）：
>
> 1. 打开 https://support.github.com/contact （或 GitHub Community 的 Bug 区）。
> 2. 标题、正文复制下面 **English ticket** 部分。
> 3. 附上两张截图：桌面浏览器 + 安卓浏览器，都是仓库首页右侧「Contributors / 贡献者」显示「暂无贡献者」。
> 4. 如果 Support 要求更多信息，把 `contributors-data` 的 JSON 或本文末尾的命令输出贴上去。
>
> 重点：**不要只说“没有我”**，要强调「图数据里有 3 个贡献者，首页侧栏却是空」——这是两个不同的组件，问题在侧栏缓存。

---

## English ticket

**Subject:** Fork repository home sidebar shows "No contributors yet" although contributors graph data contains 3 contributors

### Summary

The public fork `pawamamama/neovim-cursor-for-jetbrains` has three contributors
according to the repository's own contributors graph data and the REST API, but
the repository home page's right-hand **Contributors** sidebar persistently shows
**"No contributors yet"** / **"暂无贡献者"**.

This is not a browser cache issue: the same empty sidebar appears on desktop
Chrome/Edge and on Android Chrome, including incognito/private windows. It has
been happening since the fork was created (the project was at v0.9.83 at that
time), across many pushes and several releases.

### Repository

- URL: https://github.com/pawamamama/neovim-cursor-for-jetbrains
- Visibility: public
- `isFork`: true
- Parent: https://github.com/yyyyolo7a79-sketch/neovim-cursor-for-jetbrains
- Default branch: `main`

### Expected

The home-page Contributors sidebar should list the repository's contributors,
consistent with the graph data:

| Login | Contributions |
|---|---|
| `pawamamama` | 315 |
| `claude` | 107 |
| `yyyyolo7a79-sketch` | 17 |

### Actual

The sidebar shows **"No contributors yet"** / **"暂无贡献者"**. The graph page and
raw graph data contain the contributors:

- Graph page: https://github.com/pawamamama/neovim-cursor-for-jetbrains/graphs/contributors
- Raw graph data: https://github.com/pawamamama/neovim-cursor-for-jetbrains/graphs/contributors-data

The raw data returns entries equivalent to:

```json
[
  {"author": {"login": "yyyyolo7a79-sketch"}, "total": 17},
  {"author": {"login": "claude"}, "total": 107},
  {"author": {"login": "pawamamama"}, "total": 315}
]
```

REST API also returns data:

```text
GET https://api.github.com/repos/pawamamama/neovim-cursor-for-jetbrains/contributors
pawamamama         315
yyyyolo7a79-sketch  17
```

The account/commit-email association works: the upstream repository shows
`pawamamama` as a contributor for a merged contribution (1 commit), and the
fork's graph data attributes 315 contributions to the same account.

### Steps to reproduce

1. Open https://github.com/pawamamama/neovim-cursor-for-jetbrains in desktop
   Chrome/Edge (also try an incognito window).
2. Look at the right sidebar → **Contributors** → it shows **"No contributors yet"**.
3. Repeat on Android Chrome (normal and incognito) → same empty state.
4. Open `/graphs/contributors-data` → the three contributors above are present.
5. The repository home HTML currently renders the Contributors widget as an
   async loading skeleton, but it never resolves to the contributor list.

### Request

Please refresh/rebuild the contributor graph and the home-page **Contributors**
sidebar cache for this fork. If forks are subject to a separate contributor-graph
policy, please explain the limitation and advise whether detaching the fork into a
standalone repository is required.

### Attachments

- Screenshot: desktop repo home, right sidebar shows "暂无贡献者".
- Screenshot: Android Chrome, same empty sidebar.
- Optional: the JSON from `/graphs/contributors-data`.

---

## 复核命令（给用户/Support 均可）

```bash
# 图数据：应看到 pawamamama 315 / claude 107 / upstream 17
curl -L -s -A "Mozilla/5.0" \
  "https://github.com/pawamamama/neovim-cursor-for-jetbrains/graphs/contributors-data"

# REST API：应看到 pawamamama 315 / upstream 17
gh api repos/pawamamama/neovim-cursor-for-jetbrains/contributors \
  --jq '.[] | "\(.login)=\(.contributions)"'
```
