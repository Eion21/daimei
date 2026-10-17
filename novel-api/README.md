# Writing a novel extension

`app.novel:novel-api` is the contract between a novel extension APK and the host app. An extension is a normal
Android application package that implements `app.novel.source.api.NovelSource`; the host discovers it the same way it
discovers manga extensions, prompts the user to trust it, and then loads its class.

This README is for someone writing that extension in **their own repository**, not inside this one.

## 1. Depend on the contract

```kotlin
dependencies {
    compileOnly("app.novel:novel-api:1.2")
}
```

- **Coordinates:** `app.novel:novel-api:1.2`
- The version tracks the contract version declared by `NOVEL_API_VERSION` in
  `app/novel/source/api/NovelApiVersion.kt`. If your manifest declares `app.novel.extensionApi` `1.2`, you depend on
  `1.2`. An extension that does not need the host network can stay on `1.0`, and one that fetches only text can stay on
  `1.1`; the host keeps previous contract versions loadable.
- The dependency **must be `compileOnly`**, never `implementation`. The host provides the `:novel-api` classes at
  runtime. If your APK bundled its own copy of `app.novel.source.api.NovelSource`, the host's class loader would see
  two distinct `NovelSource` classes, `is NovelSource` would be false, and your source would never load.

## 2. The single most important rule: bundle nothing the host provides

Your extension must not package **anything the host already ships**, and that includes the **Kotlin standard
library**.

This is not a style preference or a size optimisation — it is a hard linker constraint. The host loads your APK with
`DelegateLastClassLoaderCompat`, which resolves a class in this order:

```
boot classpath  →  your APK's own dex  →  parent (the host)
```

**Your bundled copy wins.** So a `kotlin.coroutines.Continuation` shipped inside your APK shadows the host's. Because
`Continuation` appears in the signature of *every* `suspend` member of `NovelSource`, no suspend method links at all:

```
LinkageError: ... method getChapterContent(Chapter, Continuation) resolves differently in interface
  Class<Continuation, DelegateLastClassLoader> vs Class<Continuation, PathClassLoader>
```

Measured on a device with `dexdump`: an extension that bundled the standard library defined **60** `kotlin/coroutines`
classes inside its own APK; after excluding it, **0**.

Exclude the standard library (and coroutines, and anything else the host provides) from your runtime classpath. For
example, mirroring the fixture extension in this repo:

```kotlin
configurations.configureEach {
    if (isCanBeResolved && name.endsWith("RuntimeClasspath")) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    }
}
```

## 3. Manifest metadata your APK must declare

The host identifies and finds your source through three exact keys:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Identifies this APK as a novel extension. Without it the host never considers your package. -->
    <uses-feature android:name="app.novel.extension" />

    <application>
        <!-- Fully-qualified name of the class implementing app.novel.source.api.NovelSource. -->
        <meta-data
            android:name="app.novel.extension.class"
            android:value="com.example.MyNovelSource" />

        <!-- The contract version this APK was built against, as a float. Must be one the host supports. -->
        <meta-data
            android:name="app.novel.extensionApi"
            android:value="1.0" />
    </application>

</manifest>
```

- `app.novel.extension` — the `uses-feature` that marks the APK as a novel extension. A package that also declares
  `tachiyomi.extension` is treated as a manga extension, so keep the two clear.
- `app.novel.extension.class` — the class the host instantiates by reflection.
- `app.novel.extensionApi` — the contract version. The host rejects an APK whose value is not in its supported list,
  with the reason "unsupported library version".

## 4. What the host does before it runs your code

Nothing of yours is instantiated until it has passed a fixed, ordered gate. In order:

1. **Contract version.** The `app.novel.extensionApi` metadata is checked against the versions the host supports. An
   unsupported version is refused first.
2. **Signature present.** An unsigned APK is refused.
3. **Trusted.** A signed APK is refused until the user has explicitly trusted its signing certificate.
4. Only then does the host build a class loader for your APK and call `Class.forName(...).newInstance()` on the class
   named in `app.novel.extension.class`.

There is no path that instantiates a third-party class before steps 1–3. Your code runs only after the user has
granted trust, exactly as with a manga extension.

## 5. Networking (contract `1.2`)

An extension ships no HTTP client. The host owns the socket, the timeouts and the `User-Agent`, and hands you the
response body; you keep the part that is actually about the site — the selectors and the parsing.

To use it, implement `NetworkedNovelSource` instead of `NovelSource`:

```kotlin
class MyNovelSource : NetworkedNovelSource {
    private lateinit var network: NovelNetwork

    override fun bindNetwork(network: NovelNetwork) {
        this.network = network
    }

    override suspend fun popular(page: Int): NovelPage {
        val html = network.fetch("https://example.com/popular?page=$page")
        // parse html with your own selectors
    }

    // ... the rest of NovelSource
}
```

- `NovelNetwork.fetch(url, headers = emptyMap())` returns the body as text. It **throws** when the request fails or
  the response is not successful, so `""` always means a genuinely empty page, never a failure.
- `NovelNetwork.fetchBytes(url, headers = emptyMap())` returns the body as a raw `ByteArray`, for a body that is not
  text — a PDF, an image, a zip. `fetch` decodes the bytes as text before you ever see them, which corrupts a binary
  body, so a binary download must go through `fetchBytes`. It carries the same guarantee: it **throws** on a transport
  failure or a non-2xx response, so an empty array always means a genuinely empty body, never a failure.
- The network is injected through `bindNetwork`, called by the host right after it constructs your class and before
  it is registered. There is no constructor parameter because the host instantiates your class by reflection with a
  no-argument constructor.
- You do **not** need `withContext(Dispatchers.IO)`. The host's implementation already moves the call off the main
  thread. Bundling a copy of kotlinx-coroutines to do it yourself would shadow the host's and break the linker.
- An extension that does not implement `NetworkedNovelSource` is left untouched and may keep bringing its own HTTP
  stack.

Declaring `app.novel.extensionApi` `1.2` in your manifest is what says you require this capability. The host still
loads `1.0` and `1.1` extensions.

Only `NovelNetwork` and `NetworkedNovelSource` cross the `NovelSource` boundary. Anything you bundle for parsing
(for example Jsoup) must never appear in a `NovelSource` method signature, or the host's class loader will see two
different copies of it.
