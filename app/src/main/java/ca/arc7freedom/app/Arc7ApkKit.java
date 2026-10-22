package ca.arc7freedom.app;

import android.content.Context;
import android.os.Build;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Pure-Java Android 4.2.2 (API 17 / ARMv7 / Dalvik DEX 035) APK & Repository JAR Validator,
 * AOSP Bug #38321 SHA1withRSA JAR v1 Re-Signer, PKCS#7 SHA256withRSA Verifier, and
 * Mount-Namespace-Safe `/data/local/tmp/` Root Package Installer.
 */
public final class Arc7ApkKit {

    private Arc7ApkKit() {}

    public static final int DEVICE_MAX_SDK = 17; // Kobo Arc 7 = Android 4.2.2 (API 17)

    public static final class ApkInspection {
        public boolean validZip;
        public String packageName = "";
        public int versionCode = 0;
        public int minSdkVersion = 1;
        public int targetSdkVersion = 0;
        public int highestDexVersion = 35; // 35 = Dalvik 035, 37+ = Android 7.0+ only
        public boolean hasNativeLibs = false;
        public boolean supportsArmv7 = true;
        public boolean hasValidAndroid42V1Sig = false;
        public String errorReason = null;

        public boolean isCompatibleWithAndroid42() {
            return validZip && errorReason == null;
        }
    }

    public static final class RootInstallOutcome {
        public final boolean attemptedRoot;
        public final boolean succeeded;
        public final String output;
        public final File worldReadableFallbackApk;

        public RootInstallOutcome(boolean attemptedRoot, boolean succeeded, String output, File fallback) {
            this.attemptedRoot = attemptedRoot;
            this.succeeded = succeeded;
            this.output = output != null ? output : "";
            this.worldReadableFallbackApk = fallback;
        }
    }

    /* ========================================================================
     * 1. Fast Binary AXML + DEX + ABI + JAR v1 Signature Inspector
     * ======================================================================== */

    public static ApkInspection inspectApk(File apkFile) {
        return inspectApk(apkFile, DEVICE_MAX_SDK);
    }

    public static void resignApkWithV1Sha1Rsa(File srcApk, File dstApk, File keyStorageDir) throws Exception {
        signZipWithSha1RsaV1(srcApk, dstApk, loadOrCreatePersistentSigner(keyStorageDir));
    }

    public static ApkInspection inspectApk(File apkFile, int maxAllowedSdk) {
        ApkInspection info = new ApkInspection();
        if (apkFile == null || !apkFile.exists() || apkFile.length() < 256) {
            info.errorReason = "Downloaded file is empty or incomplete ("
                    + (apkFile == null ? 0 : apkFile.length()) + " bytes).";
            return info;
        }

        // Quick header sanity check to catch HTML 404/captive pages renamed `.apk`
        FileInputStream hdrIn = null;
        try {
            hdrIn = new FileInputStream(apkFile);
            byte[] magic = new byte[4];
            if (hdrIn.read(magic) != 4 || magic[0] != 'P' || magic[1] != 'K') {
                info.errorReason = "Downloaded response is not a valid APK archive (server returned HTML/redirect instead of APK binary).";
                return info;
            }
        } catch (Throwable t) {
            info.errorReason = "Cannot read downloaded APK: " + t.getMessage();
            return info;
        } finally {
            closeQuietly(hdrIn);
        }

        ZipFile zf = null;
        try {
            zf = new ZipFile(apkFile);
            info.validZip = true;

            ZipEntry manifestXml = zf.getEntry("AndroidManifest.xml");
            if (manifestXml == null) {
                info.errorReason = "Archive is missing AndroidManifest.xml (not an installable Android APK).";
                return info;
            }
            byte[] axml = readAllBytes(zf.getInputStream(manifestXml), 2 * 1024 * 1024);
            parseBinaryAndroidManifest(axml, info);

            boolean sawArmv7 = false;
            boolean sawArmeabi = false;
            List<String> otherAbis = new ArrayList<String>();
            boolean hasManifestMfSha1 = false;
            boolean hasSfSha1 = false;
            boolean hasSignatureBlock = false;

            Enumeration<? extends ZipEntry> entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                String name = ze.getName();

                // Check all classes*.dex versions (Android 4.2 Dalvik only supports 035!)
                if (name.startsWith("classes") && name.endsWith(".dex") && name.indexOf('/') < 0) {
                    InputStream ds = zf.getInputStream(ze);
                    try {
                        byte[] dh = new byte[8];
                        if (readFullyUpTo(ds, dh) >= 7
                                && dh[0] == 'd' && dh[1] == 'e' && dh[2] == 'x' && dh[3] == '\n') {
                            int ver = (dh[4] - '0') * 100 + (dh[5] - '0') * 10 + (dh[6] - '0');
                            if (ver > info.highestDexVersion) {
                                info.highestDexVersion = ver;
                            }
                        }
                    } finally {
                        closeQuietly(ds);
                    }
                } else if (name.startsWith("lib/") && name.endsWith(".so")) {
                    int s1 = 4;
                    int s2 = name.indexOf('/', s1);
                    if (s2 > s1) {
                        info.hasNativeLibs = true;
                        String abi = name.substring(s1, s2);
                        if ("armeabi-v7a".equals(abi)) sawArmv7 = true;
                        else if ("armeabi".equals(abi)) sawArmeabi = true;
                        else if (!otherAbis.contains(abi)) otherAbis.add(abi);
                    }
                } else if ("META-INF/MANIFEST.MF".equalsIgnoreCase(name)) {
                    String mfTxt = new String(readAllBytes(zf.getInputStream(ze), 4 * 1024 * 1024), "UTF-8");
                    if (mfTxt.contains("SHA1-Digest:")) {
                        hasManifestMfSha1 = true;
                    }
                } else if (isMetaInfDirectChild(name)) {
                    String upper = name.toUpperCase(Locale.US);
                    if (upper.endsWith(".SF")) {
                        String sfTxt = new String(readAllBytes(zf.getInputStream(ze), 4 * 1024 * 1024), "UTF-8");
                        if (sfTxt.contains("SHA1-Digest-Manifest:") || sfTxt.contains("SHA1-Digest:")) {
                            hasSfSha1 = true;
                        }
                    } else if (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC")) {
                        hasSignatureBlock = true;
                    }
                }
            }

            if (info.hasNativeLibs) {
                info.supportsArmv7 = (sawArmv7 || sawArmeabi);
            } else {
                info.supportsArmv7 = true;
            }
            info.hasValidAndroid42V1Sig = hasManifestMfSha1 && hasSfSha1 && hasSignatureBlock;

            int effectiveMaxSdk = maxAllowedSdk > 0 ? maxAllowedSdk : DEVICE_MAX_SDK;
            if (info.minSdkVersion > effectiveMaxSdk) {
                info.errorReason = "APK requires Android API " + info.minSdkVersion
                        + " (Kobo Arc 7 runs Android 4.2.2 / API " + effectiveMaxSdk + ").";
            } else if (info.highestDexVersion > 35) {
                info.errorReason = "APK bytecode uses DEX 0" + info.highestDexVersion
                        + " (Android 7.0+ ART format; Kobo Arc 7 Dalvik requires DEX 035).";
            } else if (!info.supportsArmv7) {
                info.errorReason = "APK native libraries are for " + otherAbis
                        + " only (missing 32-bit ARMv7 armeabi-v7a / armeabi).";
            }
            return info;
        } catch (Throwable t) {
            info.errorReason = "Corrupt or truncated APK ZIP archive: " + t.getMessage();
            return info;
        } finally {
            if (zf != null) {
                try { zf.close(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Parses Android Binary XML (`RES_XML_TYPE = 0x0003`) to read exact `package`,
     * `versionCode` (`0x0101021b`), `minSdkVersion` (`0x0101020c`), and `targetSdkVersion` (`0x01010270`).
     */
    private static void parseBinaryAndroidManifest(byte[] axml, ApkInspection out) {
        if (axml == null || axml.length < 16) return;
        int fileType = u16(axml, 0);
        if (fileType != 0x0003) return;
        int totalLen = Math.min(axml.length, i32(axml, 4));
        int pos = u16(axml, 2);

        String[] strings = new String[0];
        int[] resIds = new int[0];

        while (pos + 8 <= totalLen) {
            int chunkType = u16(axml, pos);
            int headerSize = u16(axml, pos + 2);
            int chunkSize = i32(axml, pos + 4);
            if (chunkSize < 8 || pos + chunkSize > totalLen) break;

            if (chunkType == 0x0001) { // RES_STRING_POOL_TYPE
                int strCount = i32(axml, pos + 8);
                int flags = i32(axml, pos + 16);
                int stringsStart = pos + i32(axml, pos + 20);
                boolean utf8 = (flags & (1 << 8)) != 0;
                if (strCount > 0 && strCount < 200000) {
                    strings = new String[strCount];
                    int offsetsBase = pos + headerSize;
                    for (int i = 0; i < strCount; i++) {
                        if (offsetsBase + (i + 1) * 4 > pos + chunkSize) break;
                        int sOff = stringsStart + i32(axml, offsetsBase + i * 4);
                        strings[i] = decodePoolString(axml, sOff, pos + chunkSize, utf8);
                    }
                }
            } else if (chunkType == 0x0180) { // RES_XML_RESOURCE_MAP_TYPE
                int count = (chunkSize - headerSize) / 4;
                if (count > 0 && count < 65536) {
                    resIds = new int[count];
                    for (int i = 0; i < count; i++) {
                        resIds[i] = i32(axml, pos + headerSize + i * 4);
                    }
                }
            } else if (chunkType == 0x0102) { // RES_XML_START_ELEMENT_TYPE
                int elemNameIdx = i32(axml, pos + 20);
                String elemName = (elemNameIdx >= 0 && elemNameIdx < strings.length) ? strings[elemNameIdx] : "";
                int attrStart = u16(axml, pos + 24);
                int attrSize = u16(axml, pos + 26);
                int attrCount = u16(axml, pos + 28);
                int attrPos = pos + 16 + attrStart;
                if (attrSize <= 0) attrSize = 20;

                for (int i = 0; i < attrCount && attrPos + 20 <= pos + chunkSize; i++, attrPos += attrSize) {
                    int nameIdx = i32(axml, attrPos + 4);
                    int rawValIdx = i32(axml, attrPos + 8);
                    int dataType = axml[attrPos + 15] & 0xFF;
                    int data = i32(axml, attrPos + 16);
                    String attrName = (nameIdx >= 0 && nameIdx < strings.length) ? strings[nameIdx] : "";
                    int attrResId = (nameIdx >= 0 && nameIdx < resIds.length) ? resIds[nameIdx] : 0;

                    if ("manifest".equals(elemName)) {
                        if ("package".equals(attrName) && out.packageName.length() == 0) {
                            String v = resolveStringAttr(strings, rawValIdx, dataType, data);
                            if (v.length() > 0) out.packageName = v;
                        } else if (attrResId == 0x0101021b || "versionCode".equals(attrName)) {
                            out.versionCode = resolveIntAttr(strings, rawValIdx, dataType, data, out.versionCode);
                        }
                    } else if ("uses-sdk".equals(elemName)) {
                        if (attrResId == 0x0101020c || "minSdkVersion".equals(attrName)) {
                            out.minSdkVersion = resolveIntAttr(strings, rawValIdx, dataType, data, out.minSdkVersion);
                        } else if (attrResId == 0x01010270 || "targetSdkVersion".equals(attrName)) {
                            out.targetSdkVersion = resolveIntAttr(strings, rawValIdx, dataType, data, out.targetSdkVersion);
                        }
                    }
                }
            }
            pos += chunkSize;
        }
    }

    private static String resolveStringAttr(String[] pool, int rawIdx, int dataType, int data) {
        if (rawIdx >= 0 && rawIdx < pool.length && pool[rawIdx] != null) return pool[rawIdx];
        if (dataType == 0x03 && data >= 0 && data < pool.length && pool[data] != null) return pool[data];
        return "";
    }

    private static int resolveIntAttr(String[] pool, int rawIdx, int dataType, int data, int fallback) {
        if (dataType >= 0x10 && dataType <= 0x1f) return data;
        String s = resolveStringAttr(pool, rawIdx, dataType, data).trim();
        if (s.length() > 0) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static String decodePoolString(byte[] b, int off, int limit, boolean utf8) {
        try {
            if (off < 0 || off + 2 > limit) return "";
            if (utf8) {
                int p = off;
                int charLen = b[p++] & 0xFF;
                if ((charLen & 0x80) != 0) p++;
                int byteLen = b[p++] & 0xFF;
                if ((byteLen & 0x80) != 0) {
                    byteLen = ((byteLen & 0x7F) << 8) | (b[p++] & 0xFF);
                }
                if (p + byteLen > limit) byteLen = Math.max(0, limit - p);
                return new String(b, p, byteLen, "UTF-8");
            } else {
                int charLen = u16(b, off);
                int p = off + 2;
                if ((charLen & 0x8000) != 0) {
                    charLen = ((charLen & 0x7FFF) << 16) | u16(b, p);
                    p += 2;
                }
                int byteLen = charLen * 2;
                if (p + byteLen > limit) byteLen = Math.max(0, limit - p);
                return new String(b, p, byteLen, "UTF-16LE");
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int i32(byte[] b, int o) {
        return (b[o] & 0xFF)
                | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16)
                | ((b[o + 3] & 0xFF) << 24);
    }

    /* ========================================================================
     * 2. Pure-Java SHA1-Digest + SHA1withRSA JAR v1 APK Signer (Fixes AOSP #38321)
     *
     * Any modern APK whose `META-INF` uses only `SHA-256-Digest` or APK Signature
     * Scheme v2/v3 is rejected by Android <= 4.2.2 `PackageParser` with
     * `INSTALL_PARSE_FAILED_NO_CERTIFICATES` ("There is a problem parsing the package").
     * When `hasValidAndroid42V1Sig == false`, this re-signs the APK in pure Java
     * with a persistent device-wide 2048-bit RSA `SHA1withRSA` certificate (valid
     * 2012..2056 so even unsynced 2013 tablet clocks pass!) while preserving exact
     * 4-byte ZIP alignment on `resources.arsc` and uncompressed `.so` libraries!
     * ======================================================================== */

    public static File ensureAndroid42V1Signed(Context ctx, File apkFile) throws Exception {
        ApkInspection info = inspectApk(apkFile, 99);
        if (!info.validZip) {
            throw new IOException(info.errorReason != null ? info.errorReason : "Invalid APK ZIP");
        }
        if (info.hasValidAndroid42V1Sig) {
            return apkFile;
        }

        File keyStorageDir = ctx != null ? ctx.getFilesDir() : apkFile.getParentFile();
        SignerMaterial signer = loadOrCreatePersistentSigner(keyStorageDir);

        File signedOut = new File(
                apkFile.getParentFile(),
                apkFile.getName().replace(".apk", "") + ".v1signed.apk");
        signZipWithSha1RsaV1(apkFile, signedOut, signer);
        return signedOut;
    }

    private static final class SignerMaterial {
        final PrivateKey privateKey;
        final X509Certificate cert;
        final byte[] certDer;

        SignerMaterial(PrivateKey pk, X509Certificate cert, byte[] certDer) {
            this.privateKey = pk;
            this.cert = cert;
            this.certDer = certDer;
        }
    }

    private static synchronized SignerMaterial loadOrCreatePersistentSigner(File fallbackDir) throws Exception {
        File sharedPk8 = new File("/data/local/tmp/.arc7_v1_signer.pk8");
        File sharedDer = new File("/data/local/tmp/.arc7_v1_signer.der");
        File localPk8 = new File(fallbackDir, "arc7_v1_signer.pk8");
        File localDer = new File(fallbackDir, "arc7_v1_signer.der");

        try {
            if (sharedPk8.canRead() && sharedDer.canRead() && sharedPk8.length() > 200 && sharedDer.length() > 200) {
                return readSignerFromFiles(sharedPk8, sharedDer);
            }
        } catch (Throwable ignored) {}

        try {
            if (localPk8.canRead() && localDer.canRead() && localPk8.length() > 200 && localDer.length() > 200) {
                SignerMaterial sm = readSignerFromFiles(localPk8, localDer);
                tryPersistWorldReadable(sharedPk8, readAllBytes(new FileInputStream(localPk8), 65536));
                tryPersistWorldReadable(sharedDer, sm.certDer);
                return sm;
            }
        } catch (Throwable ignored) {}

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        byte[] pk8Bytes = kp.getPrivate().getEncoded();
        byte[] certDer = buildSelfSignedX509CertificateDer(kp.getPublic(), kp.getPrivate());

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate x509 = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));

        tryPersistLocal(localPk8, pk8Bytes);
        tryPersistLocal(localDer, certDer);
        tryPersistWorldReadable(sharedPk8, pk8Bytes);
        tryPersistWorldReadable(sharedDer, certDer);

        return new SignerMaterial(kp.getPrivate(), x509, certDer);
    }

    private static SignerMaterial readSignerFromFiles(File pk8File, File derFile) throws Exception {
        byte[] pk8 = readAllBytes(new FileInputStream(pk8File), 65536);
        byte[] der = readAllBytes(new FileInputStream(derFile), 65536);
        PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pk8));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return new SignerMaterial(pk, cert, der);
    }

    private static void tryPersistLocal(File f, byte[] data) {
        FileOutputStream fos = null;
        try {
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            fos = new FileOutputStream(f);
            fos.write(data);
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(fos);
        }
    }

    private static void tryPersistWorldReadable(File f, byte[] data) {
        try {
            if (f.getParentFile() != null && f.getParentFile().canWrite()) {
                tryPersistLocal(f, data);
                f.setReadable(true, false);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Streams all payload entries from `srcApk` -> `dstApk` with exact 4-byte data
     * alignment on `STORED` entries (`resources.arsc`, `lib/*.so`, raw assets) and
     * appends RFC-compliant `META-INF/MANIFEST.MF`, `META-INF/CERT.SF`, and
     * `META-INF/CERT.RSA` using `SHA1-Digest` + `SHA1withRSA` for Android 4.2.2!
     */
    public static void signZipWithSha1RsaV1(File srcApk, File dstApk, SignerMaterial signer) throws Exception {
        Map<String, String> entrySha1B64 = new LinkedHashMap<String, String>();

        ZipFile inZip = new ZipFile(srcApk);
        AlignedZipWriter zw = new AlignedZipWriter(new BufferedOutputStream(new FileOutputStream(dstApk), 65536));
        byte[] buf = new byte[32768];

        try {
            List<String> sortedNames = new ArrayList<String>();
            Enumeration<? extends ZipEntry> en = inZip.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) continue;
                String name = ze.getName();
                if (shouldStripSignatureEntry(name)) continue;
                sortedNames.add(name);
            }
            Collections.sort(sortedNames);
            if (sortedNames.remove("resources.arsc")) {
                sortedNames.add(0, "resources.arsc");
            }

            for (String name : sortedNames) {
                ZipEntry srcEntry = inZip.getEntry(name);
                boolean storeUncompressed = (srcEntry.getMethod() == ZipEntry.STORED)
                        || "resources.arsc".equals(name)
                        || (name.startsWith("lib/") && name.endsWith(".so"));

                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                CRC32 crc = new CRC32();
                long byteCount = 0L;
                InputStream p1 = inZip.getInputStream(srcEntry);
                try {
                    int n;
                    while ((n = p1.read(buf)) > 0) {
                        crc.update(buf, 0, n);
                        sha1.update(buf, 0, n);
                        byteCount += n;
                    }
                } finally {
                    closeQuietly(p1);
                }

                if (storeUncompressed) {
                    zw.beginStoredAligned(name, byteCount, crc.getValue(), 4);
                    InputStream p2 = inZip.getInputStream(srcEntry);
                    try {
                        int n;
                        while ((n = p2.read(buf)) > 0) {
                            zw.writePayload(buf, 0, n);
                        }
                    } finally {
                        closeQuietly(p2);
                    }
                    zw.endStored();
                } else {
                    ByteArrayOutputStream defBuf = new ByteArrayOutputStream((int) Math.min(byteCount + 64L, 1048576L));
                    Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
                    DeflaterOutputStream dos = new DeflaterOutputStream(defBuf, def, 16384);
                    InputStream p2 = inZip.getInputStream(srcEntry);
                    try {
                        int n;
                        while ((n = p2.read(buf)) > 0) {
                            dos.write(buf, 0, n);
                        }
                        dos.finish();
                    } finally {
                        closeQuietly(p2);
                        def.end();
                    }
                    zw.writePreCompressedDeflated(name, byteCount, crc.getValue(), defBuf.toByteArray());
                }
                entrySha1B64.put(name, b64NoWrap(sha1.digest()));
            }

            // Build `META-INF/MANIFEST.MF` & `META-INF/CERT.SF` with exact per-section SHA-1 digests
            ByteArrayOutputStream mfOut = new ByteArrayOutputStream();
            ByteArrayOutputStream sfBodySections = new ByteArrayOutputStream();

            String mfMain = "Manifest-Version: 1.0\r\nCreated-By: 1.0 (Arc7 Android 4.2 Signer)\r\n\r\n";
            byte[] mfMainBytes = mfMain.getBytes("UTF-8");
            mfOut.write(mfMainBytes);
            String mainAttrsSha1 = b64NoWrap(MessageDigest.getInstance("SHA-1").digest(mfMainBytes));

            for (Map.Entry<String, String> e : entrySha1B64.entrySet()) {
                byte[] sectionBytes = formatManifestSection("Name", e.getKey(), "SHA1-Digest", e.getValue());
                mfOut.write(sectionBytes);
                String sectionSha1 = b64NoWrap(MessageDigest.getInstance("SHA-1").digest(sectionBytes));
                sfBodySections.write(formatManifestSection("Name", e.getKey(), "SHA1-Digest", sectionSha1));
            }

            byte[] manifestBytes = mfOut.toByteArray();
            String wholeManifestSha1 = b64NoWrap(MessageDigest.getInstance("SHA-1").digest(manifestBytes));

            ByteArrayOutputStream sfOut = new ByteArrayOutputStream();
            String sfHeader = "Signature-Version: 1.0\r\n"
                    + "Created-By: 1.0 (Arc7 Android 4.2 Signer)\r\n"
                    + "SHA1-Digest-Manifest: " + wholeManifestSha1 + "\r\n"
                    + "SHA1-Digest-Manifest-Main-Attributes: " + mainAttrsSha1 + "\r\n\r\n";
            sfOut.write(sfHeader.getBytes("UTF-8"));
            sfOut.write(sfBodySections.toByteArray());
            byte[] sfBytes = sfOut.toByteArray();

            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign(signer.privateKey);
            sig.update(sfBytes);
            byte[] rsaSigBytes = sig.sign();

            byte[] pkcs7Der = buildPkcs7SignedDataDer(signer.cert, signer.certDer, rsaSigBytes);

            zw.writeDeflatedBytes("META-INF/MANIFEST.MF", manifestBytes);
            zw.writeDeflatedBytes("META-INF/CERT.SF", sfBytes);
            zw.writeDeflatedBytes("META-INF/CERT.RSA", pkcs7Der);

            zw.finish();
        } finally {
            try { inZip.close(); } catch (Throwable ignored) {}
            zw.close();
        }
    }

    /**
     * Exact specification-level PKZIP writer guaranteeing `dataOffset % 4 == 0`
     * identically on both Android 4.2 Dalvik AND Java 8–25 JVMs with zero hidden extra fields!
     */
    private static final class AlignedZipWriter {
        private static final class CdRecord {
            final byte[] nameUtf8;
            final int method;
            final long crc32;
            final long compSize;
            final long uncompSize;
            final long lfhOffset;

            CdRecord(byte[] n, int m, long crc, long cs, long us, long off) {
                this.nameUtf8 = n;
                this.method = m;
                this.crc32 = crc;
                this.compSize = cs;
                this.uncompSize = us;
                this.lfhOffset = off;
            }
        }

        private final java.io.OutputStream out;
        private final List<CdRecord> cd = new ArrayList<CdRecord>();
        private long pos = 0L;

        AlignedZipWriter(java.io.OutputStream out) { this.out = out; }

        void beginStoredAligned(String name, long size, long crc32, int align) throws IOException {
            byte[] nameBytes = name.getBytes("UTF-8");
            long lfhOff = pos;
            long unpaddedData = lfhOff + 30L + nameBytes.length;
            int pad = (int) ((align - (unpaddedData % align)) % align);
            writeLfh(nameBytes, 0, crc32, size, size, new byte[pad]);
            cd.add(new CdRecord(nameBytes, 0, crc32, size, size, lfhOff));
        }

        void writePayload(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            pos += len;
        }

        void endStored() {}

        void writePreCompressedDeflated(String name, long uncompSize, long crc32, byte[] deflated) throws IOException {
            byte[] nameBytes = name.getBytes("UTF-8");
            long lfhOff = pos;
            writeLfh(nameBytes, 8, crc32, deflated.length, uncompSize, new byte[0]);
            out.write(deflated);
            pos += deflated.length;
            cd.add(new CdRecord(nameBytes, 8, crc32, deflated.length, uncompSize, lfhOff));
        }

        void writeDeflatedBytes(String name, byte[] raw) throws IOException {
            CRC32 crc = new CRC32();
            crc.update(raw);
            ByteArrayOutputStream defBuf = new ByteArrayOutputStream(raw.length + 32);
            Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            DeflaterOutputStream dos = new DeflaterOutputStream(defBuf, def);
            dos.write(raw);
            dos.finish();
            def.end();
            writePreCompressedDeflated(name, raw.length, crc.getValue(), defBuf.toByteArray());
        }

        private void writeLfh(byte[] nameBytes, int method, long crc32, long compSize, long uncompSize, byte[] extra)
                throws IOException {
            writeLe32(0x04034b50L);
            writeLe16(20);
            writeLe16(0x0800); // UTF-8 filename flag
            writeLe16(method);
            writeLe16(0x0000);
            writeLe16(0x4C21); // 2018-01-01 DOS date
            writeLe32(crc32);
            writeLe32(compSize);
            writeLe32(uncompSize);
            writeLe16(nameBytes.length);
            writeLe16(extra.length);
            out.write(nameBytes);
            pos += nameBytes.length;
            if (extra.length > 0) {
                out.write(extra);
                pos += extra.length;
            }
        }

        void finish() throws IOException {
            long cdStart = pos;
            for (CdRecord r : cd) {
                writeLe32(0x02014b50L);
                writeLe16(20);
                writeLe16(20);
                writeLe16(0x0800);
                writeLe16(r.method);
                writeLe16(0x0000);
                writeLe16(0x4C21);
                writeLe32(r.crc32);
                writeLe32(r.compSize);
                writeLe32(r.uncompSize);
                writeLe16(r.nameUtf8.length);
                writeLe16(0);
                writeLe16(0);
                writeLe16(0);
                writeLe16(0);
                writeLe32(0L);
                writeLe32(r.lfhOffset);
                out.write(r.nameUtf8);
                pos += r.nameUtf8.length;
            }
            long cdSize = pos - cdStart;
            writeLe32(0x06054b50L);
            writeLe16(0);
            writeLe16(0);
            writeLe16(cd.size());
            writeLe16(cd.size());
            writeLe32(cdSize);
            writeLe32(cdStart);
            writeLe16(0);
            out.flush();
        }

        void close() { closeQuietly(out); }

        private void writeLe16(int v) throws IOException {
            out.write(v & 0xFF);
            out.write((v >>> 8) & 0xFF);
            pos += 2;
        }

        private void writeLe32(long v) throws IOException {
            out.write((int) (v & 0xFF));
            out.write((int) ((v >>> 8) & 0xFF));
            out.write((int) ((v >>> 16) & 0xFF));
            out.write((int) ((v >>> 24) & 0xFF));
            pos += 4;
        }
    }

    private static byte[] formatManifestSection(String k1, String v1, String k2, String v2) throws IOException {
        StringBuilder sb = new StringBuilder();
        appendWrappedHeaderLine(sb, k1 + ": " + v1);
        appendWrappedHeaderLine(sb, k2 + ": " + v2);
        sb.append("\r\n");
        return sb.toString().getBytes("UTF-8");
    }

    /**
     * Strict 70-byte UTF-8 line wrapping required by `java.util.jar.Manifest`!
     */
    private static void appendWrappedHeaderLine(StringBuilder sb, String line) throws IOException {
        byte[] utf8 = line.getBytes("UTF-8");
        if (utf8.length <= 70) {
            sb.append(line).append("\r\n");
            return;
        }
        int lineBytes = 0;
        boolean continuation = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            String piece;
            if (Character.isHighSurrogate(c) && i + 1 < line.length() && Character.isLowSurrogate(line.charAt(i + 1))) {
                piece = new String(new char[]{c, line.charAt(++i)});
            } else {
                piece = String.valueOf(c);
            }
            int pLen = piece.getBytes("UTF-8").length;
            int maxBytes = continuation ? 69 : 70;
            if (lineBytes + pLen > maxBytes) {
                sb.append("\r\n ");
                continuation = true;
                lineBytes = 0;
            }
            sb.append(piece);
            lineBytes += pLen;
        }
        sb.append("\r\n");
    }

    private static boolean shouldStripSignatureEntry(String name) {
        if (!isMetaInfDirectChild(name)) return false;
        String upper = name.toUpperCase(Locale.US);
        return "META-INF/MANIFEST.MF".equals(upper)
                || upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC")
                || upper.startsWith("META-INF/SIG-");
    }

    private static boolean isMetaInfDirectChild(String name) {
        return name.regionMatches(true, 0, "META-INF/", 0, 9) && name.indexOf('/', 9) < 0;
    }

    /* ========================================================================
     * 3. Minimal RFC 5280 X.509 v3 + RFC 2315 PKCS#7 DER Encoders (Zero Deps!)
     * ======================================================================== */

    private static final byte[] OID_SHA1_WITH_RSA = new byte[]{
            0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x05};
    private static final byte[] OID_RSA_ENCRYPTION = new byte[]{
            0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01};
    private static final byte[] OID_SHA1 = new byte[]{
            0x2B, 0x0E, 0x03, 0x02, 0x1A};
    private static final byte[] OID_COMMON_NAME = new byte[]{
            0x55, 0x04, 0x03};
    private static final byte[] OID_PKCS7_DATA = new byte[]{
            0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x07, 0x01};
    private static final byte[] OID_PKCS7_SIGNED_DATA = new byte[]{
            0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x07, 0x02};

    private static byte[] buildSelfSignedX509CertificateDer(PublicKey pubKey, PrivateKey privKey) throws Exception {
        byte[] sigAlg = derSeq(derOid(OID_SHA1_WITH_RSA), derNull());
        byte[] rdnCn = derSet(derSeq(derOid(OID_COMMON_NAME), derUtf8("Arc7 Android 4.2 Signer")));
        byte[] dn = derSeq(rdnCn);
        byte[] validity = derSeq(
                derGeneralizedTime("20120101000000Z"),
                derGeneralizedTime("20561231235959Z"));
        byte[] spkiDer = pubKey.getEncoded(); // Already DER SubjectPublicKeyInfo!
        byte[] serial = derInt(BigInteger.valueOf(0x41524337313931L));

        byte[] tbs = derSeq(
                derExplicit(0, derInt(BigInteger.valueOf(2))), // X.509 v3
                serial,
                sigAlg,
                dn,
                validity,
                dn,
                spkiDer);

        Signature sig = Signature.getInstance("SHA1withRSA");
        sig.initSign(privKey);
        sig.update(tbs);
        byte[] signatureBytes = sig.sign();

        return derSeq(tbs, sigAlg, derBitString(signatureBytes));
    }

    private static byte[] buildPkcs7SignedDataDer(X509Certificate cert, byte[] certDer, byte[] signatureBytes) {
        byte[] sha1AlgId = derSeq(derOid(OID_SHA1), derNull());
        byte[] rsaAlgId = derSeq(derOid(OID_RSA_ENCRYPTION), derNull());

        byte[] issuerDer = cert.getIssuerX500Principal().getEncoded();
        byte[] issuerAndSerial = derSeq(issuerDer, derInt(cert.getSerialNumber()));

        byte[] signerInfo = derSeq(
                derInt(BigInteger.ONE),
                issuerAndSerial,
                sha1AlgId,
                rsaAlgId,
                derOctetString(signatureBytes));

        byte[] contentInfoData = derSeq(derOid(OID_PKCS7_DATA));

        byte[] signedData = derSeq(
                derInt(BigInteger.ONE),
                derSet(sha1AlgId),
                contentInfoData,
                derTag(0xA0, certDer), // [0] IMPLICIT CertificateSet
                derSet(signerInfo));

        return derSeq(
                derOid(OID_PKCS7_SIGNED_DATA),
                derExplicit(0, signedData));
    }

    private static byte[] derTag(int tag, byte[] body) {
        int len = body.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream(len + 6);
        out.write(tag);
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else if (len < 65536) {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write((len >> 16) & 0xFF);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(body, 0, len);
        return out.toByteArray();
    }

    private static byte[] derSeq(byte[]... parts) { return derTag(0x30, concat(parts)); }
    private static byte[] derSet(byte[]... parts) { return derTag(0x31, concat(parts)); }
    private static byte[] derExplicit(int tagNo, byte[] inner) { return derTag(0xA0 | tagNo, inner); }
    private static byte[] derOid(byte[] body) { return derTag(0x06, body); }
    private static byte[] derNull() { return new byte[]{0x05, 0x00}; }
    private static byte[] derOctetString(byte[] b) { return derTag(0x04, b); }
    private static byte[] derInt(BigInteger bi) { return derTag(0x02, bi.toByteArray()); }
    private static byte[] derUtf8(String s) throws IOException { return derTag(0x0C, s.getBytes("UTF-8")); }
    private static byte[] derGeneralizedTime(String s) throws IOException { return derTag(0x18, s.getBytes("US-ASCII")); }

    private static byte[] derBitString(byte[] bits) {
        byte[] b = new byte[bits.length + 1];
        b[0] = 0x00;
        System.arraycopy(bits, 0, b, 1, bits.length);
        return derTag(0x03, b);
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int p = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, p, a.length);
            p += a.length;
        }
        return out;
    }

    /* ========================================================================
     * 4. Full Cryptographic PKCS#7 JAR Verifier for SHA-256 `index-v1.jar`
     *    (Solves AOSP Bug #38321 when 2026 F-Droid repos sign with SHA256withRSA)
     * ======================================================================== */

    public static X509Certificate verifyPkcs7JarAndExtractCert(File jarFile, String targetEntryName) throws SecurityException {
        ZipFile zf = null;
        try {
            zf = new ZipFile(jarFile);
            ZipEntry mfEntry = zf.getEntry("META-INF/MANIFEST.MF");
            if (mfEntry == null) throw new SecurityException("Missing META-INF/MANIFEST.MF");
            byte[] mfBytes = readAllBytes(zf.getInputStream(mfEntry), 8 * 1024 * 1024);

            Manifest mf = new Manifest(new ByteArrayInputStream(mfBytes));
            Attributes entryAttrs = mf.getAttributes(targetEntryName);
            if (entryAttrs == null) {
                throw new SecurityException("Unsigned entry: " + targetEntryName);
            }

            // 1. Verify target entry (`index-v1.json`) against MANIFEST.MF digest
            ZipEntry payloadEntry = zf.getEntry(targetEntryName);
            if (payloadEntry == null) throw new SecurityException("Missing entry: " + targetEntryName);
            verifyDigestAttributes(zf.getInputStream(payloadEntry), entryAttrs, targetEntryName);

            // 2. Locate `.SF` and matching `.RSA` / `.EC` / `.DSA` signature block in META-INF/
            ZipEntry sfEntry = null;
            ZipEntry sigBlockEntry = null;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                String n = ze.getName();
                if (!isMetaInfDirectChild(n)) continue;
                String up = n.toUpperCase(Locale.US);
                if (sfEntry == null && up.endsWith(".SF")) sfEntry = ze;
                else if (sigBlockEntry == null && (up.endsWith(".RSA") || up.endsWith(".EC") || up.endsWith(".DSA"))) {
                    sigBlockEntry = ze;
                }
            }
            if (sfEntry == null || sigBlockEntry == null) {
                throw new SecurityException("Missing .SF or PKCS#7 signature block in index JAR");
            }

            byte[] sfBytes = readAllBytes(zf.getInputStream(sfEntry), 8 * 1024 * 1024);
            Manifest sf = new Manifest(new ByteArrayInputStream(sfBytes));
            verifyManifestDigestInSf(mfBytes, sf.getMainAttributes());

            // 3. Extract X.509 signer certificate AND raw encryptedDigest OCTET STRING from PKCS#7 block,
            //    and cryptographically verify public-key signature over `sfBytes`!
            byte[] pkcs7 = readAllBytes(zf.getInputStream(sigBlockEntry), 2 * 1024 * 1024);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(new ByteArrayInputStream(pkcs7));
            if (certs == null || certs.isEmpty()) {
                throw new SecurityException("No X.509 certificate in PKCS#7 block");
            }

            List<byte[]> candidateOctets = extractOctetStringsFromDer(pkcs7);
            String[] sigAlgs = new String[]{
                "SHA256withRSA", "SHA512withRSA", "SHA384withRSA", "SHA1withRSA",
                "SHA256withECDSA", "SHA384withECDSA", "SHA512withECDSA", "SHA256withDSA", "SHA1withDSA"
            };

            for (Certificate c : certs) {
                if (!(c instanceof X509Certificate)) continue;
                X509Certificate x509 = (X509Certificate) c;
                PublicKey pub = x509.getPublicKey();
                for (byte[] sigCandidate : candidateOctets) {
                    if (sigCandidate.length < 40) continue;
                    for (String alg : sigAlgs) {
                        try {
                            Signature s = Signature.getInstance(alg);
                            s.initVerify(pub);
                            s.update(sfBytes);
                            if (s.verify(sigCandidate)) {
                                return x509;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
            throw new SecurityException("PKCS#7 cryptographic signature verification failed!");
        } catch (SecurityException se) {
            throw se;
        } catch (Throwable t) {
            throw new SecurityException("JAR signature verification failed: " + t.getMessage(), t);
        } finally {
            if (zf != null) {
                try { zf.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void verifyDigestAttributes(InputStream dataStream, Attributes attrs, String label) throws Exception {
        String[][] supported = new String[][]{
            {"SHA-512-Digest", "SHA-512"},
            {"SHA-384-Digest", "SHA-384"},
            {"SHA-256-Digest", "SHA-256"},
            {"SHA1-Digest", "SHA-1"}
        };
        for (String[] pair : supported) {
            String expected = attrs.getValue(pair[0]);
            if (expected != null && expected.trim().length() > 0) {
                MessageDigest md = MessageDigest.getInstance(pair[1]);
                byte[] buf = new byte[32768];
                try {
                    int n;
                    while ((n = dataStream.read(buf)) > 0) md.update(buf, 0, n);
                } finally {
                    closeQuietly(dataStream);
                }
                byte[] actual = md.digest();
                byte[] expBytes = b64Decode(expected.trim());
                if (!MessageDigest.isEqual(actual, expBytes)) {
                    throw new SecurityException("Digest mismatch on " + label + " (" + pair[0] + ")");
                }
                return;
            }
        }
        closeQuietly(dataStream);
        throw new SecurityException("No supported digest attribute on " + label);
    }

    private static void verifyManifestDigestInSf(byte[] mfBytes, Attributes sfMain) throws Exception {
        String[][] supported = new String[][]{
            {"SHA-512-Digest-Manifest", "SHA-512"},
            {"SHA-384-Digest-Manifest", "SHA-384"},
            {"SHA-256-Digest-Manifest", "SHA-256"},
            {"SHA1-Digest-Manifest", "SHA-1"}
        };
        for (String[] pair : supported) {
            String expected = sfMain.getValue(pair[0]);
            if (expected != null && expected.trim().length() > 0) {
                byte[] actual = MessageDigest.getInstance(pair[1]).digest(mfBytes);
                byte[] expBytes = b64Decode(expected.trim());
                if (!MessageDigest.isEqual(actual, expBytes)) {
                    throw new SecurityException("Manifest digest mismatch (" + pair[0] + ")");
                }
                return;
            }
        }
        throw new SecurityException("No *-Digest-Manifest header found in .SF");
    }

    private static List<byte[]> extractOctetStringsFromDer(byte[] der) {
        List<byte[]> out = new ArrayList<byte[]>();
        walkDerOctetStrings(der, 0, der.length, out, 0);
        return out;
    }

    private static void walkDerOctetStrings(byte[] b, int pos, int end, List<byte[]> out, int depth) {
        if (depth > 24) return;
        while (pos + 2 <= end) {
            int tag = b[pos++] & 0xFF;
            if (tag == 0x00) continue;
            int len = b[pos++] & 0xFF;
            if ((len & 0x80) != 0) {
                int numBytes = len & 0x7F;
                if (numBytes == 0 || numBytes > 4 || pos + numBytes > end) return;
                len = 0;
                for (int i = 0; i < numBytes; i++) {
                    len = (len << 8) | (b[pos++] & 0xFF);
                }
            }
            if (len < 0 || pos + len > end) return;
            if (tag == 0x04) { // OCTET STRING (SignerInfo.encryptedDigest!)
                byte[] slice = new byte[len];
                System.arraycopy(b, pos, slice, 0, len);
                out.add(slice);
            } else if ((tag & 0x20) != 0) { // Constructed SEQUENCE / SET / [0] EXPLICIT
                walkDerOctetStrings(b, pos, pos + len, out, depth + 1);
            }
            pos += len;
        }
    }

    /* ========================================================================
     * 5. Mount-Namespace-Safe `/data/local/tmp` Root + Fallback APK Installer
     * ======================================================================== */

    public static RootInstallOutcome stageAndInstallApk(Context ctx, File rawApk, boolean allowSignatureReplace) throws Exception {
        ApkInspection check = inspectApk(rawApk, Build.VERSION.SDK_INT > 0 ? Build.VERSION.SDK_INT : DEVICE_MAX_SDK);
        if (!check.isCompatibleWithAndroid42()) {
            throw new IOException(check.errorReason);
        }

        // Automatically add Android 4.2 SHA1withRSA v1 signature if missing (fixes AOSP Bug #38321!)
        File readyApk = ensureAndroid42V1Signed(ctx, rawApk);
        readyApk.setReadable(true, false);

        String suBin = findSuBinary();
        if (suBin == null) {
            return new RootInstallOutcome(false, false, "No root su binary present", readyApk);
        }

        String stagePath = "/data/local/tmp/arc7_stage_" + Math.abs(readyApk.getName().hashCode()) + ".apk";
        String qSrc = shellQuote(readyApk.getAbsolutePath());
        String qStage = shellQuote(stagePath);

        String cmd = "cat " + qSrc + " > " + qStage +
                " && chmod 0644 " + qStage +
                " && pm install -r " + qStage;

        String out = execRootCommand(suBin, cmd);
        if (out.contains("Success")) {
            execRootCommand(suBin, "rm -f " + qStage);
            return new RootInstallOutcome(true, true, out.trim(), readyApk);
        }

        if (allowSignatureReplace
                && check.packageName.length() > 0
                && !check.packageName.startsWith("android")
                && !"ca.arc7freedom.app".equals(check.packageName)
                && (out.contains("INCONSISTENT_CERTIFICATES") || out.contains("UPDATE_INCOMPATIBLE"))) {
            String retry = execRootCommand(
                    suBin,
                    "pm uninstall " + shellQuote(check.packageName) + " >/dev/null 2>&1 || true; "
                    + "pm install -r " + qStage + "; rm -f " + qStage);
            if (retry.contains("Success")) {
                return new RootInstallOutcome(true, true, retry.trim(), readyApk);
            }
            return new RootInstallOutcome(true, false, retry.trim(), readyApk);
        }

        execRootCommand(suBin, "rm -f " + qStage);
        return new RootInstallOutcome(true, false, out.trim(), readyApk);
    }

    private static String findSuBinary() {
        String[] paths = new String[]{"/system/xbin/su", "/system/bin/su", "/sbin/su"};
        for (String p : paths) {
            if (new File(p).exists()) return p;
        }
        return null;
    }

    private static String execRootCommand(String suBin, String script) {
        Process p = null;
        try {
            p = new ProcessBuilder(suBin, "-c", script).redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] out = readAllBytes(p.getInputStream(), 262144);
            p.waitFor();
            return new String(out, "UTF-8");
        } catch (Throwable t) {
            return "ERR: " + t.getMessage();
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
    }

    public static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /* ========================================================================
     * Stream & Encoding Helpers
     * ======================================================================== */

    private static final char[] B64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private static String b64NoWrap(byte[] data) {
        int len = data.length;
        StringBuilder sb = new StringBuilder(((len + 2) / 3) * 4);
        int i = 0;
        while (i < len) {
            int b0 = data[i++] & 0xFF;
            int b1 = (i < len) ? (data[i++] & 0xFF) : -1;
            int b2 = (i < len) ? (data[i++] & 0xFF) : -1;
            sb.append(B64_ALPHABET[b0 >>> 2]);
            sb.append(B64_ALPHABET[((b0 & 0x03) << 4) | (b1 < 0 ? 0 : (b1 >>> 4))]);
            sb.append(b1 < 0 ? '=' : B64_ALPHABET[((b1 & 0x0F) << 2) | (b2 < 0 ? 0 : (b2 >>> 6))]);
            sb.append(b2 < 0 ? '=' : B64_ALPHABET[b2 & 0x3F]);
        }
        return sb.toString();
    }

    private static byte[] b64Decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream((s.length() * 3) / 4);
        int acc = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+') v = 62;
            else if (c == '/') v = 63;
            else if (c == '=') break;
            else continue;
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((acc >>> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static byte[] readAllBytes(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > maxBytes) {
                    throw new IOException("Stream exceeded limit (" + maxBytes + " bytes)");
                }
            }
            return bos.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    private static int readFullyUpTo(InputStream in, byte[] dst) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n <= 0) break;
            off += n;
        }
        return off;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Throwable ignored) {}
        }
    }
}
