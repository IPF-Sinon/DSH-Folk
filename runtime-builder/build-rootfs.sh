#!/usr/bin/env bash
# 组装 DSH-Folk 的 Android 容器运行时：Ubuntu 24.04 base rootfs
# + Node.js + @deepseek-ai/dsh，产出 rootfs.tar.gz 与 metadata.json。
#
# 支持两个目标架构（TARGET_ARCH=arm64|amd64）。在 x86_64 的 GitHub runner 上跑：
# base rootfs 直接取官方 cloud image 的 rootfs tarball，Node 取官方预编译包，
# dsh 用 runner 本机的 Node 安装到目标 rootfs 里（npm 只搬 JS，不编译原生模块）。
# 因此不需要 qemu；唯一需要目标架构执行的步骤（postinst 之类）一概不做。
#
# arm64 的产物名保持无后缀（rootfs.tar.gz / metadata.json）—— 1.7.5 及更早的 App
# 把这两个名字写死在代码里，改名等于让存量用户拉不到运行时。
set -euo pipefail

UBUNTU_RELEASE="${UBUNTU_RELEASE:-noble}"          # 24.04 LTS
NODE_VER="${NODE_VER:-v24.19.0}"
DSH_VERSION="${DSH_VERSION:-latest}"
TARGET_ARCH="${TARGET_ARCH:-arm64}"                # arm64 | amd64

# rootfs 自身的修订号，**改动 rootfs 内容时必须递增**。
#
# metadata.json 的 version 原来只由 dsh 版本派生，于是 rootfs 内容变了（比如
# r2 补齐 git 的动态库依赖）版本串却一模一样，App 拿它做「有没有新运行时」的
# 判据就永远判不出来，存量用户收不到修复。
#   r1 = 初版（含 python3 + git，但 git 的 libcurl 依赖不全）
#   r2 = 补齐 git-remote-https 的传递依赖（libnghttp2 / libssh / krb5 / ldap …）
#
# 加 amd64 支持时**不递增**：arm64 的 rootfs 内容一个字节都没变，递增只会让所有
# 存量用户收到一次「有新运行时」的无意义提示。amd64 是全新资产，自带独立 metadata。
ROOTFS_REV="${ROOTFS_REV:-2}"
WORK="${WORK:-/tmp/dsh-runtime}"
OUT="${OUT:-$PWD/out}"

# ── 架构映射表 ──
# 每加一项都要问「这个值在另一个架构上是什么」，别再往下面散落 if。
case "$TARGET_ARCH" in
  arm64)
    UBUNTU_ARCH="arm64"           # ubuntu-base tarball 与 apt 索引里的架构名
    NODE_ARCH="arm64"             # nodejs.org 的 linux-<arch> 命名
    NPM_CPU="arm64"               # npm --cpu
    PREBUILD_KEEP="linux-arm64"   # node-pty prebuilds 里保留的目录
    APT_REPO_PATH="ubuntu-ports"  # arm64 的 deb 在 ports 仓库
    MULTIARCH="aarch64-linux-gnu"
    ELF_MACHINE=183               # EM_AARCH64
    ANDROID_ABI="arm64-v8a"       # metadata.json 的 arch（对齐 Build.SUPPORTED_ABIS）
    ASSET_SUFFIX=""               # 沿用旧名，向后兼容
    # 异架构原生模块黑名单：本架构**不该**出现的产物
    BAD_NATIVE_PATHS=("*linux-x64*" "*darwin*" "*win32*" "*x64*")
    ;;
  amd64)
    UBUNTU_ARCH="amd64"
    NODE_ARCH="x64"
    NPM_CPU="x64"
    PREBUILD_KEEP="linux-x64"
    APT_REPO_PATH="ubuntu"        # amd64 在主仓库，不是 ports
    MULTIARCH="x86_64-linux-gnu"
    ELF_MACHINE=62                # EM_X86_64
    ANDROID_ABI="x86_64"
    ASSET_SUFFIX="-x86_64"
    # 注意：这里绝不能像 arm64 那样拒 *x64* —— linux-x64 正是我们要的产物
    BAD_NATIVE_PATHS=("*linux-arm64*" "*darwin*" "*win32*" "*arm64*")
    ;;
  *)
    echo "!! TARGET_ARCH 只支持 arm64 / amd64，收到 $TARGET_ARCH" >&2
    exit 2
    ;;
esac

echo "==> 目标架构 $TARGET_ARCH（ubuntu=$UBUNTU_ARCH · node=linux-$NODE_ARCH · abi=$ANDROID_ABI）"

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

echo "==> [1/9] 下载 Ubuntu ${UBUNTU_RELEASE} ${UBUNTU_ARCH} base rootfs"
BASE_TAR="$WORK/base.tar.gz"
BASE_PATH="ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-${UBUNTU_ARCH}.tar.gz"
if [ ! -f "$BASE_TAR" ]; then
  # 国内镜像把 cdimage 挂在 /ubuntu-cdimage/ 前缀下，**不是**根路径 —— 原来的 URL
  # 少了这一段，nju/hit/aliyun 一律 404，等于每次构建都白试一圈才回落到
  # cdimage.ubuntu.com（也就是一直在走最慢的那条路）。
  try_download "$BASE_TAR" \
    "https://mirror.nju.edu.cn/ubuntu-cdimage/$BASE_PATH" \
    "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/$BASE_PATH" \
    "https://mirrors.hit.edu.cn/ubuntu-cdimage/$BASE_PATH" \
    "https://mirrors.aliyun.com/ubuntu-cdimage/$BASE_PATH" \
    "https://mirrors.bfsu.edu.cn/ubuntu-cdimage/$BASE_PATH" \
    "https://mirrors.huaweicloud.com/ubuntu-cdimage/$BASE_PATH" \
    "https://cdimage.ubuntu.com/$BASE_PATH"
fi
rm -rf "$ROOTFS"; mkdir -p "$ROOTFS"
tar -xzf "$BASE_TAR" -C "$ROOTFS"
echo "    rootfs 顶层: $(ls "$ROOTFS" | tr '\n' ' ')"

echo "==> [2/9] 安装 Node.js ${NODE_VER} (linux-${NODE_ARCH})"
NODE_TAR="$WORK/node.tar.xz"
NODE_FILE="node-${NODE_VER}-linux-${NODE_ARCH}.tar.xz"
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
# --strip-components=1 把 node-vX-linux-<arch>/{bin,lib,include,share} 摊进 /usr/local
tar -xJf "$NODE_TAR" -C "$ROOTFS/usr/local" --strip-components=1
test -x "$ROOTFS/usr/local/bin/node"

echo "==> [3/9] 安装 @deepseek-ai/dsh@${DSH_VERSION}"
# 用 runner（x86_64）的 npm 装进目标 rootfs 的前缀。
# --os/--cpu 必须显式指定：dsh 依赖 sharp 与 koffi，它们通过 optionalDependencies
# 按宿主平台挑预编译包，不指定就会装成 runner 平台的 .node，在目标上一 require 就炸。
# amd64 目标恰好与 runner 同架构，但仍然显式写死 —— 别让正确性依赖「runner 是什么」。
# --ignore-scripts 同时挡掉任何想在构建机上编译产物的 postinstall。
npm install --global \
  --prefix "$ROOTFS/usr/local" \
  --os=linux --cpu="$NPM_CPU" \
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
# 只留目标架构那份：既减掉约 24 MB，也让下面的异架构自检不必给它开特例。
find "$ROOTFS/usr/local/lib/node_modules" -type d -name prebuilds | while read -r d; do
  find "$d" -mindepth 1 -maxdepth 1 -type d ! -name "$PREBUILD_KEEP" -exec rm -rf {} +
done

# 架构自检：任何异架构的原生模块留在 rootfs 里都是隐患，设备上 require 到就是
# ENOEXEC。CI 阶段直接失败比让用户在设备上排查便宜得多。
#
# 黑名单必须**按目标架构取反**：amd64 目标要的正是 linux-x64，照 arm64 那套
# 硬编码拒 *x64* 会把想要的产物当成脏东西删/报错。
FIND_BAD=()
for pat in "${BAD_NATIVE_PATHS[@]}"; do
  [ ${#FIND_BAD[@]} -eq 0 ] || FIND_BAD+=(-o)
  FIND_BAD+=(-path "$pat")
done
BAD_NATIVE="$(find "$ROOTFS/usr/local/lib/node_modules" -name "*.node" \
  \( "${FIND_BAD[@]}" \) 2>/dev/null || true)"
if [ -n "$BAD_NATIVE" ]; then
  echo "!! rootfs 里出现非 ${TARGET_ARCH} 原生模块："
  printf '   %s\n' $BAD_NATIVE
  exit 1
fi
NATIVE_COUNT="$(find "$ROOTFS/usr/local/lib/node_modules" -name "*.node" 2>/dev/null | wc -l)"
echo "    原生模块 ${NATIVE_COUNT} 个，未发现异架构产物"

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

echo "==> [4/9] 安装 python3（无线 ADB 配对依赖）"
# 无线 ADB 配对（AdbBridge / adb-pair.py）需要容器内的 python3，
# 而 ubuntu-base 里没有它。手机上第一次配对才 apt install 的话：
#  - 要联网、要 apt 在 proot 下正常工作（dpkg 的 postinst 常在 proot 下失败）；
#  - 用户点「配对」后要等好几分钟，失败原因还很难查。
# 所以这里直接把 python3 及其依赖解包进 rootfs：
# 只用 dpkg-deb -x（纯解包，不跑任何 maintainer script，不需要目标架构可执行），
# postinst 真正做的事（sitecustomize.py 与 dist-packages 目录）下面手工补上。
# 包名清单与架构无关：这 15 个在 noble 的 arm64 与 amd64 索引里都存在。
PY_PKGS="python3.12-minimal libpython3.12-minimal libpython3.12-stdlib python3.12
         python3-minimal python3 libpython3-stdlib
         libexpat1 libsqlite3-0 libreadline8t64 readline-common
         libnsl2 libtirpc3t64 media-types netbase tzdata"
# arm64 的 deb 在 ubuntu-ports，amd64 在主仓库 ubuntu —— 路径不同，镜像域名相同。
APT_MIRRORS="https://mirrors.tuna.tsinghua.edu.cn/${APT_REPO_PATH}
             https://mirrors.aliyun.com/${APT_REPO_PATH}
             https://mirror.nju.edu.cn/${APT_REPO_PATH}"
if [ "$TARGET_ARCH" = "arm64" ]; then
  APT_MIRRORS="$APT_MIRRORS
             http://ports.ubuntu.com/ubuntu-ports"
else
  APT_MIRRORS="$APT_MIRRORS
             http://archive.ubuntu.com/ubuntu"
fi

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
    if ! curl -fsSL --connect-timeout 15 "$m/dists/$suite/main/binary-${UBUNTU_ARCH}/Packages.gz" \
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
  # 纯解包：不执行 maintainer script（它们要在目标架构上跑；arm64 时 runner 根本
  # 跑不了，amd64 时也不该让 rootfs 的正确性依赖构建机环境）。
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

# 自检：解包出来的解释器必须是目标架构，且 stdlib 齐全（缺 lib-dynload 会在设备上才炸）
test -x "$ROOTFS/usr/bin/python3.12"
head -c 20 "$ROOTFS/usr/bin/python3.12" | od -An -tx1 | tr -d ' \n' | grep -q '^7f454c46' \
  || { echo "!! python3.12 不是 ELF"; exit 1; }
# e_machine 是 2 字节小端；原来只读 1 字节，对 183(0x00b7) 与 62(0x003e) 都刚好等于
# 低位字节而侥幸成立，这里改成读满 2 字节，别再依赖这个巧合。
PY_ARCH="$(od -An -tu2 -j18 -N2 "$ROOTFS/usr/bin/python3.12" | tr -d ' \n')"
test "$PY_ARCH" = "$ELF_MACHINE" \
  || { echo "!! python3.12 不是 $TARGET_ARCH (e_machine=$PY_ARCH，期望 $ELF_MACHINE)"; exit 1; }
test -L "$ROOTFS/usr/bin/python3"
DYNLOAD_COUNT="$(ls "$ROOTFS/usr/lib/python3.12/lib-dynload"/*.so 2>/dev/null | wc -l)"
test "$DYNLOAD_COUNT" -ge 40 || { echo "!! lib-dynload 只有 $DYNLOAD_COUNT 个模块"; exit 1; }
for m in _ssl _hashlib _sqlite3 _ctypes _decimal; do
  ls "$ROOTFS/usr/lib/python3.12/lib-dynload/$m."*.so >/dev/null 2>&1 \
    || { echo "!! 缺少 python 模块 $m"; exit 1; }
done
test -f "$ROOTFS/usr/lib/python3.12/ssl.py"
echo "    python3 已就绪 · lib-dynload $DYNLOAD_COUNT 个模块"

echo "==> [5/9] 安装 git（git 源插件依赖）"
# 插件目录里 2663 条有 1357 条（51%）的安装命令是 `github:owner/name` 规格，
# pnpm 解析它要 `git ls-remote`；ubuntu-base 没有 git，于是这一半插件全装不上
# （真机报 ERR_PNPM_GIT_RESOLVE_FAILED: git executable not found on PATH）。
# 手机上现装的问题跟 python3 一样：要联网、apt 的 postinst 在 proot 下常失败、
# 用户要干等好几分钟。所以同样预解包进 rootfs。
#
# 因为是 dpkg-deb -x 纯解包，**dpkg 的依赖关系没人替我们解**，包列表必须手写全。
# r1 只列了直接依赖，漏掉 libcurl-gnutls 的 8 个传递依赖：git 本体只链
# libpcre2/libz/libc 所以看着好用，但 git-remote-https（pnpm 走 https 克隆真正
# exec 的那个）链 libcurl，一 exec 就 `cannot find libnghttp2.so.14`。
# 现在结尾用 check-elf-closure.js 求真闭包兜底，别再靠手写列表的正确性。
#
# git 只用到 perl 跑几个辅助脚本（add -i、send-email 之类），核心命令是 C 实现，
# 但 dpkg 的依赖关系摆在那儿，缺了 perl 一些子命令会直接报错，所以一并带上。
GIT_PKGS="git git-man liberror-perl perl perl-base perl-modules-5.38
          libcurl3t64-gnutls libpcre2-8-0 zlib1g
          libnghttp2-14 librtmp1 libssh-4 libpsl5t64
          libgssapi-krb5-2 libkrb5-3 libk5crypto3 libkrb5support0
          libldap2 libsasl2-2 libsasl2-modules-db libkeyutils1
          libbrotli1 libexpat1"

for pkg in $GIT_PKGS; do
  info="$(resolve_deb "$pkg")" || { echo "!! 索引里找不到 $pkg"; exit 1; }
  ver="$(printf '%s' "$info" | cut -f1)"
  fn="$(printf '%s' "$info" | cut -f2)"
  echo "    $pkg $ver"
  curl -fsSL --connect-timeout 20 --retry 2 -o "$DEB_DIR/$pkg.deb" "$APT_BASE/$fn"
  # 同 python3：纯解包，不跑 maintainer script
  dpkg-deb -x "$DEB_DIR/$pkg.deb" "$ROOTFS"
done

# 自检：git-remote-https 才是 pnpm 走 https 克隆时真正调用的那个
test -x "$ROOTFS/usr/bin/git"
test -x "$ROOTFS/usr/lib/git-core/git-remote-https" \
  || { echo "!! 缺少 git-remote-https（https 克隆会失败）"; exit 1; }
test -x "$ROOTFS/usr/bin/perl" || { echo "!! 缺少 perl"; exit 1; }
echo "    git 已就绪"

echo "==> [6/9] 检查动态库依赖闭合"
# 「文件存在 + 是 ELF」这种自检拦不住缺库（r1 就是这么放过去的），
# 这里从 git-core / perl 扩展 / python3 出发递归解析 DT_NEEDED 求真闭包。
node "$(dirname "$0")/check-elf-closure.js" --arch="$TARGET_ARCH" "$ROOTFS" \
  usr/lib/git-core \
  "usr/lib/${MULTIARCH}/perl" \
  usr/lib/python3.12/lib-dynload

echo "==> [7/9] 容器内初始设置"
install -d -m 700 "$ROOTFS/root/.dsh"
install -d -m 1777 "$ROOTFS/tmp"
# APT 换国内源（用户在容器里 apt install 时不至于卡住）；DNS 由 App 在安装后写入。
# 仓库路径要跟着架构走：arm64 在 ubuntu-ports，amd64 在 ubuntu。
cat > "$ROOTFS/etc/apt/sources.list.d/ubuntu.sources" <<EOF
Types: deb
URIs: https://mirrors.tuna.tsinghua.edu.cn/${APT_REPO_PATH}/
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

echo "==> [8/9] 打包 rootfs${ASSET_SUFFIX}.tar.gz"
TARBALL="$OUT/rootfs${ASSET_SUFFIX}.tar.gz"
rm -f "$TARBALL"
# numeric-owner + 不带前导目录：App 侧 TarGzipExtractor 直接铺到 filesDir/rootfs
tar --numeric-owner -czf "$TARBALL" -C "$ROOTFS" .
SIZE=$(stat -c %s "$TARBALL")
SHA=$(sha256sum "$TARBALL" | cut -d' ' -f1)
echo "    $TARBALL  $((SIZE / 1024 / 1024)) MB  sha256=$SHA"

echo "==> [9/9] 生成 metadata${ASSET_SUFFIX}.json"
REPO="${GITHUB_REPOSITORY:-IPF-Sinon/DSH-Folk}"
TAG="${RELEASE_TAG:-runtime-latest}"
ASSET="https://github.com/${REPO}/releases/download/${TAG}/rootfs${ASSET_SUFFIX}.tar.gz"
cat > "$OUT/metadata${ASSET_SUFFIX}.json" <<EOF
{
  "version": "${DSH_REAL_VERSION}-ubuntu${UBUNTU_RELEASE}-r${ROOTFS_REV}",
  "url": "${ASSET}",
  "sha256": "${SHA}",
  "sizeBytes": ${SIZE},
  "mirrors": [
    "https://v6.gh-proxy.org/${ASSET}",
    "https://axisnow.gh-proxy.org/${ASSET}"
  ],
  "arch": "${ANDROID_ABI}",
  "dsh": "${DSH_REAL_VERSION}",
  "nodeVersion": "${NODE_VER}",
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
cat "$OUT/metadata${ASSET_SUFFIX}.json"
