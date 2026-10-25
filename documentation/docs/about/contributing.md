# Contributing to the Documentation

Keep this manual task-oriented and factual. Use tutorials for first use, how-to guides for operational tasks, explanations for design decisions and reference pages for exact contracts. Update the corresponding examples and source/test references when behavior changes.

For implementation changes, see [contributing to the middleware](middleware-contributing.md).

## Build locally

From the repository root, create an isolated documentation environment and install the pinned requirements:

```bash
python3 -m venv documentation/.venv
documentation/.venv/bin/python -m pip install -r documentation/requirements.txt
documentation/.venv/bin/python -m mkdocs build -f documentation/mkdocs.yml --strict
```

For a local preview:

```bash
documentation/.venv/bin/python -m mkdocs serve -f documentation/mkdocs.yml
```

The build command renders the manual; it does not publish the site. Generated output and the virtual environment should not be committed as documentation sources.

## Verify runnable examples

The checked examples and source-test provenance can be validated separately from rendering:

```bash
python3 documentation/tools/verify_examples.py
python3 documentation/tools/verify_examples.py --integration
python3 -m unittest discover -s documentation/tools/tests -p 'test_*.py'
```

The first command compiles and runs five adapted scenarios. `--integration` also reruns the unchanged managed queue/job/saga integration proofs identified by the example inventory. `--check-only` checks source provenance without a build; it is not an execution result. Use `--offline` only when the required Gradle artifacts are already cached.

## Examples and diagrams

Base examples on current APIs and meaningful test scenarios. Recheck adapted code after editing; a passing original test does not automatically validate a modified snippet. Mark host-configuration fragments with the required supplied objects and distinguish them from complete runnable examples.

Use language-tagged fences for Java, JSON, YAML, shell and text. Every annotation needs an accurate usage example and its real consumer/limitation. Do not describe metadata-only declarations as automatic runtime enforcement.

Prefer diagrams that explain a concrete boundary, such as discovery versus invocation, logical outcome versus physical lifetime or the DB/outbox/queue commit sequence. Provide useful alternative text. Keep labels legible in both color schemes and on narrow screens; do not rely on color alone.

## Review

Have an implementer verify API/configuration claims and an architect verify ownership/failure semantics. Review links, navigation, all annotation inventory entries, syntax highlighting, footer copyright and the rendered mobile/desktop result. A successful MkDocs build is necessary but does not establish factual correctness.

Preserve the [license](license.md), acknowledgement and the exact copyright attribution when reusing project material.
