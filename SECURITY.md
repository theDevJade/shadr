# Security

## Reporting a vulnerability

Do not open a public issue. Use GitHub's private vulnerability reporting on this repository
(Security tab, "Report a vulnerability"), which opens a private thread with the maintainer.

Please include what you can: affected version, platform, a description of the impact, and steps
to reproduce. A proof of concept helps but is not required to file.

Expect a first response within a week. If a fix is warranted it goes out as a release, and the
in-plugin updater will offer it to servers on the stable channel.

## What the two risky features actually guarantee

Both are off by default, and both are worth understanding before you turn them on.

### The updater

`updates.download` stages a jar that replaces the running plugin on the next server start. What
it checks, in order:

- HTTPS only, and only from `github.com` or `githubusercontent.com`.
- 200 MiB cap, and the download is refused if it is empty.
- SHA-256 against the `<jar>.sha256` asset published beside the jar. A release without one is
  refused; nothing is ever staged unverified.
- The download is a readable zip containing a `plugin.yml` whose `name` is `shadr`.

The checksum comes from the same GitHub release as the jar, so it proves the download was not
mangled in transit. It does not prove who built it — anyone who can publish a release can publish
a matching checksum. If you want that stronger claim, set `updates.signing-key` to a base64
Ed25519 public key. The release must then also carry a `<jar>.sig`, and staging fails if the
signature does not verify against the key. A key held offline, away from CI, is the only
configuration that survives a compromised release pipeline.

If you would rather not hand that decision to the plugin at all, leave `updates.download: false`,
which is the default. `updates.check` only reads the release list and never writes anything.

### The web editor

`editor.web.enabled` starts an HTTP and WebSocket server that writes page YAML to disk. It is a
remote file writer with an authoring UI on top, and it is off by default for that reason.

- `EditorServer.init` refuses to start with open auth on a non-loopback bind. That is a hard
  failure, not a warning.
- Tokens are at least 16 characters and compared with `MessageDigest.isEqual`. Generated ones are
  24 random bytes in URL-safe base64.
- `/shadr editor link` mints a short-lived token per invocation, so the configured credential
  never lands in the server log.
- A configured TLS keystore that fails to load stops the editor. It does not fall back to plain
  HTTP.

Writes are confined to the pages directory, and only holders of the editor permission can mint a
link. Everything beyond that is your network's problem: bind it to loopback and reach it over an
SSH tunnel unless you have a specific reason not to.
