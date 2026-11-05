package ca.local.vvm;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import javax.mail.Address;
import javax.mail.AuthenticationFailedException;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.internet.MimeMessage;
import javax.net.SocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

final class Mailbox {
    static final class Settings {
        final String host;
        final int port;
        final String user;
        final String password;
        final String approvedKey;
        final String[] expectedNames;

        Settings(String host, int port, String user, String password) {
            this(host, port, user, password, null);
        }

        Settings(String host, int port, String user, String password, String approvedKey) {
            this(host, port, user, password, approvedKey, new String[0]);
        }

        Settings(String host, int port, String user, String password, String approvedKey, String[] expectedNames) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
            this.approvedKey = approvedKey;
            this.expectedNames = expectedNames.clone();
        }
    }

    static final class Entry {
        final long validity;
        final long uid;
        final String caller;
        final Date sentAt;

        Entry(long validity, long uid, String caller, Date sentAt) {
            this.validity = validity;
            this.uid = uid;
            this.caller = caller;
            this.sentAt = sentAt;
        }
    }

    private interface Query<T> {
        T run(IMAPFolder folder) throws Exception;
    }

    private static <T> T read(Settings settings, Query<T> query) throws Exception {
        SSLSocketFactory pin = settings.approvedKey == null ? null : ServerKeyPin.socketFactory(settings.approvedKey);
        try {
            return read(settings, query, pin);
        } catch (Failure failure) {
            if (pin != null || settings.expectedNames.length == 0 || !failure.isHostnameMismatch()) {
                throw failure;
            }
            return read(settings, query, ServerKeyPin.socketFactoryForNames(settings.expectedNames));
        }
    }

    private static <T> T read(Settings settings, Query<T> query, SSLSocketFactory identityFactory) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("mail.imaps.ssl.checkserveridentity", Boolean.toString(identityFactory == null));
        if (identityFactory != null) {
            properties.put("mail.imaps.ssl.socketFactory", identityFactory);
            properties.setProperty("mail.imaps.socketFactory.fallback", "false");
        }
        properties.setProperty("mail.imaps.connectiontimeout", "8000");
        properties.setProperty("mail.imaps.timeout", "12000");
        properties.setProperty("mail.imaps.writetimeout", "12000");
        properties.setProperty("mail.imaps.peek", "true");
        properties.setProperty("mail.imaps.partialfetch", "false");
        String stage = "starting IMAPS";
        try (Store store = Session.getInstance(properties).getStore("imaps")) {
            stage = "connecting and signing in";
            store.connect(settings.host, settings.port, settings.user, settings.password);
            stage = "opening the inbox read-only";
            IMAPFolder folder = (IMAPFolder) store.getFolder("INBOX");
            folder.open(Folder.READ_ONLY);
            try {
                stage = "reading the inbox";
                return query.run(folder);
            } finally {
                folder.close(false);
            }
        } catch (Exception failure) {
            throw new Failure(stage, failure);
        }
    }

    static final class Failure extends Exception {
        private final String stage;

        Failure(String stage, Exception cause) {
            super(cause);
            this.stage = stage;
        }

        boolean isHostnameMismatch() {
            if (!"connecting and signing in".equals(stage)) {
                return false;
            }
            Throwable cause = getCause();
            for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
                if (cause instanceof java.io.IOException && cause.getMessage() != null
                        && cause.getMessage().startsWith("Can't verify identity of server:")) {
                    return true;
                }
            }
            return false;
        }

        String summary() {
            Throwable cause = getCause();
            for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
                if (cause instanceof ServerKeyPin.UnexpectedServerName) {
                    return "TLS error. The server certificate did not name a recognized Fizz gateway. No login was sent.";
                }
                if (cause instanceof ServerKeyPin.KeyChanged) {
                    return "Server key changed. The lab stopped before sending a mailbox login.";
                }
            }
            cause = getCause();
            for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
                if (cause instanceof EmptyMessage) {
                    return "Fizz returned an empty response for the full voicemail.";
                }
                if (cause instanceof NoAudio) {
                    return "The full voicemail arrived, but it contained no non-empty audio attachment.";
                }
                if (cause instanceof AuthenticationFailedException) {
                    return "IMAPS login rejected.";
                }
                if (cause instanceof UnknownHostException) {
                    return "Network error. The mailbox hostname could not be resolved.";
                }
                String detail = cause.getMessage();
                if (detail != null && detail.startsWith("Can't verify identity of server:")) {
                    return "TLS error. The certificate does not match the mailbox hostname.";
                }
                if (cause instanceof SSLException) {
                    return "TLS error. The certificate or secure handshake failed.";
                }
                if (cause instanceof SocketTimeoutException) {
                    return "Network timeout while " + stage + ".";
                }
                if (cause instanceof NoRouteToHostException || cause instanceof ConnectException) {
                    return "Network error. The mailbox server could not be reached.";
                }
            }
            return "IMAPS error while " + stage + " (" + getCause().getClass().getSimpleName() + ").";
        }
    }

    static String inspectCertificate(String host, int port) {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try (Socket connection = new Socket()) {
            connection.connect(new InetSocketAddress(host, port), 8000);
            try (SSLSocket tls = (SSLSocket) factory.createSocket(connection, host, port, true)) {
                tls.setSoTimeout(8000);
                tls.startHandshake();
                X509Certificate certificate = (X509Certificate) tls.getSession().getPeerCertificates()[0];
                Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
                int dns = 0;
                int ip = 0;
                if (alternatives != null) {
                    for (List<?> alternative : alternatives) {
                        if (Integer.valueOf(2).equals(alternative.get(0))) dns++;
                        if (Integer.valueOf(7).equals(alternative.get(0))) ip++;
                    }
                }
                boolean ipEndpoint = host.matches("[0-9]+(?:\\.[0-9]+){3}");
                boolean matches = HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.getSession());
                return (ipEndpoint ? "Provisioned address is IPv4. " : "Provisioned address is a DNS name. ")
                        + "Certificate has " + dns + " DNS names and " + ip + " IP addresses. "
                        + (matches ? "Android's verifier matched." : "Android's verifier did not match.");
            }
        } catch (Exception failure) {
            return "Certificate inspection failed (" + failure.getClass().getSimpleName() + ").";
        }
    }

    // AllowedIMAPServers from the Fizz iOS carrier bundle, version 72.0.2.
    private static final String[] FIZZ_ALIASES = {
        "vl-vi-vvg001.ip.videotron.ca",
        "vl-vi-vvg101.ip.videotron.ca",
        "vl-mo-vmvvm01.ip.videotron.ca"
    };

    static String checkFizzAliases(String host, int port) throws UnknownHostException {
        return checkAliases(host, port, FIZZ_ALIASES);
    }

    static String checkAliases(String host, int port, String... aliases) throws UnknownHostException {
        List<InetAddress> provisionedAddresses = Arrays.asList(InetAddress.getAllByName(host));
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        for (String alias : aliases) {
            try {
                for (InetAddress address : InetAddress.getAllByName(alias)) {
                    if (!provisionedAddresses.contains(address)) {
                        continue;
                    }
                    try {
                        verifyAlias(factory, SocketFactory.getDefault(), address, port, alias);
                        return alias;
                    } catch (java.io.IOException unavailable) {
                        // Another shared address may present the right certificate.
                    }
                }
            } catch (UnknownHostException unavailable) {
                // This carrier alias may not be served on this network.
            }
        }
        return null;
    }

    static String inspectFizzIdentities(String host, int port) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(host);
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        StringBuilder report = new StringBuilder("Certificate DNS names for the provisioned endpoint:");
        try (Socket connection = new Socket()) {
            connection.connect(new InetSocketAddress(address, port), 4000);
            try (SSLSocket tls = (SSLSocket) factory.createSocket(connection, host, port, true)) {
                tls.setSoTimeout(4000);
                tls.startHandshake();
                X509Certificate certificate = (X509Certificate) tls.getSession().getPeerCertificates()[0];
                Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
                if (alternatives != null) {
                    for (List<?> alternative : alternatives) {
                        if (Integer.valueOf(2).equals(alternative.get(0))) {
                            report.append("\n").append(alternative.get(1));
                        }
                    }
                }
            }
        } catch (Exception failure) {
            report.append("\nInspection failed (").append(failure.getClass().getSimpleName()).append(").");
        }
        report.append("\n\nCarrier names tested against that same address:");
        for (String alias : FIZZ_ALIASES) {
            report.append("\n").append(alias).append(" — ");
            try {
                verifyAlias(factory, SocketFactory.getDefault(), address, port, alias);
                report.append("TLS matched");
            } catch (java.io.IOException failure) {
                report.append("failed (").append(failure.getClass().getSimpleName()).append(")");
            }
        }
        return report.toString();
    }

    static boolean verifyCarrierDnsName(SocketFactory networkSockets, InetAddress address, int port, String alias) {
        try {
            verifyAlias((SSLSocketFactory) SSLSocketFactory.getDefault(), networkSockets, address, port, alias);
            return true;
        } catch (java.io.IOException failure) {
            return false;
        }
    }

    private static void verifyAlias(SSLSocketFactory factory, SocketFactory sockets,
                                    InetAddress address, int port, String alias) throws java.io.IOException {
        try (Socket connection = sockets.createSocket()) {
            connection.connect(new InetSocketAddress(address, port), 3000);
            try (SSLSocket tls = (SSLSocket) factory.createSocket(connection, alias, port, true)) {
                tls.setSoTimeout(3000);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
            }
        }
    }

    static List<Entry> list(Settings settings) throws Exception {
        return read(settings, folder -> {
            List<Entry> entries = new ArrayList<>();
            int count = folder.getMessageCount();
            if (count == 0) {
                return entries;
            }
            Message[] messages = folder.getMessages(Math.max(1, count - 29), count);
            FetchProfile profile = new FetchProfile();
            profile.add(FetchProfile.Item.ENVELOPE);
            profile.add(FetchProfile.Item.FLAGS);
            profile.add(UIDFolder.FetchProfileItem.UID);
            folder.fetch(messages, profile);
            long validity = folder.getUIDValidity();
            for (int index = messages.length - 1; index >= 0; index--) {
                Message message = messages[index];
                if (message.isSet(Flags.Flag.DELETED)) {
                    continue;
                }
                Address[] senders = message.getFrom();
                String from = "Unknown caller";
                if (senders != null && senders.length > 0) {
                    if (senders[0] instanceof javax.mail.internet.InternetAddress) {
                        javax.mail.internet.InternetAddress address = (javax.mail.internet.InternetAddress) senders[0];
                        from = address.getPersonal() == null ? address.getAddress() : address.getPersonal();
                    } else {
                        from = senders[0].toString();
                    }
                }
                long uid = folder.getUID(message);
                if (validity > 0 && uid > 0) {
                    entries.add(new Entry(validity, uid, from, message.getSentDate()));
                }
            }
            return entries;
        });
    }

    static AudioClip audio(Settings settings, Entry entry) throws Exception {
        return read(settings, folder -> {
            if (folder.getUIDValidity() != entry.validity) {
                throw new MessagingException("Mailbox changed. Refresh first.");
            }
            IMAPMessage message = (IMAPMessage) folder.getMessageByUID(entry.uid);
            if (message == null) {
                throw new MessagingException("Voicemail is unavailable. Refresh first.");
            }
            message.setPeek(true);
            byte[] raw;
            try (InputStream stream = message.getMimeStream()) {
                raw = readLimited(stream, 18 * 1024 * 1024);
            }
            if (raw.length == 0) {
                throw new EmptyMessage();
            }
            MimeMessage local = new MimeMessage((Session) null, new ByteArrayInputStream(raw));
            AudioClip audio = findAudio(local, 0);
            if (audio == null) {
                throw new NoAudio();
            }
            return audio;
        });
    }

    private static final class NoAudio extends MessagingException {}

    private static final class EmptyMessage extends MessagingException {}

    private static byte[] readLimited(InputStream stream, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = stream.read(buffer)) != -1) {
            if (output.size() + length > maxBytes) {
                throw new MessagingException("Voicemail exceeds the playback limit.");
            }
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    private static AudioClip findAudio(Part part, int depth) throws Exception {
        if (AudioClip.isCandidate(part)) {
            try (InputStream stream = part.getInputStream()) {
                byte[] bytes = readLimited(stream, 12 * 1024 * 1024);
                if (bytes.length != 0) {
                    return new AudioClip(bytes, part.getContentType());
                }
            }
        }
        if (depth < 8 && part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int index = 0; index < multipart.getCount(); index++) {
                AudioClip audio = findAudio(multipart.getBodyPart(index), depth + 1);
                if (audio != null) {
                    return audio;
                }
            }
        }
        return null;
    }
}
