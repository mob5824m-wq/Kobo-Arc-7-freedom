package ca.arc7freedom.app;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Versioned authenticated vault format, portable to API15. No Android or network dependency.
 * PBKDF2-HMAC-SHA256 (600,000 rounds), separated encryption/MAC keys, AES-256-CBC +
 * encrypt-then-HMAC-SHA256. Authenticate the entire header and ciphertext BEFORE decrypting.
 * CBC is used because Android 4.x's GCM implementations are not reliably available.
 */
public final class Arc7VaultCrypto {
    private Arc7VaultCrypto() {}
    public static final int ITERATIONS=600000, MAX_BYTES=1024*1024;
    private static final byte[] MAGIC={'A','R','C','7','V','L','T',1};
    public static final class Session {
        private byte[] root, salt;
        private final String purpose;
        private final int rounds;
        private Session(byte[] root, byte[] salt, String purpose, int rounds) {
            this.root=root; this.salt=salt; this.purpose=purpose; this.rounds=rounds;
        }
        public synchronized void close() { wipe(root); wipe(salt); root=null; salt=null; }
        private synchronized byte[] key(String name) throws Exception {
            if(root==null) throw new GeneralSecurityException("Vault is locked");
            return hmac(root,("Arc7Vault-v1/"+purpose+"/"+name).getBytes("UTF-8"));
        }
    }
    public static final class Opened {
        public final Session session; public final byte[] plaintext;
        Opened(Session s, byte[] p) { session=s; plaintext=p; }
    }
    public static Session create(char[] password,String purpose) throws Exception {
        if(password==null || password.length<10 || password.length>1024)
            throw new GeneralSecurityException("Use a master password of 10 to 1024 characters.");
        validatePurpose(purpose);
        byte[] salt=random(16), encoded=passwordBytes(password);
        try { return new Session(pbkdf2(encoded,salt,ITERATIONS),salt,purpose,ITERATIONS); }
        finally { wipe(encoded); }
    }
    public static byte[] seal(Session session,byte[] plaintext) throws Exception {
        if(plaintext==null || plaintext.length>MAX_BYTES) throw new IOException("Vault exceeds the 1 MiB limit");
        byte[] enc=null, auth=null;
        synchronized(session) {
            try {
                enc=session.key("encryption"); auth=session.key("authentication");
                byte[] iv=random(16);
                Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(enc,"AES"),new IvParameterSpec(iv));
                byte[] encrypted=cipher.doFinal(plaintext);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
                out.write(MAGIC); out.writeInt(session.rounds); out.write(session.salt); out.write(iv);
                out.writeUTF(session.purpose); out.writeInt(encrypted.length); out.write(encrypted); out.flush();
                byte[] body=bytes.toByteArray(), tag=hmac(auth,body);
                out.write(tag); out.flush(); return bytes.toByteArray();
            } finally { wipe(enc); wipe(auth); }
        }
    }
    public static Opened open(byte[] file,char[] password,String expectedPurpose) throws Exception {
        validatePurpose(expectedPurpose);
        if(file==null || file.length<90 || file.length>MAX_BYTES+512 || password==null || password.length>1024)
            throw new GeneralSecurityException("Invalid encrypted vault");
        byte[] root=null, enc=null, auth=null; Session session=null; boolean success=false;
        try {
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(file)); byte[] magic=new byte[MAGIC.length]; in.readFully(magic);
            if(!constantTime(magic,MAGIC)) throw new IOException("Unsupported vault format");
            int rounds=in.readInt(); if(rounds<ITERATIONS || rounds>2000000) throw new IOException("Unsupported key-derivation cost");
            byte[] salt=new byte[16], iv=new byte[16]; in.readFully(salt); in.readFully(iv);
            String purpose=in.readUTF(); if(!expectedPurpose.equals(purpose)) throw new IOException("This backup belongs to a different vault app");
            int length=in.readInt(); if(length<16 || length>MAX_BYTES+16 || length%16!=0 || in.available()!=length+32)
                throw new IOException("Invalid encrypted vault length");
            byte[] encrypted=new byte[length], supplied=new byte[32]; in.readFully(encrypted); in.readFully(supplied);
            byte[] encoded=passwordBytes(password);
            try { root=pbkdf2(encoded,salt,rounds); } finally { wipe(encoded); }
            session=new Session(root,salt,purpose,rounds); root=null;
            auth=session.key("authentication");
            byte[] body=Arrays.copyOf(file,file.length-32), actual=hmac(auth,body);
            boolean valid=constantTime(actual,supplied); wipe(actual); wipe(body);
            if(!valid) throw new GeneralSecurityException("Incorrect master password or damaged backup");
            enc=session.key("encryption"); Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(enc,"AES"),new IvParameterSpec(iv));
            byte[] plain=cipher.doFinal(encrypted); success=true; return new Opened(session,plain);
        } finally { wipe(root); wipe(enc); wipe(auth); if(!success && session!=null) session.close(); }
    }
    private static void validatePurpose(String purpose) throws IOException {
        if(!"passwords".equals(purpose) && !"authenticator".equals(purpose)) throw new IOException("Unsupported vault purpose");
    }
    private static byte[] passwordBytes(char[] password) {
        ByteBuffer b=Charset.forName("UTF-8").encode(CharBuffer.wrap(password)); byte[] result=new byte[b.remaining()]; b.get(result);
        if(b.hasArray()) Arrays.fill(b.array(),(byte)0); return result;
    }
    /** RFC 8018, one 256-bit block. Public for published test-vector coverage. */
    public static byte[] pbkdf2(byte[] password,byte[] salt,int iterations) throws Exception {
        if(iterations<1 || iterations>2000000 || salt.length>1024) throw new IllegalArgumentException("Invalid KDF parameters");
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(password.length==0 ? new byte[]{0} : password,"HmacSHA256"));
        byte[] first=Arrays.copyOf(salt,salt.length+4); first[first.length-1]=1;
        byte[] u=mac.doFinal(first), result=u.clone();
        try {
            for(int i=1;i<iterations;i++) {
                if((i&2047)==0 && Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Unlock cancelled");
                byte[] next=mac.doFinal(u); wipe(u); u=next;
                for(int j=0;j<32;j++) result[j]^=u[j];
            }
            return result;
        } catch(Exception e) { wipe(result); throw e; }
        finally { wipe(u); wipe(first); }
    }
    private static byte[] hmac(byte[] key,byte[] message) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key,"HmacSHA256")); return mac.doFinal(message);
    }
    public static boolean constantTime(byte[] a,byte[] b) {
        if(a==null || b==null) return false;
        int difference=a.length^b.length;
        for(int i=0;i<Math.max(a.length,b.length);i++) difference|=(i<a.length ? a[i] : 0)^(i<b.length ? b[i] : 0);
        return difference==0;
    }
    /** Linux entropy avoids Android 4.x's historical unseeded SecureRandom vulnerability. */
    public static byte[] random(int length) throws IOException {
        if(length<1 || length>4096) throw new IOException("Invalid random request");
        byte[] bytes=new byte[length]; DataInputStream in=new DataInputStream(new FileInputStream("/dev/urandom"));
        try { in.readFully(bytes); } finally { in.close(); } return bytes;
    }
    public static String password(int length,boolean symbols) throws Exception {
        if(length<16 || length>128) throw new IllegalArgumentException("Choose 16 to 128 characters");
        String alphabet="ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"+(symbols ? "!#$%&*+-=?@_" : "");
        StringBuilder result=new StringBuilder(); int limit=256-(256%alphabet.length());
        while(result.length()<length) {
            byte[] bytes=random(64);
            for(byte b:bytes) { int n=b&255; if(n<limit && result.length()<length) result.append(alphabet.charAt(n%alphabet.length())); }
            wipe(bytes);
        }
        return result.toString();
    }
    public static void atomicWrite(File file,byte[] data) throws Exception {
        if(data.length>MAX_BYTES+512) throw new IOException("Vault file too large");
        File parent=file.getParentFile(); if(!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Private vault storage unavailable");
        File temp=File.createTempFile("vault-", ".tmp",parent); boolean saved=false;
        try {
            FileOutputStream out=new FileOutputStream(temp);
            try { out.write(data); out.flush(); out.getFD().sync(); } finally { out.close(); }
            if(!temp.renameTo(file)) throw new IOException("Could not atomically save vault; original retained"); saved=true;
        } finally { if(!saved) temp.delete(); }
    }
    public static byte[] read(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n;
            while((n=in.read(b))!=-1) { if(out.size()+n>MAX_BYTES+512) throw new IOException("Vault file too large"); out.write(b,0,n); }
            return out.toByteArray();
        } finally { in.close(); }
    }
    public static void wipe(byte[] bytes) { if(bytes!=null) Arrays.fill(bytes,(byte)0); }
    public static void wipe(char[] chars) { if(chars!=null) Arrays.fill(chars,'\0'); }
}
