# Mihon / Aniyomi extension template

A minimal, self-contained repo that compiles a Mihon (or Aniyomi) source
extension into an installable APK via GitHub Actions — no Android Studio
required.

## Before you start — read this

There are two different ways people build extensions for Mihon/Aniyomi:

1. **Contribute to the shared community repo.** In practice, almost all
   real-world manga extensions for Mihon live in one big multi-module repo,
   [`keiyoushi/extensions-source`](https://github.com/keiyoushi/extensions-source)
   (Aniyomi's anime sources have an equivalent shared repo). That repo has
   its own generator script, shared conventions, and CI that builds and
   publishes an index. **If you want your extension included in the default
   repo list users add in the app, this is the real path** — open an issue
   or PR there.
2. **Self-host your own extension repo** (what this template does). Useful
   for a private/personal source, something you don't want to submit
   upstream, or just learning how the pieces fit together. You build your
   own APK(s) and, optionally, your own repo index, and users add your
   repo's URL manually in the app.

This template is for path 2. It uses the same `extensions-lib` stub
library and manifest metadata the app actually looks for, so a real
extension built with it will load correctly once installed — but it's a
simplified, single-repo version of the build setup, not a copy of the
community repo's internal tooling (which relies on custom Gradle
convention plugins not published anywhere else).

## Structure

```
.
├── build.gradle.kts              # root Gradle config
├── settings.gradle.kts           # registers each extension module
├── gradle.properties             # shared extensions-lib version
├── .github/workflows/build.yml   # CI: compiles every module, uploads APKs
└── src/
    └── en/
        └── example/               # one extension = one module
            ├── build.gradle.kts
            └── src/main/
                ├── AndroidManifest.xml
                └── kotlin/.../Example.kt
```

Each extension is its own Gradle module under `src/<lang>/<name>/`. Add a
new source by copying the `src/en/example` folder, renaming the package,
class, and manifest values, and adding a line for it in
`settings.gradle.kts`.

## Writing the actual source

`Example.kt` extends `HttpSource` (from `extensions-lib`) and stubs out the
methods Mihon calls: popular/latest/search listing, manga details, chapter
list, and page list. Replace the CSS selectors and URLs with the real
target site's structure. Useful references while you write it:

- Browse existing sources in `keiyoushi/extensions-source` for real-world
  examples of `HttpSource`/`ParsedHttpSource` implementations.
- The `extensions-lib` interfaces: https://github.com/mihonapp/tachiyomix
  (Mihon's extension stub library) and
  https://github.com/tachiyomiorg/extensions-lib (original Tachiyomi lib
  Mihon's is based on).

For an **Aniyomi** anime source, the same idea applies but you extend
`AnimeHttpSource` instead of `HttpSource`, with `SAnime`/`SEpisode`/
`Video` in place of `SManga`/`SChapter`/`Page`.

## Building

**Locally** (needs JDK 17 + Android SDK):
```bash
gradle assembleDebug
```
The APK lands in `src/en/example/build/outputs/apk/debug/`.

**On GitHub**: push to `main` (or open a PR) and the `build.yml` workflow
compiles every module and uploads the APKs as a workflow artifact. A second
job (`release`, runs only on `main`) builds release APKs and stages them
under `repo/apk/` as an `extension-repo` artifact — a starting point if you
want to publish a self-hosted repo index later (you'd add a step to
generate `index.min.json` in the format the app expects).

No Gradle wrapper jar is committed — the workflow provisions Gradle itself
via `gradle/actions/setup-gradle`. For local dev convenience you can
generate one yourself with `gradle wrapper`.

## Signing

Every build here uses Android's default debug signing, which is fine for
sideloading and testing. If you plan to publish updates over time (so users
can upgrade instead of reinstalling), Android requires consistent
signing across versions — generate one `debug.keystore`, commit it to the
repo (or store it as a GitHub secret), and point the `release` build type
at it explicitly.

## Verify before you rely on this

`extensions-lib`'s version, exact API surface, and required manifest
metadata do shift over time as the app evolves. Before publishing anything
real, cross-check `gradle.properties`' `libVersion`, the manifest
placeholders, and the `HttpSource` method signatures against the current
`keiyoushi/extensions-source` repo and `mihonapp/tachiyomix`.
