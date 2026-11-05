package ca.local.vvm;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import javax.mail.MessagingException;
import javax.mail.Part;

final class AudioClip {
    final byte[] bytes;
    final String description;

    AudioClip(byte[] bytes, String mimeType) {
        this.bytes = bytes;
        description = format(bytes) + ", " + safeMime(mimeType) + ", " + bytes.length + " bytes";
    }

    static boolean isCandidate(Part part) throws MessagingException {
        String[] vendorType = part.getHeader("X-SwVvmg-Part-Type");
        if (vendorType != null && vendorType.length > 0 && "text".equalsIgnoreCase(vendorType[0].trim())) {
            return false;
        }
        if (part.isMimeType("audio/*")) {
            return true;
        }
        String name = part.getFileName();
        return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(amr|awb|wav|mp3|m4a|3gp|ogg|aac|au)");
    }

    private static String safeMime(String value) {
        String type = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return type.matches("[a-z0-9.+-]{1,24}/[a-z0-9.+-]{1,40}") ? type : "unknown MIME";
    }

    private static String format(byte[] bytes) {
        if (matches(bytes, 0, "#!AMR\n")) return "AMR-NB";
        if (matches(bytes, 0, "#!AMR-WB\n")) return "AMR-WB";
        if (matches(bytes, 0, "RIFF") && matches(bytes, 8, "WAVE")) {
            for (int offset = 12; offset <= bytes.length - 8;) {
                long size = unsignedLittleEndianInt(bytes, offset + 4);
                if (size > bytes.length - offset - 8) break;
                if (matches(bytes, offset, "fmt ") && size >= 2) {
                    int codec = (bytes[offset + 8] & 255) | ((bytes[offset + 9] & 255) << 8);
                    return "WAV codec " + codec;
                }
                long next = offset + 8L + size + (size & 1);
                if (next > bytes.length) break;
                offset = (int) next;
            }
            return "WAV";
        }
        if (matches(bytes, 4, "ftyp")) return "MP4/3GP";
        if (matches(bytes, 0, "OggS")) return "Ogg";
        if (matches(bytes, 0, "ID3")) return "MP3";
        if (matches(bytes, 0, ".snd")) return "AU";
        return "unidentified audio";
    }

    private static long unsignedLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
                | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
    }

    private static boolean matches(byte[] bytes, int offset, String value) {
        byte[] signature = value.getBytes(StandardCharsets.US_ASCII);
        if (offset + signature.length > bytes.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if (bytes[offset + index] != signature[index]) return false;
        }
        return true;
    }
}
