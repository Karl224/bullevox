package ca.local.vvm;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class ServerKeyPin {
    static String probe(SocketFactory sockets, InetAddress address, String host, int port) throws Exception {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (Socket connection = sockets.createSocket()) {
            connection.connect(new InetSocketAddress(address, port), 8000);
            try (SSLSocket tls = (SSLSocket) factory.createSocket(connection, host, port, true)) {
                tls.setSoTimeout(8000);
                tls.startHandshake();
                return fingerprint((X509Certificate) tls.getSession().getPeerCertificates()[0]);
            }
        }
    }

    static SSLSocketFactory socketFactory(String approved) throws GeneralSecurityException {
        return verifiedSocketFactory(certificate -> {
            if (!approved.equals(fingerprint(certificate))) {
                throw new KeyChanged();
            }
        });
    }

    static SSLSocketFactory socketFactoryForNames(String[] expected) throws GeneralSecurityException {
        return verifiedSocketFactory(certificate -> {
            Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
            if (alternatives != null) {
                for (List<?> entry : alternatives) {
                    if (entry.size() >= 2 && Integer.valueOf(2).equals(entry.get(0)) && entry.get(1) instanceof String) {
                        for (String name : expected) {
                            if (name.equalsIgnoreCase((String) entry.get(1))) {
                                return;
                            }
                        }
                    }
                }
            }
            throw new UnexpectedServerName();
        });
    }

    private interface CertificateCheck {
        void verify(X509Certificate certificate) throws CertificateException;
    }

    private static SSLSocketFactory verifiedSocketFactory(CertificateCheck check) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((java.security.KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{new CheckedTrust((X509TrustManager) manager, check)}, null);
                return context.getSocketFactory();
            }
        }
        throw new GeneralSecurityException("Platform certificate trust is unavailable");
    }

    private static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(certificate.getPublicKey().getEncoded());
            return Base64.getEncoder().encodeToString(hash);
        } catch (GeneralSecurityException failure) {
            throw new CertificateException("Cannot fingerprint server key", failure);
        }
    }

    static final class KeyChanged extends CertificateException {}

    static final class UnexpectedServerName extends CertificateException {}

    private static final class CheckedTrust implements X509TrustManager {
        private final X509TrustManager platform;
        private final CertificateCheck check;

        CheckedTrust(X509TrustManager platform, CertificateCheck check) {
            this.platform = platform;
            this.check = check;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            platform.checkServerTrusted(chain, authType);
            if (chain.length == 0) {
                throw new CertificateException("No server certificate");
            }
            check.verify(chain[0]);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            platform.checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return platform.getAcceptedIssuers();
        }
    }
}
