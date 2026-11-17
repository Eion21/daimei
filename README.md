<!-- Modified for Daimei; see MODIFICATIONS.md. -->

# Daimei

Daimei is an Android light novel reader fork of Mihon 0.21.2. It builds on Mihon's reader and adds novel sources and text-reading integration. The [original Mihon repository](https://github.com/mihonapp/mihon) is upstream, not a support channel for Daimei.

## Reading and development

- Read from novel extensions and use the text reader's chapter navigation and progress controls. See the [novel extension API guide](novel-api/README.md) for the source contract.
- Organize reading in the inherited library, and use local backup and restore.
- To build or contribute, start with the [contributing guide](CONTRIBUTING.md). Use a local checkout and contact the Daimei maintainer through your existing channel for fork-specific questions or reports.

## Beta1 distribution (preparation only)

Public identity: `app.daimei`, `0.1.0-beta.1` /code2, Android8.0/API26 minimum and target36. Public S39 installation remains fail-closed on API26–27. ARM64 and universal signed APKs will be delivered through [Eion21/daimei Releases](https://github.com/Eion21/daimei/releases); they are not published yet. Do not install unsigned/QA/Debug artifacts as a public release. Differently signed installations are not advertised as compatible updates; do not uninstall or delete data to force them.

- [Spanish Beta1 notes and installation/add-catalog instructions](docs/releases/v0.1.0-beta.1.es.md).
- [Exact human signing and read-only verification](docs/beta1-human-signing.md).
- [Immutable extension hosting and ordered publication plan](docs/beta1-publication-plan.md).

Daimei's visible branding does not imply a global rename of inherited internal identifiers or protocol headers. In particular, Kitsu's runtime and Apollo introspection User-Agent values retain the inherited `Mihon v... (applicationId)` form as a conservative compatibility choice: there is no integration evidence supporting a cosmetic change. Mihon 0.21.2's Cloudflare fix is historical context, not proof that the literal name is mandatory. OAuth client fields and other trackers are unchanged.

## History and attribution

- [Daimei history](DAIMEI-CHANGELOG.md) records fork changes.
- [Upstream Mihon history](CHANGELOG.md) is retained separately.
- [Modifications inventory](MODIFICATIONS.md) and [NOTICE](NOTICE) describe attribution and fork scope.

### Credits

Thank you to all contributors, including the [upstream Mihon contributors](https://github.com/mihonapp/mihon/graphs/contributors).

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

### License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>

See [LICENSE](LICENSE) for the full Apache License 2.0 and [NOTICE](NOTICE) for additional attribution.
