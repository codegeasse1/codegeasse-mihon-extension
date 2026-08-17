<div align="center">

# Codegeasse Mihon Extensions

**A self-hosted extension repository for Mihon / Aniyomi / Tachiyomi / Nekoread.**

</div>

---

## 📥 Add this repo to your reader

Works with **Mihon**, **Aniyomi**, **Tachiyomi**, **Nekoread** and any other reader that loads Mihon/Tachiyomi-style extensions.

1. Open your reader.
2. Go to **Settings → Extensions** (in Mihon/Aniyomi: open **Browse → Extensions**, tap the **⋮ (three-dot) menu** in the top right, then **Extension repos**).
3. Tap **+** and paste this URL:

```
https://raw.githubusercontent.com/codegeasse1/codegeasse-mihon-extension/repo/index.json
```

> Alternative form (repo base URL, also accepted):
> ```
> https://raw.githubusercontent.com/codegeasse1/codegeasse-mihon-extension/repo
> ```

4. Back in the Extensions list the **Codegeasse** repo appears — install whichever sources you want.

Everything installs as normal Mihon extensions and auto-updates whenever the repo is refreshed in the app.

## 📚 Extensions in this repo

| Extension | Source site | Package |
|---|---|---|
| Comix | [comix.to](https://comix.to) | `eu.kanade.tachiyomi.extension.en.comix` |
| Kagane | [kagane.to](https://kagane.to) | `eu.kanade.tachiyomi.extension.en.kagane` |
| KuraManga | [kuramanga.com](https://kuramanga.com) | `eu.kanade.tachiyomi.extension.en.kuramanga` |
| LunarX | [lunarx.to](https://lunarx.to) | `eu.kanade.tachiyomi.extension.en.lunarx` |
| MangaBall | [mangaball.net](https://mangaball.net) | `eu.kanade.tachiyomi.extension.en.mangaball` |
| MangaK | [mangak.io](https://mangak.io) | `eu.kanade.tachiyomi.extension.en.mangak` |
| ManhuaTop | [manhuatop.org](https://manhuatop.org) | `eu.kanade.tachiyomi.extension.en.manhuatop` |
| Manhwa18 | [manhwa18.net](https://manhwa18.net) | `eu.kanade.tachiyomi.extension.en.manhwa18` |
| ManhwaHub | [manhwahub.net](https://manhwahub.net) | `eu.kanade.tachiyomi.extension.en.manhwahub` |
| The Blank | [theblank.net](https://theblank.net) | `eu.kanade.tachiyomi.extension.en.theblank` |
| Toonily | [toonily.com](https://toonily.com) | `eu.kanade.tachiyomi.extension.en.toonily` |
| Yurivan | [yurivan.com](https://www.yurivan.com) | `eu.kanade.tachiyomi.extension.en.yurivan` |

> The index also carries a placeholder **Example** extension (`eu.kanade.tachiyomi.extension.en.example`) used for testing — safe to ignore.

## 🧩 Compatibility

- Built on the standard Tachiyomi extension API (`HttpSource`).
- Compatible with **Mihon**, **Tachiyomi**, **Aniyomi**, **Nekoread**, and any reader that supports Mihon/Tachiyomi-style extension repos.
- Extensions are **signed consistently** across versions, so updates install over the previous version without reinstalling.

## 🛠 How this repo works

- Each extension is a standalone module written and maintained in this repository.
- Every push to `main` is compiled by GitHub Actions. The workflow builds each extension module and publishes the extension index (`index.json`), the APKs (`apk/`) and icons (`icon/`) to the **`repo`** branch — that's what your reader fetches when you add the repository above.
- Want a new site? Open an issue and it may be added.

## 👨‍💻 For developers

Each extension is its own Gradle module under `src/<lang>/<name>/` and extends `HttpSource` from `extensions-lib`. Add a new one by copying an existing module, renaming the package/class/manifest values, and registering it in `settings.gradle.kts`. The workflow handles building and publishing the index for you.

## ⚖️ Disclaimer

All manga content is hosted on the source websites listed above — this repo only provides the extension code that connects to and reads those already-public sites. We don't host, upload, or store any content, and we don't own any of the source websites. All titles, logos and content belong to their respective owners.

This repository is not affiliated with, endorsed by, or a part of Keiyoushi, Mihon, Aniyomi or Tachiyomi.
