#!/usr/bin/env python3
"""只读检查标准 APK 的宿主 JNI 与离线环境 ELF 的 arm64 / 16 KB 段对齐。"""
import argparse
import io
import json
import struct
import tarfile
import zipfile


def inspect_elf(stream):
    header = stream.read(64)
    if len(header) < 64 or header[:6] != b"\x7fELF\x02\x01":
        return None
    kind, machine = struct.unpack_from("<HH", header, 16)
    if kind not in (2, 3):
        return None
    offset = struct.unpack_from("<Q", header, 32)[0]
    size, count = struct.unpack_from("<HH", header, 54)
    if not count or size < 56 or offset + size * count > 65536:
        raise ValueError("无法读取 ELF program headers")
    data = header + stream.read(max(0, offset + size * count - 64))
    aligned = True
    loads, relros = [], []
    for index in range(count):
        base = offset + index * size
        segment_type = struct.unpack_from("<I", data, base)[0]
        address = struct.unpack_from("<Q", data, base + 16)[0]
        memory_size = struct.unpack_from("<Q", data, base + 40)[0]
        if segment_type == 0x6474e552:
            relros.append((address, address + memory_size))
        if segment_type != 1:
            continue
        loads.append((address, address + memory_size))
        file_offset, address = struct.unpack_from("<QQ", data, base + 8)
        alignment = struct.unpack_from("<Q", data, base + 48)[0]
        aligned &= alignment >= 16384 and (file_offset - address) % 16384 == 0
    mapped = True
    for page in (4096, 16384):
        intervals = sorted((start // page * page, (end + page - 1) // page * page) for start, end in loads)
        for start, end in relros:
            cursor, limit = start // page * page, (end + page - 1) // page * page
            for left, right in intervals:
                if left <= cursor < right: cursor = right
            mapped &= cursor >= limit
    return dict(machine=machine, aligned_16k=aligned, relro_mapped=mapped)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("--report")
    args = parser.parse_args()
    counts = dict(host=0, rootfs=0, python=0, wheels=0, python_support=0, pnpm=0)
    failures, foreign, relro_failures = [], [], []

    def check(stream, name, group):
        elf = inspect_elf(stream)
        if not elf:
            return
        if elf["machine"] != 183:
            foreign.append(name)
            return
        counts[group] += 1
        if not elf["aligned_16k"]:
            failures.append(name)
        if not elf["relro_mapped"]:
            relro_failures.append(name)

    with zipfile.ZipFile(args.apk) as apk:
        for item in apk.infolist():
            if item.filename.startswith("lib/"):
                with apk.open(item) as stream:
                    check(stream, item.filename, "host")
        for asset, group in (("offline-rootfs.bin", "rootfs"), ("glibc-python.bin", "python"),
                             ("adb-wheels.bin", "wheels"), ("python-support.bin", "python_support"),
                             ("pnpm-runtime.bin", "pnpm")):
            with apk.open("assets/" + asset) as stream, tarfile.open(fileobj=stream, mode="r|gz") as archive:
                for item in archive:
                    if not item.isfile():
                        continue
                    if group == "wheels" and item.name.endswith(".whl"):
                        with zipfile.ZipFile(io.BytesIO(archive.extractfile(item).read())) as wheel:
                            for entry in wheel.infolist():
                                if entry.filename.endswith(".so"):
                                    with wheel.open(entry) as binary:
                                        check(binary, item.name + "/" + entry.filename, group)
                    else:
                        check(archive.extractfile(item), asset + "/" + item.name, group)
    report = dict(arm64_elf_counts=counts, unaligned_16k=failures, other_architectures=foreign,
                  relro_outside_load=relro_failures,
                  limitation="静态 ELF 检查不能替代真实 16 KB 内核上的 proot / dsh / ADB 运行验证")
    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.report:
        from pathlib import Path
        Path(args.report).write_text(text, encoding="utf-8")
    print(text)
    return bool(failures or foreign or relro_failures)


if __name__ == "__main__":
    raise SystemExit(main())
