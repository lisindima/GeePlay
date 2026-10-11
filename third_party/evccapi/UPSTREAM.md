# EVCC API 1.2 upstream

The Kotlin and AIDL sources in `evccapi-contract` and `evccapi-client` are
copied without changes from the public EVCC API Demo 1.2.0 source archive:

- Source: <https://evcc.coauto.cc/evccapi/downloads/evccapi-demo-source-1.2.0.zip>
- Archive SHA-256: `d009a806587ba766e2408d00c83acc960e63729c9123507eaf24138350cf6454`
- Protocol: EvccApi 1.2 (`evccapi-1.2`)
- Minimum complete EVCC version: 3.2.17
- License: Apache License 2.0; see `LICENSE` and `NOTICE` in this directory.

The two module `build.gradle.kts` files use this repository's existing
`com.android.library` plugin instead of repeating the versioned plugin alias.
The upstream `MANIFEST.sha256` therefore describes the downloaded archive
before that local build-only adaptation.

To update the vendored modules, download the published archive, verify its
checksum against the EVCC download manifest, replace only these two modules,
and run the repository's complete Android checks plus the GeePlay checks.
