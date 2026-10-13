# GPT-Doug GeoAtlas — TAK Third Party Source Submission

A non-operational ATAK-CIV plugin derived from the official `plugintemplate` in `sonoxo/atak-civ`. This plugin adds a local-only pane showing **synthetic** geospatial points as a simple 2D schematic. It neither connects to TAK Server nor requests any Android location or network permission.

**Gradle build target:** `./gradlew assembleCivRelease`, assuming required authenticated TAK SDK artifacts are available. The source archive targets ATAK 5.6.0 CIV and uses the TPC pipeline-documented `atak-gradle-takdev:2.+`; official template 5.6.0 currently uses 3.+, so validate version compatibility before submitting. Signed APK and operational certification are not included.

**Credentials:** Never put a `local.properties` file or TAK Artifactory password in the archive. Set authorized build credentials on the developer machine according to https://tak.gov/user_builds (access may be restricted).

**Discovery:** `app/src/main/AndroidManifest.xml` contains `com.atakmap.app.component`; `app/src/main/assets/plugin.xml` identifies `GptDougGeoAtlasPlugin`; ProGuard uses `atakplugin.GPTDougGeoAtlas`.

**Provenance:** `source-manifest.json` contains source file hashes and known build caveats. The binary Gradle wrapper originates from the upstream public template. This is not an official signed TAK release.

**Further review:** https://tak.gov/user_builds/agreement and https://github.com/sonoxo/atak-civ .
