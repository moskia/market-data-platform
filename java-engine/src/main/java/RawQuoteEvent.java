import java.time.Instant;

public record RawQuoteEvent(
        String exchangeId,
        String symbol,
        String side,
        double price,
        int quantity,
        long sequenceNumber,
        Instant exchangeTimestamp,
        Instant receivedTimestamp
        ) {}
