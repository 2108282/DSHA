#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""restore-merge.py 的最小 P0 失败注入测试。

这些用例直接调用事务边界，故不需要 Android、rootfs 或真实备份包：
  1. 候选 .dsh 校验失败：旧目录不动，stage 保留；
  2. 旧目录 rename 失败：不删除旧目录，也不落位候选；
  3. 新目录落位中断：旧目录从时间戳备份回滚，stage 保留；
  4. 新目录 rename 返回错误：与中断路径相同，旧目录仍可启动。
"""
import importlib.util
import contextlib
import io
from pathlib import Path
import sys
import tempfile


REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "app" / "src" / "main" / "assets" / "restore-merge.py"
SPEC = importlib.util.spec_from_file_location("dsha_restore_merge", SCRIPT)
MERGE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MERGE)


def reset_state():
    MERGE.report.clear()
    MERGE.partial = False
    MERGE.retain_stage = False
    MERGE.restore_committed = False


def make_old(root: Path):
    dsh = root / ".dsh"
    dsh.mkdir(parents=True)
    (dsh / "settings.yaml").write_text("old-settings\n", encoding="utf-8")
    (dsh / "old-marker").write_text("old-data\n", encoding="utf-8")
    return dsh


def make_candidate(stage: Path):
    dsh = stage / ".dsh"
    dsh.mkdir(parents=True)
    (dsh / "settings.yaml").write_text("new-settings\n", encoding="utf-8")
    (dsh / "sessions").mkdir()
    # 会话内容故意不解析：新版 dsh 可能使用 zstd/packed 格式，恢复端只透明搬运。
    (dsh / "sessions" / "session.jsonl").write_text("packed-or-json\n", encoding="utf-8")
    return dsh


def old_snapshot(dsh: Path):
    return (dsh / "settings.yaml").read_bytes(), (dsh / "old-marker").read_bytes()


def assert_old_intact(root: Path, snapshot):
    dsh = root / ".dsh"
    assert dsh.is_dir(), "旧 .dsh 不存在"
    assert old_snapshot(dsh) == snapshot, "旧 .dsh 内容被改写"


def assert_no_pre_restore(root: Path):
    assert not list(root.glob(".dsh.pre-restore-*")), "不应留下已切走的旧目录"


def run_main(stage: Path, root: Path):
    old_argv = sys.argv
    sys.argv = [str(SCRIPT), "--stage", str(stage), "--root", str(root),
                "--workdir", "deepseek-harness", "--scope", "full"]
    try:
        return MERGE.main()
    finally:
        sys.argv = old_argv


def run_main_captured(stage: Path, root: Path, alpha: bool = False):
    old_argv = sys.argv
    sys.argv = [str(SCRIPT), "--stage", str(stage), "--root", str(root),
                "--workdir", "deepseek-harness", "--scope", "full"]
    if alpha:
        sys.argv.extend(["--alpha", "1"])
    output = io.StringIO()
    try:
        with contextlib.redirect_stdout(output):
            return MERGE.main(), output.getvalue()
    finally:
        sys.argv = old_argv


def test_validation_failure():
    with tempfile.TemporaryDirectory(prefix="dsha-restore-validation-") as td:
        base = Path(td)
        root, stage = base / "root", base / "stage"
        root.mkdir()
        make_old(root)
        # 名字像 .dsh 但只有未知残片：find_dsh_dir 会找到，结构校验应拒绝。
        (stage / ".dsh").mkdir(parents=True)
        (stage / ".dsh" / "partial.bin").write_bytes(b"truncated")
        before = old_snapshot(root / ".dsh")
        reset_state()
        result, output = run_main_captured(stage, root)
        assert result == 0
        assert "RESTORE_DSH_COMMITTED" not in output, \
            "未提交的候选不得给宿主发布 commit 标记"
        assert_old_intact(root, before)
        assert_no_pre_restore(root)
        assert stage.exists(), "校验失败后 stage 必须保留"


def test_old_rename_failure():
    with tempfile.TemporaryDirectory(prefix="dsha-restore-rename-") as td:
        base = Path(td)
        root, stage = base / "root", base / "stage"
        root.mkdir()
        make_old(root)
        make_candidate(stage)
        before = old_snapshot(root / ".dsh")
        original = MERGE.os.rename

        def fail_rename(_src, _dst):
            raise OSError("injected old-directory rename failure")

        MERGE.os.rename = fail_rename
        try:
            reset_state()
            assert MERGE.restore_dsh(str(stage), str(root)) is False
        finally:
            MERGE.os.rename = original
        assert_old_intact(root, before)
        assert_no_pre_restore(root)
        assert stage.exists(), "rename 失败后 stage 必须保留"


def test_interruption_after_old_rename():
    with tempfile.TemporaryDirectory(prefix="dsha-restore-interrupt-") as td:
        base = Path(td)
        root, stage = base / "root", base / "stage"
        root.mkdir()
        make_old(root)
        make_candidate(stage)
        before = old_snapshot(root / ".dsh")
        original = MERGE.os.rename
        calls = {"n": 0}

        def interrupt_install(src, dst):
            calls["n"] += 1
            if calls["n"] == 1:
                return original(src, dst)  # 旧 .dsh -> .pre-restore-*
            if calls["n"] == 2:
                raise KeyboardInterrupt("injected interruption before commit")
            return original(src, dst)  # 事务回滚

        MERGE.os.rename = interrupt_install
        try:
            reset_state()
            try:
                MERGE.restore_dsh(str(stage), str(root))
            except KeyboardInterrupt:
                pass
            else:
                raise AssertionError("中断必须向上抛出，不能伪装成恢复成功")
        finally:
            MERGE.os.rename = original
        assert_old_intact(root, before)
        assert_no_pre_restore(root)
        assert stage.exists(), "中断后 stage 必须保留"


def test_new_rename_failure():
    with tempfile.TemporaryDirectory(prefix="dsha-restore-commit-failure-") as td:
        base = Path(td)
        root, stage = base / "root", base / "stage"
        root.mkdir()
        make_old(root)
        make_candidate(stage)
        before = old_snapshot(root / ".dsh")
        original = MERGE.os.rename
        calls = {"n": 0}

        def fail_commit(src, dst):
            calls["n"] += 1
            if calls["n"] == 1:
                return original(src, dst)
            if calls["n"] == 2:
                raise OSError("injected candidate rename failure")
            return original(src, dst)

        MERGE.os.rename = fail_commit
        try:
            reset_state()
            assert MERGE.restore_dsh(str(stage), str(root)) is False
        finally:
            MERGE.os.rename = original
        assert_old_intact(root, before)
        assert_no_pre_restore(root)
        assert stage.exists(), "候选落位失败后 stage 必须保留"


def test_env_rename_failure():
    with tempfile.TemporaryDirectory(prefix="dsha-restore-env-") as td:
        base = Path(td)
        root, stage = base / "root", base / "stage"
        root.mkdir()
        work = root / "deepseek-harness"
        work.mkdir()
        (work / ".env").write_bytes(b"OLD-API-KEY\n")
        backup_work = stage / "old-workdir"
        backup_work.mkdir(parents=True)
        (backup_work / ".env").write_bytes(b"NEW-API-KEY\n")
        original = MERGE.os.rename
        calls = {"n": 0}

        def fail_install(src, dst):
            calls["n"] += 1
            if calls["n"] == 2:
                raise OSError("injected .env rename failure")
            return original(src, dst)

        MERGE.os.rename = fail_install
        try:
            reset_state()
            assert MERGE.restore_env(str(stage), str(root), "deepseek-harness") is False
        finally:
            MERGE.os.rename = original
        assert (work / ".env").read_bytes() == b"OLD-API-KEY\n", \
            ".env rename 失败时旧 API key 必须原样保留"
        assert stage.exists(), ".env rename 失败后 stage 必须保留"
        assert not list(work.glob(".env.dsha-env-stage-*")), \
            "失败的 .env 临时文件应清理"


def test_alpha_restore_is_transparent_and_committed_only_after_swap():
    with tempfile.TemporaryDirectory(prefix="dsha-restore-alpha-") as td:
        base = Path(td)
        root, stage = base / "root", base / "stage"
        root.mkdir()
        make_old(root)
        candidate = make_candidate(stage)
        profile = candidate / "profiles" / "web"
        profile.mkdir(parents=True)
        package = b'{"dependencies":{"old-plugin":"link:/missing"},"dsh":{"profile":{"bundles":["old-plugin"]}}}\n'
        session = b"\x28\xb5\x2f\xfdpacked-session-bytes\x00\xff"
        (profile / "package.json").write_bytes(package)
        (candidate / "sessions" / "session.jsonl.zstd").write_bytes(session)
        legacy_home_patch = b"- id: dsha-legacy-home-patch\n"
        (candidate / "cordis.patch.yml").write_bytes(legacy_home_patch)
        reset_state()
        result, output = run_main_captured(stage, root, alpha=True)
        assert result == 0
        assert "RESTORE_DSH_COMMITTED" in output, \
            "完整候选原子落位后必须向宿主发布 commit 标记"
        restored = root / ".dsh"
        assert not (restored / "profiles/web").exists(), \
            "Alpha 不得把旧 web profile 放回活动路径"
        assert not (restored / "cordis.patch.yml").exists(), \
            "Alpha 不得把旧 home patch 放回活动路径"
        quarantines = sorted(root.glob(".dsha-alpha-legacy-*"))
        assert len(quarantines) == 1, "旧 Alpha profile 必须进入带时间戳的隔离目录"
        assert (quarantines[0] / "profiles-web/package.json").read_bytes() == package, \
            "隔离目录必须保留旧 profile 原字节"
        assert (quarantines[0] / "cordis.patch.yml").read_bytes() == legacy_home_patch, \
            "隔离目录必须保留旧 home patch 原字节"
        assert (restored / "sessions/session.jsonl.zstd").read_bytes() == session, \
            "Alpha 不得猜测、改写或重压缩 session 内容"


if __name__ == "__main__":
    test_validation_failure()
    print("  ok   候选校验失败：旧 .dsh 保留，stage 保留")
    test_old_rename_failure()
    print("  ok   旧目录 rename 失败：旧 .dsh 保留，未删除")
    test_interruption_after_old_rename()
    print("  ok   落位中断：旧 .dsh 回滚，stage 保留")
    test_new_rename_failure()
    print("  ok   候选 rename 失败：旧 .dsh 回滚，stage 保留")
    test_env_rename_failure()
    print("  ok   .env rename 失败：旧 API key 保留，stage 保留")
    test_alpha_restore_is_transparent_and_committed_only_after_swap()
    print("  ok   Alpha 恢复：旧 profile 隔离，packed session 原样保留")
    print("全部通过")
