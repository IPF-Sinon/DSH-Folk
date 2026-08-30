#!/usr/bin/env bash
# 组装 DSH-Folk 的 Android 容器运行时：Ubuntu 24.04 arm64 base rootfs
# + Node.js + @deepseek-ai/dsh，产出 rootfs.tar.gz 与 metadata.json。
#
# 在 x86_64 的 GitHub runner 上跑：base rootfs 直接取官方 cloud image 的
# rootfs tarball（已是 arm64），Node 取官方 linux-arm64 预编译包，dsh 用
# runner 本机的 Node 安装到目标 rootfs 里（npm 只搬 JS，不编译原生模块）。
# 因此不需要 qemu；唯一需要目标架构执行的步骤（postinst 之类）一概不做。
set -euo pipefail

UBUNTU_RELEASE="${UBUNTU_RELEASE:-noble}"          # 24.04 LTS
NODE_VER="${NODE_VER:-v24.19.0}"
DSH_VERSION="${DSH_VERSION:-latest}"
WORK="${WORK:-/tmp/dsh-runtime}"
OUT="${OUT:-$PWD/out}"

ROOTFS="$WORK/rootfs"
mkdir -p "$WORK" "$OUT"

# 多镜像候选：逐个试，第一个成功的就用（CI 网络到 cdimage 常年不稳）
try_download() {
  local dest="$1"; shift
  for url in "$@"; do
    echo "    尝试 $url"
    if curl -fsSL --connect-timeout 15 --retry 2 -o "$dest" "$url"; then
      echo "    命中 $url"
      return 0
    fi
  done
  return 1
}

echo "==> [1/8] 下载 Ubuntu ${UBUNTU_RELEASE} arm64 base rootfs"
BASE_TAR="$WORK/base.tar.gz"
BASE_PATH="ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
if [ ! -f "$BASE_TAR" ]; then
  try_download "$BASE_TAR" \
    "https://mirror.nju.edu.cn/$BASE_PATH" \
    "https://mirrors.hit.edu.cn/$BASE_PATH" \
    "https://mirrors.aliyun.com/$BASE_PATH" \
    "https://mirrors.tuna.tsinghua.edu.cn/$BASE_PATH" \
    "https://mirrors.huaweicloud.com/$BASE_PATH" \
    "https://mirrors.bfsu.edu.cn/$BASE_PATH" \
    "https://cdimage.ubuntu.com/$BASE_PATH"
fi
rm -rf "$ROOTFS"; mkdir -p "$ROOTFS"
tar -xzf "$BASE_TAR" -C "$ROOTFS"
echo "    rootfs 顶层: $(ls "$ROOTFS" | tr '\n' ' ')"

echo "==> [2/8] 安装 Node.js ${NODE_VER} (linux-arm64)"
NODE_TAR="$WORK/node.tar.xz"
NODE_FILE="node-${NODE_VER}-linux-arm64.tar.xz"
if [ ! -f "$NODE_TAR" ]; then
  try_download "$NODE_TAR" \
    "https://mirrors.huaweicloud.com/nodejs/${NODE_VER}/${NODE_FILE}" \
    "https://registry.npmmirror.com/-/binary/node/${NODE_VER}/${NODE_FILE}" \
    "https://mirrors.aliyun.com/nodejs-release/${NODE_VER}/${NODE_FILE}" \
    "https://mirrors.cloud.tencent.com/nodejs-release/${NODE_VER}/${NODE_FILE}" \
    "https://mirror.nju.edu.cn/nodejs-release/${NODE_VER}/${NODE_FILE}" \
    "https://mirrors.sjtug.sjtu.edu.cn/nodejs-release/${NODE_VER}/${NODE_FILE}" \
    "https://nodejs.org/dist/${NODE_VER}/${NODE_FILE}"
fi
# --strip-components=1 把 node-vX-linux-arm64/{bin,lib,include,share} 摊进 /usr/local
tar -xJf "$NODE_TAR" -C "$ROOTFS/usr/local" --strip-components=1
test -x "$ROOTFS/usr/local/bin/node"

echo "==> [3/8] 安装 @deepseek-ai/dsh@${DSH_VERSION}"
# 用 runner（x86_64）的 npm 装进目标 rootfs 的前缀。
# --os/--cpu 必须显式指定：dsh 依赖 sharp 与 koffi，它们通过 optionalDependencies
# 按宿主平台挑预编译包，不指定就会装进 linux-x64 的 .node，在手机上一 require 就炸。
# --ignore-scripts 同时挡掉任何想在 x86 上编译产物的 postinstall。
npm install --global \
  --prefix "$ROOTFS/usr/local" \
  --os=linux --cpu=arm64 \
  --ignore-scripts --no-audit --no-fund \
  "@deepseek-ai/dsh@${DSH_VERSION}"

DSH_ENTRY="$ROOTFS/usr/local/lib/node_modules/@deepseek-ai/dsh"
test -d "$DSH_ENTRY"
DSH_REAL_VERSION="$(node -p "require('$DSH_ENTRY/package.json').version")"
echo "    dsh = $DSH_REAL_VERSION"

# 重建 bin 软链：入口路径从 package.json 的 bin 字段读，不要写死
# （dsh 的入口是 lib/bin.js，将来改了这里也不用跟着改）。
# App 侧靠 readlink -f 解析出真实 JS 再交给 node --expose-internals，
# 所以这个链接必须是容器内可解析的相对链接。
DSH_BIN_REL="$(node -p "
  const b = require('$DSH_ENTRY/package.json').bin;
  typeof b === 'string' ? b : b.dsh
")"
rm -f "$ROOTFS/usr/local/bin/dsh"
ln -s "../lib/node_modules/@deepseek-ai/dsh/${DSH_BIN_REL}" "$ROOTFS/usr/local/bin/dsh"
test -f "$ROOTFS/usr/local/lib/node_modules/@deepseek-ai/dsh/${DSH_BIN_REL}"
echo "    入口 = ${DSH_BIN_REL}"

# node-pty 把所有平台的预编译产物打在同一个包里（win32 那两份各 12 MB），
# 只留 linux-arm64：既减掉约 24 MB，也让下面的异架构自检不必给它开特例。
find "$ROOTFS/usr/local/lib/node_modules" -type d -name prebuilds | while read -r d; do
  find "$d" -mindepth 1 -maxdepth 1 -type d ! -name "linux-arm64" -exec rm -rf {} +
done

# 架构自检：任何 linux-x64 / darwin / win32 的原生模块留在 rootfs 里都是隐患，
# 手机上 require 到就是 ENOEXEC。CI 阶段直接失败比让用户在设备上排查便宜得多。
BAD_NATIVE="$(find "$ROOTFS/usr/local/lib/node_modules" -name "*.node" \
  \( -path "*linux-x64*" -o -path "*darwin*" -o -path "*win32*" -o -path "*x64*" \) 2>/dev/null || true)"
if [ -n "$BAD_NATIVE" ]; then
  echo "!! rootfs 里出现非 arm64 原生模块："
  printf '   %s\n' $BAD_NATIVE
  exit 1
fi
ARM_NATIVE_COUNT="$(find "$ROOTFS/usr/local/lib/node_modules" -name "*.node" 2>/dev/null | wc -l)"
echo "    原生模块 ${ARM_NATIVE_COUNT} 个，未发现异架构产物"

# dsh 的插件管理（dsh plugin --profile web add …）内部转发 pnpm，PATH 上没有 pnpm
# 就直接返回 127「pnpm not found on PATH」。rootfs 里只有 corepack 的 shim，
# 而 corepack 首次运行要联网下载 —— 用户在手机上装插件时才发现没网就太晚了。
# pnpm 是纯 JS、零 runtime 依赖，异架构安装完全安全（不像 sharp/koffi 要挑预编译产物）。
echo "    附带安装 pnpm（dsh plugin 内部转发它）"
npm install --global \
  --prefix "$ROOTFS/usr/local" \
  --ignore-scripts --no-audit --no-fund \
  pnpm
test -f "$ROOTFS/usr/local/bin/pnpm"
PNPM_VERSION="$(node -p "require('$ROOTFS/usr/local/lib/node_modules/pnpm/package.json').version")"
echo "    pnpm = $PNPM_VERSION"

echo "==> [4/8] 安装 python3（无线 ADB 配对依赖）"
# 无线 ADB 配对（AdbBridge / adb-pair.py）需要容器内的 python3，
# 而 ubuntu-base 里没有它。手机上第一次配对才 apt install 的话：
#  - 要联网、要 apt 在 proot 下正常工作（dpkg 的 postinst 常在 proot 下失败）；
#  - 用户点「配对」后要等好几分钟，失败原因还很难查。
# 所以这里直接把 python3 及其依赖解包进 rootfs：
# 只用 dpkg-deb -x（纯解包，不跑任何 maintainer script，不需要目标架构可执行），
# postinst 真正做的事（sitecustomize.py 与 dist-packages 目录）下面手工补上。
PY_PKGS="python3.12-minimal libpython3.12-minimal libpython3.12-stdlib python3.12
         python3-minimal python3 libpython3-stdlib
         libexpat1 libsqlite3-0 libreadline8t64 readline-common
         libnsl2 libtirpc3t64 media-types netbase tzdata"
APT_MIRRORS="https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
             https://mirrors.aliyun.com/ubuntu-ports
             https://mirror.nju.edu.cn/ubuntu-ports
             http://ports.ubuntu.com/ubuntu-ports"

DEB_DIR="$WORK/debs"
mkdir -p "$DEB_DIR"
INDEX_DIR="$WORK/pkgindex"
mkdir -p "$INDEX_DIR"

# 找一个能同时给出三个 suite 索引的镜像（noble / -updates / -security：
# 安全更新里的版本比 noble 里的新，只读 noble 会拿到装不上的旧版本组合）
APT_BASE=""
for m in $APT_MIRRORS; do
  index_ok=1
  for suite in "$UBUNTU_RELEASE" "$UBUNTU_RELEASE-updates" "$UBUNTU_RELEASE-security"; do
    if ! curl -fsSL --connect-timeout 15 "$m/dists/$suite/main/binary-arm64/Packages.gz" \
         | gzip -d > "$INDEX_DIR/$suite.txt" 2>/dev/null; then
      index_ok=0; break
    fi
  done
  if [ "$index_ok" = 1 ]; then APT_BASE="$m"; echo "    包索引镜像: $m"; break; fi
done
test -n "$APT_BASE"

# 从索引里挑每个包的最高版本（跨三个 suite 比较）
resolve_deb() {
  local pkg="$1" best_ver="" best_fn="" ver fn block
  for suite in "$UBUNTU_RELEASE" "$UBUNTU_RELEASE-updates" "$UBUNTU_RELEASE-security"; do
    # RS="" 段落模式下 $0 是整段，用正则 ~ 会把 "Package: python3" 误配到
    # "Package: python3.12" 那一段；改成逐字段精确等值比较
    block="$(awk -v want="Package: $pkg" 'BEGIN{RS="";FS="\n";ORS="\n\n"}
      { for (i=1;i<=NF;i++) if ($i==want) { print; next } }' "$INDEX_DIR/$suite.txt")"
    [ -n "$block" ] || continue
    ver="$(printf '%s\n' "$block" | sed -n 's/^Version: //p' | head -1)"
    fn="$(printf '%s\n' "$block" | sed -n 's/^Filename: //p' | head -1)"
    [ -n "$ver" ] || continue
    if [ -z "$best_ver" ] || dpkg --compare-versions "$ver" gt "$best_ver"; then
      best_ver="$ver"; best_fn="$fn"
    fi
  done
  [ -n "$best_fn" ] || return 1
  printf '%s\t%s\n' "$best_ver" "$best_fn"
}

for pkg in $PY_PKGS; do
  info="$(resolve_deb "$pkg")" || { echo "!! 索引里找不到 $pkg"; exit 1; }
  ver="$(printf '%s' "$info" | cut -f1)"
  fn="$(printf '%s' "$info" | cut -f2)"
  echo "    $pkg $ver"
  curl -fsSL --connect-timeout 20 --retry 2 -o "$DEB_DIR/$pkg.deb" "$APT_BASE/$fn"
  # 纯解包：不执行 maintainer script（它们要在 arm64 上跑，runner 是 x86_64）。
  # 副作用：dpkg 数据库里没有这些包的记录，容器内 apt 仍会认为 python3 未安装 ——
  # 用户真去 apt install python3 时会重新装一遍并覆盖，属可接受（不会坏）。
  dpkg-deb -x "$DEB_DIR/$pkg.deb" "$ROOTFS"
done

# postinst 真正会做的两件事，手工补：
# 1) sitecustomize.py：python3.12 的 lib 里那个是指向 /etc 的符号链接，缺文件即 dangling
install -d "$ROOTFS/etc/python3.12"
printf '# Empty sitecustomize.py to avoid a dangling symlink\n' \
  > "$ROOTFS/etc/python3.12/sitecustomize.py"
# 2) /usr/local/lib/python3.12/dist-packages：pip --break-system-packages 的落点
install -d "$ROOTFS/usr/local/lib/python3.12/dist-packages"
install -d "$ROOTFS/usr/lib/python3/dist-packages"

# 自检：解包出来的解释器必须是 arm64，且 stdlib 齐全（缺 lib-dynload 会在手机上才炸）
test -x "$ROOTFS/usr/bin/python3.12"
head -c 20 "$ROOTFS/usr/bin/python3.12" | od -An -tx1 | tr -d ' \n' | grep -q '^7f454c46' \
  || { echo "!! python3.12 不是 ELF"; exit 1; }
PY_ARCH="$(od -An -tu1 -j18 -N1 "$ROOTFS/usr/bin/python3.12" | tr -d ' ')"
test "$PY_ARCH" = "183" || { echo "!! python3.12 不是 aarch64 (e_machine=$PY_ARCH)"; exit 1; }
test -L "$ROOTFS/usr/bin/python3"
DYNLOAD_COUNT="$(ls "$ROOTFS/usr/lib/python3.12/lib-dynload"/*.so 2>/dev/null | wc -l)"
test "$DYNLOAD_COUNT" -ge 40 || { echo "!! lib-dynload 只有 $DYNLOAD_COUNT 个模块"; exit 1; }
for m in _ssl _hashlib _sqlite3 _ctypes _decimal; do
  ls "$ROOTFS/usr/lib/python3.12/lib-dynload/$m."*.so >/dev/null 2>&1 \
    || { echo "!! 缺少 python 模块 $m"; exit 1; }
done
test -f "$ROOTFS/usr/lib/python3.12/ssl.py"
echo "    python3 已就绪 · lib-dynload $DYNLOAD_COUNT 个模块"

echo "==> [5/8] 安装 git（git 源插件依赖）"
# 插件目录里 2659 条有 1357 条（51%）的安装命令是 `github:owner/name` 规格，
# pnpm 解析它要 `git ls-remote`；ubuntu-base 没有 git，于是这一半插件全装不上
# （真机报 ERR_PNPM_GIT_RESOLVE_FAILED: git executable not found on PATH）。
# 手机上现装的问题跟 python3 一样：要联网、apt 的 postinst 在 proot 下常失败、
# 用户要干等好几分钟。所以同样预解包进 rootfs。
#
# git 只用到 perl 跑几个辅助脚本（add -i、send-email 之类），核心命令是 C 实现，
# 但 dpkg 的依赖关系摆在那儿，缺了 perl 一些子命令会直接报错，所以一并带上。
GIT_PKGS="git git-man liberror-perl perl perl-base perl-modules-5.38
          libcurl3t64-gnutls libpcre2-8-0 zlib1g"

for pkg in $GIT_PKGS; do
  info="$(resolve_deb "$pkg")" || { echo "!! 索引里找不到 $pkg"; exit 1; }
  ver="$(printf '%s' "$info" | cut -f1)"
  fn="$(printf '%s' "$info" | cut -f2)"
  echo "    $pkg $ver"
  curl -fsSL --connect-timeout 20 --retry 2 -o "$DEB_DIR/$pkg.deb" "$APT_BASE/$fn"
  # 同 python3：纯解包，不跑 maintainer script（要在 arm64 上执行，runner 是 x86_64）
  dpkg-deb -x "$DEB_DIR/$pkg.deb" "$ROOTFS"
done

# 自检：git 必须是 aarch64 ELF，且 core 的辅助程序在位（少了在手机上才炸）
test -x "$ROOTFS/usr/bin/git"
head -c 4 "$ROOTFS/usr/bin/git" | od -An -tx1 | tr -d ' \n' | grep -q '^7f454c46' \
  || { echo "!! git 不是 ELF"; exit 1; }
GIT_ARCH="$(od -An -tu1 -j18 -N1 "$ROOTFS/usr/bin/git" | tr -d ' ')"
test "$GIT_ARCH" = "183" || { echo "!! git 不是 aarch64 (e_machine=$GIT_ARCH)"; exit 1; }
# git-remote-https 才是 pnpm 走 https 克隆时真正调用的那个
test -x "$ROOTFS/usr/lib/git-core/git-remote-https" \
  || { echo "!! 缺少 git-remote-https（https 克隆会失败）"; exit 1; }
test -x "$ROOTFS/usr/bin/perl" || { echo "!! 缺少 perl"; exit 1; }
echo "    git 已就绪"

echo "==> [6/8] 容器内初始设置"
install -d -m 700 "$ROOTFS/root/.dsh"
install -d -m 1777 "$ROOTFS/tmp"
# APT 换国内源（用户在容器里 apt install 时不至于卡住）；DNS 由 App 在安装后写入
cat > "$ROOTFS/etc/apt/sources.list.d/ubuntu.sources" <<EOF
Types: deb
URIs: https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/
Suites: ${UBUNTU_RELEASE} ${UBUNTU_RELEASE}-updates ${UBUNTU_RELEASE}-security ${UBUNTU_RELEASE}-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
# proot 下不能跑的东西提前禁掉，避免 apt 触发时整条命令失败
printf '#!/bin/sh\nexit 0\n' > "$ROOTFS/usr/sbin/policy-rc.d"
chmod +x "$ROOTFS/usr/sbin/policy-rc.d"
cat > "$ROOTFS/root/.profile" <<'EOF'
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DSH_HOME=/root/.dsh
export LANG=C.UTF-8
export TERM=xterm-256color
EOF

echo "==> [7/8] 打包 rootfs.tar.gz"
TARBALL="$OUT/rootfs.tar.gz"
rm -f "$TARBALL"
# numeric-owner + 不带前导目录：App 侧 TarGzipExtractor 直接铺到 filesDir/rootfs
tar --numeric-owner -czf "$TARBALL" -C "$ROOTFS" .
SIZE=$(stat -c %s "$TARBALL")
SHA=$(sha256sum "$TARBALL" | cut -d' ' -f1)
echo "    $TARBALL  $((SIZE / 1024 / 1024)) MB  sha256=$SHA"

echo "==> [8/8] 生成 metadata.json"
REPO="${GITHUB_REPOSITORY:-IPF-Sinon/DSH-Folk}"
TAG="${RELEASE_TAG:-runtime-latest}"
ASSET="https://github.com/${REPO}/releases/download/${TAG}/rootfs.tar.gz"
cat > "$OUT/metadata.json" <<EOF
{
  "version": "${DSH_REAL_VERSION}-ubuntu${UBUNTU_RELEASE}",
  "url": "${ASSET}",
  "sha256": "${SHA}",
  "sizeBytes": ${SIZE},
  "mirrors": [
    "https://v6.gh-proxy.org/${ASSET}",
    "https://axisnow.gh-proxy.org/${ASSET}"
  ],
  "arch": "arm64-v8a",
  "dsh": "${DSH_REAL_VERSION}",
  "nodeVersion": "${NODE_VER}",
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
cat "$OUT/metadata.json"
