package ca.local.vvm;

import android.net.DnsResolver;
import android.net.Network;
import android.os.CancellationSignal;
import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CarrierDnsCheck {
    static String run(Network network, String host, int port) {
        DnsResolver resolver = DnsResolver.getInstance();
        CancellationSignal cancellation = new CancellationSignal();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        resolver.rawQuery(network, host, DnsResolver.CLASS_IN, DnsResolver.TYPE_A,
                DnsResolver.FLAG_NO_CACHE_LOOKUP | DnsResolver.FLAG_NO_CACHE_STORE,
                Runnable::run, cancellation, new DnsResolver.Callback<byte[]>() {
                    @Override
                    public void onAnswer(byte[] answer, int rcode) {
                        if (rcode == 0) {
                            result.complete(answer);
                        } else {
                            result.completeExceptionally(new IOException("DNS response code " + rcode));
                        }
                    }

                    @Override
                    public void onError(DnsResolver.DnsException error) {
                        result.completeExceptionally(error);
                    }
                });
        try {
            String alias = DnsAliases.match(result.get(8, TimeUnit.SECONDS), host);
            if (alias == null) {
                return "Cellular DNS did not link the provisioned host to any of the four certificate names.";
            }
            InetAddress[] addresses = network.getAllByName(host);
            for (int index = 0; index < Math.min(addresses.length, 3); index++) {
                if (Mailbox.verifyCarrierDnsName(network.getSocketFactory(), addresses[index], port, alias)) {
                    return "Cellular DNS linked to " + alias + " and TLS matched.";
                }
            }
            return "Cellular DNS linked to " + alias + ", but TLS did not match on the tested addresses.";
        } catch (ExecutionException | IOException failure) {
            return "Cellular DNS check could not read a usable answer.";
        } catch (TimeoutException timeout) {
            return "Cellular DNS check timed out.";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "Cellular DNS check interrupted.";
        } finally {
            cancellation.cancel();
        }
    }
}
