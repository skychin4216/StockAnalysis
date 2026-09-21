# GitHub 分支保护配置清单（照着点即可）

> 目标：`dev` / `main` 只有**你自己能写、能合并**；`maosheng` 开放给**协作者开发**。
> 仓库：`https://github.com/skychin4216/StockAnalysis`（**private**，2026-09-22 确认）
> 配套文件：`.github/CODEOWNERS`（已建好）

---

## 〇、先明确一件事（避免误解）

| 你想达到的效果 | 能不能做到 | 怎么做 |
|---|---|---|
| 别人**不能 push / 不能 merge** 到 dev、main | ✅ 能 | 下面「二、三」 |
| 别人**必须你批准**才能改 | ✅ 能 | 下面「四」 CODEOWNERS |
| 非协作者**连看都看不到** | ✅ **已完成** | 仓库已是 **private**（2026-09-22 确认）|
| 协作者**能看全部代码** | ⚠️ 现状如此 | private 仓库的协作者可读所有分支、全部历史 |

> **2026-09-22 现状确认**：仓库**已是 private**，对外隔离这一步**已完成**，无需再做。
> 现在的边界是：
> - **非协作者**：看不到任何东西 ✅
> - **协作者**：能看到**全部代码 + 全部历史**（含选股核心），且可在 `maosheng` 上开发
>
> 也就是说 —— **「拆分仓库 / 镜像对外仓库」这套方案当前并不需要**。
> 只有当哪天你希望「协作者能开发，但看不到选股核心」时，才需要重新考虑它。

---

## 一、进入设置页

1. 打开 `https://github.com/skychin4216/StockAnalysis`
2. 点顶部 **Settings**（需要你是仓库 owner/admin）
3. 左侧菜单：**Rules → Rulesets**
   （老版界面是 **Branches**，两种都行；下面以新版 Rulesets 为准，更细）

---

## 二、给 `main` 建规则

1. **New ruleset → New branch ruleset**
2. **Ruleset Name**：`protect-main`
3. **Enforcement status**：`Active`（不要选 Evaluate）
4. **Target branches**：
   - 点 `Add target` → `Include by pattern` → 填 `main`
5. **Branch rules**（逐个勾选）：

| 勾选项 | 作用 |
|---|---|
| ✅ **Restrict deletions** | 禁止删分支 |
| ✅ **Block force pushes** | 禁止强推（防历史被篡改）|
| ✅ **Require a pull request before merging** | 禁止直接 push |
| 　　└ Required approvals：`1` | 至少 1 人批准 |
| 　　└ ✅ **Dismiss stale pull request approvals when new commits are pushed** | 改了代码就得重新批准 |
| 　　└ ✅ **Require review from Code Owners** | **让 CODEOWNERS 生效** |
| ✅ **Require status checks to pass** | 按需；本项目无 CI，可先不勾 |
| ✅ **Require signed commits** | 按需（推荐勾，防冒名提交）|
| ✅ **Block creations** | 禁止别人新建同名分支绕过 |

6. 页面底部 **Bypass list**（关键）：
   - 点 `Add bypass`
   - 只加 **你自己** `skychin4216`
   - **不要**勾 "Allow repository admins to bypass"（勾了等于自己能绕过，规则形同虚设）

7. 点 **Create**

---

## 三、给 `dev` 建规则（同上，只改名字和分支名）

1. **New ruleset → New branch ruleset**
2. Name：`protect-dev`
3. Enforcement：`Active`
4. Target branches：`Include by pattern` → `dev`
5. 勾选项与「二」第 5 步**完全一致**
6. Bypass list：同样**只加你自己**
7. **Create**

> 💡 顺带建议：把 `feature/*` 也加一条只勾 `Restrict deletions + Block force pushes` 的宽松规则，防止误删。

---

## 四、确认 CODEOWNERS 已生效

1. 仓库页面 → **Settings → Rules** → 点进 `protect-main`
2. 确认 **Require review from Code Owners** 是勾选状态
3. 根目录下 `.github/CODEOWNERS` 已存在（本项目已建），内容默认 `* @skychin4216`

**验证方法**：让别人开一个 PR 到 dev，若出现
`Review required · Required reviewers: skychin4216 (Code owner)` 即成功。

---

## 五、给 `maosheng` 加保护（**与 dev/main 不同，别照抄**）

> ⚠️ **2026-09-22 更正**：`maosheng` **不是**只读快照，而是**给协作者基于你的代码做开发的分支**。
> 所以他们**必须能 push**，配置与 dev/main 相反。

- Target：`maosheng`
- 勾选：
  - ✅ `Restrict deletions` —— 防误删
  - ✅ `Block force pushes` —— **关键**，见下方「禁止强推」纪律
  - ❌ **不要**勾 `Restrict who can push`（勾了协作者就推不上去了）
  - ❌ **不要**勾 `Require a pull request before merging`（他们直接在分支上开发）
- Bypass list：只加你自己

### 🚫 铁律：`maosheng` 永远禁止 force push

协作者在 `maosheng` 上有自己的提交。**任何** `git push --force` / 「删分支重建」都会**直接抹掉他们的代码**。

同步 dev → maosheng 只允许用 **merge**：

```bash
git checkout maosheng
git pull origin maosheng        # 先拿到协作者的最新提交
git merge origin/dev            # 再合入 dev
# 有冲突就解决
git push origin maosheng        # 普通 push，绝不 --force
```

上面第五节勾了 `Block force pushes`，就是为了在服务端兜底 —— 万一误敲了 force 也会被拒绝。

---

## 六、做完以后，我们这边的日常流程

| 场景 | 操作 |
|---|---|
| 你的日常开发 | 在 `dev` 上提交（只有你能推）|
| 协作者开发 | 直接在 `maosheng` 上提交（他们能推，你也能推）|
| 把 dev 的新代码给到协作者 | **`merge`**：`git merge origin/dev` → 普通 push（**禁止 force**）|
| 把协作者的代码收回主线 | 他们从 `maosheng` 开 PR → `dev` → 你（Code Owner）批准才合入 |
| 发布稳定版 | `dev` → `main`（快进）|

### 协作者参与的注意事项

1. **他们能看到全部代码与历史**（private 仓库协作者权限如此）。若日后不想让他们看到选股核心，
   那才需要「镜像对外仓库」方案，届时可再启用。
2. **加密文件对他们不可读**：`git-crypt` 加密的 5 个文件（交易/持仓/选票）他们没有密钥，
   工作区里是密文，**照原样提交回去不会泄露**；但若要他们参与这部分开发，才需要考虑给密钥。
3. **历史里有一处明文**：`ddd3c68`(2026-09-20) 的 `data/_trade_orders.jsonl` 未加密入库过
   （2 行，内容为 `gateway=paper` / `dry_run=true` 的**模拟盘测试数据**，无真实成交）。
   协作者可见。若要清除需 force-push 重写历史 —— 代价大于收益，当前决定**不动**。

---

## 七、常见问题

**Q：我（owner）自己也会被挡住吗？**
A：会。若 Bypass list 里加了自己 + 勾选了 "Allow admins to bypass"，则你能绕过；
若**没勾** admins bypass，则你自己也要走 PR。**推荐：Bypass 只加自己、不勾 admins 绕过**。

**Q：CODEOWNERS 里写了不存在的用户名会怎样？**
A：该条目静默失效（GitHub 不报错）。所以加人前先确认用户名拼写。

**Q：这条规则能防住 GitHub Actions / 机器人吗？**
A：Rulesets 对 Actions 也生效（除你加进 Bypass 的）。我们自己的守护不推 GitHub，无影响。
