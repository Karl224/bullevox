package ca.local.vvm;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionManager;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

final class StatusRequest {
    private static final String SENT = "ca.local.vvm.FIZZ_STATUS_SENT";
    private static final String DATA_SMS = "android.intent.action.DATA_SMS_RECEIVED";
    private static final String TEXT_SMS = Telephony.Sms.Intents.SMS_RECEIVED_ACTION;
    private final Activity activity;
    private final Consumer<String> status;
    private final Consumer<Mailbox.Settings> received;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> finish("No Fizz STATUS reply within 60 seconds.");
    private PendingIntent sent;
    private boolean listening;

    StatusRequest(Activity activity, Consumer<String> status, Consumer<Mailbox.Settings> received) {
        this.activity = activity;
        this.status = status;
        this.received = received;
    }

    private final BroadcastReceiver replyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!listening) {
                return;
            }
            StringBuilder content = new StringBuilder();
            try {
                SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
                if (messages == null) {
                    return;
                }
                for (SmsMessage message : messages) {
                    if (message == null) {
                        return;
                    }
                    if (DATA_SMS.equals(intent.getAction())) {
                        byte[] data = message.getUserData();
                        if (data == null) {
                            return;
                        }
                        content.append(new String(data, StandardCharsets.ISO_8859_1));
                    } else if (message.getMessageBody() != null) {
                        content.append(message.getMessageBody());
                    }
                }
            } catch (RuntimeException invalidPdu) {
                return;
            }
            String payload = content.toString();
            if (!FizzStatus.matches(payload)) {
                return;
            }
            int replySub = intent.getIntExtra("subscription", SubscriptionManager.INVALID_SUBSCRIPTION_ID);
            if (SubscriptionManager.isValidSubscriptionId(replySub) && replySub != subscription) {
                return;
            }
            Mailbox.Settings settings = FizzStatus.parse(payload);
            cancel();
            if (settings == null) {
                status.accept("A STATUS reply arrived without usable mailbox settings.");
            } else {
                received.accept(settings);
            }
        }
    };

    private final BroadcastReceiver sentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!listening) {
                return;
            }
            int result = getResultCode();
            if (result == Activity.RESULT_OK) {
                status.accept("Android sent STATUS. Keep this screen open for the reply.");
            } else {
                finish("Android could not send STATUS (SMS result " + result + ").");
            }
        }
    };
    private int subscription;
    private boolean replyRegistered;
    private boolean sentRegistered;

    void start() {
        if (listening) {
            return;
        }
        subscription = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (!SubscriptionManager.isValidSubscriptionId(subscription)) {
            status.accept("Set Fizz as the default SIM for SMS in Android settings first.");
            return;
        }
        try {
            IntentFilter data = new IntentFilter(DATA_SMS);
            data.addDataScheme("sms");
            data.addDataAuthority("localhost", Integer.toString(FizzStatus.PORT));
            registerReply(data);
            replyRegistered = true;
            registerReply(new IntentFilter(TEXT_SMS));
            IntentFilter result = new IntentFilter(SENT);
            if (Build.VERSION.SDK_INT >= 33) {
                activity.registerReceiver(sentReceiver, result, Context.RECEIVER_NOT_EXPORTED);
            } else {
                activity.registerReceiver(sentReceiver, result, activity.getPackageName() + ".STATUS_CALLBACK", null);
            }
            sentRegistered = true;
            sent = PendingIntent.getBroadcast(activity, 0, new Intent(SENT).setPackage(activity.getPackageName()),
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            listening = true;
            handler.postDelayed(timeout, 60_000);
            status.accept("Sending one STATUS request via the default SMS SIM…");
            activity.getSystemService(SmsManager.class).createForSubscriptionId(subscription)
                    .sendDataMessage(FizzStatus.DESTINATION, null, (short) FizzStatus.PORT,
                            FizzStatus.REQUEST.getBytes(StandardCharsets.UTF_8), sent, null);
        } catch (RuntimeException failure) {
            finish("Android blocked STATUS. Check SMS permissions and the default SMS SIM.");
        }
    }

    private void registerReply(IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(replyReceiver, filter, Manifest.permission.BROADCAST_SMS, null,
                    Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(replyReceiver, filter, Manifest.permission.BROADCAST_SMS, null);
        }
    }

    private void finish(String message) {
        cancel();
        status.accept(message);
    }

    void cancel() {
        listening = false;
        handler.removeCallbacks(timeout);
        if (sent != null) {
            sent.cancel();
            sent = null;
        }
        if (replyRegistered) {
            activity.unregisterReceiver(replyReceiver);
            replyRegistered = false;
        }
        if (sentRegistered) {
            activity.unregisterReceiver(sentReceiver);
            sentRegistered = false;
        }
    }
}
