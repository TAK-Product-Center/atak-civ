# GPT-Doug GeoAtlas for ATAK-CIV — Third Party Pipeline

**Release stage:** third-party **source archive preparation only**. This project has not been compiled against authenticated TAK SDK artifacts, submitted to TAK.gov, signed by TPC, or approved for operational use.

**Scope:** offline synthetic geospatial preview in the ATAK-CIV plugin pane. No location, storage, internet or TAK Server permissions; no military/sensitive position feeds; no CoT injection; no targeting, field command, robot control, covert collection or unapproved external map requests.

## Baseline

- Fork: https://github.com/sonoxo/atak-civ (source template: `plugin-examples/plugintemplate`)
- Companion: https://github.com/sonoxo/gpt-doug-llm (TAK-GEO/ATAK-Maps catalog).
- TAK.gov third-party pipeline: https://tak.gov/user_builds
- Terms: https://tak.gov/user_builds/agreement
- Official docs: https://tak.gov/documentation/resources
- Current target: ATAK 5.6.0 CIV, Gradle target `assembleCivRelease`, JDK 17, Android Gradle Plugin 8.13.0.
- `atak-gradle-takdev` dependency: this source-package defaults to **2.+** as directed by the TAK.gov Third Party Pipeline instructions provided with this task for ATAK >=4.2; the fork's latest plugin template currently declares **3.+**. **Authenticated release-build validation is mandatory before uploading.** Do not assume compatibility is proven.

## Build source ZIP without credentials

From the root of this repository:

```bash
python3 gpt-doug-atak-plugin/tools/build_archive.py --output gpt-doug-atak-plugin/dist/GPTDougGeoAtlas.zip --force
python3 gpt-doug-atak-plugin/tools/preflight_archive.py gpt-doug-atak-plugin/dist/GPTDougGeoAtlas.zip --json-out gpt-doug-atak-plugin/dist/preflight.json
```

ZIP structure:

```text
GPTDougGeoAtlas.zip
└── GPTDougGeoAtlas/
    ├── build.gradle
    ├── settings.gradle
    ├── gradle.properties
    ├── gradlew and gradlew.bat
    ├── gradle/wrapper/gradle-wrapper.jar and properties
    ├── app/build.gradle
    ├── app/src/main/AndroidManifest.xml
    ├── app/src/main/assets/plugin.xml
    ├── app/src/main/assets/synthetic_field_demo.geojson
    ├── app/src/main/java/com/atakmap/android/gptdouggeo/plugin/
    │   ├── GptDougGeoAtlasPlugin.java
    │   └── GeoAtlasMiniMapView.java
    ├── source-manifest.json
    └── ... other official Gradle resources
```

No application or Maven repository credentials are included. The official Gradle wrapper JAR from the fork is reused and is subject to the upstream project's licenses.

## Authorized developer build and upload

1. Review the [TAK.gov pipeline participation agreement](https://tak.gov/user_builds/agreement), EULA, package content and organizational distribution permissions.
2. Use **your own authorized USG TAK artifact repository account**, Android SDK, JDK 17, and Gradle build environment. The sample `takrepo.user`/`takrepo.password` credentials in TAK.gov's docs are placeholders, not access we can invent.
3. Extract the archive. Create `GPTDougGeoAtlas/local.properties` **locally only**, with restrictive file permissions (`chmod 600`) containing `sdk.dir`, `takrepo.url`, `takrepo.user`, `takrepo.password` and `takdev.plugin=.` as documented by TPC. Never commit or repackage this file.
4. From the unpacked project's root, run `./gradlew --no-daemon -Ptakrepo.force=true assembleCivRelease`. This checks the actual private Maven dependencies and compiles against authorized SDKs; a local `--offline` check cannot prove this.
5. Inspect the Gradle build output and verify the correct plugin/version and release APK. If plugin `2.+` is incompatible with current ATAK 5.6 template, resolve the discrepancy with official TPC documentation/support before submission; do not falsify build success or silently substitute plugin versions.
6. Upload **source ZIP** (not source checkout folder or a prebuilt APK) to the authenticated TAK.gov Third Party Pipeline. Wait for the pipeline's signed APK and acceptance reports; signing is not guaranteed.
7. Perform security review, install and test only on authorized ATAK-CIV devices/environments, and verify behavior, version compatibility, rights and scope.

**Security:** Commands, build logs and artifacts may contain sensitive repo credentials. Never paste plaintext passwords into PRs, Actions logs or ChatGPT. Do not store credentials in public CI. The repository's CI intentionally performs **offline packaging/static validation only**, not a protected Maven build.

## Implemented and future acceptance gates

| Phase | Current result | Exit condition |
| --- | --- | --- |
| 1. Format verification | Implemented | One source-root ZIP, Gradle wrapper, discovery manifest, TPC plugin and ProGuard package checked |
| 2. Offline civilian plugin | Source implemented | Plugin lifecycle, no permissions, synthetic map preview and release APK compiled on authorized SDK |
| 3. TPC-authenticated build | Blocked by external access | Evidence of successful `assembleCivRelease` using an authorized account |
| 4. TPC signing | Not submitted | User accepts terms, uploads ZIP and obtains signed pipeline artifact |
| 5. Civilian field validation | Not authorized | Device tests, vendor-license review, test evidence, safety/security assessment |
| 6. Future TAK maps integration | Planned, not built | Operator-approved imagery support, per-provider legal checks, additional tested UI |
| 7. Future server interoperability | Not implemented | Separate security-reviewed RFC, authenticated enrollment and explicit user authorization |

## Copyright and provenance

Built as a narrowly scoped extension of the public ATAK-CIV plugin example. Honor upstream `LICENSE.md` and `THIRDPARTY.md`. ATAK SDK access does not imply operational approval, support, redistribution rights or the right to import restricted source code. Use only synthetic or authorized data.
