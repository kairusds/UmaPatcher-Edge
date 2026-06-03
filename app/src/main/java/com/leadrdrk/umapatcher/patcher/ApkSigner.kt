package com.leadrdrk.umapatcher.patcher

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale

internal class ApkSigner(
    private val cn: String, password: String
) {
    private val passwordCharArray = password.toCharArray()
    private fun newKeystore(out: File) {
        val (publicKey, privateKey) = createKey()
        val privateKS = KeyStore.getInstance("BKS", "BC")
        privateKS.load(null, passwordCharArray)
        privateKS.setKeyEntry("alias", privateKey, passwordCharArray, arrayOf(publicKey))
        privateKS.store(FileOutputStream(out), passwordCharArray)
    }

    private fun createKey(): Pair<X509Certificate, PrivateKey> {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        val pair = gen.generateKeyPair()
        var serialNumber: BigInteger
        do serialNumber =
            BigInteger.valueOf(SecureRandom().nextLong()) while (serialNumber < BigInteger.ZERO)
        val x500Name = X500Name("CN=$cn")
        val builder = X509v3CertificateBuilder(
            x500Name,
            serialNumber,
            Date(System.currentTimeMillis() - 1000L * 60L * 60L * 24L * 30L),
            Date(System.currentTimeMillis() + 1000L * 60L * 60L * 24L * 366L * 30L),
            Locale.ENGLISH,
            x500Name,
            SubjectPublicKeyInfo.getInstance(pair.public.encoded)
        )
        val signer: ContentSigner = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer)) to pair.private
    }

    fun signApk(input: File, output: File, ks: File) {
        Security.addProvider(BouncyCastleProvider())

        if (!ks.exists()) newKeystore(ks)

        val keyStore = KeyStore.getInstance("BKS", "BC")
        FileInputStream(ks).use { fis -> keyStore.load(fis, null) }
        val alias = keyStore.aliases().nextElement()

        val config = ApkSigner.SignerConfig.Builder(
            cn,
            KeyConfig.Jca(keyStore.getKey(alias, passwordCharArray) as PrivateKey),
            listOf(keyStore.getCertificate(alias) as X509Certificate)
        ).build()

        val signer = ApkSigner.Builder(listOf(config))
        signer.setV1SigningEnabled(true)
        signer.setV2SigningEnabled(true)
        signer.setV3SigningEnabled(true)
        signer.setV4SigningEnabled(false)
        signer.setCreatedBy(cn)
        signer.setInputApk(input)
        signer.setOutputApk(output)

        signer.build().sign()
    }

    companion object {
        private const val UNIVERSAL_KS_PASSWORD = "securep@ssw0rd816-n"
        private const val UNIVERSAL_KS_ALIAS = "patcheduma"

        fun signApkUniversal(input: File, output: File, ks: File) {
            val passwordCharArray = UNIVERSAL_KS_PASSWORD.toCharArray()

            val keyStore = KeyStore.getInstance("PKCS12")
            FileInputStream(ks).use { fis -> keyStore.load(fis, passwordCharArray) }

            val config = ApkSigner.SignerConfig.Builder(
                "UmaPatcher Edge",
                KeyConfig.Jca(keyStore.getKey(UNIVERSAL_KS_ALIAS, passwordCharArray) as PrivateKey),
                listOf(keyStore.getCertificate(UNIVERSAL_KS_ALIAS) as X509Certificate)
            ).build()

            val signer = ApkSigner.Builder(listOf(config))
            signer.setV1SigningEnabled(true)
            signer.setV2SigningEnabled(true)
            signer.setV3SigningEnabled(true)
            signer.setV4SigningEnabled(false)
            signer.setCreatedBy("UmaPatcher Edge")
            signer.setInputApk(input)
            signer.setOutputApk(output)

            signer.build().sign()
        }
    }
}