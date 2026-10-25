# J7M documentation

This directory contains the English developer and operator manual, its MkDocs
Material theme, and executable documentation examples. It is maintained by the
J7M project maintainers. Content review date: 25 October 2026. Review the affected
pages whenever their public API, defaults, security policy or failure contract
changes. Historical development notes are sources, not the public manual.

## Build and preview

From the repository root, with Python 3 and Java 25 installed:

```bash
python3 -m venv documentation/.venv
documentation/.venv/bin/python -m pip install -r documentation/requirements.txt
documentation/.venv/bin/python -m mkdocs build --strict -f documentation/mkdocs.yml
documentation/.venv/bin/python -m mkdocs serve -f documentation/mkdocs.yml
```

The generated site is `documentation/site/`; the preview address is printed by
MkDocs. Java examples use the repository Gradle wrapper; follow
[`examples/README.md`](examples/README.md) for their verification command.

## Content and evidence

The navigation follows the approved developer/operator outline. Every public
annotation has a worked reference entry, while application patterns compare
annotation-based and explicit APIs. The reference distinguishes runtime
enforcement from generated metadata and reserved declarations. Practical application snippets appear directly on the relevant pages.
Source provenance is maintained separately from the reader-facing examples.

`data/annotation-api.json` records the public annotation inventory from the
current Java source; it is not an assertion that every annotation installs a
runtime policy. The source remains authoritative. All diagrams, theme assets
and code highlighting are served locally; diagrams have textual explanations.

The footer preserves the project copyright and BSD-4-Clause acknowledgement.
The full license appears in the manual and remains authoritative in `LICENSE`
at the repository root. The documentation build does not deploy or publish the
site.

The homepage artwork in `docs/assets/images/distributed-systems-hero.png` is a
conceptual illustration, not a photograph of a J7M installation. The project-history account of j5m is separate from the current
implementation's capability boundaries.

## GitHub Pages publication

The publication is one complete site: the manual at
`https://www.egothor.org/J7M/`, the API index at
`https://www.egothor.org/J7M/javadoc/`, and each Java module at
`https://www.egothor.org/J7M/javadoc/<module>/` (for example `j7m-api`).
The homepage and reference index link to the API index.

Two GitHub Actions entry points run on `main` and also support manual dispatch:

- `.github/workflows/documentation.yml` publishes documentation and workflow changes.
- `.github/workflows/javadoc.yml` publishes changes to main sources and build configuration.

Both call `.github/workflows/publish-pages.yml`, which builds strict module Javadoc,
builds MkDocs, assembles all Java modules and deploys a single Pages artifact.
It deliberately rebuilds the manual on a Javadoc refresh: Pages deployments replace
the entire site, so publishing only API files would remove the manual. Both entry
points share one publication concurrency group. No branch push, deployment secret
or personal access token is required; deployment uses the Pages OIDC permissions.
Pull requests and manual dispatch from another branch do not deploy.

For the first publication, the repository owner must select **Settings → Pages →
Build and deployment → Source: GitHub Actions** and permit the `github-pages`
environment to deploy from `main`. The requested domain/path must already route
to this project's Pages site. With GitHub Pages hosting, configure
`www.egothor.org` on the owner's user/organization Pages site and verify its DNS;
a project site then inherits that domain with its case-sensitive `/J7M/` repository
path. Alternatively, an existing web host can route `/J7M/` to the project Pages
site. `site_url` defines canonical links; it does not configure DNS or web routing.
No project-level `CNAME` is emitted, because that would assign the domain root
to this repository rather than the requested `/J7M/` path.

To reproduce the publication locally from the repository root:

```bash
./gradlew javadoc --no-daemon
python3 -m unittest discover -s documentation/tools/tests -v
documentation/.venv/bin/python -m mkdocs build --strict \
  --config-file documentation/mkdocs.yml --site-dir "$PWD/build/pages"
python3 documentation/tools/assemble_site.py --site build/pages
```

The assembler fails if a Java module lacks Javadoc, preserves the manual, and
creates the API index with copyright, BSD-4 acknowledgement and license text.
`j7m-tests` has no main Java API and is not published as a module reference.
The artifact is generated under ignored `build/pages/`; private development notes
are not publication inputs. Build logs, the uploaded artifact and the `github-pages`
deployment URL provide the publication result. To restore a previous release,
rerun the successful publisher job from that revision, or revert the affected source
change on `main`; each run replaces the site with a self-contained artifact.
