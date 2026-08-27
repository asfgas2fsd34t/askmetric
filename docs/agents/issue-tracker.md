# Issue 跟踪：GitHub

本仓库的 Issue 和规格说明都存放在 GitHub Issues 中。所有操作使用 `gh` CLI。

## 操作约定

- **创建 Issue**：`gh issue create --title "..." --body "..."`。
- **读取 Issue**：`gh issue view <number> --comments`。
- **列出 Issue**：使用 `gh issue list`，并按需传入标签和状态过滤条件。
- **评论**：`gh issue comment <number> --body "..."`。
- **添加或移除标签**：使用 `gh issue edit`。
- **关闭 Issue**：`gh issue close <number> --comment "..."`。

从 `git remote -v` 推断仓库；在仓库 clone 内运行时，`gh` 会自动完成该推断。

## 是否将 Pull Request 纳入分流

**不将 PR 作为需求入口。**

GitHub 的 Issue 和 Pull Request 共享编号空间。遇到含义不明的 `#42` 时，先运行 `gh pr view 42`，失败后再运行 `gh issue view 42`。

## Skill 语义

- “发布到 Issue 跟踪系统”表示创建 GitHub Issue。
- “获取相关 Ticket”表示运行 `gh issue view <number> --comments`。
- `/wayfinder` 使用 GitHub Issue、子 Issue、依赖、标签和负责人表达 Map 与子 Ticket。
- Map 使用 `wayfinder:map`，子 Ticket 类型使用 `wayfinder:<type>`。
- 使用 `gh issue edit <number> --add-assignee @me` 认领工作。
- 完成工作时先评论结论，再关闭 Issue。
