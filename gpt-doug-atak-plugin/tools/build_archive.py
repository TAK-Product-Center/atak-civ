#!/usr/bin/env python3
"""Build a reviewable single-root ATAK-CIV third-party pipeline source ZIP.

Uses the official in-repository plugin template and a narrowly scoped
GPT-Doug civilian/offline UI overlay. Never embeds credentials, precompiled
SDK blobs, APKs or customer/user location data.

Does not invoke Gradle or TAK.gov signing. A verified Gradle build requires
the developer's own authorized toolchain/artifact access.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE_ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "plugin-examples" / "plugintemplate"
OVERLAY = PACKAGE_ROOT / "overlay"
NAME = "GPTDougGeoAtlas"
MAX_FILE_BYTES = 12 * 1024 * 1024
SKIP_NAMES = {
    ".git", ".gradle", ".idea", ".DS_Store", "__pycache__", "dist",
    "build", "local.properties", "template.local.properties",
    "gradle.properties.local", ".env",
}
FORBIDDEN_SUFFIXES = {".apk", ".aab", ".aab", ".keystore", ".jks", ".p12", ".pfx"}
ALLOWED_WRAPPER_JAR = "gradle/wrapper/gradle-wrapper.jar"


class PackageError(ValueError):
    pass


def _check_file(root: Path, path: Path) -> None:
    if path.is_symlink():
        raise PackageError(f"symbolic link in source archive: {path}")
    if not path.is_file():
        raise PackageError(f"not a regular file: {path}")
    relative = path.relative_to(root).as_posix()
    if path.stat().st_size > MAX_FILE_BYTES:
        raise PackageError(f"oversized source file: {relative}")
    if path.suffix.lower() in FORBIDDEN_SUFFIXES:
        raise PackageError(f"binary release/credential material prohibited: {relative}")
    if path.suffix.lower() == ".jar" and relative != ALLOWED_WRAPPER_JAR:
        raise PackageError(f"non-wrapper JAR cannot be archived: {relative}")


def _copy_template(dest: Path) -> None:
    if not (TEMPLATE / "app/build.gradle").is_file() or not (TEMPLATE / "gradlew").is_file():
        raise PackageError(f"expected official ATAK plugin template not found: {TEMPLATE}")
    if not OVERLAY.is_dir():
        raise PackageError("plugin source overlay is missing")
    if TEMPLATE.is_symlink() or OVERLAY.is_symlink():
        raise PackageError("template and overlay must be real directories")

    def ignore(folder: str, names: list[str]) -> list[str]:
        ignored = []
        for name in names:
            file = Path(folder) / name
            if name in SKIP_NAMES or name.startswith(".env") or (file.is_file() and file.suffix.lower() in FORBIDDEN_SUFFIXES):
                ignored.append(name)
            elif file.is_symlink():
                raise PackageError("source template symlink prohibited")
        return ignored

    shutil.copytree(TEMPLATE, dest, ignore=ignore)
    stale = dest / "app/src/main/java/com/atakmap/android/plugintemplate"
    if stale.exists():
        shutil.rmtree(stale)
    stale_test = dest / "app/src/test"
    if stale_test.exists():
        shutil.rmtree(stale_test)
    # Overlay replaces only explicitly reviewed source/resource files.
    for source in sorted(OVERLAY.rglob("*")):
        if source.is_symlink():
            raise PackageError("overlay symlink prohibited")
        if not source.is_file():
            continue
        relative = source.relative_to(OVERLAY)
        if any(item in SKIP_NAMES for item in relative.parts):
            raise PackageError(f"forbidden overlay file: {relative}")
        target = dest / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)

    # The provided TAK.gov pipeline instructions require 2.+ for >=4.2;
    # current upstream 5.6.0 plugin template advertises 3.+.
    # This explicit substitution is isolated to the *submission archive*.
    gradle_file = dest / "app/build.gradle"
    gradle = gradle_file.read_text(encoding="utf-8")
    original_version = "def takdevVersion = '3.+'"
    original_namespace = "namespace 'com.atakmap.android.plugintemplate.plugin'"
    if gradle.count(original_version) != 1 or gradle.count(original_namespace) != 1:
        raise PackageError("official template changed; review Gradle plugin and namespace manually")
    gradle = gradle.replace(original_version, "def takdevVersion = '2.+'")
    gradle = gradle.replace(original_namespace, "namespace 'com.atakmap.android.gptdouggeo.plugin'")
    gradle_file.write_text(gradle, encoding="utf-8")
    settings = dest / "settings.gradle"
    settings.write_text(settings.read_text(encoding="utf-8") + "\nrootProject.name = 'GPTDougGeoAtlas'\n", encoding="utf-8")
    (dest / "README.md").write_text((PACKAGE_ROOT / "SUBMISSION_README.md").read_text(encoding="utf-8"), encoding="utf-8")
    (dest / "app/proguard-gradle-repackage.txt").write_text("-repackageclasses atakplugin.GPTDougGeoAtlas\n", encoding="utf-8")
    (dest / "gradlew").chmod((dest / "gradlew").stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def _build_files(stage: Path) -> tuple[list[Path], list[dict]]:
    files = []
    manifest = []
    for path in sorted(stage.rglob("*")):
        if path.is_symlink():
            raise PackageError(f"unsafe symlink: {path}")
        if not path.is_file():
            continue
        relative = path.relative_to(stage).as_posix()
        if any(part in SKIP_NAMES for part in Path(relative).parts):
            raise PackageError(f"excluded file found in stage: {relative}")
        _check_file(stage, path)
        raw = path.read_bytes()
        files.append(path)
        manifest.append({"path": relative, "sha256": hashlib.sha256(raw).hexdigest(), "bytes": len(raw)})
    return files, manifest


def make_archive(output: Path, *, force: bool = False) -> dict:
    output = Path(output).expanduser().resolve()
    if output.exists() and not force:
        raise PackageError(f"archive exists: {output} (use --force)")
    with tempfile.TemporaryDirectory(prefix="atak-pipeline-") as temp:
        stage = Path(temp) / NAME
        _copy_template(stage)
        files, manifest = _build_files(stage)
        archive_manifest = {
            "schema": "xunia.atak.thirdparty.source-archive.v1",
            "archive_root": NAME,
            "source_template": "sonoxo/atak-civ/plugin-examples/plugintemplate",
            "atak_civ_target": "5.6.0",
            "takdev_dependency": "2.+",
            "upstream_template_expected_dependency": "3.+",
            "discrepancy_note": "2.+ follows supplied pipeline guidance; full authenticated Gradle compilation not yet verified",
            "target": "assembleCivRelease",
            "certification": "NOT_BUILT_OR_SIGNED",
            "network_permissions": "NONE_DECLARED_BY_PLUGIN",
            "operator_review_required": True,
            "files": manifest,
        }
        manifest_path = stage / "source-manifest.json"
        manifest_path.write_text(json.dumps(archive_manifest, indent=2) + "\n", encoding="utf-8")
        files.append(manifest_path)
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=7) as zipf:
            for file in sorted(files):
                arcname = NAME + "/" + file.relative_to(stage).as_posix()
                entry = zipfile.ZipInfo(arcname, date_time=(2026, 1, 1, 0, 0, 0))
                entry.create_system = 3
                mode = stat.S_IFREG | (0o755 if file.name == "gradlew" else 0o644)
                entry.external_attr = mode << 16
                entry.compress_type = zipfile.ZIP_DEFLATED
                zipf.writestr(entry, file.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
        archive_sha = hashlib.sha256(output.read_bytes()).hexdigest()
        return {
            "archive": str(output),
            "sha256": archive_sha,
            "root_folder": NAME,
            "source_files": len(files),
            "size_bytes": output.stat().st_size,
            "gradle_target": "assembleCivRelease",
            "takdev": "2.+",
            "compiled": False, "signed": False, "tak_server_connected": False,
        }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=PACKAGE_ROOT / "dist" / (NAME + ".zip"))
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = make_archive(args.output, force=args.force)
    except (PackageError, OSError, UnicodeError) as exc:
        parser.exit(2, "archive build failed: " + str(exc) + "\n")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
