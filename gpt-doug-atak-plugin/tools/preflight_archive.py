#!/usr/bin/env python3
"""Local, fail-closed preflight of a TAK third-party source ZIP; no signing."""
import argparse
import hashlib
import json
import os
import re
import stat
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = "GPTDougGeoAtlas/"
REQUIRED = {
    "build.gradle", "settings.gradle", "gradle.properties", "gradlew",
    "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
    "app/build.gradle", "app/proguard-gradle-repackage.txt",
    "app/src/main/AndroidManifest.xml", "app/src/main/assets/plugin.xml",
    "app/src/main/res/values/strings.xml", "app/src/main/res/layout/main_layout.xml",
    "app/src/main/assets/synthetic_field_demo.geojson",
    "app/src/main/java/com/atakmap/android/gptdouggeo/plugin/GptDougGeoAtlasPlugin.java",
    "app/src/main/java/com/atakmap/android/gptdouggeo/plugin/GeoAtlasMiniMapView.java",
    "source-manifest.json",
}


class PreflightError(ValueError):
    pass


def check_archive(path: Path) -> dict:
    path = Path(path)
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        if len(infos) > 1200 or not infos:
            raise PreflightError("archive source file count invalid")
        present, total = set(), 0
        for info in infos:
            name = info.filename
            if not name.startswith(ROOT) or "\\" in name or name.startswith("/"):
                raise PreflightError("not a single-root source archive")
            parts = name.split("/")
            if ".." in parts or "." in parts or "\x00" in name:
                raise PreflightError("source path traversal detected")
            if info.is_dir():
                continue
            relative = name[len(ROOT):]
            if relative in present:
                raise PreflightError("duplicate archive path")
            present.add(relative)
            if any(part in {".git", ".gradle", "build", "__pycache__"} for part in parts):
                raise PreflightError("cache / build material in source ZIP")
            if Path(relative).name in {"local.properties", "template.local.properties", ".env"}:
                raise PreflightError("source archive contains credentials")
            if Path(relative).suffix.lower() in {".apk", ".aab", ".p12", ".jks", ".keystore", ".pfx"}:
                raise PreflightError("binary release or credential artifact present")
            if Path(relative).suffix.lower() == ".jar" and relative != "gradle/wrapper/gradle-wrapper.jar":
                raise PreflightError("non-wrapper binary JAR in source ZIP")
            if stat.S_IFMT(info.external_attr >> 16) == stat.S_IFLNK:
                raise PreflightError("symlinks are prohibited")
            if info.file_size > 12 * 1024 * 1024:
                raise PreflightError("oversized source entry")
            total += info.file_size
            if total > 45 * 1024 * 1024:
                raise PreflightError("uncompressed source ZIP exceeds budget")
        if missing := sorted(REQUIRED - present):
            raise PreflightError("required source files missing: " + ", ".join(missing))
        def read(name):
            return archive.read(ROOT + name)
        app_gradle = read("app/build.gradle").decode()
        if "com.atakmap.gradle:atak-gradle-takdev:" not in app_gradle or "def takdevVersion = '2.+'" not in app_gradle:
            raise PreflightError("TAK third-party 2.+ Gradle plugin version not set")
        if "namespace 'com.atakmap.android.gptdouggeo.plugin'" not in app_gradle:
            raise PreflightError("Android plugin namespace mismatch")
        if "id(\"com.android.application\")" not in read("build.gradle").decode():
            raise PreflightError("Android Gradle plugin not defined")
        if "[ name : 'civ'" not in app_gradle or "release {" not in app_gradle:
            raise PreflightError("assembleCivRelease cannot be inferred")
        if "rootProject.name = 'GPTDougGeoAtlas'" not in read("settings.gradle").decode():
            raise PreflightError("Gradle root project name mismatch")
        if "atakplugin.GPTDougGeoAtlas" not in read("app/proguard-gradle-repackage.txt").decode():
            raise PreflightError("ProGuard repackage descriptor missing")
        try:
            app = ET.fromstring(read("app/src/main/AndroidManifest.xml"))
            plugin = ET.fromstring(read("app/src/main/assets/plugin.xml"))
        except ET.ParseError as exc:
            raise PreflightError("malformed Android or ATAK XML") from exc
        ns = "{http://schemas.android.com/apk/res/android}"
        if app.findall("uses-permission"):
            raise PreflightError("offline demo must not request Android permissions")
        application = app.find("application")
        if application is None or application.attrib.get(ns+"allowBackup") != "false":
            raise PreflightError("app backup must be explicitly disabled")
        if not any(
            activity.attrib.get(ns+"name") == "com.atakmap.app.component" and
            any(action.attrib.get(ns+"name") == "com.atakmap.app.component"
                for intent in activity.findall("intent-filter")
                for action in intent.findall("action"))
            for activity in application.findall("activity")
        ):
            raise PreflightError("ATAK discovery activity required")
        if not any(ext.attrib.get("type") == "gov.tak.api.plugin.IPlugin" and
                   ext.attrib.get("impl") == "com.atakmap.android.gptdouggeo.plugin.GptDougGeoAtlasPlugin"
                   for ext in plugin.findall("extension")):
            raise PreflightError("ATAK IPlugin reference incorrect")
        demo = json.loads(read("app/src/main/assets/synthetic_field_demo.geojson"))
        if demo.get("type") != "FeatureCollection" or not demo.get("features"):
            raise PreflightError("synthetic GeoJSON missing")
        if any(f.get("properties", {}).get("source_kind") != "SYNTHETIC" for f in demo["features"]):
            raise PreflightError("bundled GeoJSON must contain only synthetic data")
        manifest = json.loads(read("source-manifest.json"))
        files = {f["path"]: f for f in manifest["files"]}
        if len(files) != len(manifest["files"]) or set(files) != present - {"source-manifest.json"}:
            raise PreflightError("provenance file inventory mismatch")
        for name, data in files.items():
            contents = read(name)
            if hashlib.sha256(contents).hexdigest() != data["sha256"] or len(contents) != data["bytes"]:
                raise PreflightError("provenance hash mismatch: " + name)
    return {
        "ok": True, "source_archive": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "files": len(present), "root_folder": ROOT.rstrip("/"), "target": "assembleCivRelease",
        "Gradle_TAK_plugin": "2.+", "permissionless_synthetic": True, "signed": False,
        "compiled": False, "TAK_submission": "NOT_SUBMITTED",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--json-out", type=Path)
    args = parser.parse_args()
    try:
        result = check_archive(args.archive)
    except (PreflightError, OSError, zipfile.BadZipFile, UnicodeError,
            ValueError, KeyError, json.JSONDecodeError) as exc:
        print(json.dumps({"ok": False, "error": str(exc)}), file=sys.stderr)
        return 2
    txt = json.dumps(result, indent=2)
    print(txt)
    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(txt+"\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
