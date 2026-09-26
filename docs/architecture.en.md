# How It Runs

[← Back to README](../README.en.md)

## How It Runs

```
DSH-Folk (Android app)                      ← Split by ABI: arm64-v8a / x86_64
  └─ proot / proroot                        ← Executable .so bundled in the APK
       └─ Ubuntu 24.04 rootfs               ← Downloaded online on first launch (arm64 or x86_64)
            ├─ python3                       ← Used for wireless ADB pairing, preinstalled
            ├─ git                           ← Used for git-source plugins, preinstalled
            └─ Node.js 24 + @deepseek-ai/dsh
                 └─ dsh web --port 3080      ← Listens only on 127.0.0.1 by default
                      └─ Mobile browser / open inside the app
```

Several parts have to work this way:

- Android's `app_data_file` is mounted **noexec**; only `.so` files in `nativeLibraryDir` are executable, so proot / proroot are packaged in the APK as `.so` files.
- proroot is available only for arm64 ([upstream](https://github.com/coderredlab/proroot) publishes only arm64-v8a), so x86_64 devices are fixed to proot;
  the corresponding Settings option is disabled and explains why.
- Some devices prohibit `link(2)` in private directories (real-device testing produces `AccessDeniedException`). The app first detects whether hard links work and adds `--link2symlink` to proot when they do not;
  proroot always enables it. pnpm itself uses `link()` to install packages from its content store — once links are rewritten as symbolic links,
  Node's `require.resolve` resolves realpath into the content store's flat hash directory, and the plugin's declared `./lib/client.cjs` can no longer be constructed
  (the symptom is `MissingClientBundleError` from `dsh web` after installing the plugin). In this environment, the profile's `pnpm-workspace.yaml` is therefore configured with
  `packageImportMethod: copy`, making pnpm copy real files. The cost is losing content-store deduplication and slightly increasing container size.
- `dsh plugin` only delegates to pnpm and exits with code 127 if pnpm is absent from PATH. The runtime therefore pins the self-contained
  `pnpm@10.34.5` (not `latest`: pnpm 12's npm package became a launcher that relies on postinstall to fetch a native binary, conflicting with
  the `--ignore-scripts` required for cross-architecture assembly), rebuilds the `/usr/local/bin` links from `package.json.bin`, and verifies the
  JS CLI with `node bin/pnpm.cjs --version` before packaging. This layer belongs to the runtime alone: the app no longer patches the rootfs on
  device, which would only hide the fact that the runtime is broken. The fixed revision ships as r3 on **both** channels, so existing users
  (including stable users still on 0.1.2-r2) can update the runtime in place instead of reinstalling.
- More than half of the entries in the plugin catalog use `github:owner/name` installation specifications, which pnpm resolves with `git ls-remote`, so git is also preinstalled in the rootfs.
  Note that the rootfs is built by extracting with `dpkg-deb -x` only (without running maintainer scripts, which would need to execute on the target architecture), so
  **no one resolves dpkg dependencies for us** — omit one transitive dependency from the package list, and everything looks fine during the build until the exact moment of exec on the device,
  when it reports `cannot find libxxx.so.N`. The build therefore ends with `check-elf-closure.js`: starting from git-core / Perl extensions / python3,
  it recursively resolves ELF `DT_NEEDED` entries, fails the build if any SONAME has no provider, and asserts that the entry points target the correct architecture.
- `dsh web` binds only to the loopback address by default; configuration backup uses the same loopback HTTP API. LAN access is a disabled-by-default toggle in Settings.
- **1.9.0 promotes dsh 0.1.5 to the stable channel** (`runtime-latest` = `0.1.5-rc.1-ubuntunoble-r4`, requires app ≥ 1.9.0).
  The beta channel ran the same rootfs through a full beta cycle, but **too few people tested it**: upgrading dsh rewrites
  session and plugin data, and the paths the app preserves (`root/.dsh`, `root/.local`, `.l2s`) keep the files but cannot keep
  up with an upstream format change. Back up before updating the runtime — that is the warning shown in the error colour at
  the top of the update dialog.

