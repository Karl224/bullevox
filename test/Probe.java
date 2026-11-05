package ca.local.vvm;

import java.util.Arrays;
import java.util.List;

final class Probe {
    public static void main(String[] args) throws Exception {
        checkStatusParser();
        checkDnsParser();
        AudioClip amr = new AudioClip("#!AMR\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                "audio/amr; name=private.amr");
        if (!"AMR-NB, audio/amr, 6 bytes".equals(amr.description)) {
            throw new AssertionError("Audio diagnostic did not identify AMR or hide its filename");
        }
        javax.mail.internet.MimeBodyPart attachment = new javax.mail.internet.MimeBodyPart();
        attachment.setFileName("voice.amr");
        if (!AudioClip.isCandidate(attachment)) {
            throw new AssertionError("Generic MIME audio attachment was skipped");
        }
        attachment.setHeader("X-SwVvmg-Part-Type", "text");
        if (AudioClip.isCandidate(attachment)) {
            throw new AssertionError("Transcription was treated as audio");
        }
        int port = Integer.parseInt(args[0]);
        Mailbox.Settings settings = new Mailbox.Settings("localhost", port, "mock-user", "mock-password");
        List<Mailbox.Entry> entries = Mailbox.list(settings);
        if (entries.size() != 1 || entries.get(0).uid != 42 || entries.get(0).validity != 99) {
            throw new AssertionError("Mock entry missing");
        }
        byte[] audio = Mailbox.audio(settings, entries.get(0)).bytes;
        if (!Arrays.equals(audio, new byte[]{'R', 'I', 'F', 'F'})) {
            throw new AssertionError("Mock audio did not decode");
        }
        try {
            Mailbox.list(new Mailbox.Settings("127.0.0.1", port, "mock-user", "mock-password"));
            throw new AssertionError("Accepted a certificate for the wrong hostname");
        } catch (Mailbox.Failure expected) {
            if (!expected.summary().equals("TLS error. The certificate does not match the mailbox hostname.")) {
                throw expected;
            }
        }
        String certificate = Mailbox.inspectCertificate("localhost", port);
        if (!certificate.contains("1 DNS names and 0 IP addresses")) {
            throw new AssertionError("Unexpected mock certificate result: " + certificate);
        }
        if (Mailbox.checkAliases("localhost", port, "127.0.0.1") != null
                || !"localhost".equals(Mailbox.checkAliases("127.0.0.1", port, "localhost"))) {
            throw new AssertionError("Alias trust check failed");
        }
        if (!Mailbox.inspectFizzIdentities("127.0.0.1", port).contains("endpoint:\nlocalhost")) {
            throw new AssertionError("Endpoint identities did not include the fixture name");
        }
        String key = ServerKeyPin.probe(javax.net.SocketFactory.getDefault(),
                java.net.InetAddress.getByName("127.0.0.1"), "127.0.0.1", port);
        if (Mailbox.list(new Mailbox.Settings("127.0.0.1", port, "mock-user", "mock-password", key)).size() != 1) {
            throw new AssertionError("Approved key did not open the mock inbox");
        }
        try {
            Mailbox.list(new Mailbox.Settings("127.0.0.1", port, "mock-user", "mock-password", "different-key"));
            throw new AssertionError("Accepted the wrong key");
        } catch (Mailbox.Failure expected) {
            if (!expected.summary().startsWith("Server key changed.")) {
                throw expected;
            }
        }
        if (Mailbox.list(new Mailbox.Settings("127.0.0.1", port, "mock-user", "mock-password",
                null, new String[]{"localhost"})).size() != 1) {
            throw new AssertionError("Expected gateway name did not open the inbox");
        }
        try {
            Mailbox.list(new Mailbox.Settings("127.0.0.1", port, "mock-user", "mock-password",
                    null, new String[]{"different.invalid"}));
            throw new AssertionError("Accepted an unrelated trusted certificate");
        } catch (Mailbox.Failure expected) {
            if (!expected.summary().contains("did not name a recognized Fizz gateway")) {
                throw expected;
            }
        }
        System.out.println("Gateway TLS required the exact configured certificate name.");
        System.out.println("Key pin accepted the approved key and blocked the wrong key.");
        System.out.println("Mock mailbox, audio decoding, and TLS hostname rejection passed.");
    }
    private static void checkDnsParser() throws Exception {
        String fixture = "000181800001000100000000036c616207696e76616c69640000010001c00c000500010000003c001e0c766c2d76692d76766730313102697009766964656f74726f6e02636100";
        byte[] answer = new byte[fixture.length() / 2];
        for (int index = 0; index < answer.length; index++) {
            answer[index] = (byte) Integer.parseInt(fixture.substring(index * 2, index * 2 + 2), 16);
        }
        if (!"vl-vi-vvg011.ip.videotron.ca".equals(DnsAliases.match(answer, "lab.invalid"))) {
            throw new AssertionError("CNAME answer did not match");
        }
        answer[4] = 0;
        answer[5] = 0;
        try {
            DnsAliases.match(answer, "lab.invalid");
            throw new AssertionError("Invalid DNS question count accepted");
        } catch (java.io.IOException expected) {
            System.out.println("Synthetic DNS alias checks passed.");
        }
    }

    private static void checkStatusParser() {
        String sample = "//VVM:STATUS:st=R;rc=0;srv=mail.example.invalid;ipt=993;u=test;pw=abc==";
        Mailbox.Settings parsed = FizzStatus.parse(sample);
        if (parsed == null || parsed.port != 993 || !parsed.host.equals("mail.example.invalid")
                || !parsed.user.equals("test") || !parsed.password.equals("abc==")) {
            throw new AssertionError("Valid STATUS failed");
        }
        for (String invalid : new String[]{
                sample.replace("STATUS", "SYNC"), sample.replace("st=R", "st=B"),
                sample.replace("993", "65536"), sample.replace("mail.example.invalid", "https://invalid"),
                sample + ";pw=duplicate", sample.replace(";pw=abc==", "")
        }) {
            if (FizzStatus.parse(invalid) != null) {
                throw new AssertionError("Invalid STATUS accepted");
            }
        }
        System.out.println("Synthetic STATUS parser checks passed.");
    }
}
