package com.auratv.launcher.backup

import java.security.Key
import java.security.MessageDigestSpi
import java.security.Provider
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.MacSpi
import org.bouncycastle.crypto.digests.MD4Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.macs.CMac
import org.bouncycastle.crypto.params.KeyParameter

/**
 * The two algorithms jcifs-ng asks its crypto provider for: MD4 (NTLM password hash) and
 * AES-CMAC (SMB 3 signing). Handing it this provider ([jcifs.util.Crypto.initProvider]) instead
 * of the full BouncyCastleProvider keeps R8 free to drop the rest of Bouncy Castle's JCA
 * classes, which the full provider would only find by name.
 */
@Suppress("DEPRECATION") // the (String, String, String) constructor is Android 14+ only
class JcifsCrypto : Provider("GlowHomeJcifs", 1.0, "MD4 and AES-CMAC for jcifs-ng") {
    init {
        put("MessageDigest.MD4", Md4::class.java.name)
        put("Mac.AESCMAC", AesCmac::class.java.name)
    }

    /** Created by name through the provider (kept in proguard-rules.pro). */
    class Md4 : MessageDigestSpi() {
        private val d = MD4Digest()
        override fun engineUpdate(input: Byte) = d.update(input)
        override fun engineUpdate(input: ByteArray, offset: Int, len: Int) = d.update(input, offset, len)
        override fun engineDigest(): ByteArray = ByteArray(d.digestSize).also { d.doFinal(it, 0) }
        override fun engineReset() = d.reset()
        override fun engineGetDigestLength(): Int = d.digestSize
    }

    /** Created by name through the provider (kept in proguard-rules.pro). */
    class AesCmac : MacSpi() {
        private val mac = CMac(AESEngine.newInstance())
        override fun engineGetMacLength(): Int = mac.macSize
        override fun engineInit(key: Key, params: AlgorithmParameterSpec?) = mac.init(KeyParameter(key.encoded))
        override fun engineUpdate(input: Byte) = mac.update(input)
        override fun engineUpdate(input: ByteArray, offset: Int, len: Int) = mac.update(input, offset, len)
        override fun engineDoFinal(): ByteArray = ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
        override fun engineReset() = mac.reset()
    }
}
