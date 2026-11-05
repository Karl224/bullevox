"""Run the compiled Java probe against an isolated IMAPS fixture."""
import socketserver
import ssl
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

ENVELOPE = (
    '("Wed, 04 Nov 2026 12:00:00 +0000" "Fixture" '
    '(("Test Caller" NIL "caller" "example.invalid")) NIL NIL NIL NIL NIL NIL "<fixture>")'
)
MIME = "\r\n".join([
    "MIME-Version: 1.0", 'Content-Type: multipart/mixed; boundary="lab"', "",
    "--lab", "Content-Type: audio/amr", "",
    "--lab", "Content-Type: audio/amr", "X-SwVvmg-Part-Type: text", "", "Ignore this part.",
    "--lab", "Content-Type: application/octet-stream", 'Content-Disposition: attachment; filename="test.amr"',
    "Content-Transfer-Encoding: base64", "", "UklGRg==",
    "--lab--", "",
]).encode()
commands = []
unexpected = []


class Session(socketserver.StreamRequestHandler):
    def handle(self):
        self.wfile.write(b"* OK Mock IMAP\r\n")
        for raw in self.rfile:
            tag, command = raw.decode("ascii").strip().split(" ", 1)
            command = command.upper()
            action = command.split()[0]
            commands.append(command if action != "LOGIN" else action)
            if action == "CAPABILITY":
                self.reply(tag, "* CAPABILITY IMAP4rev1")
            elif command == "LOGIN MOCK-USER MOCK-PASSWORD":
                self.reply(tag)
            elif command == "EXAMINE INBOX":
                self.reply(tag, "* FLAGS (\\Seen)\r\n* 1 EXISTS\r\n* 0 RECENT\r\n* OK [UIDVALIDITY 99] valid", "[READ-ONLY]")
            elif command.startswith("UID FETCH 42"):
                self.reply(tag, "* 1 FETCH (UID 42)")
            elif command.startswith("FETCH") and "ENVELOPE" in command:
                self.reply(tag, f"* 1 FETCH (UID 42 FLAGS () ENVELOPE {ENVELOPE} INTERNALDATE \"04-Nov-2026 12:00:00 +0000\" RFC822.SIZE {len(MIME)})")
            elif command == "FETCH 1 (BODY.PEEK[])":
                self.literal(tag, MIME)
            elif action in {"NOOP", "UNSELECT", "CLOSE"}:
                self.reply(tag)
            elif action == "LOGOUT":
                self.reply(tag, "* BYE")
                return
            else:
                unexpected.append(command if action != "LOGIN" else action)
                self.wfile.write(f"{tag} BAD Unsupported by fixture\r\n".encode())

    def literal(self, tag, data):
        self.wfile.write(f"* 1 FETCH (BODY[] {{{len(data)}}}\r\n".encode())
        self.wfile.write(data + b")\r\n")
        self.reply(tag)

    def reply(self, tag, extra="", code=""):
        if extra:
            self.wfile.write((extra + "\r\n").encode())
        self.wfile.write(f"{tag} OK {code} done\r\n".encode())


def main(classpath):
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        cert = root / "cert.pem"
        key = root / "key.pem"
        trust = root / "trust.p12"
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
             "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost",
             "-keyout", key, "-out", cert], check=True, capture_output=True,
        )
        subprocess.run(
            ["keytool", "-importcert", "-noprompt", "-alias", "mock", "-file", cert,
             "-keystore", trust, "-storepass", "changeit"], check=True, capture_output=True,
        )
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        with socketserver.ThreadingTCPServer(("127.0.0.1", 0), Session) as server:
            server.socket = context.wrap_socket(server.socket, server_side=True)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                subprocess.run(
                    ["java", f"-Djavax.net.ssl.trustStore={trust}", "-Djavax.net.ssl.trustStorePassword=changeit",
                     "-cp", classpath, "ca.local.vvm.Probe", str(server.server_address[1])],
                    check=True, timeout=30,
                )
            finally:
                server.shutdown()
                print("Fixture transcript:", ", ".join(commands))
        assert not unexpected, unexpected
        assert commands.count("EXAMINE INBOX") == 4
        assert commands.count("LOGIN") == 4
        assert any("BODY.PEEK[]" in command for command in commands)


if __name__ == "__main__":
    main(sys.argv[1])
