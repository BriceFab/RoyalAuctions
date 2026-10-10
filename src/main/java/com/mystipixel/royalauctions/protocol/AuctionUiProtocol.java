package com.mystipixel.royalauctions.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Framework-neutral wire contract for the RoyalAuctions auction UI.
 *
 * <p>The server remains authoritative. Client messages contain only browse intent
 * or stable listing identifiers; they never contain commands, ItemStacks, money
 * mutations or trusted ownership state.
 */
public final class AuctionUiProtocol {
    public static final String CHANNEL = "royalauctions:auction_ui";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 512 * 1024;
    public static final int MAX_PAGE_SIZE = 60;
    public static final int MAX_CATEGORIES = 64;
    public static final int MAX_SEARCH = 64;

    private static final int BROWSE = 1;
    private static final int PURCHASE = 2;
    private static final int CLOSE = 3;
    private static final int SNAPSHOT = 16;
    private static final int RESULT = 17;

    private static final Set<String> RESULT_STATUSES =
            Set.of("ok", "rejected", "stale", "not_found", "rate_limited", "unavailable");

    private AuctionUiProtocol() {
    }

    public enum Sort {
        NEWEST,
        OLDEST,
        PRICE_LOW,
        PRICE_HIGH,
        ENDING_SOON
    }

    public enum ListingKind {
        BIN,
        AUCTION
    }

    public sealed interface ClientMessage permits BrowseRequest, PurchaseRequest, CloseRequest {
        UUID session();
    }

    public sealed interface ServerMessage permits Snapshot, Result {
        UUID session();
    }

    public record Query(int page, String category, String search, Sort sort) {
        public Query {
            check(page >= 0 && page <= 1_000_000);
            category = normalize(category, 64);
            search = normalize(search, MAX_SEARCH);
            check(sort != null);
        }

        public static Query newest() {
            return new Query(0, "", "", Sort.NEWEST);
        }
    }

    public record BrowseRequest(UUID session, UUID requestId, Query query) implements ClientMessage {
        public BrowseRequest {
            check(session != null && requestId != null && query != null);
        }
    }

    public record PurchaseRequest(UUID session, UUID requestId, UUID listingId, double expectedPrice)
            implements ClientMessage {
        public PurchaseRequest {
            check(session != null && requestId != null && listingId != null);
            finite(expectedPrice);
            check(expectedPrice >= 0);
        }
    }

    public record CloseRequest(UUID session) implements ClientMessage {
        public CloseRequest {
            check(session != null);
        }
    }

    public record Category(String id, String displayName, String iconKey) {
        public Category {
            id = required(id, 64);
            displayName = required(displayName, 96);
            iconKey = required(iconKey, 128);
            key(iconKey);
        }
    }

    /**
     * Presentation-safe listing view. The item registry key is enough to render
     * a native icon in the first pilot; the server-side listing remains the sole
     * source of truth for the actual ItemStack and transaction.
     */
    public record Listing(
            UUID id,
            String itemKey,
            int amount,
            String displayName,
            String sellerName,
            String category,
            String tier,
            ListingKind type,
            double price,
            double displayPrice,
            String priceText,
            long expiresAt,
            int bidCount) {
        public Listing {
            check(id != null);
            itemKey = required(itemKey, 128);
            key(itemKey);
            check(amount >= 1 && amount <= 127);
            displayName = required(displayName, 256);
            sellerName = required(sellerName, 64);
            category = required(category, 64);
            tier = normalize(tier, 64);
            check(type != null);
            finite(price);
            finite(displayPrice);
            priceText = required(priceText, 64);
            check(price >= 0 && displayPrice >= 0 && expiresAt >= 0 && bidCount >= 0);
        }
    }

    public record Snapshot(
            UUID session,
            boolean openScreen,
            Query query,
            int perPage,
            int total,
            double balance,
            String balanceText,
            List<Category> categories,
            List<Listing> listings) implements ServerMessage {
        public Snapshot {
            check(session != null && query != null);
            check(perPage >= 1 && perPage <= MAX_PAGE_SIZE && total >= 0);
            finite(balance);
            balanceText = required(balanceText, 64);
            categories = List.copyOf(categories);
            listings = List.copyOf(listings);
            check(categories.size() <= MAX_CATEGORIES && listings.size() <= perPage);
        }

        public int pageCount() {
            return Math.max(1, (int) Math.ceil(total / (double) perPage));
        }
    }

    public record Result(UUID session, UUID requestId, String status, String messageKey) implements ServerMessage {
        public Result {
            check(session != null && requestId != null);
            status = required(status, 32);
            check(RESULT_STATUSES.contains(status));
            messageKey = normalize(messageKey, 128);
        }
    }

    public static byte[] encodeClient(ClientMessage message) {
        return write(out -> {
            out.writeByte(VERSION);
            if (message instanceof BrowseRequest browse) {
                out.writeByte(BROWSE);
                uuid(out, browse.session());
                uuid(out, browse.requestId());
                query(out, browse.query());
            } else if (message instanceof PurchaseRequest purchase) {
                out.writeByte(PURCHASE);
                uuid(out, purchase.session());
                uuid(out, purchase.requestId());
                uuid(out, purchase.listingId());
                out.writeDouble(purchase.expectedPrice());
            } else if (message instanceof CloseRequest close) {
                out.writeByte(CLOSE);
                uuid(out, close.session());
            } else {
                throw new IllegalArgumentException("Unknown auction client message");
            }
        });
    }

    public static ClientMessage decodeClient(byte[] bytes) {
        ByteBuffer in = input(bytes);
        try {
            int kind = Byte.toUnsignedInt(in.get());
            ClientMessage message = switch (kind) {
                case BROWSE -> new BrowseRequest(uuid(in), uuid(in), query(in));
                case PURCHASE -> new PurchaseRequest(uuid(in), uuid(in), uuid(in), in.getDouble());
                case CLOSE -> new CloseRequest(uuid(in));
                default -> throw new IllegalArgumentException("Unknown auction client message type");
            };
            check(!in.hasRemaining());
            return message;
        } catch (RuntimeException error) {
            throw invalid("client", error);
        }
    }

    public static byte[] encodeServer(ServerMessage message) {
        return write(out -> {
            out.writeByte(VERSION);
            if (message instanceof Snapshot snapshot) {
                out.writeByte(SNAPSHOT);
                uuid(out, snapshot.session());
                out.writeBoolean(snapshot.openScreen());
                query(out, snapshot.query());
                out.writeByte(snapshot.perPage());
                out.writeInt(snapshot.total());
                out.writeDouble(snapshot.balance());
                text(out, snapshot.balanceText());
                out.writeByte(snapshot.categories().size());
                for (Category category : snapshot.categories()) {
                    text(out, category.id());
                    text(out, category.displayName());
                    text(out, category.iconKey());
                }
                out.writeByte(snapshot.listings().size());
                for (Listing listing : snapshot.listings()) {
                    uuid(out, listing.id());
                    text(out, listing.itemKey());
                    out.writeByte(listing.amount());
                    text(out, listing.displayName());
                    text(out, listing.sellerName());
                    text(out, listing.category());
                    text(out, listing.tier());
                    out.writeByte(listing.type().ordinal());
                    out.writeDouble(listing.price());
                    out.writeDouble(listing.displayPrice());
                    text(out, listing.priceText());
                    out.writeLong(listing.expiresAt());
                    out.writeInt(listing.bidCount());
                }
            } else if (message instanceof Result result) {
                out.writeByte(RESULT);
                uuid(out, result.session());
                uuid(out, result.requestId());
                text(out, result.status());
                text(out, result.messageKey());
            } else {
                throw new IllegalArgumentException("Unknown auction server message");
            }
        });
    }

    public static ServerMessage decodeServer(byte[] bytes) {
        ByteBuffer in = input(bytes);
        try {
            int kind = Byte.toUnsignedInt(in.get());
            ServerMessage message;
            if (kind == SNAPSHOT) {
                UUID session = uuid(in);
                boolean open = bool(in);
                Query query = query(in);
                int perPage = Byte.toUnsignedInt(in.get());
                int total = in.getInt();
                double balance = in.getDouble();
                String balanceText = text(in);
                int categoryCount = countByte(in, MAX_CATEGORIES);
                List<Category> categories = new ArrayList<>(categoryCount);
                for (int i = 0; i < categoryCount; i++) {
                    categories.add(new Category(text(in), text(in), text(in)));
                }
                int listingCount = countByte(in, MAX_PAGE_SIZE);
                List<Listing> listings = new ArrayList<>(listingCount);
                for (int i = 0; i < listingCount; i++) {
                    UUID id = uuid(in);
                    String itemKey = text(in);
                    int amount = Byte.toUnsignedInt(in.get());
                    String displayName = text(in);
                    String sellerName = text(in);
                    String category = text(in);
                    String tier = text(in);
                    int type = Byte.toUnsignedInt(in.get());
                    check(type < ListingKind.values().length);
                    listings.add(new Listing(
                            id,
                            itemKey,
                            amount,
                            displayName,
                            sellerName,
                            category,
                            tier,
                            ListingKind.values()[type],
                            in.getDouble(),
                            in.getDouble(),
                            text(in),
                            in.getLong(),
                            in.getInt()));
                }
                message = new Snapshot(
                        session, open, query, perPage, total, balance, balanceText, categories, listings);
            } else if (kind == RESULT) {
                message = new Result(uuid(in), uuid(in), text(in), text(in));
            } else {
                throw new IllegalArgumentException("Unknown auction server message type");
            }
            check(!in.hasRemaining());
            return message;
        } catch (RuntimeException error) {
            throw invalid("server", error);
        }
    }

    private static void query(DataOutputStream out, Query query) throws IOException {
        out.writeInt(query.page());
        text(out, query.category());
        text(out, query.search());
        out.writeByte(query.sort().ordinal());
    }

    private static Query query(ByteBuffer in) {
        int page = in.getInt();
        String category = text(in);
        String search = text(in);
        int sort = Byte.toUnsignedInt(in.get());
        check(sort < Sort.values().length);
        return new Query(page, category, search, Sort.values()[sort]);
    }

    private static byte[] write(Writer writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                writer.write(out);
            }
            check(bytes.size() <= MAX_BYTES);
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ByteBuffer input(byte[] bytes) {
        check(bytes != null && bytes.length >= 2 && bytes.length <= MAX_BYTES);
        ByteBuffer in = ByteBuffer.wrap(bytes);
        check(Byte.toUnsignedInt(in.get()) == VERSION);
        return in;
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID uuid(ByteBuffer in) {
        return new UUID(in.getLong(), in.getLong());
    }

    private static boolean bool(ByteBuffer in) {
        byte value = in.get();
        check(value == 0 || value == 1);
        return value == 1;
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        check(utf8.length <= 4096);
        out.writeShort(utf8.length);
        out.write(utf8);
    }

    private static String text(ByteBuffer in) {
        int size = Short.toUnsignedInt(in.getShort());
        check(size <= 4096 && size <= in.remaining());
        byte[] utf8 = new byte[size];
        in.get(utf8);
        String value = new String(utf8, StandardCharsets.UTF_8);
        check(value.indexOf('\uFFFD') < 0);
        return value;
    }

    private static int countByte(ByteBuffer in, int maximum) {
        int count = Byte.toUnsignedInt(in.get());
        check(count <= maximum);
        return count;
    }

    private static String normalize(String value, int maximum) {
        String normalized = value == null ? "" : value.strip();
        clean(normalized, maximum);
        return normalized;
    }

    private static String required(String value, int maximum) {
        String normalized = normalize(value, maximum);
        check(!normalized.isEmpty());
        return normalized;
    }

    private static void clean(String value, int maximum) {
        check(value.length() <= maximum);
        check(value.codePoints().noneMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.FORMAT
                || Character.getType(c) == Character.SURROGATE
                || c == 167));
    }

    private static void key(String value) {
        check(value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"));
    }

    private static void finite(double value) {
        check(Double.isFinite(value));
    }

    private static IllegalArgumentException invalid(String side, RuntimeException cause) {
        return new IllegalArgumentException("Invalid auction " + side + " message", cause);
    }

    private static void check(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid auction data");
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }
}
