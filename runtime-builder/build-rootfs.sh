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

echo "==> [1/6] 下载 Ubuntu ${UBUNTU_RELEASE} arm64 base rootfs"
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

echo "==> [2/6] 安装 Node.js ${NODE_VER} (linux-arm64)"
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

echo "==> [3/6] 安装 @deepseek-ai/dsh@${DSH_VERSION}"
# 用 runner 的 npm 装进目标 rootfs 的前缀。dsh 是纯 JS，无需为 arm64 编译；
# --ignore-scripts 防止任何 postinstall 在 x86 上生成宿主架构产物。
npm install --global \
  --prefix "$ROOTFS/usr/local" \
  --ignore-scripts --no-audit --no-fund \
  "@deepseek-ai/dsh@${DSH_VERSION}"

DSH_ENTRY="$ROOTFS/usr/local/lib/node_modules/@deepseek-ai/dsh"
test -d "$DSH_ENTRY"
DSH_REAL_VERSION="$(node -p "require('$DSH_ENTRY/package.json').version")"
echo "    dsh = $DSH_REAL_VERSION"

# npm 生成的 bin 软链会指向 runner 路径，重建成容器内相对链接
rm -f "$ROOTFS/usr/local/bin/dsh"
ln -s "../lib/node_modules/@deepseek-ai/dsh/bin/dsh.js" "$ROOTFS/usr/local/bin/dsh" 2>/dev/null \
  || ln -s "../lib/node_modules/@deepseek-ai/dsh/dist/cli.js" "$ROOTFS/usr/local/bin/dsh"

echo "==> [4/6] 容器内初始设置"
install -d -m 700 "$ROOTFS/root/.dsh"
install -d -m 1777 "$ROOTFS/tmp"
# APT 换国内源（用户在容器里 apt install 时不至于卡住）；DNS 由 App 在安装后写入
cat > "$ROOTFS/etc/apt/sources.list.d/ubuntu.sources" <<EOF
Types: deb
URIs: https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/
Suites: ${UBUNTU_RELEASE} ${UBUNTU_RELEASE}-updates ${UBUNTU_RELEASE}-backports
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

echo "==> [5/6] 打包 rootfs.tar.gz"
TARBALL="$OUT/rootfs.tar.gz"
rm -f "$TARBALL"
# numeric-owner + 不带前导目录：App 侧 TarGzipExtractor 直接铺到 filesDir/rootfs
tar --numeric-owner -czf "$TARBALL" -C "$ROOTFS" .
SIZE=$(stat -c %s "$TARBALL")
SHA=$(sha256sum "$TARBALL" | cut -d' ' -f1)
echo "    $TARBALL  $((SIZE / 1024 / 1024)) MB  sha256=$SHA"

echo "==> [6/6] 生成 metadata.json"
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
