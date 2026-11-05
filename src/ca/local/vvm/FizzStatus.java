package ca.local.vvm;

import java.util.HashMap;
import java.util.Map;

final class FizzStatus {
    static final String DESTINATION = "44744";
    static final int PORT = 5499;
    static final String REQUEST = "STATUS:pv=11;ct=OMTP;pt=5499;//VVM";
    private static final String PREFIX = "//VVM:STATUS:";

    static boolean matches(String message) {
        return message != null && message.startsWith(PREFIX);
    }

    static Mailbox.Settings parse(String message) {
        if (!matches(message) || message.length() > 4096) {
            return null;
        }
        Map<String, String> fields = new HashMap<>();
        for (String field : message.substring(PREFIX.length()).split(";")) {
            int equals = field.indexOf('=');
            if (equals <= 0 || fields.put(field.substring(0, equals), field.substring(equals + 1)) != null) {
                return null;
            }
        }
        String host = fields.get("srv");
        String user = fields.get("u");
        String password = fields.get("pw");
        String port = fields.get("ipt");
        String state = fields.get("st");
        if (host == null || !host.matches("[a-zA-Z0-9](?:[a-zA-Z0-9.-]{0,251}[a-zA-Z0-9])?")
                || !isCredential(user) || !isCredential(password)
                || port == null || !port.matches("[0-9]{1,5}")
                || "B".equals(state) || "U".equals(state)) {
            return null;
        }
        int number = Integer.parseInt(port);
        return number > 0 && number <= 65535 ? new Mailbox.Settings(host, number, user, password) : null;
    }

    private static boolean isCredential(String value) {
        return value != null && !value.isEmpty() && value.chars().noneMatch(Character::isISOControl);
    }
}
