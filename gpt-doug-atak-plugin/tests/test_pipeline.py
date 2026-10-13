"""Offline tests for a sanitized source ZIP; does not use TAK artifact credentials."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

BASE = Path(__file__).resolve().parents[1]
def import_python(path, module_name):
    spec = importlib.util.spec_from_file_location(module_name, str(path))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

packager = import_python(BASE / "tools/build_archive.py", "tak_archive_packager")
preflight = import_python(BASE / "tools/preflight_archive.py", "tak_archive_preflight")


class PipelinePackageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.archive = self.dir / "GPTDougGeoAtlas.zip"
        self.summary = packager.make_archive(self.archive)

    def test_archive_passes_preflight(self):
        result = preflight.check_archive(self.archive)
        self.assertTrue(result["ok"])
        self.assertEqual(result["root_folder"], "GPTDougGeoAtlas")
        self.assertEqual(result["target"], "assembleCivRelease")
        self.assertEqual(result["Gradle_TAK_plugin"], "2.+")
        self.assertEqual(result["signed"], False)
        self.assertEqual(result["compiled"], False)
        self.assertGreater(result["files"], 12)

    def test_build_is_bitwise_reproducible(self):
        other = self.dir / "second.zip"
        packager.make_archive(other)
        self.assertEqual(self.archive.read_bytes(), other.read_bytes())

    def test_no_secrets_or_template_classes(self):
        with zipfile.ZipFile(self.archive) as z:
            names = z.namelist()
            self.assertTrue(all(x.startswith("GPTDougGeoAtlas/") for x in names))
            self.assertFalse(any("local.properties" in name for name in names))
            self.assertFalse(any(name.endswith(".apk") or name.endswith(".keystore")
                                 for name in names))
            self.assertFalse(any("/plugintemplate/plugin/" in name for name in names))
            self.assertIn("GPTDougGeoAtlas/gradle/wrapper/gradle-wrapper.jar", names)
            manifest = z.read("GPTDougGeoAtlas/app/src/main/AndroidManifest.xml").decode()
            self.assertNotIn('<uses-permission', manifest)
            self.assertIn("com.atakmap.app.component", manifest)

    def test_reject_archive_with_unknown_second_root(self):
        evil = self.dir / "bad-root.zip"
        with zipfile.ZipFile(self.archive) as zin, zipfile.ZipFile(evil, "w") as zout:
            for item in zin.infolist():
                zout.writestr(item, zin.read(item.filename))
            zout.writestr("another-folder/injected.txt", "unsafe")
        with self.assertRaises(preflight.PreflightError):
            preflight.check_archive(evil)

    def test_reject_archived_secrets(self):
        unsafe = self.dir / "secrets.zip"
        with zipfile.ZipFile(self.archive) as zin, zipfile.ZipFile(unsafe, "w") as zout:
            for item in zin.infolist():
                zout.writestr(item, zin.read(item.filename))
            zout.writestr("GPTDougGeoAtlas/local.properties", "takrepo.password=do-not-log")
        with self.assertRaises(preflight.PreflightError):
            preflight.check_archive(unsafe)

    def test_reject_mutated_source_digest(self):
        tampered = self.dir / "tampered.zip"
        with zipfile.ZipFile(self.archive) as zin, zipfile.ZipFile(tampered, "w") as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                if item.filename.endswith("synthetic_field_demo.geojson"):
                    data = data.replace(b"SYNTHETIC", b"NOT_VERIFIED")
                zout.writestr(item, data)
        with self.assertRaises(preflight.PreflightError):
            preflight.check_archive(tampered)

    def test_submission_contains_only_synthetic_points(self):
        with zipfile.ZipFile(self.archive) as z:
            path = "GPTDougGeoAtlas/app/src/main/assets/synthetic_field_demo.geojson"
            data = json.loads(z.read(path))
            self.assertEqual(len(data["features"]), 3)
            self.assertEqual({x["properties"]["source_kind"] for x in data["features"]}, {"SYNTHETIC"})

    def test_do_not_overwrite_existing_archive_without_force(self):
        with self.assertRaises(packager.PackageError):
            packager.make_archive(self.archive)


if __name__ == "__main__":
    unittest.main()
