#!/usr/bin/env python3
"""少量插件回归检查：仅操作临时目录，不调用网络/pnpm，不碰真实用户数据。"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ASSET = Path(__file__).resolve().parents[1] / "app/src/main/assets/plugin-manager.py"


class PluginManagerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {"DSHA_TEST_ROOT": str(self.root), "DSH_HOME": "/root/.dsh"})
        self.env.start()
        spec = importlib.util.spec_from_file_location("manager", ASSET)
        self.manager = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.manager)
        self.home = self.root / "root/.dsh"
        self.home.mkdir(parents=True)
        self.out = io.StringIO()
        self.capture = contextlib.redirect_stdout(self.out)
        self.capture.__enter__()

    def tearDown(self):
        self.capture.__exit__(None, None, None)
        self.env.stop()
        self.temp.cleanup()

    def package(self, path, name="dsh-demo", version="1"):
        path.mkdir(parents=True, exist_ok=True)
        (path / "package.json").write_text(json.dumps({
            "name": name, "version": version, "main": "index.js",
            "dsh": {"bundle": {"patch": "cordis.patch.yml"}}}), encoding="utf-8")
        (path / "index.js").write_text("export default {};", encoding="utf-8")
        (path / "cordis.patch.yml").write_text("[]", encoding="utf-8")
        return path

    def marker(self, name):
        marker = Path(self.manager.builtin.marker_path(name))
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.touch()

    def test_scoped_multi_export_import_preserves_disabled_state_and_bytes(self):
        names = ["@sample/demo", "dsh-extra"]
        for name in names:
            self.package(self.home / "plugin-src" / name, name)
            self.marker(name)
        archive = self.home / "out.tar.gz"
        self.manager.cmd_export(json.dumps(names), str(archive))
        self.assertEqual(0, self.manager.cmd_import(str(archive)))
        manifest = self.manager.builtin.read_manifest()
        self.assertEqual(list(self.manager.builtin.OFFICIAL_BUNDLES), manifest["dsh"]["profile"]["bundles"])
        for name in names:
            self.assertEqual("link:/root/.dsh/plugin-src/" + name, manifest["dependencies"][name])
            self.assertEqual("export default {};", (self.home / "plugin-src" / name / "index.js").read_text())
        self.assertFalse(list((self.home / "plugin-src").glob(".install-*")))

    def test_bad_update_keeps_existing_plugin_and_manifest(self):
        name = "dsh-demo"
        self.package(self.home / "plugin-src" / name, name, "old")
        original = {"name": "existing", "dependencies": {}, "dsh": {"profile": {"bundles": []}}}
        self.manager.builtin.write_manifest(original)
        incoming = self.package(self.root / "incoming", name, "new")
        with patch.object(self.manager.os, "symlink", side_effect=OSError("link unavailable")):
            with self.assertRaises(OSError):
                self.manager.register_plugin(str(incoming), "")
        self.assertEqual(original, self.manager.builtin.read_manifest())
        self.assertEqual("old", json.loads((self.home / "plugin-src" / name / "package.json").read_text())["version"])

    def test_delete_scoped_plugin_only_and_protect_builtins(self):
        name = "@sample/demo"
        self.package(self.home / "plugin-src" / name, name)
        other = self.package(self.home / "plugin-src/dsh-other", "dsh-other")
        self.marker(name)
        config = self.home / "settings.json"
        config.write_text('{"keep":true}')
        self.manager.builtin.write_manifest({
            "dependencies": {name: "link:/root/.dsh/plugin-src/" + name, "dsh-other": "1"},
            "dsh": {"profile": {"bundles": [name, "dsh-other"]}}})
        sources = self.home / "plugin-sources.json"
        sources.write_text(json.dumps({name: "https://example.org/plugin.tgz"}))
        original = self.manager.builtin.read_manifest()
        with patch.object(self.manager.builtin, "write_manifest", side_effect=OSError("disk unavailable")):
            with self.assertRaises(OSError):
                self.manager.cmd_delete(name)
        self.assertTrue((self.home / "plugin-src" / name / "index.js").is_file())
        self.assertTrue(Path(self.manager.builtin.marker_path(name)).exists())
        self.assertEqual(original, self.manager.builtin.read_manifest())
        self.assertIn(name, json.loads(sources.read_text()))
        self.manager.cmd_delete(name)
        self.assertFalse((self.home / "plugin-src" / name).exists())
        self.assertFalse(Path(self.manager.builtin.marker_path(name)).exists())
        self.assertTrue(other.is_dir())
        self.assertNotIn(name, json.loads(sources.read_text()))
        self.assertEqual('{"keep":true}', config.read_text())
        self.assertEqual(["dsh-other"], self.manager.builtin.read_manifest()["dsh"]["profile"]["bundles"])
        for protected in ["@deepseek-ai/dsh-base", "dsh-web-mobile", "../settings.json"]:
            with self.assertRaises(ValueError):
                self.manager.cmd_delete(protected)

    def test_subdirectory_does_not_install_neighbor_plugins(self):
        staging = self.root / "staging"
        selected = self.package(staging / "repo-main/packages/one", "dsh-one")
        self.package(staging / "repo-main/packages/two", "dsh-two")
        self.assertEqual([str(selected)], self.manager.find_plugin_roots(str(staging), "packages/one"))
        with self.assertRaises(ValueError):
            self.manager.find_plugin_roots(str(staging), "packages/missing")

    def test_zip_tar_and_absolute_or_traversal_entries(self):
        for filename in ("../escape", "/root/escape", "C:/escape"):
            archive = self.root / "bad.zip"
            with zipfile.ZipFile(archive, "w") as z:
                z.writestr(filename, "bad")
            with self.assertRaises(ValueError):
                self.manager.extract_archive(str(archive), str(self.root / "unzip"))
        archive = self.root / "good.tar"
        with tarfile.open(archive, "w") as tar:
            entry = tarfile.TarInfo("package/index.js")
            entry.size, entry.mode = 2, 0o755
            tar.addfile(entry, io.BytesIO(b"ok"))
        self.manager.extract_archive(str(archive), str(self.root / "untar"))
        self.assertEqual(b"ok", (self.root / "untar/package/index.js").read_bytes())

    def test_invalid_bundle_and_missing_build_are_not_reported_as_success(self):
        incoming = self.package(self.root / "bad")
        (incoming / "index.js").unlink()
        with self.assertRaisesRegex(ValueError, "入口"):
            self.manager.plugin_package(str(incoming))
        manifest = incoming / "package.json"
        manifest.write_text(json.dumps({"name": "dsh-fake"}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "dsh.bundle.patch"):
            self.manager.plugin_package(str(incoming))
        # 一个包中一好一坏：结果必须是 partial，不能因为出现一个成功就吞掉失败。
        good = self.package(self.root / "good", "dsh-good")
        bad = self.package(self.root / "bad-build", "dsh-bad")
        (bad / "index.js").unlink()
        self.marker("dsh-good")
        archive = self.root / "mixed.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            tar.add(good, arcname="plugins/good")
            tar.add(bad, arcname="plugins/bad")
        self.assertEqual(1, self.manager.cmd_import(str(archive)))
        last = json.loads(self.out.getvalue().strip().split("\n")[-1].removeprefix("PLUGIN_RESULT: "))
        self.assertEqual("partial", last["status"])
        self.assertEqual(["dsh-good"], last["installed"])

    def test_slash_branch_resolves_requested_commit_and_subdirectory(self):
        responses = []
        def response(url):
            responses.append(url)
            if url.endswith("feature%2Fandroid"):
                return io.BytesIO(json.dumps({"sha": "abc123"}).encode())
            raise self.manager.urllib.error.HTTPError(url, 404, "missing", {}, None)
        with patch.object(self.manager, "open_url", side_effect=response), \
                patch.object(self.manager, "cmd_download", return_value=0) as download:
            self.manager.cmd_github("owner", "repo", "feature/android/packages/demo")
            args = download.call_args.args
            self.assertEqual("https://codeload.github.com/owner/repo/tar.gz/abc123", args[0])
            self.assertEqual("packages/demo", args[1])

    def test_github_zip_uses_official_download_and_preserves_ref(self):
        rewrite = self.manager.archive_download_url
        self.assertEqual("https://codeload.github.com/Minglink/dsh-infinite-gen-3/zip/refs/heads/master",
                         rewrite("https://github.com/Minglink/dsh-infinite-gen-3/archive/refs/heads/master.zip"))
        self.assertEqual("https://codeload.github.com/o/r/tar.gz/refs/tags/v1.0",
                         rewrite("https://github.com/o/r/archive/refs/tags/v1.0.tar.gz"))
        external = "https://example.org/plugin.zip"
        self.assertEqual(external, rewrite(external))

    def test_npm_packs_only_requested_package_and_rejects_options(self):
        def pack(args, **kwargs):
            destination = Path(args[args.index("--pack-destination") + 1])
            archive = destination / "sample-demo-1.0.0.tgz"
            archive.write_bytes(b"test")
            self.assertEqual(["--", "@sample/demo@1.0.0"], args[-2:])
            self.assertIn("--ignore-scripts", args)
            return self.manager.subprocess.CompletedProcess(args, 0, '[{"filename":"sample-demo-1.0.0.tgz"}]', '')
        with patch.object(self.manager.shutil, "which", return_value="/usr/bin/npm"), \
                patch.object(self.manager.subprocess, "run", side_effect=pack), \
                patch.object(self.manager, "cmd_import", return_value=0) as install:
            self.assertEqual(0, self.manager.cmd_npm("@sample/demo@1.0.0"))
            self.assertEqual("npm:@sample/demo@1.0.0", install.call_args.kwargs["source"])
        for invalid in ["--global", "../package", "https://example.com/x", "demo;rm -rf /", ""]:
            with self.assertRaises(ValueError):
                self.manager.cmd_npm(invalid)


if __name__ == "__main__":
    unittest.main()
