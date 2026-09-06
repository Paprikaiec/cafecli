# 01 — 发布可直接运行的 GitHub Release

Status: **ready-for-human**
Type: 发布工程

## Parent

`.scratch/release-and-publish/PRD.md`

## 问题

README 的「快速开始」是 `git clone` → `cp .env.example .env` → `mvn clean package` → `java -jar`。对一个想花两分钟看看的人，这里有四道门槛，其中 `mvn clean package` 还需要本地有 Maven。

## 要做的

发一个 `v1.0.0` Release，挂上已 shade 的可执行 jar（CI 里 `mvn -B clean package` 已经在产出 `target/cafecli-*.jar`，产物存在性也已有断言，等于发布链路已经跑通，只差把产物挂出去）。

- Release notes 简述：能力范围、Java 21 硬要求（虚拟线程）、至少一个 API Key
- README 快速开始改为**先给下载运行路径**，`clone + package` 降为「从源码构建」小节
- 考虑让 CI 在打 tag 时自动上传产物

## 验收

从零开始的人可以只做两步跑起来：下载 jar → 配一个 API Key → `java -jar`。

## 成本

约半天。CI 已有 package 步骤，主要是 Release notes 和 README 改写。

## Comments

- 2026-09-06：与简历同期推进。简历不依赖此票，但面试官若点开仓库，「有 Release」与「要自己编译」给人的完成度印象差别很大。
