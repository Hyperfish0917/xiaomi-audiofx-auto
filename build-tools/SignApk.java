import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

public final class SignApk {
    public static void main(String[] args) throws Exception {
        KeyStore ks=KeyStore.getInstance("PKCS12");
        String password=System.getenv("AUDIOFX_STORE_PASSWORD");
        if(password==null||password.isEmpty())throw new IllegalArgumentException("AUDIOFX_STORE_PASSWORD is required");
        String alias=System.getenv("AUDIOFX_KEY_ALIAS");if(alias==null||alias.isEmpty())alias="audiofx";
        try(FileInputStream in=new FileInputStream(args[0])) {ks.load(in,password.toCharArray());}
        PrivateKey key=(PrivateKey)ks.getKey(alias,password.toCharArray());
        X509Certificate cert=(X509Certificate)ks.getCertificate(alias);
        if(key==null||cert==null)throw new IllegalArgumentException("Private key or certificate missing for configured alias");
        ApkSigner.SignerConfig signer=new ApkSigner.SignerConfig.Builder("audiofx",key,Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(signer)).setInputApk(new File(args[1]))
                .setOutputApk(new File(args[2])).setMinSdkVersion(36)
                .setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .setV4SigningEnabled(false).build().sign();
        ApkVerifier.Result result=new ApkVerifier.Builder(new File(args[2])).build().verify();
        if(!result.isVerified())throw new IllegalStateException("APK verification: "+result.getErrors());
        System.out.println("APK_VERIFIED v2="+result.isVerifiedUsingV2Scheme()+" v3="+result.isVerifiedUsingV3Scheme());
    }
}
