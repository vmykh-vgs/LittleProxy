package org.littleshoot.proxy.extras;

import static org.littleshoot.proxy.extras.SelfSignedSslEngineSource.ALIAS;
import static org.littleshoot.proxy.extras.SelfSignedSslEngineSource.KEY_STORE_CERT_NAME;
import static org.littleshoot.proxy.extras.SelfSignedSslEngineSource.KEY_STORE_NAME;
import static org.littleshoot.proxy.extras.SelfSignedSslEngineSource.PASSWORD;
import static org.littleshoot.proxy.extras.SelfSignedSslEngineSource.SSL_DIR_PATH_PROPERTY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class SelfSignedSslEngineSourceTest {

    private List<String> captor;

    class SelfSignedSslEngineSourceSpy extends SelfSignedSslEngineSource {

        public SelfSignedSslEngineSourceSpy() {
            super();
        }

        public SelfSignedSslEngineSourceSpy(String keyStorePath, boolean trustAllServers, boolean sendCerts) {
            super(keyStorePath, trustAllServers, sendCerts);
        }

        protected String nativeCall(final String... commands) {
            captor.addAll(Arrays.asList(commands));
            return "Who cares?";
        }

        protected void initializeSSLContext() {
            /*NOP, Just a mock to ignore calls*/
        }
    }

    @Before
    public void cleanUp() {
        System.clearProperty(SSL_DIR_PATH_PROPERTY);
        captor = spy(new ArrayList<>());
    }

    @Test
    public void testExistingCertificateFile() {
        final SelfSignedSslEngineSourceSpy spy = new SelfSignedSslEngineSourceSpy();
        verify(captor, times(0)).addAll(any());
    }

    @Test
    public void testNonExistingCertificateFile() {
        final String keyStorePath = "/tmp/littleproxy_keystore.jks";
        final SelfSignedSslEngineSourceSpy spy = new SelfSignedSslEngineSourceSpy(
            keyStorePath, false, true);
        final List<String> genkeyArgument = Arrays.asList(
            "keytool", "-genkey", "-alias", ALIAS, "-keysize",
            "4096", "-validity", "36500", "-keyalg", "RSA", "-dname",
            "CN=littleproxy", "-keypass", PASSWORD, "-storepass",
            PASSWORD, "-keystore", keyStorePath);
        final List<String> exportcertArgument = Arrays.asList(
            "keytool", "-exportcert", "-alias", ALIAS, "-keystore",
            keyStorePath, "-storepass", PASSWORD, "-file", KEY_STORE_CERT_NAME
        );
        verify(captor, times(1)).addAll(genkeyArgument);
        verify(captor, times(1)).addAll(exportcertArgument);
    }

    @Test
    public void testNonExistingCertificateFileWithSslDirEnv() {
        final String directory = "/usr/local/bin/";
        System.setProperty(SSL_DIR_PATH_PROPERTY, directory);
        final SelfSignedSslEngineSourceSpy spy = new SelfSignedSslEngineSourceSpy();
        final String keyStorePath = directory + KEY_STORE_NAME;
        final List<String> genkeyArgument = Arrays.asList(
            "keytool", "-genkey", "-alias", "littleproxy", "-keysize",
            "4096", "-validity", "36500", "-keyalg", "RSA", "-dname",
            "CN=littleproxy", "-keypass", "Be Your Own Lantern", "-storepass",
            "Be Your Own Lantern", "-keystore", keyStorePath);
        final List<String> exportcertArgument = Arrays.asList(
            "keytool", "-exportcert", "-alias", ALIAS, "-keystore",
            keyStorePath, "-storepass", PASSWORD, "-file", directory + KEY_STORE_CERT_NAME
        );
        verify(captor, times(1)).addAll(genkeyArgument);
        verify(captor, times(1)).addAll(exportcertArgument);
    }

}