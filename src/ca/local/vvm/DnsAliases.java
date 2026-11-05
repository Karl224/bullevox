package ca.local.vvm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class DnsAliases {
    private static final Set<String> CERTIFICATE_NAMES = new HashSet<>(java.util.Arrays.asList(
            "vl-vi-vvg011.ip.videotron.ca", "vl-vi-vvg012.ip.videotron.ca",
            "vl-vi-vvg111.ip.videotron.ca", "vl-vi-vvg112.ip.videotron.ca"));

    static String[] observedCertificateNames() {
        return CERTIFICATE_NAMES.toArray(new String[0]);
    }

    static String match(byte[] reply, String host) throws IOException {
        try {
            ByteBuffer packet = ByteBuffer.wrap(reply);
            packet.getShort();
            int flags = unsignedShort(packet);
            if ((flags & 0x820f) != 0x8000 || unsignedShort(packet) != 1) {
                throw new IOException("Incomplete or invalid DNS response");
            }
            int answers = unsignedShort(packet);
            packet.getInt(); // Authority and additional counts.
            if (!readName(packet).equalsIgnoreCase(host) || unsignedShort(packet) != 1
                    || unsignedShort(packet) != 1) {
                throw new IOException("Unexpected DNS question");
            }
            Map<String, String> aliases = new HashMap<>();
            for (int index = 0; index < answers; index++) {
                String owner = readName(packet).toLowerCase(Locale.ROOT);
                int type = unsignedShort(packet);
                int recordClass = unsignedShort(packet);
                packet.getInt(); // TTL.
                int end = unsignedShort(packet) + packet.position();
                if (type == 5 && recordClass == 1) {
                    String target = readName(packet).toLowerCase(Locale.ROOT);
                    if (packet.position() != end || aliases.put(owner, target) != null) {
                        throw new IOException("Invalid CNAME answer");
                    }
                }
                packet.position(end);
            }
            Set<String> visited = new HashSet<>();
            String name = aliases.get(host.toLowerCase(Locale.ROOT));
            while (name != null && visited.add(name)) {
                if (CERTIFICATE_NAMES.contains(name)) {
                    return name;
                }
                name = aliases.get(name);
            }
            return null;
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed DNS response", malformed);
        }
    }

    private static int unsignedShort(ByteBuffer packet) {
        return Short.toUnsignedInt(packet.getShort());
    }

    private static String readName(ByteBuffer packet) throws IOException {
        ByteBuffer labels = packet;
        StringBuilder name = new StringBuilder();
        for (int hop = 0; hop < 128; hop++) {
            int size = Byte.toUnsignedInt(labels.get());
            if (size == 0) {
                return name.toString();
            }
            if ((size & 0xc0) == 0xc0) {
                int pointer = ((size & 0x3f) << 8) | Byte.toUnsignedInt(labels.get());
                if (pointer >= labels.position() - 2) {
                    throw new IOException("Invalid DNS compression pointer");
                }
                labels = packet.duplicate();
                labels.position(pointer);
                continue;
            }
            if (size > 63 || name.length() + size > 253) {
                throw new IOException("Invalid DNS label");
            }
            byte[] label = new byte[size];
            labels.get(label);
            if (name.length() > 0) {
                name.append('.');
            }
            name.append(new String(label, StandardCharsets.US_ASCII));
        }
        throw new IOException("DNS name exceeded parser limit");
    }
}
