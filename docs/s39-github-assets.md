# S39 GitHub release assets: transport evidence

Status: **BLOCKED — no demonstrated installable URL**. Public installation remains disabled until the separately authorized Android gate and other publication requirements pass.

Anonymous HTTPS observations for the selected `Daimei-Extensions/extensions-repo` repository:

- `https://api.github.com/repos/Daimei-Extensions/extensions-repo/releases`: HTTP 200, empty array `[]`.
- `https://api.github.com/repos/Daimei-Extensions/extensions-repo/releases/latest`: HTTP 404, `Not Found`.
- `https://github.com/Daimei-Extensions/extensions-repo/releases`: HTTP 200. This is the releases page, not an asset download.

The requests used `curl --disable`, HTTPS-only protocol, no cookies, and no explicit credentials. No release asset or redirect destination was observed, so there is no evidence for a repository-bound CDN path or an installable URL. Do not infer one from a simulated fixture. A future selected public asset must be checked anonymously, with its short-lived redirect query redacted, before defining and testing a narrowly scoped redirect policy. Existing artifact downloads continue to reject redirects.

Stable production signing, Android installation and update proof, and publication remain separate unresolved gates. No signing continuity or Android behavior is established by these HTTP observations.
