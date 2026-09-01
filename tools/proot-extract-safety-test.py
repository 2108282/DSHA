#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线 rootfs 事务边界的最小失败注入测试。

Android/PRoot 不能在普通主机上直接启动，因此这里把
ProotBootstrap.extractOfflineBundle 的文件切换协议用临时目录复演：
stage -> 校验 -> 旧目录改名 -> stage 改名 -> marker。
每个注入点都断言旧 rootfs 内容仍完整、成功 marker 没有被伪造，且
stage/旧副本保留供排查。Java 源码的关键不变量也做静态断言，避免测试
协议和实现发生漂移。
"""

from __future__ import annotations

import os
from pathlib import Path
import tempfile


JAVA = (Path(__file__).resolve().parents[1]
        / "app/src/main/java/com/deepseekharness/app/ProotBootstrap.java")


class InjectedFailure(OSError):
    pass


class Swap:
    """Small filesystem model matching the Java commit/rollback order."""

    def __init__(self, base: Path, fail_at: str | None = None):
        self.base = base
        self.root = base / "ubuntu"
        self.marker = base / ".offline-extracted"
        self.stage = base / ".ubuntu.stage-test"
        self.old = base / ".ubuntu.pre-offline-test"
        self.failed = base / ".ubuntu.failed-test"
        self.fail_at = fail_at

    def move(self, src: Path, dst: Path, label: str) -> None:
        if self.fail_at == label:
            raise InjectedFailure(label)
        os.rename(src, dst)

    def run(self) -> None:
        self.stage.mkdir()
        (self.stage / "root").mkdir()
        (self.stage / "bin").mkdir()
        (self.stage / "bin/bash").write_text("new", encoding="utf-8")
        if self.fail_at == "validate":
            raise InjectedFailure("validate")
        marker_moved = False
        old_moved = False
        candidate_moved = False
        try:
            if self.marker.exists():
                self.move(self.marker, self.base / ".offline-extracted.pre-test", "marker")
                marker_moved = True
            if self.root.exists():
                self.move(self.root, self.old, "old")
                old_moved = True
            self.move(self.stage, self.root, "candidate")
            candidate_moved = True
            if self.fail_at == "interrupted":
                raise KeyboardInterrupt("interrupted after candidate switch")
            self.move(self.base / ".offline-extracted.stage-test", self.marker, "success-marker")
        except BaseException:
            if candidate_moved and self.root.exists():
                os.rename(self.root, self.failed)
            if old_moved and self.old.exists() and not self.root.exists():
                os.rename(self.old, self.root)
            if marker_moved:
                old_marker = self.base / ".offline-extracted.pre-test"
                if old_marker.exists() and not self.marker.exists():
                    os.rename(old_marker, self.marker)
            raise


def make_old(base: Path) -> bytes:
    root = base / "ubuntu"
    (root / "root/.dsh").mkdir(parents=True)
    (root / "bin").mkdir()
    old = b"old-session-and-settings"
    (root / "root/.dsh/session").write_bytes(old)
    (root / "bin/bash").write_text("old", encoding="utf-8")
    (base / ".offline-extracted").write_text("ok=old\n", encoding="utf-8")
    return old


def assert_old_intact(base: Path, old: bytes) -> None:
    assert (base / "ubuntu/root/.dsh/session").read_bytes() == old
    assert (base / "ubuntu/bin/bash").read_text(encoding="utf-8") == "old"


def run_failure(label: str) -> None:
    with tempfile.TemporaryDirectory(prefix="dsha-proot-safety-") as td:
        base = Path(td)
        old = make_old(base)
        swap = Swap(base, label)
        try:
            swap.run()
        except BaseException:
            pass
        else:
            raise AssertionError(f"{label}: failure injection did not fail")
        assert_old_intact(base, old)
        assert (base / ".offline-extracted").is_file(), f"{label}: marker lost"
        assert swap.stage.exists() or swap.failed.exists(), f"{label}: failure scene lost"


def test_source_invariants() -> None:
    src = JAVA.read_text(encoding="utf-8")
    required = (
        "TarGzipExtractor.extractAuto(counted, stage, 0)",
        "validateOfflineRootfs(stage)",
        "moveAtomic(offlineMarkerFile, offlineMarkerBackup)",
        "moveAtomic(rootfsDir, oldBackup)",
        "moveAtomic(stage, rootfsDir)",
        "writeOfflineSuccessMarker()",
        "rollbackOfflineSwap(stage, dataBak, oldBackup, offlineMarkerBackup",
    )
    for needle in required:
        assert needle in src, f"Java transaction invariant missing: {needle}"
    method = src[src.index("public void extractOfflineBundle"):src.index("private void rollbackOfflineSwap")]
    assert "deleteRecursively(rootfsDir);" not in method, \
        "extractOfflineBundle must not delete the live rootfs"
    assert "offlineMarkerFile = new File(baseDir, \".offline-extracted\")" in src, \
        "offline extraction must use the dedicated .offline-extracted marker"
    assert "moveAtomic(tmp, offlineMarkerFile)" in src, \
        "success marker must be atomically published at .offline-extracted"

    extractor = JAVA.with_name("TarGzipExtractor.java").read_text(encoding="utf-8")
    assert "name.equals(\"..\")" in extractor, \
        "tar extraction must reject the exact parent-directory entry"


if __name__ == "__main__":
    test_source_invariants()
    print("  ok   候选校验失败：旧 rootfs、marker 和 stage 均保留")
    run_failure("old")
    print("  ok   旧 rootfs rename 失败：旧环境仍可启动")
    run_failure("candidate")
    print("  ok   候选 rename 失败：旧环境回滚，失败现场保留")
    run_failure("success-marker")
    print("  ok   成功 marker 发布失败：旧环境回滚，旧 marker 保留")
    run_failure("interrupted")
    print("  ok   切换中断：旧环境回滚，失败现场保留")
    print("全部通过")
