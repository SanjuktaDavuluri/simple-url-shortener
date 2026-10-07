---
status: accepted
date: 2026-10-07
---

# Python 3.14 + FastAPI + pytest as the stack

We are building the URL shortener in Python 3.14 with FastAPI for the HTTP API and the minimal web page, and pytest for tests. Dependencies are managed with uv. We chose it mainly because Python is the language the maintainer knows best. In a project judged on its process, fluency in the language matters more than any runtime advantage.

## Considered Options

- **TypeScript on Node with Vitest and Hono.** This is the default the workflow skills assume. We rejected it because the maintainer is less fluent in TypeScript.
- **TypeScript on Bun.** It needs the least tooling, but it is less standard and less familiar to reviewers. We rejected it for the same reason as Node.
