package ca.local.vvm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private LinearLayout messages;
    private TextView status;
    private EditText host;
    private EditText port;
    private EditText user;
    private EditText password;
    private MediaPlayer player;
    private StatusRequest fizzStatus;
    private Mailbox.Settings provisioned;
    private Mailbox.Settings manualSession;
    private static final int SMS_PERMISSIONS = 14;
    private boolean requestingSmsPermissions;
    private boolean connectWhenFocused;
    private volatile int generation;

    private static final int BACKGROUND = 0xff101b1a;
    private static final int SURFACE = 0xff1d2c2a;
    private static final int INK = 0xffedf5ef;
    private static final int MUTED = 0xffb4c8c2;
    private static final int ACCENT = 0xffa5efd0;
    private static final int ACCENT_INK = 0xff10372c;
    private final Handler playbackClock = new Handler(Looper.getMainLooper());
    private final Runnable updateProgress = new Runnable() {
        @Override public void run() {
            if (!playerReady) return;
            int position = player.getCurrentPosition();
            if (!seeking) progress.setProgress(position);
            playbackTime.setText(time(position) + " / " + time(player.getDuration()));
            playbackClock.postDelayed(this, 250);
        }
    };
    private LinearLayout inbox;
    private LinearLayout setup;
    private LinearLayout troubleshooting;
    private LinearLayout playback;
    private ScrollView scroll;
    private TextView heading;
    private TextView inboxState;
    private TextView playbackTitle;
    private TextView playbackTime;
    private Button playbackToggle;
    private Button inboxAction;
    private boolean inboxConnected;
    private SeekBar progress;
    private boolean playerReady;
    private boolean seeking;
    private String audioTitle;
    private int currentPage;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root = column();
        root.setPadding(dp(22), dp(16), dp(22), dp(28));
        root.setFocusableInTouchMode(true);
        scroll.addView(root);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()
                            | android.view.WindowInsets.Type.ime());
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        scroll.requestApplyInsets();

        TextView eyebrow = text("BulleVox for Fizz", 14, ACCENT);
        eyebrow.setLetterSpacing(0.06f);
        root.addView(eyebrow);
        heading = text("Inbox", 36, INK);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        spaced(root, heading, 4);

        status = text("", 14, MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        spaced(root, status, 12);
        inbox = column();
        setup = column();
        troubleshooting = column();
        spaced(root, inbox, 16);
        spaced(root, setup, 16);
        spaced(root, troubleshooting, 16);

        LinearLayout actions = new LinearLayout(this);
        inbox.addView(actions);
        inboxAction = action(actions, "Connect to Fizz", this::refreshFromInbox, true);
        action(actions, "Settings", () -> showPage(1), false);
        inboxState = text(setupHint(), 16, MUTED);
        spaced(inbox, inboxState, 20);
        messages = column();
        spaced(inbox, messages, 8);
        createPlaybackPanel();

        spaced(setup, text(setupHint() + " The app clears mailbox credentials when you leave.", 16, MUTED), 0);
        action(setup, "Connect to Fizz", this::connectToFizz, true);
        button(setup, "Troubleshooting and manual settings", () -> showPage(2));
        button(setup, "Clear mailbox session", this::clearSession);
        button(setup, "Support on Ko-fi", this::openSupportPage);
        button(setup, "Back to inbox", () -> showPage(0));

        spaced(troubleshooting, text("Server identity", 20, INK), 0);
        if (!strictSetupTest()) {
            button(troubleshooting, "Review and pin Fizz server key", this::reviewServerKey);
            button(troubleshooting, "Forget approved server key", this::forgetServerKey);
        }
        button(troubleshooting, "Check Fizz certificate", this::inspectFizzCertificate);
        button(troubleshooting, "Check Fizz carrier aliases", this::checkFizzAliases);
        button(troubleshooting, "Inspect endpoint identities", this::inspectFizzIdentities);
        button(troubleshooting, "Check cellular DNS link", this::checkCarrierDns);
        spaced(troubleshooting, text("Manual IMAPS setup", 20, INK), 24);
        host = field("IMAPS host", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        port = field("IMAPS port", InputType.TYPE_CLASS_NUMBER);
        port.setText("993");
        user = field("Mailbox username", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        password = field("Mailbox password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button(troubleshooting, "Open manual inbox", this::refreshManual);
        button(troubleshooting, "Play offline test tone", () -> {
            generation++;
            audioTitle = "Offline test tone";
            showPage(0);
            play(demoAudio());
        });
        button(troubleshooting, "Back to settings", () -> showPage(1));

        fizzStatus = new StatusRequest(this, status::setText, settings -> {
            provisioned = settings;
            manualSession = null;
            refresh();
        });
        showPage(0);
    }

    private void openSupportPage() {
        Intent link = new Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/karlb79925"));
        try {
            startActivity(link);
        } catch (ActivityNotFoundException unavailable) {
            status.setText("No browser is available to open Ko-fi.");
        }
    }

    private void showPage(int page) {
        currentPage = page;
        inbox.setVisibility(page == 0 ? View.VISIBLE : View.GONE);
        setup.setVisibility(page == 1 ? View.VISIBLE : View.GONE);
        troubleshooting.setVisibility(page == 2 ? View.VISIBLE : View.GONE);
        heading.setText(page == 0 ? "Inbox" : page == 1 ? "Settings" : "Troubleshooting");
        root.requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    private void refreshFromInbox() {
        if (inboxConnected && provisioned != null) {
            refresh();
        } else if (inboxConnected && manualSession != null) {
            refresh(manualSession);
        } else {
            connectToFizz();
        }
    }

    @Override
    public void onBackPressed() {
        if (currentPage > 0) {
            showPage(currentPage - 1);
        } else {
            super.onBackPressed();
        }
    }

    private void createPlaybackPanel() {
        playback = column();
        playback.setBackground(surface(SURFACE, 18));
        playback.setPadding(dp(14), dp(12), dp(14), dp(12));
        spaced(inbox, playback, 16);
        playbackTitle = text("", 17, INK);
        playback.addView(playbackTitle);
        playbackTime = text("", 13, MUTED);
        playback.addView(playbackTime);
        progress = new SeekBar(this);
        progress.setContentDescription("Playback position");
        progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progress.setThumbTintList(ColorStateList.valueOf(ACCENT));
        playback.addView(progress);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { seeking = false; }
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && playerReady) player.seekTo(value);
            }
        });
        LinearLayout controls = new LinearLayout(this);
        playback.addView(controls);
        playbackToggle = action(controls, "Pause", this::togglePlayback, true);
        action(controls, "Stop", () -> {
            stopAudio();
            status.setText("Playback stopped.");
        }, false);
        playback.setVisibility(View.GONE);
    }

    private void togglePlayback() {
        if (!playerReady) return;
        if (player.isPlaying()) {
            player.pause();
            playbackToggle.setText("Resume");
        } else {
            player.start();
            playbackToggle.setText("Pause");
        }
    }

    private static String time(int millis) {
        int seconds = millis / 1000;
        return String.format(java.util.Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        return group;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private void spaced(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(view, params);
    }

    private Button action(LinearLayout group, String label, Runnable task, boolean primary) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(15);
        view.setTextColor(primary ? ACCENT_INK : INK);
        view.setMinHeight(dp(48));
        view.setPadding(dp(14), dp(9), dp(14), dp(9));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3392cbb8),
                surface(primary ? ACCENT : SURFACE, 14), null));
        view.setOnClickListener(clicked -> task.run());
        if (group.getOrientation() == LinearLayout.HORIZONTAL) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMarginEnd(dp(6));
            group.addView(view, params);
        } else {
            spaced(group, view, 9);
        }
        return view;
    }

    private void addMessage(Mailbox.Settings settings, Mailbox.Entry entry) {
        String caller = entry.caller == null || entry.caller.trim().isEmpty() ? "Unknown caller" : entry.caller;
        String when = entry.sentAt == null ? "Unknown date"
                : android.text.format.DateUtils.formatDateTime(this, entry.sentAt.getTime(),
                    android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_TIME
                            | android.text.format.DateUtils.FORMAT_SHOW_YEAR);
        Button row = action(messages, caller + "\n" + when, () -> {
            stopAudio();
            audioTitle = caller;
            status.setText("Fetching voicemail…");
            submit(() -> Mailbox.audio(settings, entry), this::playVoicemail);
        }, false);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        row.setMaxLines(3);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setContentDescription("Play voicemail from " + caller + ", " + when);
    }

    private void setInboxConnected(boolean connected) {
        inboxConnected = connected;
        inboxAction.setText(connected ? "Refresh inbox" : "Connect to Fizz");
    }

    private void showMessageCount(int count) {
        inboxState.setText(count == 0 ? "No voicemail messages" : count == 1 ? "1 voicemail" : count + " voicemails");
    }

    private EditText field(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setContentDescription(hint);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setBackgroundTintList(ColorStateList.valueOf(MUTED));
        troubleshooting.addView(input);
        return input;
    }

    private void button(LinearLayout group, String label, Runnable task) {
        action(group, label, task, false);
    }

    private boolean strictSetupTest() {
        return getPackageName().equals("ca.local.vvm.setup");
    }

    private static String setupHint() {
        return "Connect sends one service SMS. Use Fizz as the default SMS and mobile data SIM.";
    }

    private boolean hasSmsPermissions() {
        return checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void connectToFizz() {
        if (requestingSmsPermissions || connectWhenFocused) {
            return;
        }
        setInboxConnected(false);
        if (hasSmsPermissions()) {
            fizzStatus.start();
        } else {
            requestingSmsPermissions = true;
            status.setText("Allow SMS access to request your mailbox settings.");
            requestPermissions(new String[]{Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS},
                    SMS_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != SMS_PERMISSIONS || !requestingSmsPermissions) {
            return;
        }
        requestingSmsPermissions = false;
        if (hasSmsPermissions()) {
            connectWhenFocused = true;
            sendWhenFocused();
        } else {
            status.setText("SMS access was not granted. Check whether your installer allows SMS permissions.");
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            sendWhenFocused();
        }
    }

    private void sendWhenFocused() {
        if (connectWhenFocused && hasWindowFocus() && !isFinishing()) {
            connectWhenFocused = false;
            fizzStatus.start();
        }
    }

    private void inspectFizzCertificate() {
        if (provisioned == null) {
            status.setText("Tap Connect to Fizz in Settings before checking its certificate.");
            return;
        }
        String host = provisioned.host;
        int port = provisioned.port;
        status.setText("Checking the certificate without sending a mailbox login…");
        submit(() -> Mailbox.inspectCertificate(host, port), status::setText);
    }

    private void checkFizzAliases() {
        Mailbox.Settings settings = provisioned;
        if (settings == null) {
            status.setText("Tap Connect to Fizz in Settings before checking carrier aliases.");
            return;
        }
        status.setText("Checking Fizz carrier aliases without sending a login…");
        submit(() -> {
            try {
                return Mailbox.checkFizzAliases(settings.host, settings.port);
            } catch (java.net.UnknownHostException failure) {
                throw new Mailbox.Failure("resolving the provisioned Fizz host", failure);
            }
        }, alias -> {
            if (provisioned != settings) {
                return;
            }
            if (alias == null) {
                status.setText("No listed carrier alias shared an address and passed TLS.");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Verified carrier alias")
                    .setMessage(alias + " shares an address with the provisioned host and passed TLS. "
                            + "Use it for this session?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Use alias", (dialog, which) -> {
                        if (provisioned == settings) {
                            provisioned = new Mailbox.Settings(alias, settings.port, settings.user, settings.password);
                            status.setText("Verified alias selected. Tap Refresh inbox to connect.");
                        }
                    })
                    .show();
        });
    }

    private void inspectFizzIdentities() {
        Mailbox.Settings settings = provisioned;
        if (settings == null) {
            status.setText("Tap Connect to Fizz in Settings before inspecting endpoint identities.");
            return;
        }
        status.setText("Checking TLS names on the provisioned endpoint without a login…");
        submit(() -> {
            try {
                return Mailbox.inspectFizzIdentities(settings.host, settings.port);
            } catch (java.net.UnknownHostException failure) {
                throw new Mailbox.Failure("resolving the provisioned Fizz host", failure);
            }
        }, report -> {
            if (provisioned == settings) {
                status.setText(report);
            }
        });
    }

    private void checkCarrierDns() {
        Mailbox.Settings settings = provisioned;
        if (settings == null) {
            status.setText("Tap Connect to Fizz in Settings before checking cellular DNS.");
            return;
        }
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        Network defaultNetwork = connectivity.getActiveNetwork();
        NetworkCapabilities defaults = connectivity.getNetworkCapabilities(defaultNetwork);
        if (isCellularInternet(defaults)) {
            runCarrierDnsCheck(settings, defaultNetwork);
            return;
        }

        Network cellular = null;
        for (Network candidate : connectivity.getAllNetworks()) {
            if (isCellularInternet(connectivity.getNetworkCapabilities(candidate))) {
                if (cellular != null) {
                    status.setText("Android exposed multiple cellular data routes. Select Fizz as the only active mobile data SIM and retry.");
                    return;
                }
                cellular = candidate;
            }
        }
        boolean vpn = defaults != null && defaults.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        if (cellular == null) {
            status.setText(vpn
                    ? "Android reports a VPN as default and exposes no separate cellular route to the lab."
                    : "Android exposes no cellular internet route to the lab. Check the active data SIM.");
            return;
        }

        Network probeNetwork = cellular;
        new AlertDialog.Builder(this)
                .setTitle("Use cellular for this check?")
                .setMessage(vpn
                        ? "Android uses a VPN as its default route. Run this one DNS and TLS check directly on cellular, outside the VPN? No mailbox login is sent."
                        : "Android uses a different default route. Run this one DNS and TLS check directly on cellular? No mailbox login is sent.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run check", (dialog, which) -> runCarrierDnsCheck(settings, probeNetwork))
                .show();
    }

    private static boolean isCellularInternet(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private void runCarrierDnsCheck(Mailbox.Settings settings, Network network) {
        if (provisioned != settings) {
            return;
        }
        status.setText("Checking cellular DNS and TLS without sending a mailbox login…");
        submit(() -> CarrierDnsCheck.run(network, settings.host, settings.port), status::setText);
    }

    private SharedPreferences serverKeyPreferences() {
        return getSharedPreferences("server-key", MODE_PRIVATE);
    }

    private String approvedKey(Mailbox.Settings settings) {
        if (strictSetupTest()) {
            return null;
        }
        SharedPreferences preferences = serverKeyPreferences();
        if (settings.host.equalsIgnoreCase(preferences.getString("host", ""))
                && settings.port == preferences.getInt("port", -1)) {
            return preferences.getString("key", null);
        }
        return null;
    }

    private void reviewServerKey() {
        Mailbox.Settings settings = provisioned;
        if (settings == null) {
            status.setText("Tap Connect to Fizz in Settings before reviewing its server key.");
            return;
        }
        if (approvedKey(settings) != null) {
            status.setText("This endpoint already has an approved key. Forget it explicitly before reviewing a replacement.");
            return;
        }
        ConnectivityManager connectivity = getSystemService(ConnectivityManager.class);
        Network network = connectivity.getActiveNetwork();
        if (!isCellularInternet(connectivity.getNetworkCapabilities(network))) {
            status.setText("For first approval, use Fizz mobile data with Wi-Fi and VPN off.");
            return;
        }
        status.setText("Reading the server key without sending a mailbox login…");
        submit(() -> ServerKeyPin.probe(network.getSocketFactory(), network.getByName(settings.host),
                settings.host, settings.port), key -> {
            if (provisioned != settings) {
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Approve this server key?")
                    .setMessage("The certificate chain is trusted, but the hostname has not been verified. "
                            + "Approve only if you trust the first Fizz cellular connection. The key is saved "
                            + "for this endpoint. Approval does not send a login.\n\nSHA-256: " + key)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Approve key", (dialog, which) -> {
                        if (provisioned == settings) {
                            serverKeyPreferences().edit().putString("host", settings.host)
                                    .putInt("port", settings.port).putString("key", key).apply();
                            status.setText("Server key approved. Tap Refresh inbox to verify the key and sign in.");
                        }
                    })
                    .show();
        });
    }

    private void forgetServerKey() {
        generation++;
        stopAudio();
        messages.removeAllViews();
        serverKeyPreferences().edit().clear().apply();
        inboxState.setText("Refresh to reload your mailbox.");
        status.setText("Approved key removed. Inbox refresh will use strict hostname verification.");
    }

    private void refresh() {
        if (provisioned != null) {
            Mailbox.Settings settings = provisioned;
            String[] expected = strictSetupTest() ? DnsAliases.observedCertificateNames() : new String[0];
            refresh(new Mailbox.Settings(settings.host, settings.port, settings.user, settings.password,
                    approvedKey(settings), expected));
            return;
        }
        refreshManual();
    }

    private void refreshManual() {
        String hostname = host.getText().toString().trim();
        String username = user.getText().toString();
        String secret = password.getText().toString();
        int number;
        try {
            number = Integer.parseInt(port.getText().toString());
        } catch (NumberFormatException ignored) {
            number = 0;
        }
        if (hostname.isEmpty() || username.isEmpty() || secret.isEmpty() || number < 1 || number > 65535) {
            status.setText("Enter a host, port, username, and password.");
            return;
        }
        provisioned = null;
        manualSession = new Mailbox.Settings(hostname, number, username, secret);
        refresh(manualSession);
    }

    private void refresh(Mailbox.Settings settings) {
        showPage(0);
        setInboxConnected(false);
        stopAudio();
        messages.removeAllViews();
        inboxState.setText("Loading messages…");
        status.setText(settings.approvedKey == null ? "Opening inbox read-only…"
                : "Verifying the approved key and opening inbox read-only…");
        submit(() -> Mailbox.list(settings), entries -> {
            setInboxConnected(true);
            status.setText("Connected");
            showMessageCount(entries.size());
            for (Mailbox.Entry entry : entries) {
                addMessage(settings, entry);
            }
        }, true);
    }

    private <T> void submit(Callable<T> action, Consumer<T> show) {
        submit(action, show, false);
    }

    private <T> void submit(Callable<T> action, Consumer<T> show, boolean openingInbox) {
        int request = ++generation;
        inboxAction.setEnabled(!openingInbox);
        worker.execute(() -> {
            if (request != generation) {
                return;
            }
            try {
                T result = action.call();
                runOnUiThread(() -> {
                    if (request == generation && !isDestroyed()) {
                        inboxAction.setEnabled(true);
                        show.accept(result);
                    }
                });
            } catch (Exception | LinkageError failure) {
                String summary = failure instanceof Mailbox.Failure
                        ? ((Mailbox.Failure) failure).summary()
                        : "App error (" + failure.getClass().getSimpleName() + ").";
                runOnUiThread(() -> {
                    if (request == generation && !isDestroyed()) {
                        inboxAction.setEnabled(true);
                        status.setText(summary);
                        if (openingInbox) {
                            setInboxConnected(false);
                            inboxState.setText("Could not open the inbox.");
                        }
                    }
                });
            }
        });
    }

    private void playVoicemail(AudioClip clip) {
        play(clip.bytes, clip.description);
    }

    private void play(byte[] bytes) {
        play(bytes, null);
    }

    private void play(byte[] bytes, String description) {
        stopAudio();
        MediaPlayer media = new MediaPlayer();
        player = media;
        try {
            if (description != null) {
                setVoicemailSource(media, bytes);
            } else {
                media.setDataSource(new MediaDataSource() {
                    @Override
                    public int readAt(long position, byte[] destination, int offset, int size) {
                        if (size == 0) return 0;
                        if (position >= bytes.length) return -1;
                        int length = Math.min(size, bytes.length - (int) position);
                        System.arraycopy(bytes, (int) position, destination, offset, length);
                        return length;
                    }

                    @Override
                    public long getSize() { return bytes.length; }

                    @Override
                    public void close() {}
                });
            }
            media.setOnPreparedListener(ready -> {
                if (player == ready) {
                    ready.start();
                    playerReady = true;
                    playbackTitle.setText(audioTitle);
                    playbackToggle.setText("Pause");
                    progress.setMax(ready.getDuration());
                    progress.setProgress(0);
                    playback.setVisibility(View.VISIBLE);
                    playbackClock.post(updateProgress);
                    scroll.post(() -> scroll.smoothScrollTo(0, playback.getBottom()));
                    status.setText(description == null ? "Playing test tone" : "Playing voicemail");
                }
            });
            media.setOnCompletionListener(done -> {
                if (player != done) return;
                stopAudio();
                status.setText("Playback finished");
            });
            media.setOnErrorListener((failed, what, extra) -> {
                if (player != failed) return true;
                stopAudio();
                status.setText(description == null ? "Android could not play the demo."
                        : "Android rejected " + description + " (player error " + what + "/" + extra + ").");
                return true;
            });
            media.prepareAsync();
        } catch (Exception ignored) {
            stopAudio();
            status.setText(description == null ? "Android could not open the demo."
                    : "Android could not open " + description + " (" + ignored.getClass().getSimpleName() + ").");
        }
    }

    private void setVoicemailSource(MediaPlayer media, byte[] bytes) throws Exception {
        File temporary = File.createTempFile("voicemail-", ".tmp", getCacheDir());
        try (FileOutputStream output = new FileOutputStream(temporary);
             ParcelFileDescriptor input = ParcelFileDescriptor.open(temporary, ParcelFileDescriptor.MODE_READ_ONLY)) {
            // Remove the directory entry before writing private audio.
            java.nio.file.Files.delete(temporary.toPath());
            output.write(bytes);
            media.setDataSource(input.getFileDescriptor(), 0, bytes.length);
        } finally {
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private static byte[] demoAudio() {
        int sampleCount = 8000;
        ByteBuffer wav = ByteBuffer.allocate(44 + sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R','I','F','F'}).putInt(36 + sampleCount * 2);
        wav.put(new byte[]{'W','A','V','E','f','m','t',' '}).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put(new byte[]{'d','a','t','a'}).putInt(sampleCount * 2);
        for (int index = 0; index < sampleCount; index++) {
            double fade = Math.min(1, Math.min(index, sampleCount - index) / 200.0);
            wav.putShort((short) (3000 * fade * Math.sin(2 * Math.PI * 440 * index / 8000)));
        }
        return wav.array();
    }

    private void stopAudio() {
        playbackClock.removeCallbacks(updateProgress);
        playerReady = false;
        seeking = false;
        playback.setVisibility(View.GONE);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void clearSession() {
        requestingSmsPermissions = false;
        connectWhenFocused = false;
        clearMailboxSession();
    }

    private void clearMailboxSession() {
        generation++;
        fizzStatus.cancel();
        provisioned = null;
        manualSession = null;
        setInboxConnected(false);
        inboxAction.setEnabled(true);
        stopAudio();
        password.setText("");
        user.setText("");
        messages.removeAllViews();
        inboxState.setText(setupHint());
        status.setText("");
        showPage(0);
    }

    @Override
    protected void onStop() {
        if (requestingSmsPermissions) {
            clearMailboxSession();
        } else {
            clearSession();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
