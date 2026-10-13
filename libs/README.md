# libs/

The Shizuku API jars (`shizuku-api.jar`, `shizuku-provider.jar`) are **not**
committed here. `build-apk.sh` downloads them from Maven Central
(`dev.rikka.shizuku:api:12.2.0` and `:provider:12.2.0`, MIT-licensed) on the
first build and caches them in this directory.

Nothing else is needed — the manual aapt2/kotlinc/d8 pipeline stays
dependency-free apart from these two small jars.
