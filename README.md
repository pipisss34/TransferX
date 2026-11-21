# TransferX (Android starter)

Browser Android + transfer manager. This is a buildable starter project intended to be committed to GitHub and built with GitHub Actions.

## What is included

- Android WebView browser with JavaScript and DOM storage enabled.
- WebView file chooser bridge for websites that use `<input type="file">`.
- Background download worker using HTTP `Range` when a partial file already exists. It resumes only when the server answers `206 Partial Content`; if the server ignores Range and answers `200`, it restarts that file safely instead of appending duplicate bytes.
- Generic TUS 1.0 upload worker. Enter a server's TUS creation endpoint and optional Authorization header, select a local file, and enqueue the upload. The worker creates a TUS resource, persists its upload URL, checks `Upload-Offset`, and sends 8 MiB PATCH chunks. If a connection fails, WorkManager retries and the worker checks the server offset before continuing.
- GitHub Actions workflow to build a debug APK and publish it as a workflow artifact.

## Important limitations

1. **TUS upload works only with servers that explicitly implement the TUS protocol** and expose a valid TUS creation endpoint. An ordinary multipart upload form is not automatically resumable. This app does not claim that Vexfile or every host supports TUS.
2. WebView uploads submitted by websites still use the website's own upload implementation. The app provides the browser file chooser, but it cannot transparently convert an arbitrary website's multipart upload into TUS.
3. Download resume requires HTTP Range support (`206` response). Some servers, signed URLs, anti-hotlink systems, and dynamic download links may not support it.
4. This is an MVP starter, not a polished IDM replacement. It has no full transfer-list screen, pause/cancel UI, checksum UI, cookie-sharing with the browser, or per-host integration yet.
5. For TUS endpoints that require special metadata or non-standard auth, the client will need a host-specific configuration. Keep API keys private; do not commit them into the repository.

## Build using GitHub Actions

1. Create a GitHub repository (public repositories get free standard GitHub-hosted Actions usage).
2. Extract this ZIP and commit all files to the repository root.
3. Open **Actions** → **Build TransferX APK** → **Run workflow**.
4. When the run completes, open its summary and download the `TransferX-debug-apk` artifact.
5. Extract the artifact ZIP to get `app-debug.apk`, then install it on Android. You may need to allow installation from the browser/file manager.

Pushes to `main` or `master` also trigger a build.

## Build locally

Requires JDK 17 and Gradle 8.9:

```bash
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## GitHub Actions quota (GitHub Free)

GitHub's current published allowance lists 2,000 minutes/month for private repositories on GitHub Free; standard GitHub-hosted runners are free for public repositories. GitHub Free includes 500 MB artifact storage. See the official links below because GitHub may change allowances:

- https://docs.github.com/en/billing/concepts/product-billing/github-actions
- https://docs.github.com/en/actions/concepts/billing-and-usage
