# 上游 issue：dsh-config-manager 的 import 会破坏 `cordis.patch.yml` 的 `insert` 语义

> 这份文稿记录一个**已在本机实证**的上游缺陷，正文部分（英文）可直接提交到：
> - `https://github.com/xiajiajun516/dsh-config-manager`（`dsh-config-manager@0.1.68`）
> - `https://github.com/dale0525/dsh-plugins`（`@logictan/dsh-config-manager@0.1.76`，同一份逻辑的 fork）
>
> 两个包都含同一段问题代码与同一句 `# rewritten by dsh-config-manager import
> (original comments not preserved)` 注释。

## 中文摘要（背景与影响）

DSH-Folk 通过 `$DSH_HOME/cordis.patch.yml`（home 层 patch）挂一个本地 cordis 插件
`dsh-folk-host`，用来往 system prompt 注入「你跑在安卓 DSH-Folk 里、有哪些宿主能力」这一段。

用户执行过一次 config-manager 的 **import** 之后，这段提示词**整段消失**（连安卓原生能力桥
一起没了）。定位结果：import 把 patch 文件重写成了扁平条目表，抹掉了外层 `- insert:`。

dsh 的 patch 语义是：

- **带 `insert:`** 的条目 = **新增**插件到条目表；
- **不带 `insert:`** 的条目 = **按 id 寻址一个已存在的行**；找不到就 `warn` 后 `continue`（跳过）。

于是被压平的那行退化成「patch 一个不存在的 id」→ 被 dsh 静默跳过 → 插件从未加载。

**这不是理论问题**：本机复现过，且**没有任何报错弹到用户面前**，只有一行 warn 日志。

## 复现（本机实证）

```sh
# 1. 容器里确认插件确实因形状不对而没被加载
sed -n '1,12p' /root/.dsh/cordis.patch.yml | cat -A
# 被 import 重写后长这样（注意：没有 - insert: 外层）
#   # rewritten by dsh-config-manager import (original comments not preserved)$
#   - id: dsh-folk-host$
#     name: /root/.dsh/plugins/dsh-folk-host.mjs$

# 2. 对照 dsh 的 patch 语义
node -e '
const m=require("/usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js");
' # 见下方 applyEntryPatches 源码引用
```

`@deepseek-ai/dsh-app-boot`（`0.1.7-rc.2` 与 `0.2.0-rc.2` 逐字一致，已离线核对）的
`applyEntryPatches`：

```js
for (const patch of patches) {
  const { id, insert, name, ...overrides } = patch;
  if (insert) {
    if (id) { /* 插进某个 group 的 config */ }
    else data.push(...insert);          // ← 只有 insert 才会新增
    buildMap(insert);
    continue;
  }
  if (!id) { warn("patch: id is required for non-insert patches"); continue; }
  const target = entryMap.get(id);
  if (!target) { warn("patch: entry %C not found", id); continue; }   // ← 裸行走到这里，被跳过
  ...
}
```

## 问题代码位置（`dsh-config-manager@0.1.68`，`@logictan` fork 同）

1. **读入时把 `insert` 拆开**：`src/index.ts:806-817`（`readPatchLines`，定义在 787）与
   `src/index.ts:827-858`（`applyPatchChanges` 的读侧，定义在 821）——遇到 `insert: [...]`
   就把内部条目逐个当成独立行，**丢弃外层包裹**。
2. **回写时一律输出成顶层裸行**：`src/index.ts:873-883`，并附了这样一句注释：

   ```ts
   // 3. Rebuild: every id row is emitted as a top-level row. The loader treats
   //    a top-level { id, name } row exactly like an `- insert:` block member
   //    (dsh-base patch precedent), so the document stays semantically equal.
   ```

**这句注释的前提是错的。** 顶层 `{ id, name }` 与 `insert:` 成员**语义并不相同**：前者是
「按 id 寻址」，后者才是「新增」。而所引用的 "dsh-base patch precedent" 经核对并不存在：

| patch 文件 | 顶层条目 | `- insert:` | 裸条目 | 裸条目里带 `name:` 的 |
|---|---|---|---|---|
| `@deepseek-ai/dsh-base/cordis.patch.yml` | 1 | 1 | 0 | **0** |
| `@deepseek-ai/dsh-web-app/cordis.patch.yml` | 29 | 2 | 27 | **0** |
| `@deepseek-ai/dsh-web-app/presets/cordis.patch.yml` | 1 | 1 | 0 | **0** |

dsh-base 是用**一个** `insert` 引入全部插件，它自己的文件头注释也写明：

> "the shared core of each base-backed profile, **applied as ONE insert** over the empty
> profile root. Later bundle patches and the user's profile cordis.patch.yml **address these
> rows by id**, with the last write winning per row."

dsh-web-app 那 27 条裸行全部只带 `id` + `config`/`disabled`，用来**改写已 insert 出来的行**，
**没有一条**靠 `name:` 引入新插件。也就是说：上游从未用裸行做过「新增」。

## 影响面

任何**通过 `insert` 向 home 层 / profile 层 patch 添加插件**的用户（这是 dsh 官方的标准做法）
在跑过一次 import 之后，那些插件都会**静默失效**：

- 没有报错，只有一行 `patch: entry … not found` warn；
- 表现为「某个插件突然不工作了」，用户几乎不可能自己定位到 patch 文件形状上；
- 且**不会自愈** —— 文件已经被重写坏，后续每次启动都照样跳过。

本次的具体受害者是 DSH-Folk 的 `dsh-folk-host`：它一失效，容器里 agent 的**整段宿主环境
提示词**（Android 原生能力桥、共享存储说明等）全部消失。

### 更严重的一面：config-manager 自己的「激活行」也是裸行

`ensureActivationRow`（`src/index.ts:480-488`）给**非 bundle 插件**写的激活行长这样：

```ts
const id = `pm-${slugOf(pkg)}`
await patchFile.applyPatchChanges(PROFILE_PATCH_FILE, [
  { lineId: id, raw: { id, name: pkg }, action: 'insert' },
])
```

注意写进去的 `raw` 是 `{ id, name }` —— **裸行，没有 `insert` 外层**。而 dsh 的
`composeEntries` 是 `applyEntryPatches([], …)`，**初始数据为空数组**，整个条目表只能由
`insert` 建起来。所以这条「激活行」要想生效，前提是别处已经用 `insert` 建过
`pm-<slug>` 这个 id。

我们在 `@deepseek-ai/dsh-*` 全部官方包里搜过 `pm-` 的拼接（`pm-${`、`"pm-"` 等），
**没有任何一处**会创建这种 id。也就是说这条激活行**必然被 dsh 判为 `entry not found` 并跳过**，
非 bundle 插件实际上从未被它激活。作者注释里说的「仿 marketplace ensureRow」，
官方 marketplace 侧也没有对应的裸行写法可仿。

（我们机器上的实例：`/root/.dsh/cordis.patch.yml` 第 22-23 行就是

```yaml
- id: pm-dsh-settings-organizer
  name: dsh-settings-organizer
```

——正是这条规则产出的，按上述分析它是惰性的。）

## 建议修法

回写时**保留 `insert` 语义**，二选一：

1. **不要拆包**：把 `insert` 块整体当成一个不可拆的行来读写（行 id 可用块内首条或合成 id）；
2. **回写时重新包起来**：凡是原本来自 `insert` 的条目，回写时重新输出为
   `- insert:` 块（可合并成一块），而不是摊平成顶层裸行。

顺带一提：`src/index.ts:471` 的 `patchRowActivates`（定义在 468）也基于同一个错误假设
（`Array.isArray(obj['insert']) ? obj['insert'] : [obj]`），判定「某行是否激活了某包」时会把
裸行与 insert 成员等同看待，同源问题建议一并检查。

## 我看到的现象（英文正文里会再写一遍）

- 环境：DSH-Folk（Android / proot）内的 `@deepseek-ai/dsh@0.1.7-rc.2`
- 现象：import 之后 `dsh-folk-host` 插件失效，宿主提示词整段消失
- 证据：`$DSH_HOME/cordis.patch.yml` 被重写成扁平表，`insert` 外层丢失

---

# English issue body (ready to file)

**Title:** `import` flattens `- insert:` blocks in `cordis.patch.yml`, silently disabling every plugin added that way

## Summary

`dsh-config-manager` rewrites `$DSH_HOME/cordis.patch.yml` during import, converting every
entry — including members of an `- insert:` block — into a **top-level bare row**. dsh's loader
gives those two shapes **different semantics**, so plugins added via `insert` stop loading.

## Environment

- DSH core: `@deepseek-ai/dsh@0.1.7-rc.2` (bug also present with `0.2.0-rc.2`)
- `dsh-config-manager@0.1.68` (`@logictan/dsh-config-manager@0.1.76` has the same code)
- Host: DSH-Folk on Android (proot container)

## Steps to reproduce

1. Add a plugin to the **home-layer** patch file, the documented way — wrapped in `insert`:

   ```yaml
   # $DSH_HOME/cordis.patch.yml
   - insert:
       - id: my-plugin
         name: /root/.dsh/plugins/my-plugin.mjs
   ```

2. Run a config **import** with dsh-config-manager.
3. Inspect the file:

   ```console
   $ sed -n '1,6p' /root/.dsh/cordis.patch.yml | cat -A
   # rewritten by dsh-config-manager import (original comments not preserved)$
   - id: my-plugin$
     name: /root/.dsh/plugins/my-plugin.mjs$
   ```

   The `- insert:` wrapper is gone.
4. The plugin no longer loads. The only trace is a startup warning:
   `patch: entry my-plugin not found`.

## Root cause

`@deepseek-ai/dsh-app-boot`'s `applyEntryPatches` (identical in `0.1.7-rc.2` and `0.2.0-rc.2`):

```js
if (insert) {
  if (id) { /* insert into a group's config */ }
  else data.push(...insert);        // only `insert` adds entries
  buildMap(insert);
  continue;
}
if (!id) { warn("patch: id is required for non-insert patches"); continue; }
const target = entryMap.get(id);
if (!target) { warn("patch: entry %C not found", id); continue; }  // bare row lands here
```

So a bare top-level `{ id, name }` row is a **patch addressed at an existing id**, *not* an
insertion — it is skipped when no such row exists.

In `dsh-config-manager`, the offending code is:

- `src/index.ts:806-817` and `src/index.ts:840-858` — reading: `insert` blocks are unpacked and
  the wrapper discarded;
- `src/index.ts:873-883` — writing: every row is emitted at top level, with this comment:

  ```ts
  // 3. Rebuild: every id row is emitted as a top-level row. The loader treats
  //    a top-level { id, name } row exactly like an `- insert:` block member
  //    (dsh-base patch precedent), so the document stays semantically equal.
  ```

**That premise is incorrect, and the cited precedent does not exist.** Counting the official
patch files:

| patch file | top-level entries | `- insert:` | bare rows | bare rows carrying `name:` |
|---|---|---|---|---|
| `@deepseek-ai/dsh-base/cordis.patch.yml` | 1 | 1 | 0 | **0** |
| `@deepseek-ai/dsh-web-app/cordis.patch.yml` | 29 | 2 | 27 | **0** |
| `@deepseek-ai/dsh-web-app/presets/cordis.patch.yml` | 1 | 1 | 0 | **0** |

`dsh-base` adds **all** of its plugins inside a single `- insert:`, and its own header states:

> "applied as ONE insert over the empty profile root. Later bundle patches and the user's
> profile cordis.patch.yml address these rows by id, with the last write winning per row."

Every bare row in `dsh-web-app` carries only `id` plus `config`/`disabled` — i.e. they *address*
rows created by `insert`. **No upstream patch file ever adds a plugin with a bare `{ id, name }`
row.**

## Impact

Any user whose home-layer or profile-layer patch adds a plugin via `insert` — the documented,
official way — silently loses that plugin after an import:

- no error is surfaced; only a `patch: entry … not found` warning at startup;
- it presents as "plugin X stopped working", with no hint pointing at the patch file's shape;
- it does **not** self-heal: the file stays broken on every subsequent boot.

In our case the casualty was `dsh-folk-host`, a plugin that injects the host-environment section
into the system prompt. Losing it removed the **entire** host section (Android native-capability
tooling guidance, shared-storage rules, …) from the agent's system prompt.

### Worse: dsh-config-manager's own "activation rows" are bare rows too

`ensureActivationRow` (`src/index.ts:480-488`) writes this row for every non-bundle plugin:

```ts
const id = `pm-${slugOf(pkg)}`
await patchFile.applyPatchChanges(PROFILE_PATCH_FILE, [
  { lineId: id, raw: { id, name: pkg }, action: 'insert' },
])
```

The `raw` value is `{ id, name }` — a **bare row, with no `insert` wrapper**. dsh composes via
`composeEntries` = `applyEntryPatches([], …)`, i.e. it starts from an **empty array**; the whole
entry table can only ever be created by `insert` blocks. So this row only takes effect if
something else already created the id `pm-<slug>` via `insert`.

We searched every official `@deepseek-ai/dsh-*` package for any construction of a `pm-` id
(`pm-${`, `"pm-"`, …) and found **none**. Therefore this activation row is **always** skipped as
`patch: entry pm-<slug> not found` — the mechanism never activates anything. The comment's claim
of "mimicking the marketplace's ensureRow" has no counterpart on the official side either.

(In our instance, `/root/.dsh/cordis.patch.yml` lines 22-23 are exactly such a row:

```yaml
- id: pm-dsh-settings-organizer
  name: dsh-settings-organizer
```

— and by the above it is inert.)

## Proposed fix

Preserve `insert` semantics when rewriting. Either:

1. **Do not unpack** `insert` blocks — treat the whole block as one indivisible row, or
2. **Re-wrap on write**: rows that originated inside an `insert` block must be emitted again as
   `- insert:` blocks (coalescing is fine), never flattened to top-level bare rows.

Related: `src/index.ts:471` (`patchRowActivates`, defined at 468) shares the same assumption
(`Array.isArray(obj['insert']) ? obj['insert'] : [obj]`) when deciding whether a row "activates" a
package; worth auditing at the same time.

## Workaround (for anyone hitting this)

Re-wrap the flattened row by hand and restart dsh:

```sh
node -e '
const fs=require("fs"),p=process.env.DSH_HOME+"/cordis.patch.yml";
let s=fs.readFileSync(p,"utf8");
s=s.replace(/^- id: my-plugin\n {2}name: [^\n]*\n/m,"");
s=s.replace(/\s*$/,"")+"\n- insert:\n    - id: my-plugin\n      name: /root/.dsh/plugins/my-plugin.mjs\n";
fs.writeFileSync(p,s);console.log("patched");
'
```
