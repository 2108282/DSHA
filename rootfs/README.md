# DSHA 0.1.5-rc.2 纯净原生 Linux 底包资产

本目录为 GitHub Actions 自动化流水线熔铸生成的 **100% 纯净全新底包**。
已彻底物理清除旧 PRoot 时代残留，并集成消除空白行与通知防误弹等最新修复。

---

## 方式一：完整单文件直链下载（推荐）
- **直链下载**: [rootfs.tar.gz](https://github.com/2108282/DSHA/releases/download/0.1.5rc.2-base/rootfs.tar.gz)
- **文件大小**: 218M
- **SHA-256 校验和**: `01cc6dea52e82f4e3d0c22e7869c20a18126f08e77d89a7f2c6683199115a549`

---

## 方式二：本分支直接本地合并还原
因 GitHub 限制单个文件不能超过 100MB，本目录下提供 50MB 分卷文件（`rootfs.tar.gz.part-*`）。
克隆本分支后，直接在当前目录执行：
```bash
./merge.sh
```
即可还原为完整的 `rootfs.tar.gz`。
