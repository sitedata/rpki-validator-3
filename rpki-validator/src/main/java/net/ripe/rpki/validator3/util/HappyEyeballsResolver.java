/*
 * The BSD License
 *
 * Copyright (c) 2010-2018 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.rpki.validator3.util;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.SocketAddressResolver;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>A SocketAddressResolver implementation that implements some of the RFC8305 to work around problems
 * on hosts with broken IPv6 connectivity.</p>
 */
@Slf4j
public class HappyEyeballsResolver implements SocketAddressResolver {

    // delays from RFC8305/Section 8
    private static final long RESOLUTION_DELAY_MILLIS = 50L;
    private static final long CONNECTION_ATTEMPT_DELAY_MS = 250L;

    @Override
    public void resolve(String host, int port, Promise<List<InetSocketAddress>> promise) {
        try {
            final Name hostname = Name.fromString(host);
            InetSocketAddress bestAddress;

            final ConcurrentLinkedQueue<Optional<InetAddress>> resolvedAddressesV6 = new ConcurrentLinkedQueue<>();
            final Thread v6thread = new Thread(dnsLookupRunnable(hostname, Type.AAAA, resolvedAddressesV6));
            v6thread.start();

            final ConcurrentLinkedQueue<Optional<InetAddress>> resolvedAddressesV4 = new ConcurrentLinkedQueue<>();
            final Thread v4thread = new Thread(dnsLookupRunnable(hostname, Type.A, resolvedAddressesV4));
            v4thread.start();

            awaitDnsResponses(resolvedAddressesV6, resolvedAddressesV4);

            if (log.isDebugEnabled()) {
                log.debug("Resolved v6 addresses: {}",
                        joinAddresses(resolvedAddressesV6));
                log.debug("Resolved v4 addresses: {}",
                        joinAddresses(resolvedAddressesV4));
            }

            final ConcurrentLinkedQueue<Optional<SocketChannel>> sockets = new ConcurrentLinkedQueue<>();
            final Thread connectionsThread =
                    new Thread(connectAttemptsRunnable(resolvedAddressesV6, resolvedAddressesV4, sockets, port));
            connectionsThread.start();

            bestAddress = (InetSocketAddress) awaitSuccessfulConnection(sockets);
            if (bestAddress == null) {
                promise.failed(new UnknownHostException(host));
            } else {
                promise.succeeded(Collections.singletonList(bestAddress));
            }

            connectionsThread.interrupt();
            v6thread.interrupt();
            v4thread.interrupt();
            for (Optional<SocketChannel> channelOptional : sockets) {
                channelOptional.ifPresent(channel -> {
                    try {
                        channel.close();
                    } catch (Exception e) {
                        // ignore
                    }
                });
            }
        } catch (Exception e) {
            log.warn("Error during looking for happy eyeballs: ", e);
            promise.failed(e);
        }
    }

    private Object joinAddresses(ConcurrentLinkedQueue<Optional<InetAddress>> resolvedAddressesV6) {
        return resolvedAddressesV6.stream()
                                  .filter(Optional::isPresent)
                                  .map(Optional::get)
                                  .map(InetAddress::getHostAddress)
                                  .collect(Collectors.joining(","));
    }

    private static SocketAddress awaitSuccessfulConnection(ConcurrentLinkedQueue<Optional<SocketChannel>> sockets) throws IOException {
        boolean keepGoing = true;
        while (keepGoing) {
            final Iterator<Optional<SocketChannel>> iterator = sockets.iterator();
            int count = 0;
            while (iterator.hasNext()) {
                count++;
                final Optional<SocketChannel> socketChannelOptional = iterator.next();
                if (socketChannelOptional.isPresent()) {
                    final SocketChannel socketChannel = socketChannelOptional.get();
                    try {
                        if (socketChannel.finishConnect()) {
                            if (log.isDebugEnabled()) {
                                log.debug("Successfully connected to: {}", socketChannel.getRemoteAddress());
                            }
                            return socketChannel.getRemoteAddress();
                        }
                    } catch (IOException e) {
                        if (log.isDebugEnabled()) {
                            log.debug("Error connecting to " + socketChannel.getRemoteAddress(), e);
                        }
                        iterator.remove();
                    }
                } else {
                    if (count == 1) {
                        keepGoing = false;
                    }
                    break;
                }
            }
        }
        return null;
    }

    private static void awaitDnsResponses(ConcurrentLinkedQueue<Optional<InetAddress>> resolvedAddressesV6,
                                          ConcurrentLinkedQueue<Optional<InetAddress>> resolvedAddressesV4) {
        boolean keepGoing = true;
        while (resolvedAddressesV6.isEmpty() && keepGoing) {
            if (!resolvedAddressesV4.isEmpty()) {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESOLUTION_DELAY_MILLIS);
                while (haveTime(deadline)) {
                    if (!resolvedAddressesV6.isEmpty()) break;
                }
                keepGoing = false;
            }
        }
    }

    private static boolean haveTime(long nanoDeadline) {
        return System.nanoTime() - nanoDeadline < 0;
    }

    private static Runnable dnsLookupRunnable(Name hostname, int queryType, Queue<Optional<InetAddress>> resolvedAddresses) {
        return () -> {
            try {
                final Lookup lookup = new Lookup(hostname, queryType, DClass.IN);
                final Record[] records = lookup.run();
                if (records != null) {
                    for (Record record : records) {
                        InetAddress address = null;
                        if (record instanceof AAAARecord) {
                            address = ((AAAARecord) record).getAddress();
                        } else if (record instanceof ARecord) {
                            address = ((ARecord) record).getAddress();
                        }
                        if (address != null) resolvedAddresses.add(Optional.of(address));
                    }
                }
            } finally {
                resolvedAddresses.add(Optional.empty());
            }
        };
    }

    private static Runnable connectAttemptsRunnable(Queue<Optional<InetAddress>> firstAfAddresses,
                                                    Queue<Optional<InetAddress>> secondAfAddresses,
                                                    Queue<Optional<SocketChannel>> socketChannels,
                                                    final int port) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    boolean keepGoing = true;
                    while (keepGoing && !Thread.currentThread().isInterrupted()) {
                        final boolean haveMoreAF1 = openConnectionFromQueue(firstAfAddresses);
                        final boolean haveMoreAF2 = openConnectionFromQueue(secondAfAddresses);
                        // watch out! do not inline these booleans,
                        // otherwise you'll shortcircuit second evaluation
                        keepGoing = haveMoreAF1 || haveMoreAF2;
                    }
                } catch (InterruptedException e) {
                    // just stop
                } finally {
                    socketChannels.add(Optional.empty());
                }
            }

            private boolean openConnectionFromQueue(Queue<Optional<InetAddress>> queue) throws InterruptedException {
                final Optional<InetAddress> addressOptional = queue.poll();
                if (addressOptional != null) {
                    if (addressOptional.isPresent()) {
                        final InetAddress address = addressOptional.get();
                        try {
                            log.debug("Connecting to {}", address);
                            final SocketChannel socketChannel = SocketChannel.open();
                            socketChannel.configureBlocking(false);
                            socketChannel.connect(new InetSocketAddress(address, port));
                            socketChannels.add(Optional.of(socketChannel));
                        } catch (IOException e) {
                            // can't connect - move on
                            log.debug("Error connecting to {}", address);
                        } finally {
                            TimeUnit.MILLISECONDS.sleep(CONNECTION_ATTEMPT_DELAY_MS);
                        }
                    } else {
                        return false;
                    }
                }
                return true;
            }
        };
    }
}
