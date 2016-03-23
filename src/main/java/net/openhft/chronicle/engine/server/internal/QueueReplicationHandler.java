/*
 *
 *  *     Copyright (C) 2016  higherfrequencytrading.com
 *  *
 *  *     This program is free software: you can redistribute it and/or modify
 *  *     it under the terms of the GNU Lesser General Public License as published by
 *  *     the Free Software Foundation, either version 3 of the License.
 *  *
 *  *     This program is distributed in the hope that it will be useful,
 *  *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  *     GNU Lesser General Public License for more details.
 *  *
 *  *     You should have received a copy of the GNU Lesser General Public License
 *  *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package net.openhft.chronicle.engine.server.internal;

import net.openhft.chronicle.bytes.Bytes;
import net.openhft.chronicle.bytes.BytesStore;
import net.openhft.chronicle.core.annotation.UsedViaReflection;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.tree.ChronicleQueueView;
import net.openhft.chronicle.engine.tree.QueueView;
import net.openhft.chronicle.network.cluster.AbstractSubHandler;
import net.openhft.chronicle.network.connection.WireOutPublisher;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.*;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StreamCorruptedException;
import java.util.concurrent.TimeoutException;

import static net.openhft.chronicle.network.WireTcpHandler.logYaml;

/**
 * Created by Rob Austin
 */
public class QueueReplicationHandler extends AbstractSubHandler<EngineWireNetworkContext>
        implements Demarshallable, WriteMarshallable {

    static {
        ClassAliasPool.CLASS_ALIASES.addAlias(QueueReplicationEvent.class);
    }

    private static final Logger LOG = LoggerFactory.getLogger(QueueReplicationHandler.class);
    private final boolean isSource;

    private long lastIndexReceived;
    private EventLoop eventLoop;
    private Asset rootAsset;
    private ExcerptAppender appender;
    private boolean closed;
    private ChronicleQueueView chronicleQueueView;

    @UsedViaReflection
    private QueueReplicationHandler(WireIn wire) {
        lastIndexReceived = wire.read(() -> "lastIndexReceived").int64();
        isSource = wire.read(() -> "isSource").bool();
    }

    public QueueReplicationHandler(long lastIndexReceived, boolean isSource) {
        this.lastIndexReceived = lastIndexReceived;
        this.isSource = isSource;
    }

    @Override
    public void writeMarshallable(@NotNull WireOut wire) {
        wire.write(() -> "lastIndexReceived").int64(lastIndexReceived);
        wire.write(() -> "isSource").bool(isSource);
    }

    @Override
    public void processData(@NotNull WireIn inWire, @NotNull WireOut outWire) {

        if (closed)
            return;

        final StringBuilder eventName = Wires.acquireStringBuilder();
        final ValueIn valueIn = inWire.readEventName(eventName);

        // receives replication events
        if ("replicationEvent".contentEquals(eventName)) {
            final QueueReplicationEvent replicationEvent = valueIn.typedMarshallable();

            if (replicationEvent == null)
                return;

            try {
                appender.writeBytes(replicationEvent.index, replicationEvent.payload().bytesForRead());
            } catch (StreamCorruptedException e) {
                LOG.error("", e);
            }

        } else {
            LOG.error("", new IllegalStateException("unsupported eventName=" + eventName));
        }


    }


    public static class QueueReplicationEvent implements Demarshallable, WriteMarshallable {

        private final long index;
        private final BytesStore payload;

        @UsedViaReflection
        private QueueReplicationEvent(@NotNull WireIn wireIn) {
            index = wireIn.read(() -> "index").int64();
            payload = wireIn.read(() -> "payload").bytesStore();
        }

        public QueueReplicationEvent(long index, @NotNull Bytes payload) {
            this.index = index;
            this.payload = payload;
        }

        public long index() {
            return index;
        }

        @NotNull
        public BytesStore payload() {
            return payload;
        }

        @Override
        public void writeMarshallable(@NotNull WireOut wire) {
            wire.write(() -> "index").int64(index);
            wire.write(() -> "payload").bytes(payload);
        }

        @Override
        public String toString() {
            return "ReplicationEvent{" +
                    "index=" + index +
                    ", payload=" + Wires.fromSizePrefixedBlobs(payload.bytesForRead()) +
                    '}';
        }
    }

    private class EventListener implements EventHandler {


        @NotNull
        final Bytes bytes = Bytes.elasticByteBuffer();

        @NotNull
        final ExcerptTailer tailer;

        @NotNull
        final WireOutPublisher publisher;


        public EventListener(@NotNull final ExcerptTailer tailer,
                             @NotNull final WireOutPublisher publisher) {
            this.tailer = tailer;
            this.publisher = publisher;
        }

        @Override
        public boolean action() throws InvalidEventHandlerException, InterruptedException {

            if (closed)
                throw new InvalidEventHandlerException("closed");

            if (!publisher.canTakeMoreData())
                return false;


            final boolean success = tailer.readBytes(bytes);
            if (!success)
                return false;

            final long index = tailer.index();

            final QueueReplicationEvent event = new QueueReplicationEvent(index, bytes);
            publisher.put("", d -> d.writeDocument(false,
                    w -> w.writeEventName(() -> "replicationEvent").typedMarshallable
                            (event)));
            bytes.clear();
            return false;
        }

        @Override
        public HandlerPriority priority() {
            return HandlerPriority.REPLICATION;
        }
    }


    @Override
    public void onInitialize(@NotNull WireOut outWire) {

        rootAsset = nc().rootAsset();
        final Asset asset = rootAsset.acquireAsset(csp());
        chronicleQueueView = (ChronicleQueueView) asset.acquireView(QueueView
                .class);
        final ChronicleQueue chronicleQueue = chronicleQueueView.chronicleQueue();
        appender = chronicleQueue.createAppender();

        assert appender != null;

        if (!isSource)
            return;

        eventLoop = rootAsset.findOrCreateView(EventLoop.class);
        eventLoop.start();

        final ExcerptTailer tailer = chronicleQueue.createTailer();
        if (lastIndexReceived > 0)
            try {
                tailer.moveToIndex(lastIndexReceived);
            } catch (TimeoutException e) {
                LOG.error("", e);
            }

        eventLoop.addHandler(new EventListener(tailer, nc().wireOutPublisher()));
        logYaml(outWire);
    }

    @Override
    public void close() {
        this.closed = true;
    }

}