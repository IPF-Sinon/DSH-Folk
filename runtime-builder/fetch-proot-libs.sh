#!/usr/bin/env bash
# 从 Termux 官方 apt 仓库提取 proot 系列可执行文件，落成 app/libs/<abi>/*.so。
#
# 为什么需要这个脚本：Android 的 app_data_file 带 noexec，只有 nativeLibraryDir
# 里的 .so 可执行，所以 proot 及其 loader / 依赖库必须改名成 lib*.so 打进 APK。
# 原来 arm64 那五个文件是人工放进仓库的，没有可复核的来源记录；加 x86_64 时
# 把整个过程固化成脚本，将来升级 proot 或再加架构都走同一条路。
#
# 用法：
#   bash runtime-builder/fetch-proot-libs.sh x86_64        # 只出 x86_64
#   bash runtime-builder/fetch-proot-libs.sh aarch64       # 只出 arm64-v8a
#   PROOT_VERSION=5.1.107.92 bash runtime-builder/fetch-proot-libs.sh x86_64
#
# 依赖：curl、ar（binutils）、tar + xz（deb 的 data.tar.xz）。GitHub runner 自带。
#
# 注意：默认**不覆盖**已存在的文件（arm64 那五个是现网在用的既有产物，来源与
# 当前上游 deb 不同 —— 仓库里的 libproot.so 内含 5.1.107.91 且 loader 体积也不
# 一样）。要强制覆盖传 FORCE=1，但换 arm64 的二进制等于换现网运行时，务必真机验证。
set -euo pipefail

TERMUX_ARCH="${1:-}"
if [ -z "$TERMUX_ARCH" ]; then
  echo "用法: $0 <aarch64|x86_64>" >&2
  exit 2
fi

case "$TERMUX_ARCH" in
  aarch64) ANDROID_ABI="arm64-v8a"; WANT_MACHINE=183; WANT_MACHINE32=40 ;;
  x86_64)  ANDROID_ABI="x86_64";    WANT_MACHINE=62;  WANT_MACHINE32=3  ;;
  *) echo "!! 只支持 aarch64 / x86_64，收到 $TERMUX_ARCH" >&2; exit 2 ;;
esac

PROOT_VERSION="${PROOT_VERSION:-5.1.107.92}"
TALLOC_VERSION="${TALLOC_VERSION:-2.4.3}"
SHMEM_VERSION="${SHMEM_VERSION:-0.7}"
REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/libs/$ANDROID_ABI"
WORK="${WORK:-$(mktemp -d)}"
PREFIX="data/data/com.termux/files/usr"

mkdir -p "$DEST" "$WORK"

# deb → 解出 data.tar.xz → 摊到 $WORK/<pkg>/
unpack_deb() {
  local url="$1" name="$2"
  local deb="$WORK/$name.deb" out="$WORK/$name"
  echo "    下载 $url"
  curl -fsSL --connect-timeout 20 --retry 2 -o "$deb" "$url"
  rm -rf "$out"; mkdir -p "$out"
  # ar 只取 data 成员；Termux 用 xz 压缩（.zst 的话这里要跟着改）
  (cd "$out" && ar x "$deb" data.tar.xz && tar -xJf data.tar.xz && rm -f data.tar.xz)
}

# 读 ELF 的 e_machine（小端 ELF64/ELF32 通用：偏移 0x12 起 2 字节）
elf_machine() {
  od -An -tu2 -j18 -N2 "$1" | tr -d ' \n'
}

# 把一个文件装成 lib*.so，并断言架构
install_lib() {
  local src="$1" dst_name="$2" want="$3"
  local dst="$DEST/$dst_name"
  if [ ! -f "$src" ]; then
    echo "!! 源文件不存在: $src" >&2
    exit 1
  fi
  local m
  m="$(elf_machine "$src")"
  if [ "$m" != "$want" ]; then
    echo "!! $dst_name 架构不符：e_machine=$m，期望 $want" >&2
    exit 1
  fi
  if [ -f "$dst" ] && [ "${FORCE:-0}" != "1" ]; then
    echo "    跳过 $dst_name（已存在；FORCE=1 可覆盖）"
    return 0
  fi
  install -m 644 "$src" "$dst"
  echo "    写入 $dst_name  $(stat -c %s "$dst") bytes  e_machine=$m"
}

echo "==> [1/4] proot $PROOT_VERSION ($TERMUX_ARCH)"
unpack_deb "$REPO/pool/main/p/proot/proot_${PROOT_VERSION}_${TERMUX_ARCH}.deb" proot
install_lib "$WORK/proot/$PREFIX/bin/proot"               libproot.so         "$WANT_MACHINE"
install_lib "$WORK/proot/$PREFIX/libexec/proot/loader"    libprootloader.so   "$WANT_MACHINE"
# loader32 是给容器里 32 位子进程用的，本身就是 32 位 ELF（arm64 上是 ARM，x86_64 上是 i386）
install_lib "$WORK/proot/$PREFIX/libexec/proot/loader32"  libprootloader32.so "$WANT_MACHINE32"

echo "==> [2/4] libtalloc $TALLOC_VERSION"
unpack_deb "$REPO/pool/main/libt/libtalloc/libtalloc_${TALLOC_VERSION}_${TERMUX_ARCH}.deb" talloc
# 取带完整版本号的实体文件，不要软链接；App 侧 copyExec 会改名成 SONAME libtalloc.so.2
install_lib "$WORK/talloc/$PREFIX/lib/libtalloc.so.${TALLOC_VERSION}" libtalloc.so "$WANT_MACHINE"

echo "==> [3/4] libandroid-shmem $SHMEM_VERSION"
unpack_deb "$REPO/pool/main/liba/libandroid-shmem/libandroid-shmem_${SHMEM_VERSION}_${TERMUX_ARCH}.deb" shmem
install_lib "$WORK/shmem/$PREFIX/lib/libandroid-shmem.so" libandroidshmem.so "$WANT_MACHINE"

echo "==> [4/4] 校验 $ANDROID_ABI 目录完整性"
MISSING=""
for n in libproot.so libprootloader.so libprootloader32.so libtalloc.so libandroidshmem.so; do
  [ -f "$DEST/$n" ] || MISSING="$MISSING $n"
done
if [ -n "$MISSING" ]; then
  echo "!! 缺少:$MISSING" >&2
  exit 1
fi
echo "    $ANDROID_ABI 五个文件齐备"
ls -la "$DEST"
