# Attachment Upload: Durable-Publish Pitfalls on Android / proroot

This document records how **file upload inside dsh's web UI failed in the DSH-Folk container**:
the symptoms, how the evidence was obtained, which upstream line is responsible, and why
“some files upload while others do not”. It is written for whoever maintains the runtime/bridge
layer next — errors like this are **swallowed into one generic message upstream**, and without a
way to collect evidence you can only guess.

> License: this document is released under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
> Keep the attribution and this notice when sharing or adapting it.
> The code itself remains GPL-3.0 (see the project LICENSE).

---

## 1. Symptoms

- Picking a file in the chat composer yields a card saying “upload failed, click to retry”;
- On the same device **some files upload and others do not**, specifically: previously uploaded content works, new content does not;
- Nothing in the page or `dsh.log` explains it — only `{"ok":false,"error":{"code":"session/attachment-invalid","reason":"ATTACHMENT_WRITE_FAILED"}}`;
- An external browser fails the same way (so it is **not** a WebView issue);
- The device runs the `proroot` container runtime.

## 2. Make it talk: three observation points

Upstream returns HTTP 200 with the failure inside the JSON body, and the frontend renders one
generic string. You must collect evidence yourself:

1. **Page-side hook**: `@deepseek-ai/dsh-client-file-upload` honours the pre-boot hook
   `window.__DSH_FILE_UPLOAD__ = { fetch }`. Installing it lets the failure report `status`, blob size and
   the first 300 characters of the response body through `console`, which the app's `onConsoleMessage`
   then writes to the run log under `[DSH-Folk upload]`.
2. **Container storage check**: on startup run `df`, `ls -ld $DSH_HOME/attachments` and a small write test,
   logging the result under `[attach]`.
3. **Probe the syscalls directly** (decisive): inside the container use `node` to replay
   “write temp file → link → unlink source → chmod target” and print each step.

The third step produced the decisive evidence:

```
[attach] LINK_OK symlink=false
[attach] CHMOD_AFTER_UNLINK_FAIL ENOENT
[attach] FSYNC_DIR_OK
[attach] WRITE_OK
```

Read it as: `link` **does not fail**, but **deleting the source name also removes the target name**
(chmod on the target returns ENOENT). Disk, permissions and directory fsync are all fine.

## 3. Root cause

### 3.1 Upstream publishes with a hard link

`@deepseek-ai/dsh-attachment-local` is content-addressed:

- write bytes to `<DSH_HOME>/attachments/v1/tmp/<uuid>` and fsync the file;
- **`link(staging, target)`** into `.../file-objects/<sha2>/<sha>`;
- then **`unlink(staging)`**, `chmod(target, 0o400)` and fsync each parent directory;
- finally `link` again to create the named alias `.../files/<sha2>/<sha>/<name>`.

(Upstream commit `65d2015` “feat(attachment): store and project generic files” added generic file
storage and `file-store.ts`; images and plain files share `publishStagedObject` / `publishImmutableAlias`.)

### 3.2 proroot's `--link2symlink` is not a real hard link

proroot documents it as **“Emulate hardlinks via anchor + symlink groups”**. So `link()` does not
create an independent directory entry, but an “anchor + symlink group”:

- `link(staging, target)` returns success;
- once `unlink(staging)` removes the anchor, the group member `target` **becomes invalid**;
- the following `chmod(target)` therefore gets ENOENT and the whole chain throws `ATTACHMENT_WRITE_FAILED`.

App-private storage cannot create real hard links anyway (SELinux denies it), so there is no
“falls back to a real hard link” path. The two limits together make hard-link publishing
impossible on Android + proroot.

### 3.3 Why some files upload and others do not

The decisive detail is the `EEXIST` branch:

- target **already exists** (this content was stored successfully before) → `link` raises `EEXIST` → upstream skips creation, then `unlink(staging)` and `chmod(existing target)` → **works**;
- target **does not exist** (new content) → `link` takes the “successful” anchor-group path → after `unlink(staging)` the target is gone → **fails**;
- worse, failed attempts leave **dangling links** in `file-objects/`, so every retry of that content fails on them (`digestFile` / `chmod` hit ENOENT) — that file can never be uploaded.

So it is not a “cached failure state”; it is simply that **only pre-existing objects take the working branch**.

## 4. Dead ends worth remembering

| Dead end | Why it was wrong | Lesson |
|---|---|---|
| Assume `link` fails, add a “fall back to copy” guard | `link` **never fails** under l2s, so the guard never fires | **Do not trust `link`'s return value**; verify that the target survives after removing the source |
| Assume “just copyFile” is safe | the copy hit `copyfile ENOENT`, and pre-existing dangling targets block it | **Remove the target name first** (dangling link) before copying |
| Blame WebView / the file picker | an external browser fails identically | Separate “inside WebView” from “same-origin browser” before blaming the host |
| Blame the newly added chroot runtime | the device has no root, never used chroot; it runs proroot | Read `ContainerRuntime` in `basic.txt` instead of guessing |

## 5. Fix

Replace hard-link publishing with **rename publishing** (same-directory `rename` is atomic and keeps
exactly one real name, no hard link needed):

1. `link(staging, target)` → **`rename(staging, target)`**;
2. delete the following **`unlink(staging)`** (rename already consumed the temp name; deleting again takes the target with it);
3. alias: `link(source, target)` → **`unlink(target)` (ignoring absence/dangling) then `copyFile(source, target)`**;
4. add `copyFile` to the import list.

Apply it the same way as `patchLanHost`: **idempotently on every start** (so a runtime reinstall is
patched again), with a **count check per replacement** — if any anchor is not found exactly once, do
not write the file at all.

## 6. Minimal reproduction / acceptance probe

Inside the container (no root required):

```bash
node -e 'const fs=require("fs");const d="/root/.dsh/attachments/v1/tmp";fs.mkdirSync(d,{recursive:true,mode:448});
const a=d+"/p1";fs.writeFileSync(a,"x");
try{fs.linkSync(a,d+"/p2");console.log("LINK_OK symlink="+fs.lstatSync(d+"/p2").isSymbolicLink());
 fs.unlinkSync(a);
 try{fs.chmodSync(d+"/p2",256);console.log("CHMOD_AFTER_UNLINK_OK")}catch(e){console.log("CHMOD_AFTER_UNLINK_FAIL "+e.code)}
 fs.unlinkSync(d+"/p2")}catch(e){console.log("LINK_FAIL "+e.code)}'
```

- `CHMOD_AFTER_UNLINK_FAIL ENOENT` → this runtime cannot use link-based publishing; apply §5;
- `CHMOD_AFTER_UNLINK_OK` → hard-link publishing is safe on this runtime.

## 7. General lessons

1. **Android app-private storage usually cannot create hard links** (SELinux); treat link-based publishing as unavailable on phones.
2. **`--link2symlink` in proot/proroot is emulation**: a successful `link` does not mean two independent entries; removing the anchor invalidates the group.
3. Directory `fsync` may fail on some kernels/filesystems — it is only a crash-safety optimisation and must not abort the operation.
4. When upstream compresses errors into one generic message, **install a hook to capture the response body first**; do not guess from the error name.
5. Idempotent patches need **per-anchor count checks**; skipping on a mismatch is far safer than writing a half-applied patch.
6. Keep the `[attach]` and `[DSH-Folk upload]` log prefixes — they were the main handle for this investigation.
