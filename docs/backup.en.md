# Why the App Encrypts Backups Itself

[← Back to README](../README.en.md)

## Why the App Encrypts Backups Itself

The contents of a config backup still come from the `dsh-config-manager` plugin inside the container
(it is the one that knows what in `~/.dsh` is configuration), but the part **between what it is asked
for and what it hands back** is done by the app:

1. ask the plugin for a **plain** ZIP, explicitly without the `sessions` section;
2. add the selected sessions, the app settings and native capability log, and (when the vault is
   selected) the credentials, locally;
3. when a password is given, seal the container in the app (`DCA1`:
   `magic+version+salt(16)+iv(12)+tag(16)+ciphertext`, scrypt N=16384/r=8/p=1 + AES-256-GCM).

It cannot be the other way around, because the plugin seals the final container the moment it is given
a password - the app would never get a chance to put anything into the archive, which rules out both
"export the last five sessions" and "take my app settings along". An archive built this way is
byte-for-byte the plugin format, so restoring inside the plugin and on the desktop still works; on
import the app opens the container first and hands the plugin an ordinary plain archive.

Two mistakes there are invisible in the app and fatal on the other side, so the checkers pin them:

- **checksums must be recomputed**: the import side verifies every entry in `integrity/checksums.json`
  with SHA-256 and rejects the whole archive otherwise. The table covers everything except
  `manifest.json` and the table itself.
- **`encrypted=true` requires `security/secrets.enc`**: imports refuse to run when an archive claims to
  be encrypted but yields no credentials, so an empty placeholder is written even when the vault is not
  included (the plugin does the same).

The encryption implementation (its own scrypt plus a hand-written PBKDF2, because `PBEKeySpec` char to
byte encoding differs between implementations) self-tests against **vectors produced by the plugin
itself**: one scrypt vector and one real `DCA1` container. A failed self-test refuses the export - a
backup nobody can open is worse than no backup, and that failure mode is completely invisible from the
app side (the file is produced, the size is right, the password is right, and neither the plugin nor
the desktop can open it). `tools/check-backup-crypto.js` recomputes both vectors independently with
the Node standard library.

What the UI offers: five levels for the contents (app data only / DSH only / DSH only + vault, added in
1.9.2.5 / app + DSH, the default / app + DSH + vault, the last two warned about first because they carry
credential plaintext), five levels for sessions (none by default, last 5, 20, 50, all), a password field
that leaves the archive unencrypted when empty but is mandatory with either vault scope. Import counts
the sessions in the archive first and then asks whether to skip them, restore them while dsh runs, or
restore them with the service stopped - restoring live does work (the registry is read at startup), but
a later workspace operation would overwrite the grouping, hence the recommendation.

**WebDAV cloud backup moved entirely into the `dsh-folk-cloud` plugin in 1.9.2.5.** The app no longer
uploads zips itself; the backup screen's cloud section is a thin front-end that only appears when the
plugin is detected, showing status, a config dialog (writes the plugin's `/api/dsh-folk-cloud/config`),
and Sync-now / Restore-from-upstream buttons. WebDAV settings live only in the plugin (password in DSH
credentials, never returned — leave the field empty to keep it); the plugin keeps its own manifest with
hash-based dedup and stops to ask on conflicts. App-data scopes fall back automatically when the app's
packing interface (`/cloud/appdata/*` on the loopback bridge) is absent.

"App data" means `SharedPreferences` plus `audit/*.jsonl`, and it carries **no secrets**: all
`webdav_*` keys, anything named like a password, token or secret, and `app_initialized` (restoring it
would make a new device skip first-run setup).

