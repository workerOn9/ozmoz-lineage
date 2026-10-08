# 参与开发

项目处于 M0（骨架）阶段，接口、模块边界与命令都可能变动。**先开 issue 聊清楚，再写代码**，能省掉双方的时间。

## 环境

- JDK 21（Gradle toolchain）
- Gradle（使用仓库内 wrapper，不依赖本机版本）
- 前端：Node 22+ 与 npm（`web/` 子项目）

```bash
./gradlew build        # 构建 + 测试（骨架落地后可用）
./gradlew :cli:test    # 只跑 CLI 模块
```

> 构建脚本仍在落地中，本节随 M0 更新。

## 提交规范

- 提交信息：`<type>: <主题>——<补充说明>`；一次提交只做一件事，验证通过后再提交。
- type 取值：`feat` / `fix` / `test` / `build` / `refactor` / `docs` / `content`（`content` 留给 SQL 语料）。
- 分支：`feat/<主题>`、`fix/<主题>`；`main` 始终保持可构建。
- PR 描述写清三件事：动机、改动范围、**验证方式（贴真实命令与输出）**。

## 设计约束（重要）

- **Never wrong**：无法从权威来源推导的绑定 / 类型，留空或标 `unknown`，绝不猜。
- **Lossless**：不丢信息——每个 token 保留精确 span，标识符保留原始大小写与引号形式。
- 引擎私有类型不得进入公共 API 签名。
- 公共签名不得出现 `value class`（Java 互操作）。

## 依赖

新增依赖请在 [`THIRD-PARTY.md`](THIRD-PARTY.md) 登记许可证。
