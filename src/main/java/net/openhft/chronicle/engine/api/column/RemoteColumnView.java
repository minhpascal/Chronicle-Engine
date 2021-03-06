package net.openhft.chronicle.engine.api.column;

import net.openhft.chronicle.engine.api.tree.Asset;
import net.openhft.chronicle.engine.api.tree.RequestContext;
import net.openhft.chronicle.engine.collection.ClientWiredStatelessRowIterator;
import net.openhft.chronicle.engine.map.ObjectSubscription;
import net.openhft.chronicle.network.connection.AbstractStatelessClient;
import net.openhft.chronicle.network.connection.CoreFields;
import net.openhft.chronicle.network.connection.TcpChannelHub;
import net.openhft.chronicle.wire.ParameterizeWireKey;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.WireKey;
import net.openhft.chronicle.wire.Wires;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static net.openhft.chronicle.engine.api.column.RemoteColumnView.EventId.*;
import static net.openhft.chronicle.engine.api.column.RemoteColumnView.Params.*;
import static net.openhft.chronicle.engine.query.Filter.empty;

/**
 * @author Rob Austin.
 */
public class RemoteColumnView extends AbstractStatelessClient implements ColumnView {
    private final Asset asset;
    private final ThreadLocal<List> th = ThreadLocal.withInitial(ArrayList::new);


    public RemoteColumnView(@NotNull RequestContext context, @NotNull Asset asset) {

        super(asset.findView(TcpChannelHub.class), (long) 0, toURL(context));
        this.asset = asset;
    }

    private static String toURL(final RequestContext context) {
        return context.viewType(ColumnView.class).toUri();
    }

    @Override
    public List<Column> columns() {
        final List l = th.get();
        l.clear();
        return (List) proxyReturnWireTypedObject(columns, th, List.class);
    }

    @Override
    public int rowCount(@NotNull List<MarshableFilter> sortedFilter) {
        return proxyReturnInt(rowCount, sortedFilter);
    }

    @Override
    public int changedRow(@NotNull Map<String, Object> row, @NotNull Map<String, Object> oldRow) {
        return proxyReturnInt(changedRow, row, oldRow);
    }

    @Override
    public void registerChangeListener(@NotNull Runnable r) {
        final RequestContext rc = RequestContext.requestContext().fullName(asset.fullName());
        asset.acquireView(ObjectSubscription.class).registerSubscriber(rc, o -> r.run(), empty());
    }

    @Override
    public Iterator<? extends Row> iterator(@NotNull SortedFilter sortedFilter) {
        final StringBuilder csp = Wires.acquireStringBuilder();
        final Function<ValueIn, Long> function = v -> v.wireIn().read().int64();
        long cid = (Long) proxyReturnWireConsumerInOut(
                iterator,
                CoreFields.reply,
                valueOut -> valueOut.object(sortedFilter),
                function);

        return new ClientWiredStatelessRowIterator(hub, csp.toString(), cid);
    }

    @Override
    public boolean canDeleteRows() {
        return proxyReturnBoolean(canDeleteRows);
    }

    @Override
    public boolean containsRowWithKey(Object[] keys) {
        return containsRowWithKey(keys);
    }

    @Override
    public ObjectSubscription objectSubscription() {
        return asset.getView(ObjectSubscription.class);
    }

    public enum Params implements WireKey {
        sortedFilter,
        row,
        oldRow,
        keys;
    }

    public enum EventId implements ParameterizeWireKey {
        columns,
        rowCount(sortedFilter),     // used only by the queue view
        changedRow(row, oldRow),
        canDeleteRows,
        containsRowWithKey(keys),
        iterator(sortedFilter);
        private final WireKey[] params;

        <P extends WireKey> EventId(P... params) {
            this.params = params;
        }

        @NotNull
        public <P extends WireKey> P[] params() {
            return (P[]) this.params;
        }
    }


}
