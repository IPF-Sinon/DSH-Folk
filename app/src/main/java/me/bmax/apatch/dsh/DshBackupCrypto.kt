package me.bmax.apatch.dsh

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * DSH 备份的容器加密（App 侧），与容器里 `dsh-config-manager` 插件的 `security/encryption.js`
 * **逐字节兼容**。
 *
 * 为什么需要它：备份原来完全靠插件 —— 插件导出明文 ZIP 后自己加密，导入时 App 把密码交给插件
 * 的 /decrypt-archive。改成 App 侧加解密后，App 才能在插件产出的明文 ZIP 里补进自己的文件
 * （选中的会话、App 设置、凭据）再加密，导入时也能先解成明文 ZIP 再交给插件分析。
 * 代价是两边的算法必须 1:1 对齐，否则会出现「手机导出的备份电脑打不开」这种最难查的问题；
 * 而本机没有 Android SDK，编译与真机验证都得等 CI，能用的手段只剩「严格照抄格式 + 拿插件
 * 自己产出的向量做交叉验证」（见 [selfTest]）。
 *
 * 容器布局（`DCA1` 整包加密与 `DSC1` 凭据只差 magic，其余完全相同）：
 * ```
 * magic(4B ASCII) + version(1B = 1) + salt(16B) + iv(12B) + authTag(16B) + ciphertext
 * ```
 * KDF 与 AEAD：scrypt（RFC 7914，N=16384 r=8 p=1，输出 32 字节密钥）+ AES-256-GCM
 * （12 字节 iv、16 字节 tag、**不带 AAD**）。密码按 **UTF-8 字节**参与 KDF。
 *
 * 为什么 header 固定 49 字节：解密方必须先拿到 salt/iv 才能派生密钥，而派生一次要几百毫秒，
 * 所以这三段随机量只能按固定偏移从头部直接切出来（插件侧同样是 `HEADER_LENGTH` 常量）。
 * 为什么 tag 放在 header 而不是密文尾部：Node 的 crypto 把 tag 从密文里单独取出来
 * （`cipher.getAuthTag()`），插件据此拼的容器；Java 的 Cipher 恰好相反，`doFinal()` 返回的
 * 是「密文||tag」，所以本文件在加密侧拆一次、在解密侧拼回去（见 [decryptBlock]）。
 *
 * 本文件只做字节级加解密，不碰文件系统：ZIP 怎么组装、会话怎么挑、manifest 怎么写都属于调用方。
 */
object DshBackupCrypto {

    /** 整包加密备份容器的 magic（D C A rchive）。 */
    const val ARCHIVE_MAGIC = "DCA1"

    /** 凭据容器（包里 `security/secrets.enc`）的 magic。 */
    const val SECRETS_MAGIC = "DSC1"

    /** 容器版本；插件侧目前只有 1，比它新的一律拒绝（否则可能误解出错误内容）。 */
    const val VERSION = 1

    /**
     * scrypt 参数。
     *
     * 这三个值不能改：插件把同样一组值写进了 manifest 的 `security.encryption.kdfParams`，
     * 而且**解密时以 manifest 里的值为准**（插件会校验 N 在 2^14..2^20），所以 App 侧派生
     * 出来的密钥必须与这组参数逐一对应。内存开销 N*r*128 = 16 MB，手机上一次几百毫秒。
     */
    const val SCRYPT_N = 16384
    const val SCRYPT_R = 8
    const val SCRYPT_P = 1
    const val KEY_LENGTH = 32
    const val SALT_LENGTH = 16
    const val IV_LENGTH = 12
    const val TAG_LENGTH = 16

    /** magic(4) + version(1) + salt(16) + iv(12) + authTag(16) = 49。 */
    const val HEADER_LENGTH = 4 + 1 + SALT_LENGTH + IV_LENGTH + TAG_LENGTH

    /** `secrets.enc` 的产物：容器本体 + 要写进 manifest 的三段 base64（标准字母表、带 padding）。 */
    data class Secrets(
        val blob: ByteArray,
        val saltB64: String,
        val ivB64: String,
        val authTagB64: String
    )

    private const val MAGIC_LENGTH = 4
    private const val VERSION_OFFSET = MAGIC_LENGTH
    private const val SALT_OFFSET = VERSION_OFFSET + 1
    private const val IV_OFFSET = SALT_OFFSET + SALT_LENGTH
    private const val TAG_OFFSET = IV_OFFSET + IV_LENGTH

    /** GCM tag 位数：容器里存 16 字节，Java 的 GCMParameterSpec 要的是位。 */
    private const val TAG_BITS = TAG_LENGTH * 8

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AES_ALGORITHM = "AES"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HMAC_LENGTH = 32
    private const val HEX_DIGITS = "0123456789abcdef"

    /** 容器里 salt/iv 每次导出都要重新随机：复用会让同一份备份的两个版本可被比对。 */
    private val random = SecureRandom()

    @Volatile
    private var selfTestFinished = false

    @Volatile
    private var selfTestReason: String? = null

    private val selfTestLock = Any()

    /** 流式加解密的块大小：几百 MB 的包也只占这两个缓冲区。 */
    private const val STREAM_BUFFER = 1 shl 16

    /**
     * 分块 GCM 容器版本（`DCA1` magic + version=2）。
     *
     * 为什么要它：Android 的 Conscrypt 对 AES/GCM 会把**整段密文攒到 doFinal() 才吐出来**
     * （见旧 [encryptArchiveToFile] 里的注释），所以「一把 GCM 从头加到尾」在真机上必然把整包
     * 读进内存——137 MB 的主题包就这样把 192 MB 的堆撑爆（OOM 现场：Failed to allocate a
     * 201195536 byte allocation）。分块格式把明文切成 [CHUNK_PLAIN_SIZE] 一段、每段独立一次
     * GCM，内存只占一个分块，任意大小都不再 OOM。
     *
     * 只有 App 自己产出/消费的大包（含主题/软件数据）走这条路；与插件、dsh-config-manager
     * 共享的小包仍是 version=1 的单段 DCA1，跨端兼容不受影响。解密侧按 version 字节自动分流，
     * 老备份（version=1）永远还能解。
     */
    const val VERSION_CHUNKED = 2

    /** 分块容器每块明文大小：4 MiB。峰值内存 ≈ 明文块 + 密文块 + Conscrypt 内部缓冲 ≈ 12 MB。 */
    private const val CHUNK_PLAIN_SIZE = 4 * 1024 * 1024

    /** 每块 IV 的随机前缀长度；后 4 字节放大端块序号，凑满 [IV_LENGTH]=12。 */
    private const val NONCE_PREFIX_LENGTH = 8

    /** 分块容器头：magic(4) + version(1) + salt(16) + noncePrefix(8) + chunkSize(4 BE) = 33。 */
    private const val CHUNK_HEADER_LENGTH = MAGIC_LENGTH + 1 + SALT_LENGTH + NONCE_PREFIX_LENGTH + 4

    /** 自检用的 scrypt 向量密码。 */
    private const val SELFTEST_PASSWORD = "dsh-folk-selftest-2026"

    /** 密码 [SELFTEST_PASSWORD]、salt 为 00 01 .. 0f 时，插件（Node crypto.scrypt）算出的 32 字节密钥。 */
    private const val SELFTEST_KEY_HEX =
        "7c2e75e77d092cd0fc4d39867b31159110060219527e58c7950edb111767ddb1"

    /**
     * 插件 `encryptArchive()` 产出的 DCA1 容器（base64），明文是 `DSH-Folk self-test payload\n`，
     * 密码同 [SELFTEST_PASSWORD]。
     *
     * 附带的 manifest 三段 base64 是 salt `6/HXbIexHXpiBvzutfCFKQ==`、iv `N71sC24zQZpQzIz6`、
     * authTag `kXLV65Q15F/Hk/GAR2Uag==`；实测从这段 blob 里切出来的是 salt `6/HXbIexHXpiBvzutfCFKQ==`、
     * iv `N71sC24zQZpQzIz6`、authTag `kXLV640Q15F/Hk/GAR2Uag==` —— 第三段与记录下来的值差一个字符，
     * 而只有 blob 里的那 16 字节能让 GCM 认证通过，所以以 blob 为准，自检也只断言 blob。
     */
    private const val SELFTEST_BLOB_B64 =
        "RENBMQHr8ddsh7EdemIG/O618IUpN71sC24zQZpQzIz6kXLV640Q15F/Hk/GAR2Uaifrk2/FsW4SgfFTQOxAwueA48kattc950Y0/Q=="

    /** DCA1 向量解出来的明文（末尾一个换行，用来把「多解/少解一个字节」也暴露出来）。 */
    private const val SELFTEST_PLAINTEXT = "DSH-Folk self-test payload\n"

    /** 往返自检的载荷：故意不是纯文本，避免「按字符串拼接」这类错误碰巧通过。 */
    private val selfTestPayload = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x21, 0x7F, 0x10, 0x0D, 0x0A)

    /**
     * 自检：返回 null 表示通过，否则返回一句中文原因。
     *
     * 为什么要自检、而且要带插件的向量：本机没有 Android SDK，这段代码写完到 CI 编译之前没有
     * 任何执行机会，而 scrypt / GCM 里最典型的错误（字节序、tag 位置、KDF 参数、密码编码）都会
     * 安静地算出「另一个正确但不同」的结果 —— 表现是备份文件生成了、密码也对，却谁也打不开。
     * 所以这里用插件自己产出的两段真实向量做交叉验证（等价于拿 Node 的 crypto 当参考实现），
     * 再补一次自加密往返。
     *
     * 结果缓存：整个自检要跑 4 次 scrypt（约 16 MB × 4），每次导出都重跑既慢又毫无新意。
     */
    fun selfTest(): String? {
        if (selfTestFinished) return selfTestReason
        synchronized(selfTestLock) {
            if (selfTestFinished) return selfTestReason
            val reason = runSelfTest()
            selfTestReason = reason
            selfTestFinished = true
            return reason
        }
    }

    private fun runSelfTest(): String? {
        // 1) scrypt 向量：先单独验 KDF，失败时能直接指向「派生」而不是容器拼装。
        val salt = ByteArray(SALT_LENGTH) { it.toByte() }
        val key = try {
            scrypt(
                SELFTEST_PASSWORD.toByteArray(StandardCharsets.UTF_8),
                salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LENGTH
            )
        } catch (e: Exception) {
            return "scrypt 向量派生失败（" + describe(e) + "）"
        }
        val hex = toHex(key)
        if (hex != SELFTEST_KEY_HEX) {
            return "scrypt 向量不匹配：期望 " + SELFTEST_KEY_HEX + "，实际 " + hex
        }

        // 2) DCA1 向量：解插件产出的真实容器，覆盖 header 偏移、tag 位置与「无 AAD」。
        val blob = try {
            Base64.decode(SELFTEST_BLOB_B64, Base64.DEFAULT)
        } catch (e: Exception) {
            return "DCA1 向量 base64 解不开（" + describe(e) + "）"
        }
        val plain = decryptArchive(blob, SELFTEST_PASSWORD)
            ?: return "DCA1 向量解不开：与插件产出的容器不兼容（header 布局或 GCM 参数对不上）"
        if (String(plain, StandardCharsets.UTF_8) != SELFTEST_PLAINTEXT) {
            return "DCA1 向量明文不符：期望 " + SELFTEST_PLAINTEXT.length + " 字节，实际 " + plain.size + " 字节"
        }

        // 3) 往返：向量只验了「解」，这里验「封」，顺带覆盖随机 salt/iv 与 Java 的 tag 拆分。
        val sealed = encryptArchive(selfTestPayload, SELFTEST_PASSWORD)
        val opened = decryptArchive(sealed, SELFTEST_PASSWORD)
        if (opened == null || !opened.contentEquals(selfTestPayload)) {
            return "自加密往返失败：封包后解回来的字节不一致"
        }
        return null
    }

    /** 字节流是不是整包加密容器（只看前 4 字节 magic）。 */
    fun isArchiveBlob(bytes: ByteArray): Boolean = magicMatches(bytes, ARCHIVE_MAGIC)

    /**
     * 明文 ZIP → DCA1 容器字节。
     *
     * 密码不能为空：空密码会被 scrypt 直接拒绝（HMAC 不接受空密钥），与其让它变成一个含糊的
     * 异常，不如在这里明确报错 —— 导出页面上也拦了一道。
     *
     * @throws IllegalArgumentException 密码为空。
     */
    fun encryptArchive(plainZip: ByteArray, password: String): ByteArray =
        seal(ARCHIVE_MAGIC, plainZip, password)

    /**
     * DCA1 容器 → 明文 ZIP。
     *
     * 密码错、密文被改、magic/version 不对、blob 被截断，全部返回 null：调用方只需要知道
     * 「解不开」，区分不出具体原因反而好 —— 密码错与密文被篡改本来就该给同一个提示
     * （插件那边也都是 BAD_PASSWORD），而不抛异常可以让 UI 层少一层 try。
     */
    fun decryptArchive(blob: ByteArray, password: String): ByteArray? =
        open(blob, ARCHIVE_MAGIC, password)

    /**
     * 凭据原文 → DSC1 容器（写进包里的 `security/secrets.enc`）+ manifest 需要的字段。
     *
     * 为什么凭据要单独一层容器而不是跟着整包一起加密：整包密码是「迁移时临时定的」，而
     * 凭据可能在**不加密的备份**里也要带上（用户只想带凭据、不要整包加密），插件就是这么分的：
     * 整包 DCA1、凭据 DSC1，两者都用同一个密码派生，但 salt/iv 各自独立。
     *
     * @throws IllegalArgumentException 密码为空。
     */
    fun encryptSecrets(credentialsYaml: String, password: String): Secrets {
        val blob = seal(SECRETS_MAGIC, credentialsYaml.toByteArray(StandardCharsets.UTF_8), password)
        return Secrets(
            blob = blob,
            saltB64 = encodeBase64(blob, SALT_OFFSET, SALT_LENGTH),
            ivB64 = encodeBase64(blob, IV_OFFSET, IV_LENGTH),
            authTagB64 = encodeBase64(blob, TAG_OFFSET, TAG_LENGTH)
        )
    }

    /**
     * 反解 secrets.enc；三段 base64 来自 manifest 的 `security.encryption` 字段。失败返回 null。
     *
     * 为什么还要传 manifest 的三段参数（blob 里明明就有）：插件在解密前会比对两者，不一致就报
     * TAMPERED —— 防止有人只改 manifest 里的参数、让调用方用错的 salt 去派生。这里保持一致：
     * 非空但不相等即判失败。空串视为 manifest 没写这三段，此时以 blob 内嵌参数为准（它们本身
     * 受 GCM 认证保护，改了照样解不开）。
     */
    fun decryptSecrets(
        blob: ByteArray,
        saltB64: String,
        ivB64: String,
        authTagB64: String,
        password: String
    ): String? {
        if (blob.size < HEADER_LENGTH) return null
        if (!magicMatches(blob, SECRETS_MAGIC)) return null
        if (blob[VERSION_OFFSET].toInt() != VERSION) return null
        val salt = blob.copyOfRange(SALT_OFFSET, SALT_OFFSET + SALT_LENGTH)
        val iv = blob.copyOfRange(IV_OFFSET, IV_OFFSET + IV_LENGTH)
        val tag = blob.copyOfRange(TAG_OFFSET, TAG_OFFSET + TAG_LENGTH)
        if (!matchesBase64(saltB64, salt)) return null
        if (!matchesBase64(ivB64, iv)) return null
        if (!matchesBase64(authTagB64, tag)) return null
        val plain = try {
            decryptBlock(password, salt, iv, tag, blob)
        } catch (e: Exception) {
            null
        }
        if (plain == null) return null
        return String(plain, StandardCharsets.UTF_8)
    }

    /**
     * 不读整文件，只看前 4 字节：这个文件是不是 DCA1 容器。
     *
     * 导入时要用它决定「是我们自己加密的包，还是插件产出的明文 ZIP」—— 判据必须是**内容**
     * 而不是扩展名：用户手上的备份可能被改名、也可能来自桌面端，而两者都是 .zip 结尾。
     */
    fun isArchiveBlobFile(file: File): Boolean = runCatching {
        FileInputStream(file).use { ins ->
            val head = ByteArray(4)
            ins.read(head) == 4 && magicMatches(head, ARCHIVE_MAGIC)
        }
    }.getOrDefault(false)

    /**
     * 流式加密整包（明文 ZIP 文件 → DCA1 容器文件）。
     *
     * 为什么不能直接用 [encryptArchive]：会话全带时明文 ZIP 很容易到几百 MB，先读成
     * ByteArray 再加密在手机上就是一次必然的 OOM。这里按 64 KB 块喂给 Cipher，全程只占
     * 两个固定缓冲区。
     *
     * 为什么要写两趟：DCA1 把 authTag 放在 header 的第 17..32 字节，而 tag 只有在**全部**
     * 数据加密完之后才产生。所以先写 49 字节占位 header、把密文顺着写出去，最后回到文件头
     * 把 magic/version/salt/iv/tag 补上 —— 比「先加密到内存再拼」省掉的正是那份内存。
     */
    /**
     * 流式版本的自检：真的落两个文件、加密、比对大小、再解回来逐字节核对。
     *
     * 为什么非要单独做这件事 —— [selfTest] 只覆盖内存里的那对函数，而导出用的是
     * [-encryptArchiveToFile] / [-decryptArchiveToFile]：**流式那条路一次都没被验证过**。
     * 出问题的现场就是它：用户拿到一个 49 字节的包（正好等于容器头长度、密文长度为 0），
     * 恢复自然失败。所以这里用 300KB（跨过 64KB 缓冲区好几次）做一遍真写入。
     *
     * @return null = 通过；否则返回一句能直接显示给人的原因
     */
    fun selfTestFiles(dir: File): String? = runCatching {
        dir.mkdirs()
        val plain = File(dir, "selftest-plain.bin")
        val blob = File(dir, "selftest-blob.bin")
        val back = File(dir, "selftest-back.bin")
        try {
            // 300KB 明文 + 故意调小的块（100KB）→ 跨 3 块、末块非满：把分块框架、每块 IV 序号、
            // AAD、末块标志一次跑齐。用的就是导出大包那条 encryptChunked/decryptChunked。
            val payload = ByteArray(300_000)
            random.nextBytes(payload)
            plain.writeBytes(payload)
            encryptChunked(plain, blob, SELFTEST_PASSWORD, 100_000)
            if (!isChunkedContainer(blob)) return "分块容器头不对：version 字节不是 $VERSION_CHUNKED"
            if (!decryptArchiveToFile(blob, back, SELFTEST_PASSWORD)) return "写出的分块容器解不回来（密码是对的）"
            if (back.length() != plain.length()) {
                return "解出来的大小不对：${back.length()} != ${plain.length()}"
            }
            val a = sha256File(plain)
            val b = sha256File(back)
            if (a != b) return "解出来的内容不一致（sha256 $a != $b）"
            // 截断检测：砍掉末尾 32 字节（末块的 tag/密文）后必须解不开，否则「缺数据也算成功」
            val truncated = File(dir, "selftest-trunc.bin")
            try {
                val full = blob.readBytes()
                truncated.writeBytes(full.copyOfRange(0, full.size - 32))
                if (decryptArchiveToFile(truncated, back, SELFTEST_PASSWORD)) {
                    return "截断的分块容器竟然解开了：分块认证没生效"
                }
            } finally {
                truncated.delete()
            }
            null
        } finally {
            plain.delete()
            blob.delete()
            back.delete()
        }
    }.getOrElse { "分块自检抛异常：" + it.javaClass.simpleName + ": " + it.message }

    /** 逐块算 sha256（比对用；不把整个文件读进内存）。 */
    private fun sha256File(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(STREAM_BUFFER)
        FileInputStream(f).use { ins ->
            var n = ins.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = ins.read(buf)
            }
        }
        return toHex(md.digest())
    }

    fun encryptArchiveToFile(plain: File, output: File, password: String) {
        require(password.isNotEmpty()) { "加密密码不能为空" }
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(password, salt), AES_ALGORITHM), GCMParameterSpec(TAG_BITS, iv))
        val buffer = ByteArray(STREAM_BUFFER)

        // 两趟写法：先把密文流到一个临时文件（顺带拿到 tag），再拼出「头 + 密文」。
        // 原来是在输出文件里先占位 49 字节、写完再用 RandomAccessFile 跳回去回填头部 ——
        // 少一个「写进去又回头改」的环节，出问题时也就少一种解释，而且每一步都能验。
        val body = File(output.parentFile, output.name + ".body")
        body.delete()
        var written = 0L
        var tag: ByteArray? = null
        BufferedOutputStream(FileOutputStream(body), STREAM_BUFFER).use { out ->
            BufferedInputStream(FileInputStream(plain), STREAM_BUFFER).use { ins ->
                var n = ins.read(buffer)
                while (n > 0) {
                    val chunk = cipher.update(buffer, 0, n)
                    if (chunk != null && chunk.isNotEmpty()) out.write(chunk)
                    written += n.toLong()
                    n = ins.read(buffer)
                }
            }
            // 关键：Android 的 Conscrypt 会把 AES/GCM 的数据攒到 doFinal() 才吐出来 ——
            // 上面那个 while 循环里 update() 一直返回 null，密文全在这里的返回值里。
            // 所以不能"直接把返回值当 tag"：它是「剩余密文 + 16 字节 tag」。
            // 真机现场：300000 字节明文（读满了，written=300000）最后写出 49 字节的空容器，
            // 就是因为这里只取了前 16 字节当 tag、剩下整段密文丢掉。
            val rest = cipher.doFinal()
            if (rest.size < TAG_LENGTH) {
                body.delete()
                throw IllegalStateException("doFinal 只返回 " + rest.size + " 字节，拿不到认证标签")
            }
            val restBody = rest.size - TAG_LENGTH
            if (restBody > 0) out.write(rest, 0, restBody)
            tag = rest.copyOfRange(restBody, rest.size)
        }
        // 这一条就是为「拿到的明文是空的」这种现场准备的：真发生了，报的是确切数字，
        // 而不是留下一个 49 字节、看起来成功的空容器（头 + 空密文的 tag 正好 49 字节）。
        if (written != plain.length()) {
            body.delete()
            throw IllegalStateException("只读到 $written 字节，而文件是 ${plain.length()} 字节")
        }
        val header = ByteArray(HEADER_LENGTH)
        writeMagic(header, ARCHIVE_MAGIC)
        header[VERSION_OFFSET] = VERSION.toByte()
        System.arraycopy(salt, 0, header, SALT_OFFSET, SALT_LENGTH)
        System.arraycopy(iv, 0, header, IV_OFFSET, IV_LENGTH)
        System.arraycopy(tag ?: throw IllegalStateException("没有拿到认证标签"), 0, header, TAG_OFFSET, TAG_LENGTH)
        BufferedOutputStream(FileOutputStream(output), STREAM_BUFFER).use { out ->
            out.write(header)
            BufferedInputStream(FileInputStream(body), STREAM_BUFFER).use { it.copyTo(out) }
        }
        body.delete()
    }

    /**
     * 流式解密整包（DCA1 容器文件 → 明文 ZIP 文件）。密码错或文件被改过返回 false。
     *
     * Java 的 GCM 只认「密文||tag」这一种输入拼接，而这里 tag 在 header 里，所以要扣住密文
     * 最后 16 字节，连同 tag 一起交给 doFinal —— update 与 doFinal 的输入拼起来必须正好是
     * 「全部密文 + tag」，少一个字节都会认证失败。
     */
    fun decryptArchiveToFile(blob: File, output: File, password: String): Boolean = runCatching {
        val size = blob.length()
        if (size < MAGIC_LENGTH + 1) return false
        BufferedInputStream(FileInputStream(blob), STREAM_BUFFER).use { ins ->
            // 先读 magic(4) + version(1)，按版本分流：version=2 走分块，version=1 走原单段流式。
            val head = ByteArray(MAGIC_LENGTH + 1)
            var h = 0
            while (h < head.size) {
                val n = ins.read(head, h, head.size - h)
                if (n <= 0) return false
                h += n
            }
            if (!magicMatches(head, ARCHIVE_MAGIC)) return false
            when (head[VERSION_OFFSET].toInt()) {
                VERSION_CHUNKED -> {
                    // v2 头剩余：salt(16) + noncePrefix(8) + chunkSize(4)
                    val rest = ByteArray(SALT_LENGTH + NONCE_PREFIX_LENGTH + 4)
                    var r = 0
                    while (r < rest.size) {
                        val n = ins.read(rest, r, rest.size - r)
                        if (n <= 0) return false
                        r += n
                    }
                    val salt = rest.copyOfRange(0, SALT_LENGTH)
                    val prefix = rest.copyOfRange(SALT_LENGTH, SALT_LENGTH + NONCE_PREFIX_LENGTH)
                    val csOff = SALT_LENGTH + NONCE_PREFIX_LENGTH
                    val chunkSize = ((rest[csOff].toInt() and 0xff) shl 24) or
                        ((rest[csOff + 1].toInt() and 0xff) shl 16) or
                        ((rest[csOff + 2].toInt() and 0xff) shl 8) or (rest[csOff + 3].toInt() and 0xff)
                    return@runCatching decryptChunked(ins, output, password, salt, prefix, chunkSize)
                }
                VERSION -> {
                    // v1：读齐 49 字节头（已读 5 字节，还差 salt+iv+tag），再按原单段流式解。
                    val tail = ByteArray(HEADER_LENGTH - (MAGIC_LENGTH + 1))
                    var t = 0
                    while (t < tail.size) {
                        val n = ins.read(tail, t, tail.size - t)
                        if (n <= 0) return false
                        t += n
                    }
                    val salt = tail.copyOfRange(0, SALT_LENGTH)
                    val iv = tail.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
                    val tag = tail.copyOfRange(SALT_LENGTH + IV_LENGTH, SALT_LENGTH + IV_LENGTH + TAG_LENGTH)
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(deriveKey(password, salt), AES_ALGORITHM),
                        GCMParameterSpec(TAG_BITS, iv),
                    )
                    val body = size - HEADER_LENGTH
                    if (body < 0L) return false
                    // Java 的 GCM 只认「密文||tag」：tag 在 header 里，所以扣住密文最后 16 字节，
                    // 连同 tag 一起交给 doFinal。空密文（body=0）时就是「只给 tag」。
                    val held = TAG_LENGTH.toLong().coerceAtMost(body).toInt()
                    val streamed = body - held
                    BufferedOutputStream(FileOutputStream(output), STREAM_BUFFER).use { out ->
                        var remaining = streamed
                        val buf = ByteArray(STREAM_BUFFER)
                        while (remaining > 0L) {
                            val want = minOf(buf.size.toLong(), remaining).toInt()
                            val n = ins.read(buf, 0, want)
                            if (n <= 0) return false
                            remaining -= n.toLong()
                            val chunk = cipher.update(buf, 0, n)
                            if (chunk != null && chunk.isNotEmpty()) out.write(chunk)
                        }
                        val tailBuf = ByteArray(held)
                        var tailGot = 0
                        while (tailGot < held) {
                            val n = ins.read(tailBuf, tailGot, held - tailGot)
                            if (n <= 0) return false
                            tailGot += n
                        }
                        val last = cipher.doFinal(tailBuf + tag)
                        if (last.isNotEmpty()) out.write(last)
                    }
                }
                else -> return false
            }
        }
        true
    }.getOrElse { false }

    /** 分块容器：判断一个文件是不是 version=2 的 DCA1（读前 5 字节即可）。 */
    fun isChunkedContainer(file: File): Boolean = runCatching {
        FileInputStream(file).use { ins ->
            val head = ByteArray(MAGIC_LENGTH + 1)
            var got = 0
            while (got < head.size) {
                val n = ins.read(head, got, head.size - got)
                if (n <= 0) return false
                got += n
            }
            magicMatches(head, ARCHIVE_MAGIC) && head[VERSION_OFFSET].toInt() == VERSION_CHUNKED
        }
    }.getOrDefault(false)

    /** 每块 IV = 8 字节随机前缀 || 4 字节大端块序号（同一文件内保证不重复）。 */
    private fun chunkIv(prefix: ByteArray, index: Int): ByteArray {
        val iv = ByteArray(IV_LENGTH)
        System.arraycopy(prefix, 0, iv, 0, NONCE_PREFIX_LENGTH)
        iv[NONCE_PREFIX_LENGTH] = (index ushr 24).toByte()
        iv[NONCE_PREFIX_LENGTH + 1] = (index ushr 16).toByte()
        iv[NONCE_PREFIX_LENGTH + 2] = (index ushr 8).toByte()
        iv[NONCE_PREFIX_LENGTH + 3] = index.toByte()
        return iv
    }

    /**
     * 每块的 AAD = version(1) || 块序号(4 BE) || isFinal(1)。
     *
     * 把序号和「是不是最后一块」绑进认证：任何重排、丢块、截断（最后一块本应 isFinal=1，
     * 截断后解密侧会把倒数第二块当成末块 → AAD 对不上 → 认证失败）都会被 GCM 直接拒掉。
     */
    private fun chunkAad(index: Int, isFinal: Boolean): ByteArray = byteArrayOf(
        VERSION_CHUNKED.toByte(),
        (index ushr 24).toByte(),
        (index ushr 16).toByte(),
        (index ushr 8).toByte(),
        index.toByte(),
        if (isFinal) 1 else 0,
    )

    private fun writeIntBE(out: java.io.OutputStream, v: Int) {
        out.write((v ushr 24) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }

    /** 读 4 字节大端。返回 null = 干净的 EOF（一个字节都没读到）；-1 = 读到一半（损坏）。 */
    private fun readIntBE(ins: java.io.InputStream): Int? {
        val b = ByteArray(4)
        val first = ins.read(b, 0, 1)
        if (first < 0) return null
        var got = first
        while (got < 4) {
            val n = ins.read(b, got, 4 - got)
            if (n < 0) return -1
            got += n
        }
        return ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or
            ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
    }

    /**
     * 分块加密：明文文件 → version=2 的 DCA1 容器。每块独立一次 AES-GCM，内存只占一个分块，
     * 任意大小都不 OOM（这正是替代 [encryptArchiveToFile] 的原因）。[chunkSize] 仅自检时调小。
     */
    fun encryptArchiveChunkedToFile(plain: File, output: File, password: String) =
        encryptChunked(plain, output, password, CHUNK_PLAIN_SIZE)

    private fun encryptChunked(plain: File, output: File, password: String, chunkSize: Int) {
        require(password.isNotEmpty()) { "加密密码不能为空" }
        require(chunkSize > 0) { "块大小必须为正" }
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val prefix = ByteArray(NONCE_PREFIX_LENGTH).also { random.nextBytes(it) }
        val key = SecretKeySpec(deriveKey(password, salt), AES_ALGORITHM)
        val total = plain.length()
        // 块数：空文件也产出 1 个（空明文）末块，好让解密侧永远能读到 isFinal=1
        val chunkCount = if (total == 0L) 1 else ((total + chunkSize - 1L) / chunkSize).toInt()
        val buffer = ByteArray(chunkSize)
        var readTotal = 0L
        BufferedOutputStream(FileOutputStream(output), STREAM_BUFFER).use { out ->
            val header = ByteArray(CHUNK_HEADER_LENGTH)
            writeMagic(header, ARCHIVE_MAGIC)
            header[VERSION_OFFSET] = VERSION_CHUNKED.toByte()
            System.arraycopy(salt, 0, header, SALT_OFFSET, SALT_LENGTH)
            System.arraycopy(prefix, 0, header, SALT_OFFSET + SALT_LENGTH, NONCE_PREFIX_LENGTH)
            val csOff = SALT_OFFSET + SALT_LENGTH + NONCE_PREFIX_LENGTH
            header[csOff] = (chunkSize ushr 24).toByte()
            header[csOff + 1] = (chunkSize ushr 16).toByte()
            header[csOff + 2] = (chunkSize ushr 8).toByte()
            header[csOff + 3] = chunkSize.toByte()
            out.write(header)
            BufferedInputStream(FileInputStream(plain), STREAM_BUFFER).use { ins ->
                var index = 0
                while (index < chunkCount) {
                    var filled = 0
                    while (filled < chunkSize) {
                        val n = ins.read(buffer, filled, chunkSize - filled)
                        if (n < 0) break
                        filled += n
                    }
                    readTotal += filled.toLong()
                    val isFinal = index == chunkCount - 1
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, chunkIv(prefix, index)))
                    cipher.updateAAD(chunkAad(index, isFinal))
                    val ct = cipher.doFinal(buffer, 0, filled) // ct = filled + TAG_LENGTH
                    writeIntBE(out, ct.size)
                    out.write(ct)
                    index++
                }
            }
        }
        // 与旧路一致：读到的字节数必须与文件长度对得上，否则当场失败而不是留个能开却缺数据的包
        if (readTotal != total) {
            output.delete()
            throw IllegalStateException("只读到 $readTotal 字节，而文件是 $total 字节")
        }
    }

    /** 分块解密：version=2 的 DCA1 容器 → 明文文件。密码错/被改/被截断一律返回 false。 */
    private fun decryptChunked(
        ins: java.io.InputStream,
        output: File,
        password: String,
        salt: ByteArray,
        prefix: ByteArray,
        chunkSize: Int,
    ): Boolean {
        if (chunkSize <= 0) return false
        val key = SecretKeySpec(deriveKey(password, salt), AES_ALGORITHM)
        BufferedOutputStream(FileOutputStream(output), STREAM_BUFFER).use { out ->
            var index = 0
            var len = readIntBE(ins) ?: return false // 一个块都没有 = 非法（空明文也有一块）
            while (true) {
                if (len <= 0 || len < TAG_LENGTH || len > chunkSize + TAG_LENGTH) return false
                val ct = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = ins.read(ct, got, len - got)
                    if (n < 0) return false
                    got += n
                }
                // 前瞻下一块长度：干净 EOF ⇒ 当前是末块；否则当前非末块且拿到了下一块长度
                val nextLen = readIntBE(ins)
                if (nextLen == -1) return false // 读到半个长度头 = 损坏
                val isFinal = nextLen == null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, chunkIv(prefix, index)))
                cipher.updateAAD(chunkAad(index, isFinal))
                val pt = cipher.doFinal(ct) // AAD/tag 不符抛 AEADBadTagException → 外层 runCatching → false
                if (pt.isNotEmpty()) out.write(pt)
                if (isFinal) break
                len = nextLen ?: return false
                index++
            }
        }
        return true
    }

    /** 封一个容器：随机 salt/iv，head 49 字节按固定偏移拼好，密文跟在后面。 */
    private fun seal(magic: String, plaintext: ByteArray, password: String): ByteArray {
        require(password.isNotEmpty()) { "加密密码不能为空" }
        val salt = ByteArray(SALT_LENGTH)
        val iv = ByteArray(IV_LENGTH)
        random.nextBytes(salt)
        random.nextBytes(iv)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(TAG_BITS, iv))
        // Java 的 GCM 输出是「密文||tag」：tag 在最后 16 字节，得挪到 header 里，密文留在后面。
        val sealedBytes = cipher.doFinal(plaintext)
        val body = sealedBytes.size - TAG_LENGTH
        val out = ByteArray(HEADER_LENGTH + body)
        writeMagic(out, magic)
        out[VERSION_OFFSET] = VERSION.toByte()
        System.arraycopy(salt, 0, out, SALT_OFFSET, SALT_LENGTH)
        System.arraycopy(iv, 0, out, IV_OFFSET, IV_LENGTH)
        System.arraycopy(sealedBytes, body, out, TAG_OFFSET, TAG_LENGTH)
        System.arraycopy(sealedBytes, 0, out, HEADER_LENGTH, body)
        return out
    }

    /** 开一个容器：magic/version 不符或认证失败一律 null（AEADBadTagException 属正常路径）。 */
    private fun open(blob: ByteArray, magic: String, password: String): ByteArray? {
        if (blob.size < HEADER_LENGTH) return null
        if (!magicMatches(blob, magic)) return null
        if (blob[VERSION_OFFSET].toInt() != VERSION) return null
        return try {
            val salt = blob.copyOfRange(SALT_OFFSET, SALT_OFFSET + SALT_LENGTH)
            val iv = blob.copyOfRange(IV_OFFSET, IV_OFFSET + IV_LENGTH)
            val tag = blob.copyOfRange(TAG_OFFSET, TAG_OFFSET + TAG_LENGTH)
            decryptBlock(password, salt, iv, tag, blob)
        } catch (e: Exception) {
            // 密码错（AEADBadTagException）、blob 被截断、密码为空导致 HMAC 拒绝，都不该往上抛
            null
        }
    }

    /**
     * GCM 解密：把 header 里的 tag 拼回密文尾部（Java 的 Cipher 只认「密文||tag」）。
     *
     * 这里刻意不加 AAD：插件用的是 Node `createDecipheriv`，没有 setAAD，一旦这边加 AAD，
     * 认证就会失败 —— 而这类不一致在自检里恰好能被 DCA1 向量抓到。
     */
    private fun decryptBlock(
        password: String,
        salt: ByteArray,
        iv: ByteArray,
        tag: ByteArray,
        blob: ByteArray
    ): ByteArray {
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES_ALGORITHM), GCMParameterSpec(TAG_BITS, iv))
        val body = blob.size - HEADER_LENGTH
        val sealedBytes = ByteArray(body + TAG_LENGTH)
        System.arraycopy(blob, HEADER_LENGTH, sealedBytes, 0, body)
        System.arraycopy(tag, 0, sealedBytes, body, TAG_LENGTH)
        return cipher.doFinal(sealedBytes)
    }

    /**
     * 派生密钥：scrypt(password 的 UTF-8 字节, salt)。
     *
     * 为什么不用 `SecretKeyFactory("PBKDF2WithHmacSHA256")` 来实现里面的 PBKDF2：`PBEKeySpec`
     * 接受的是 char 数组，把它转成字节这一步各实现/各平台并不统一（PKCS#5 v2 用 UTF-8，
     * 但 Android 的旧实现按单字节截断），而插件那边 Node 是铁定的 UTF-8。密码里只要有一个
     * 非 ASCII 字符，派生结果就会分叉，且只在真机 + 中文密码时才暴露。所以 PBKDF2 自己用
     * `Mac("HmacSHA256")` 写死，密码在哪一层都只当字节看。
     */
    private fun deriveKey(password: String, salt: ByteArray): ByteArray =
        scrypt(password.toByteArray(StandardCharsets.UTF_8), salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, KEY_LENGTH)

    /**
     * scrypt（RFC 7914）：
     * 1. B = PBKDF2-HMAC-SHA256(password, salt, 1, p * 128 * r)
     * 2. 每块 B_i = ROMix(B_i, N)
     * 3. key = PBKDF2-HMAC-SHA256(password, B, 1, keyLength)
     *
     * 为什么自己实现而不是引 BouncyCastle：只为一次导出多背一个几百 KB 的依赖不值得，
     * 而且 Android 自带的 Cipher/Mac 已经提供了这里需要的全部原语。scrypt 的每一步都有
     * RFC 7914 的官方测试向量，加上本文件的插件向量交叉验证，正确性不靠「看着像」。
     *
     * 注意所有中间量按 little-endian 读写：Salsa20/8 的 32 位字就是按小端从字节流切出来的，
     * 顺手把字节序转换收在 [readLittleEndian] / [writeLittleEndian] 两处，Salsa 内部不用再换序。
     */
    private fun scrypt(
        password: ByteArray,
        salt: ByteArray,
        n: Int,
        r: Int,
        p: Int,
        keyLength: Int
    ): ByteArray {
        val blockInts = 32 * r
        val initial = pbkdf2(password, salt, p * blockInts * 4)
        val blocks = IntArray(p * blockInts)
        readLittleEndian(initial, blocks)
        // V 是 N * 128 * r 字节 = 16 MB：只分配一次，p 轮 ROMix 共用，绝不能在循环里重建
        val v = IntArray(n * blockInts)
        for (i in 0 until p) {
            roMix(blocks, i * blockInts, r, n, v)
        }
        return pbkdf2(password, writeLittleEndian(blocks), keyLength)
    }

    /**
     * PBKDF2-HMAC-SHA256，迭代次数固定 1 —— scrypt 就是这么定义的（RFC 7914 的 PBKDF2(P, S, 1, dkLen)），
     * 所以 T_i 就是 U_1，不需要内层循环。块序号按大端拼在 salt 之后（PBKDF2 的规定，与 scrypt
     * 内部的小端无关，别顺手写成小端）。
     */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, keyLength: Int): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(password, HMAC_ALGORITHM))
        val out = ByteArray(keyLength)
        val counter = ByteArray(4)
        var offset = 0
        var block = 1
        while (offset < keyLength) {
            counter[0] = (block ushr 24).toByte()
            counter[1] = (block ushr 16).toByte()
            counter[2] = (block ushr 8).toByte()
            counter[3] = block.toByte()
            mac.reset()
            mac.update(salt)
            mac.update(counter)
            val u = mac.doFinal()
            val take = minOf(HMAC_LENGTH, keyLength - offset)
            System.arraycopy(u, 0, out, offset, take)
            offset += take
            block++
        }
        return out
    }

    /**
     * ROMix（RFC 7914 §5）：先顺序填满 V，再用前一步的结果当索引随机回读 V 并 XOR。
     * 慢就慢在这一步的内存访问上 —— 这正是 scrypt 抗 GPU 的原因，别「优化」成缓存友好的写法。
     */
    private fun roMix(b: IntArray, offset: Int, r: Int, n: Int, v: IntArray) {
        val blockInts = 32 * r
        val x = IntArray(blockInts)
        val y = IntArray(blockInts)
        val scratch = IntArray(16)
        System.arraycopy(b, offset, x, 0, blockInts)
        for (i in 0 until n) {
            System.arraycopy(x, 0, v, i * blockInts, blockInts)
            blockMix(x, r, scratch, y)
        }
        // integerify：取最后 64 字节块的首个字。N 是 2 的幂，所以低位掩码就等于取模；
        // 这里只需要低 32 位（N 远小于 2^31），高位不影响结果。
        val integerifyAt = (2 * r - 1) * 16
        val mask = n - 1
        for (i in 0 until n) {
            val at = (x[integerifyAt] and mask) * blockInts
            for (k in 0 until blockInts) {
                x[k] = x[k] xor v[at + k]
            }
            blockMix(x, r, scratch, y)
        }
        System.arraycopy(x, 0, b, offset, blockInts)
    }

    /** BlockMix（RFC 7914 §4）：链式 Salsa20/8，然后把偶数块排前面、奇数块排后面。 */
    private fun blockMix(b: IntArray, r: Int, scratch: IntArray, y: IntArray) {
        System.arraycopy(b, (2 * r - 1) * 16, scratch, 0, 16)
        for (i in 0 until 2 * r) {
            val at = i * 16
            for (k in 0 until 16) {
                scratch[k] = scratch[k] xor b[at + k]
            }
            salsa8(scratch, 0, y, at)
            System.arraycopy(y, at, scratch, 0, 16)
        }
        for (i in 0 until r) {
            System.arraycopy(y, i * 32, b, i * 16, 16)
            System.arraycopy(y, i * 32 + 16, b, (i + r) * 16, 16)
        }
    }

    /**
     * Salsa20/8 核心：4 次「列变换 + 行变换」的双轮，最后把结果加回输入。
     *
     * 为什么展开成 16 个局部变量而不是用数组下标：scrypt 一次要跑三万多次这个函数，
     * 数组版本的边界检查与反复读写在这里是实打实的瓶颈。加回输入用的那 16 个原始值必须
     * 单独留一份（[c0]..[c15]），因为输出会原地覆盖输入。Kotlin 的 Int 加法溢出回绕
     * 与 [Integer.rotateLeft] 就是 Salsa 需要的 32 位无符号语义。
     */
    private fun salsa8(input: IntArray, inputOffset: Int, output: IntArray, outputOffset: Int) {
        val c0 = input[inputOffset]
        val c1 = input[inputOffset + 1]
        val c2 = input[inputOffset + 2]
        val c3 = input[inputOffset + 3]
        val c4 = input[inputOffset + 4]
        val c5 = input[inputOffset + 5]
        val c6 = input[inputOffset + 6]
        val c7 = input[inputOffset + 7]
        val c8 = input[inputOffset + 8]
        val c9 = input[inputOffset + 9]
        val c10 = input[inputOffset + 10]
        val c11 = input[inputOffset + 11]
        val c12 = input[inputOffset + 12]
        val c13 = input[inputOffset + 13]
        val c14 = input[inputOffset + 14]
        val c15 = input[inputOffset + 15]
        var x0 = c0
        var x1 = c1
        var x2 = c2
        var x3 = c3
        var x4 = c4
        var x5 = c5
        var x6 = c6
        var x7 = c7
        var x8 = c8
        var x9 = c9
        var x10 = c10
        var x11 = c11
        var x12 = c12
        var x13 = c13
        var x14 = c14
        var x15 = c15
        var round = 0
        while (round < 4) {
            x4 = x4 xor Integer.rotateLeft(x0 + x12, 7)
            x8 = x8 xor Integer.rotateLeft(x4 + x0, 9)
            x12 = x12 xor Integer.rotateLeft(x8 + x4, 13)
            x0 = x0 xor Integer.rotateLeft(x12 + x8, 18)
            x9 = x9 xor Integer.rotateLeft(x5 + x1, 7)
            x13 = x13 xor Integer.rotateLeft(x9 + x5, 9)
            x1 = x1 xor Integer.rotateLeft(x13 + x9, 13)
            x5 = x5 xor Integer.rotateLeft(x1 + x13, 18)
            x14 = x14 xor Integer.rotateLeft(x10 + x6, 7)
            x2 = x2 xor Integer.rotateLeft(x14 + x10, 9)
            x6 = x6 xor Integer.rotateLeft(x2 + x14, 13)
            x10 = x10 xor Integer.rotateLeft(x6 + x2, 18)
            x3 = x3 xor Integer.rotateLeft(x15 + x11, 7)
            x7 = x7 xor Integer.rotateLeft(x3 + x15, 9)
            x11 = x11 xor Integer.rotateLeft(x7 + x3, 13)
            x15 = x15 xor Integer.rotateLeft(x11 + x7, 18)
            x1 = x1 xor Integer.rotateLeft(x0 + x3, 7)
            x2 = x2 xor Integer.rotateLeft(x1 + x0, 9)
            x3 = x3 xor Integer.rotateLeft(x2 + x1, 13)
            x0 = x0 xor Integer.rotateLeft(x3 + x2, 18)
            x6 = x6 xor Integer.rotateLeft(x5 + x4, 7)
            x7 = x7 xor Integer.rotateLeft(x6 + x5, 9)
            x4 = x4 xor Integer.rotateLeft(x7 + x6, 13)
            x5 = x5 xor Integer.rotateLeft(x4 + x7, 18)
            x11 = x11 xor Integer.rotateLeft(x10 + x9, 7)
            x8 = x8 xor Integer.rotateLeft(x11 + x10, 9)
            x9 = x9 xor Integer.rotateLeft(x8 + x11, 13)
            x10 = x10 xor Integer.rotateLeft(x9 + x8, 18)
            x12 = x12 xor Integer.rotateLeft(x15 + x14, 7)
            x13 = x13 xor Integer.rotateLeft(x12 + x15, 9)
            x14 = x14 xor Integer.rotateLeft(x13 + x12, 13)
            x15 = x15 xor Integer.rotateLeft(x14 + x13, 18)
            round++
        }
        output[outputOffset] = x0 + c0
        output[outputOffset + 1] = x1 + c1
        output[outputOffset + 2] = x2 + c2
        output[outputOffset + 3] = x3 + c3
        output[outputOffset + 4] = x4 + c4
        output[outputOffset + 5] = x5 + c5
        output[outputOffset + 6] = x6 + c6
        output[outputOffset + 7] = x7 + c7
        output[outputOffset + 8] = x8 + c8
        output[outputOffset + 9] = x9 + c9
        output[outputOffset + 10] = x10 + c10
        output[outputOffset + 11] = x11 + c11
        output[outputOffset + 12] = x12 + c12
        output[outputOffset + 13] = x13 + c13
        output[outputOffset + 14] = x14 + c14
        output[outputOffset + 15] = x15 + c15
    }

    /** 小端读入 Int。长度必须是 4 的倍数（调用方保证来自 128*r 的整块）。 */
    private fun readLittleEndian(bytes: ByteArray, out: IntArray) {
        for (i in out.indices) {
            val at = i * 4
            out[i] = (bytes[at].toInt() and 0xFF) or
                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                ((bytes[at + 3].toInt() and 0xFF) shl 24)
        }
    }

    /** 小端写回。用 ushr 而不是 shr：移位后还要 toByte 截断，算术右移会先把高位补成 1。 */
    private fun writeLittleEndian(values: IntArray): ByteArray {
        val out = ByteArray(values.size * 4)
        for (i in values.indices) {
            val at = i * 4
            val v = values[i]
            out[at] = v.toByte()
            out[at + 1] = (v ushr 8).toByte()
            out[at + 2] = (v ushr 16).toByte()
            out[at + 3] = (v ushr 24).toByte()
        }
        return out
    }

    private fun magicMatches(bytes: ByteArray, magic: String): Boolean {
        if (bytes.size < MAGIC_LENGTH) return false
        for (i in 0 until MAGIC_LENGTH) {
            if ((bytes[i].toInt() and 0xFF) != magic[i].code) return false
        }
        return true
    }

    private fun writeMagic(out: ByteArray, magic: String) {
        for (i in 0 until MAGIC_LENGTH) {
            out[i] = magic[i].code.toByte()
        }
    }

    private fun encodeBase64(bytes: ByteArray, offset: Int, length: Int): String =
        Base64.encodeToString(bytes.copyOfRange(offset, offset + length), Base64.NO_WRAP)

    /**
     * 比对 manifest 里的 base64 与 blob 内嵌的字节。
     *
     * 空串当作「manifest 没写这段」（老版本或手工拼的 manifest），以 blob 为准而不是直接判失败。
     * 解码前先把长度补齐到 4 的倍数：这三段本来就是标准 base64，但手工改过的 manifest 可能
     * 把尾部 padding 去掉了，那种情况没必要当成篡改。
     */
    private fun matchesBase64(encoded: String, expect: ByteArray): Boolean {
        if (encoded.isEmpty()) return true
        val decoded = decodeBase64(encoded) ?: return false
        return decoded.contentEquals(expect)
    }

    private fun decodeBase64(encoded: String): ByteArray? {
        val padded = when (encoded.length % 4) {
            0 -> encoded
            2 -> encoded + "=="
            3 -> encoded + "="
            else -> return null
        }
        return try {
            Base64.decode(padded, Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    private fun describe(e: Exception): String = e.javaClass.simpleName + ": " + (e.message ?: "")
}
