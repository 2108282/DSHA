#!/usr/bin/env python3
"""生成 Android 11+ 的分发资产；原始 rootfs 不改动，运行环境版本保持不变。"""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import tarfile


def excluded_platform(values, current):
    if isinstance(values, str):
        values = [values]
    if not isinstance(values, list) or not values:
        return False
    positives = [v for v in values if isinstance(v, str) and not v.startswith("!")]
    return "!" + current in values or bool(positives and current not in positives and "any" not in positives)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def omit(name, foreign):
    parts = name.split("/")
    if ".npm" in parts:
        return "npm-cache"
    if "prebuilds" in parts:
        index = parts.index("prebuilds") + 1
        if index < len(parts) and parts[index].startswith(("win32-", "darwin-", "linux-x64", "linux-ia32")):
            return "foreign-prebuild"
    if name == "data/data/com.termux" or name.startswith("data/data/com.termux/") \
            or name in ("usr/bin/python3.14", "usr/bin/python3", "bin/python3.14", "bin/python3"):
        return "replaced-termux-python"
    for prefix in foreign:
        if name == prefix or name.startswith(prefix + "/"):
            return "foreign-platform"
    return ""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    source, output = Path(args.source).resolve(), Path(args.output).resolve()
    if source == output or source in output.parents:
        raise ValueError("生成目录不能放在原始资产目录内")
    output.mkdir(parents=True, exist_ok=True)
    rootfs = source / "offline-rootfs.bin"
    excluded = {"offline-rootfs.bin", "runtime-python/python-runtime.tgz",
                "glibc-python.tar.gz", "adb-wheels.tar.gz"}
    for path in source.rglob("*"):
        relative = path.relative_to(source)
        if not path.is_file() or "__pycache__" in relative.parts or path.suffix == ".pyc" \
                or relative.parts[0] == "runtime-python" or relative.as_posix() in excluded:
            continue
        destination = output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
    # 清理旧构建生成的两个兼容版资产，不触碰输入目录。
    for name in ("python3.14", "python-runtime.tgz"):
        (output / "runtime-python" / name).unlink(missing_ok=True)
    # 用 bin 扩展名避免 aapt 展开 gzip；读取端按 magic 判断格式。
    for name in ("glibc-python", "adb-wheels"):
        src = source / (name + ".tar.gz")
        if not src.is_file():
            src = source / (name + ".bin")
        if src.is_file():
            shutil.copyfile(src, output / (name + ".bin"))
    if not rootfs.is_file():
        (output / "offline-rootfs.bin").unlink(missing_ok=True)
        (output.parent / "standard-assets-report.json").unlink(missing_ok=True)
        print("未提供离线 rootfs，生成精简资产")
        return
    signature = {"source_sha256": sha256(rootfs), "recipe_sha256": sha256(Path(__file__))}
    report_path = output.parent / "standard-assets-report.json"
    optimized = output / "offline-rootfs.bin"
    if report_path.is_file() and optimized.is_file():
        try:
            cached = json.loads(report_path.read_text(encoding="utf-8"))
            if cached.get("inputs") == signature and cached.get("output_sha256") == sha256(optimized):
                print("rootfs 内容未变化，复用已校验的减重资产")
                return
        except (ValueError, OSError):
            pass
    foreign = set()
    with tarfile.open(rootfs, "r|gz") as archive:
        for item in archive:
            name = item.name.removeprefix("./").rstrip("/")
            if item.isfile() and name.endswith("/package.json") and item.size < 1024 * 1024 \
                    and "/node_modules/" in name and ".npm" not in name.split("/"):
                try:
                    pkg = json.load(archive.extractfile(item))
                    if excluded_platform(pkg.get("os"), "linux") or excluded_platform(pkg.get("cpu"), "arm64") \
                            or excluded_platform(pkg.get("libc"), "glibc"):
                        foreign.add(name.rsplit("/", 1)[0])
                except (ValueError, UnicodeError, AttributeError):
                    pass
    removed = collections.Counter()
    kept = hashlib.sha256()
    temp = output / "offline-rootfs.bin.tmp"
    with temp.open("wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0,
                                             compresslevel=9) as compressed:
        with tarfile.open(rootfs, "r|gz") as archive, tarfile.open(
                fileobj=compressed, mode="w|", format=tarfile.PAX_FORMAT) as target:
            for item in archive:
                name = item.name.removeprefix("./").rstrip("/")
                reason = omit(name, foreign)
                if reason:
                    removed[reason] += item.size
                    continue
                # 删除外平台包时同目录的符号链接也必须一起去掉。
                if item.issym():
                    import posixpath
                    resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), item.linkname))
                    if omit(resolved, foreign):
                        removed["obsolete-link"] += 0
                        continue
                kept.update((name + "\n").encode())
                target.addfile(item, archive.extractfile(item) if item.isfile() else None)
    temp.replace(output / "offline-rootfs.bin")
    report = dict(original_bytes=rootfs.stat().st_size,
                  optimized_bytes=(output / "offline-rootfs.bin").stat().st_size,
                  removed_unpacked_bytes=dict(removed), foreign_packages=sorted(foreign),
                  kept_paths_sha256=kept.hexdigest(),
                  inputs=signature, output_sha256=sha256(optimized),
                  note="仅新安装资产减重；不改变环境版本，不触发旧用户 rootfs 清空重装")
    report_path.write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print("rootfs: %.2f -> %.2f MiB" % (report["original_bytes"] / 1048576,
                                     report["optimized_bytes"] / 1048576))
    print("移除解压内容:", {key: round(value / 1048576, 2) for key, value in removed.items()})


if __name__ == "__main__":
    main()
