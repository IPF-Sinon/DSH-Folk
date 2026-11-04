#!/system/bin/sh
# DSH-Folk chroot 启动器 —— 这是**模板**，App 在起容器前把下面这些 @占位符@ 替换掉，
# 再写进 filesDir/chroot/launch.sh 执行。
#
# 为什么是文件而不是在 Kotlin 里一行行 append：
#  - 这份脚本会被 shell 解析两次（su -c 'sh launch.sh' 一次、脚本自己再一次），
#    引号与 $ 极容易写错。issue #12 的 `launch.sh[N]: no closing quote` 就是 Kotlin
#    普通字符串里的 $ 转义写法写岔了 —— 落到脚本里的是一段非法的参数展开，
#    shell 一直到 EOF 才发现引号没闭合，报的行号还是「最后一行 +1」，排查绕了三轮；
#  - 放在 assets 里，本地与 CI 都能直接 `sh -n` 做语法自检（tools/check-chroot.js），
#    不必等真机报那一行莫名其妙的行号。
#
# 占位符（Kotlin 侧必须逐个 replace，门禁会核对有没有漏）：
#   @RFS@ @MNT@ @APP_UID@ @APP_GID@ @UNMOUNT_TARGETS@ @MOUNT_LINES@ @GUEST_ARGS@ @ENV@
RFS=@RFS@
MNT=@MNT@
APP_UID=@APP_UID@
APP_GID=@APP_GID@
CREATED_MNT=0

# 拆掉这次自己建的全部挂载：先子后父，一律 -l（懒卸载），免得被占用时卡住脚本。
umount_all() {
  for d in @UNMOUNT_TARGETS@ "$MNT/sys" "$MNT/proc" "$MNT/dev"; do
    umount -l "$d" 2>/dev/null
  done
  umount -l "$MNT" 2>/dev/null
}

# 把容器数据修回 App uid。
# chroot 是**真 root**，它写出的 /root/.dsh、/tmp、/usr/local 归 root；而切回
# proot/proroot 时进程是 App uid，连读都读不了 —— 真机表现就是 proot 启动直接报
# `EACCES ... /root/.dsh/profiles/web/cordis.patch.yml`（issue #12）。
# `find ! -user` 是主判据（干净时只是一次遍历）；toybox 的 find 万一不认它，
# 退化成无条件 chown 一次，宁可慢也不能漏。
restore_ownership() {
  if ! command -v find >/dev/null 2>&1 ||
     [ -n "$(find "$RFS/root" ! -user "$APP_UID" 2>/dev/null | head -n 1)" ]; then
    chown -R "$APP_UID:$APP_GID" "$RFS/root" "$RFS/tmp" "$RFS/usr/local" 2>/dev/null
  fi
}

cleanup() {
  if [ "$CREATED_MNT" = 1 ]; then
    umount_all
  fi
  restore_ownership
}

# 退出（含异常）与被 TERM/INT 都要收尾。
# **绝不能再用 `exec chroot`**：exec 会把本 shell 替换掉，EXIT trap 永远不会执行 ——
# 挂载一直留在全局命名空间里、属主也不会修回，这正是 issue #12 里
# 「chroot 用完切 proot 就 EACCES」的由来。
trap cleanup EXIT INT TERM

# Android 应用进程的 umask 是 077，而这里是真 root：照它建出来的挂载点是 0700 root 拥有，
# 切回 proot 连穿都穿不过去。umask 000 让本脚本与容器写出的文件对 App uid 可读写
# （外层目录是 App 私有的 0700，不对外暴露）。
umask 000
mkdir -p "$MNT" || exit 90
chmod 0777 "$MNT" 2>/dev/null

# 已经有挂载时区分两种情况：
#  - 服务正在跑，这只是它的一个短命子命令（插件操作、工作区检查）→ 必须原样复用。
#    拆掉会把正在跑的容器里的 /dev、/proc、/sdcard 一起抽掉；
#  - 上次异常退出的残留，而 rootfs 已被运行时更新换掉 → 必须重建，
#    否则容器会跑在旧 rootfs 的视图上（运行时替换会换掉整个 rootfs 目录）。
if grep -qs " $MNT " /proc/mounts; then
  if [ ! -d "$MNT/usr/local/lib/node_modules/@deepseek-ai/dsh" ]; then
    umount_all
  fi
fi

if ! grep -qs " $MNT " /proc/mounts; then
  # 先把 rootfs bind 到 $MNT，再把 /dev、/proc、/sys 与存储/工作区挂进 $MNT，
  # 最后 chroot "$MNT" 进去。**不能直接 `chroot "$RFS"`**：那样所有挂载点都落在
  # rootfs 之外，容器里根本看不到 —— 真机表现就是「新建工作区后列目录 ENOENT」。
  mount --bind "$RFS" "$MNT" || exit 91
  CREATED_MNT=1
  mkdir -p "$MNT/dev" "$MNT/proc" "$MNT/sys"
  mount --bind /dev "$MNT/dev" || exit 92
  mount -t proc proc "$MNT/proc" || exit 93
  mount --bind /sys "$MNT/sys" 2>/dev/null
@MOUNT_LINES@
fi

# 进容器前先修一次（上次 chroot 可能留下 root 属主的文件），退出时 cleanup 再修一次
restore_ownership

# guest argv 交给脚本自己的位置参数：每项只被 shellQuote 一次，引号配对与 argv 边界
# 都由 shell 保证。set 前的 -- 是防某一项以 - 开头被当选项。
set -- @GUEST_ARGS@

# env -i 起干净环境，再逐个喂我们这份，最后把位置参数摊开
chroot "$MNT" /usr/bin/env -i @ENV@ "$@"
RC=$?
exit $RC
