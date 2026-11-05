# BulleVox

An independent, read-only Android visual voicemail client compatible with Fizz.

Private preview of an independent read-only Android voicemail client.
The preview opens to the inbox with a separate Settings page and troubleshooting.
After you confirm **Connect to Fizz**, the app sends one status request and opens
the inbox automatically if the connection passes verification. Tap a message
to play it, pause, or seek. The app clears its mailbox session on leaving the
foreground. It retains only an explicitly approved server key and endpoint.

This is a development build. Fresh-install provisioning without ADB and
independent Fizz server identity still need to be resolved before a public
consumer release. This project is independent of Fizz and STREAMWIDE.

## Technical notes

An Android 12 or later prototype with manual IMAPS refresh and in-memory audio playback. The offline demo plays a generated tone. It works without mailbox settings or connectivity.

The mailbox code opens INBOX read-only, identifies messages by UID and UIDVALIDITY, and uses IMAP PEEK to retrieve audio. The app uses the default network route and requires a trusted certificate that matches the entered hostname. It requests Internet access and asks for send/receive SMS permissions only when you choose Get Fizz settings. The demo and mailbox list are separate actions. Credentials are session-only and the app clears the input fields and message list when it leaves the foreground. An in-flight connection can finish after clearing the screen, subject to its socket timeouts.

## Build and run the mock test

Use a JDK 17, Python 3, OpenSSL, Android SDK platform 35 and build tools 35.0.0. Set these environment variables to absolute paths before running `./build.sh`.

- `ANDROID_SDK_ROOT` points at the Android SDK.
- `MAIL_JAR` points at Maven Central's `com.sun.mail:android-mail:1.6.7` JAR.
- `ACTIVATION_JAR` points at Maven Central's `com.sun.mail:android-activation:1.6.7` JAR.

The script signs a test APK in `build/` with a local test key. The mock test uses the same mailbox implementation on a desktop JVM. It opens a TLS listener on loopback, serves synthetic data, and checks read-only open, audio retrieval with PEEK, and rejection of a trusted certificate for the wrong hostname. The test trust store stays in a temporary directory and is never packaged in the APK.

Phone playback, Fizz connectivity and the carrier-issued settings flow still need separate validation.

## Fizz STATUS trial

Set Fizz as the default SIM for SMS and mobile data, and turn off Wi-Fi for the test.
Open the app, tap **Connect to Fizz**, grant the two SMS permissions if asked,
and tap the button again to confirm one request. Keep the app in the foreground.
The lab listens for a STATUS reply for 60 seconds and accepts both port-addressed
binary and text replies. Android supplies the SMS broadcast. A carrier sender
identity and reply delivery on the target phone remain to be verified. Use
**Refresh inbox** to reload your messages. The request flow has no
activation, deactivation, or automatic retry. The lab discards the received
settings when you clear the session or leave the screen.

**Check Fizz certificate** makes a separate TLS connection without sending any
IMAP commands or mailbox credentials. It checks trust and reports only name
counts and Android's hostname-verifier result. Inbox refresh still requires
JavaMail's TLS identity check.

**Check Fizz carrier aliases** checks the three AllowedIMAPServers in the
decoded Fizz iOS carrier bundle 72.0.2. It requires a shared resolved address
with the provisioned host and a trusted certificate valid for the alias. The
probe sends no mailbox credentials. Choosing a verified alias only updates
the current session. A separate inbox refresh performs its own TLS check.
Source: https://carrierexplode.com/ios/carriers/Videotron_Fizz_ca/72.0.2/settings

**Inspect endpoint identities** displays certificate DNS names locally and
tries the carrier-bundle names against one address of the provisioned host,
using the candidate name for TLS. It sends no mailbox credentials and changes
no connection settings.

**Check cellular DNS link** queries the provisioned host for an A record over
Android's visible cellular internet route and follows CNAME records included in that
answer. For one of the four known certificate names, it tests strict TLS
against up to three addresses of the provisioned host on the same network.
It does not change the login hostname, send a login, or override Android
Private DNS. A DNS link alone does not independently authorize a TLS name.

If Android has a different default route, including a VPN, the check asks
before it uses a separate cellular network. This applies only to the probe.

**Review and pin Fizz server key** is an optional first-use trust mode.
Review runs a credential-free probe on the active cellular network and still
requires a platform-trusted certificate chain. Approval saves the leaf
public-key SHA-256 fingerprint scoped to one hostname and port. The next
refresh requires platform trust and the approved key on its actual IMAPS
connection before login. Unpinned endpoints use hostname verification.
**Forget approved server key** removes the pin. Mailbox credentials stay
session-only.

Fetched voicemail playback uses a seekable file descriptor. The app creates
an empty file in its private cache, unlinks it before writing audio, and
closes its descriptors after handing off to the player. Playback errors show
only the recognized format, MIME type, byte count, and Android error code.

Version 0.11 retrieves the complete MIME message using BODY.PEEK[] and parses
the attachments locally, skipping empty audio and STREAMWIDE transcription
parts. Playback remains read-only.

## Fresh one-button setup test

Run `STRICT_SETUP_TEST=1 ./build.sh` to build **BulleVox for Fizz**.
It installs alongside BulleVox Lab under a separate application ID, so it
starts without the existing app's SMS grants or approved key. Install the APK
through the phone's normal installer for this test. Tap **Connect to Fizz**
once and approve Android's SMS permission prompt if offered. Setup resumes
automatically when the app returns to the foreground.

The test variant cannot use a saved server pin. It first verifies the
provisioned TLS name. On a hostname mismatch, it retries using a fixed list
of four exact DNS identities observed on the working Fizz connection. The
retry requires platform certificate trust and an exact leaf SAN match before
login. This experimental mapping is not independently confirmed by Fizz and
is enabled only in the setup test. SMS permission, SMS delivery, and TLS are distinct
checkpoints. The app sends one STATUS request per connect attempt and does not
activate or deactivate voicemail.

## Inbox connection state

The primary inbox button changes from **Connect to Fizz** to **Refresh inbox**
after the app successfully loads the mailbox. Listing and playback are read-only.

## License

BulleVox's original code and assets are available under the [MIT License](LICENSE).
See [Third-party notices](THIRD_PARTY_NOTICES.md) for separately licensed
dependencies and their corresponding source archives.
