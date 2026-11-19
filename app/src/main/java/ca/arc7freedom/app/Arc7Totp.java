package ca.arc7freedom.app;

import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC4226/6238 offline TOTP. No accounts, analytics, clock sync or Internet permission. */
public final class Arc7Totp {
    private Arc7Totp() {}
    public static final class Account {
        public String issuer="", label="", secret="", algorithm="SHA1";
        public int digits=6, period=30;
    }
    public static byte[] decodeBase32(String text) throws Exception {
        if(text!=null && text.length()>512) throw new Exception("Base32 secret is too long");
        String value=text==null ? "" : text.replace(" ","").replace("-","").toUpperCase(Locale.US);
        int pad=value.indexOf('=');
        if(pad>=0) {
            for(int i=pad;i<value.length();i++) if(value.charAt(i)!='=') throw new Exception("Invalid Base32 padding");
            if(value.length()%8!=0 || value.length()-pad!=((8-(pad%8))%8)) throw new Exception("Invalid Base32 padding");
            value=value.substring(0,pad);
        }
        if(value.length()<16 || value.length()>256 || value.length()%8==1 || value.length()%8==3 || value.length()%8==6)
            throw new Exception("Use a Base32 secret of at least 80 bits (16 characters)");
        byte[] bytes=new byte[value.length()*5/8]; int bits=0, buffer=0, offset=0;
        for(int i=0;i<value.length();i++) {
            int n="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(value.charAt(i));
            if(n<0) throw new Exception("Secret contains a non-Base32 character");
            buffer=(buffer<<5)|n; bits+=5;
            if(bits>=8) { bits-=8; bytes[offset++]=(byte)(buffer>>bits); }
        }
        if(bits>0 && (buffer&((1<<bits)-1))!=0) throw new Exception("Invalid Base32 trailing bits");
        return bytes;
    }
    public static String generate(String secret,String algorithm,int digits,int period,long unixSeconds) throws Exception {
        if(digits!=6 && digits!=8) throw new Exception("TOTP supports 6 or 8 digits");
        if(period<15 || period>120 || unixSeconds<0) throw new Exception("Invalid TOTP time/period");
        String alg=normalizeAlgorithm(algorithm);
        byte[] key=decodeBase32(secret), counter=new byte[8], digest=null;
        long step=unixSeconds/period;
        for(int i=7;i>=0;i--) { counter[i]=(byte)step; step>>>=8; }
        try {
            Mac mac=Mac.getInstance("Hmac"+alg); mac.init(new SecretKeySpec(key,"Hmac"+alg)); digest=mac.doFinal(counter);
            int o=digest[digest.length-1]&15;
            long binary=((digest[o]&127L)<<24)|((digest[o+1]&255L)<<16)|((digest[o+2]&255L)<<8)|(digest[o+3]&255L);
            long modulus=digits==8 ? 100000000L : 1000000L;
            return String.format(Locale.US,digits==8 ? "%08d" : "%06d",binary%modulus);
        } finally { Arc7VaultCrypto.wipe(key); Arc7VaultCrypto.wipe(counter); Arc7VaultCrypto.wipe(digest); }
    }
    public static String normalizeAlgorithm(String algorithm) throws Exception {
        String value=algorithm==null ? "SHA1" : algorithm.toUpperCase(Locale.US).replace("-","");
        if(!value.equals("SHA1") && !value.equals("SHA256") && !value.equals("SHA512")) throw new Exception("Unsupported TOTP algorithm");
        return value;
    }
    public static Account parse(String input) throws Exception {
        Account account=new Account();
        if(input==null || input.length()>4096) throw new Exception("Invalid account input");
        if(!input.startsWith("otpauth://")) { account.secret=input.trim(); byte[] b=decodeBase32(account.secret); Arc7VaultCrypto.wipe(b); return account; }
        URI uri=new URI(input);
        if(!"otpauth".equals(uri.getScheme()) || !"totp".equals(uri.getHost()) || uri.getRawQuery()==null || uri.getFragment()!=null)
            throw new Exception("Only otpauth://totp accounts are supported (not HOTP)");
        String path=uri.getRawPath(); account.label=decode(path!=null && path.startsWith("/") ? path.substring(1) : "");
        Map<String,String> params=new LinkedHashMap<String,String>();
        for(String pair:uri.getRawQuery().split("&")) {
            int equal=pair.indexOf('='); if(equal<1) throw new Exception("Invalid account parameter");
            String name=decode(pair.substring(0,equal)).toLowerCase(Locale.US);
            if(params.containsKey(name)) throw new Exception("Duplicate account parameter");
            params.put(name,decode(pair.substring(equal+1)));
        }
        account.secret=params.get("secret"); if(account.secret==null) throw new Exception("Account secret is missing");
        int colon=account.label.indexOf(':');
        if(colon>=0) { account.issuer=account.label.substring(0,colon); account.label=account.label.substring(colon+1).trim(); }
        if(params.containsKey("issuer")) {
            if(account.issuer.length()>0 && !account.issuer.equals(params.get("issuer"))) throw new Exception("Issuer and account label disagree");
            account.issuer=params.get("issuer");
        }
        if(params.containsKey("algorithm")) account.algorithm=normalizeAlgorithm(params.get("algorithm"));
        if(params.containsKey("digits")) account.digits=Integer.parseInt(params.get("digits"));
        if(params.containsKey("period")) account.period=Integer.parseInt(params.get("period"));
        // Validates secret, algorithm, period and digits without exposing it in errors.
        generate(account.secret,account.algorithm,account.digits,account.period,0);
        if(account.label.length()>200 || account.issuer.length()>200) throw new Exception("Account label is too long");
        return account;
    }
    private static String decode(String value) throws Exception { return URLDecoder.decode(value.replace("+","%2B"),"UTF-8"); }
}
