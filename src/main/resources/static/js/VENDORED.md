# Vendored scripts

| File | Source | Version | License | Integrity (npm tarball) |
|---|---|---|---|---|
| `htmx-2.0.11.min.js` | [`htmx.org`](https://www.npmjs.com/package/htmx.org) on npm (`dist/htmx.min.js`) | 2.0.11 | Zero-Clause BSD | `sha512-Thx/WtpeOQqSrqBCw/A1cwGJGg4UrVa3+sW0GmrM3p4gJgO89ecH4qtbnyzDDWFvBTqjnIMCgELTNt636dtamA==` |

Self-hosted rather than loaded from a CDN, so the page works offline, makes no third-party requests, and needs no build step (ADR 0006). To upgrade: download the new tarball from npm, verify its `dist.integrity`, replace the file (with the version in its name), update the `<script>` tag in `templates/index.html`, and update this table.
