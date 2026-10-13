# Bundled Opus decoder

`libdiplay_opus_jni.so` restores the libopus decoder backend shipped in the validated
`DiPlay_0.2.13_Packed_ADSigned_20261006 (2).apk` artifact. The release packaging step that added
these libraries was outside the source repository, so the GeePlay application module did not
inherit them when it was created.

The binaries identify their codec as libopus 1.6.1. Their license is packaged at
`shared/src/main/assets/licenses/libopus-1.6.1.txt` and documented in `docs/THIRD_PARTY_NOTICES.md`.
The x86 ABI was not present in the reference artifact; x86 builds use the bundled Java Concentus
decoder fallback instead.

SHA-256:

- `arm64-v8a/libdiplay_opus_jni.so`: `2f849bf461395541bf4101dcdec3aedcfbaa23a8862a21903ba46b76c464fe31`
- `armeabi-v7a/libdiplay_opus_jni.so`: `504efdaeea30b91bfbc09fcea609d46235c81b2d25854c070f593c8143c974fe`
- `x86_64/libdiplay_opus_jni.so`: `6269a7ae68a1d34902ff1a57512e6ecc896095d42a19bf0d0204406066f0cdbc`
