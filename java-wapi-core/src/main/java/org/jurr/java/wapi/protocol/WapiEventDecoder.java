package org.jurr.java.wapi.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiTokenRegistry;
import org.jurr.java.wapi.WapiValue;
import org.jurr.java.wapi.node.WapiNodeDefinitionCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec.ChannelFrame;

/** Decodes the unsolicited 0xd7/hash/value records, which C returns as wTV records (PDF "wGetParsedEvents"). */
public final class WapiEventDecoder
{
	static final int TOKEN_EVENT = 0xD7;

	private final WapiTokenRegistry registry;
	private final Map<Integer, byte[]> pendingByChannel = new HashMap<>();

	/**
	 * Creates a decoder that resolves event hashes through a registry.
	 *
	 * @param registry non-null registry of known tokens
	 * @throws WapiException if {@code registry} is null
	 */
	public WapiEventDecoder(final WapiTokenRegistry registry)
	{
		if (registry == null)
		{
			throw new WapiException("Token registry is required");
		}
		this.registry = registry;
	}

	/**
	 * Decodes available unsolicited token records and retains incomplete trailing records
	 * for the next call. Navigation tokens are consumed without resolving tree paths.
	 * Unknown hashes produce opaque tokens. Pending bytes are kept separately for each
	 * channel ID and cleared for that channel if decoding fails.
	 *
	 * @param frame non-null channel data; an empty payload produces no new events
	 * @return mutable list of decoded events in stream order
	 * @throws WapiException if the frame is null, or with {@link WapiError#WTOKEN} if the stream contains
	 *             an unknown token or a token where it is not allowed; incomplete tokens are retained
	 */
	public List<WapiEvent> decode(final ChannelFrame frame)
	{
		final var events = new ArrayList<WapiEvent>();
		for (final var streamRecord : decodeRecords(frame))
		{
			if (streamRecord.isEvent())
			{
				events.add(streamRecord.event());
			}
		}
		return events;
	}

	/**
	 * Decodes available records like {@link #decode(ChannelFrame)}, but also returns the raw bytes
	 * of each {@code 0xd7}/hash/value record, an end-of-data marker for each
	 * {@link WapiBinaryStreamCodec#TOKEN_END_DATA} token between records, and each node-definition
	 * response ({@link WapiBinaryStreamCodec#TOKEN_NODE_DEF_RESPONSE}).
	 *
	 * @param frame non-null channel data; an empty payload produces no new records
	 * @return mutable list of records in stream order
	 * @throws WapiException if the frame is null, or with {@link WapiError#WTOKEN} if the stream contains
	 *             an unknown token or a token where it is not allowed; incomplete tokens are retained
	 */
	public List<StreamRecord> decodeRecords(final ChannelFrame frame)
	{
		if (frame == null)
		{
			throw new WapiException("Channel frame is required");
		}
		return decodeAvailable(frame);
	}

	/** Discards incomplete records kept for all channels, for example when a new connection starts. */
	public void reset()
	{
		pendingByChannel.clear();
	}

	private List<StreamRecord> decodeAvailable(final ChannelFrame frame)
	{
		final var bytes = withPending(frame);
		final var records = new ArrayList<StreamRecord>();
		var offset = 0;
		while (offset < bytes.length)
		{
			final int next = (bytes[offset] & 0xFF) == TOKEN_EVENT ? decodeEvent(bytes, offset, records) : decodeOtherToken(bytes, offset, records);
			if (next < 0)
			{
				break;
			}
			offset = next;
		}
		keepPending(frame.channelId(), bytes, offset);
		return records;
	}

	/** {@return the bytes kept from earlier frames of the channel, followed by the frame's payload} */
	private byte[] withPending(final ChannelFrame frame)
	{
		final byte[] pending = pendingByChannel.remove(frame.channelId());
		if (pending == null)
		{
			return frame.payload();
		}
		final var bytes = new byte[pending.length + frame.payload().length];
		System.arraycopy(pending, 0, bytes, 0, pending.length);
		System.arraycopy(frame.payload(), 0, bytes, pending.length, frame.payload().length);
		return bytes;
	}

	/**
	 * Decodes a hash-addressed value record, {@code 0xD7 <hash> <value>}, into an event record.
	 *
	 * @return the offset after the record, or -1 when it is not complete yet
	 */
	private int decodeEvent(final byte[] bytes, final int offset, final List<StreamRecord> records)
	{
		if (bytes.length - offset < 6)
		{
			return -1;
		}
		final int hash = ByteUtil.readSignedInt(bytes, offset + 1);
		final var valueOffset = offset + 5;
		final int valueLength = valueLength(bytes, valueOffset);
		if (valueLength < 0 || bytes.length - valueOffset < valueLength)
		{
			return -1;
		}
		final var parsed = parseValue(bytes, valueOffset);
		final var token = registry.findOrCreateByHash(hash);
		records.add(new StreamRecord(StreamRecord.Kind.EVENT, new WapiEvent(token, parsed.value.getType(), parsed.value),
				Arrays.copyOfRange(bytes, offset, parsed.nextOffset)));
		return parsed.nextOffset;
	}

	/**
	 * Decodes a token that is not a hash-addressed value: end of data and node definitions become
	 * records, other tokens are consumed.
	 *
	 * @return the offset after the token, or -1 when it is not complete yet
	 */
	private static int decodeOtherToken(final byte[] bytes, final int offset, final List<StreamRecord> records)
	{
		final int length = streamTokenLength(bytes, offset);
		if (length < 0 || bytes.length - offset < length)
		{
			return -1;
		}
		final var token = bytes[offset] & 0xFF;
		if (token == WapiBinaryStreamCodec.TOKEN_END_DATA)
		{
			records.add(StreamRecord.END_OF_DATA);
		}
		else if (token == WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE)
		{
			records.add(new StreamRecord(StreamRecord.Kind.NODE_DEFINITION, null, Arrays.copyOfRange(bytes, offset, offset + length)));
		}
		return offset + length;
	}

	/** Keeps the bytes of an incomplete record for the next frame of the channel. */
	private void keepPending(final int channelId, final byte[] bytes, final int offset)
	{
		if (offset < bytes.length)
		{
			pendingByChannel.put(channelId, Arrays.copyOfRange(bytes, offset, bytes.length));
		}
		else
		{
			pendingByChannel.remove(channelId);
		}
	}

	private static int streamTokenLength(final byte[] bytes, final int offset)
	{
		final var token = bytes[offset] & 0xFF;
		if (token >= WapiBinaryStreamCodec.TOKEN_NODE_NAME_BASE && token < WapiBinaryStreamCodec.TOKEN_EMPTY_STRING)
		{
			return token - WapiBinaryStreamCodec.TOKEN_NODE_NAME_BASE + 2;
		}
		return switch (token)
		{
			case WapiBinaryStreamCodec.TOKEN_CLICK, WapiBinaryStreamCodec.TOKEN_ROOT, WapiBinaryStreamCodec.TOKEN_PARENT,
					WapiBinaryStreamCodec.TOKEN_DATA_REQUEST, WapiBinaryStreamCodec.TOKEN_REQUEST_NODE_DEF,
					WapiBinaryStreamCodec.TOKEN_END_DATA -> 1;
			case WapiBinaryStreamCodec.TOKEN_STEP -> 2;
			case WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE -> WapiNodeDefinitionCodec.responseLength(bytes, offset);
			default -> valueLength(bytes, offset);
		};
	}

	private static int valueLength(final byte[] bytes, final int offset)
	{
		final var token = bytes[offset] & 0xFF;
		if (token <= 0x7F || token == WapiBinaryStreamCodec.TOKEN_EMPTY_STRING)
		{
			return 1;
		}
		if (token <= 0xBF)
		{
			return token - WapiBinaryStreamCodec.TOKEN_STRING_64_BASE + 2;
		}
		return switch (token)
		{
			// -1 while the length byte has not arrived yet
			case WapiBinaryStreamCodec.TOKEN_STRING_256_BASE -> bytes.length - offset < 2 ? -1 : (bytes[offset + 1] & 0xFF) + 3;
			case WapiBinaryStreamCodec.TOKEN_NODE_INDEX_65536_BASE, WapiBinaryStreamCodec.TOKEN_INT16 -> 3;
			case WapiBinaryStreamCodec.TOKEN_INT32, WapiBinaryStreamCodec.TOKEN_FLOAT32, WapiBinaryStreamCodec.TOKEN_RAW_FLOAT32,
					WapiBinaryStreamCodec.TOKEN_NODE_HASH -> 5;
			default -> throw new WapiException("Unsupported WAPI stream token: " + String.format("0x%02X", token), WapiError.WTOKEN);
		};
	}

	List<WapiEvent> decodeFromByteArray(final byte[] bytes)
	{
		if (bytes == null)
		{
			throw new WapiException("Event payload is required");
		}
		final var events = new ArrayList<WapiEvent>();
		var offset = 0;
		while (offset < bytes.length)
		{
			if ((bytes[offset] & 0xFF) != TOKEN_EVENT)
			{
				final int length = streamTokenLength(bytes, offset);
				if (length < 0)
				{
					throw new WapiException("Incomplete WAPI stream token");
				}
				require(bytes, offset, length);
				offset += length;
				continue;
			}
			if (bytes.length - offset < 6)
			{
				throw new WapiException("Incomplete WAPI event header");
			}
			final int hash = ByteUtil.readSignedInt(bytes, offset + 1);
			final var valueOffset = offset + 5;
			final var parsed = parseValue(bytes, valueOffset);
			final var token = registry.findOrCreateByHash(hash);
			events.add(new WapiEvent(token, parsed.value.getType(), parsed.value));
			offset = parsed.nextOffset;
		}
		return events;
	}

	private ParsedValue parseValue(final byte[] bytes, final int offset)
	{
		final var token = bytes[offset] & 0xFF;
		if (token <= 0x3F)
		{
			return new ParsedValue(WapiValue.ofInt(token), offset + 1);
		}
		if (token <= 0x7F)
		{
			return new ParsedValue(WapiValue.ofNode(token - 0x40 + 1), offset + 1);
		}
		if (token <= 0xBF)
		{
			final var length = token - WapiBinaryStreamCodec.TOKEN_STRING_64_BASE + 1;
			return stringValue(bytes, offset, length);
		}
		return switch (token)
		{
			case WapiBinaryStreamCodec.TOKEN_EMPTY_STRING -> new ParsedValue(WapiValue.ofString(""), offset + 1);
			case WapiBinaryStreamCodec.TOKEN_STRING_256_BASE ->
			{
				require(bytes, offset, 2);
				final var length = (bytes[offset + 1] & 0xFF) + 1;
				require(bytes, offset, length + 2);
				yield new ParsedValue(WapiValue.ofString(ByteUtil.toAscii(bytes, offset + 2, length)), offset + length + 2);
			}
			case WapiBinaryStreamCodec.TOKEN_NODE_INDEX_65536_BASE ->
			{
				require(bytes, offset, 3);
				// Assumed to hold index - 1, so it covers indexes 1..65536 (PDF Table 2). Not verifiable: a console
				// never sent 0xd2 in any capture, and did not handle it in requests as documented.
				yield new ParsedValue(WapiValue.ofNode(WapiBinaryStreamCodec.readWord(bytes, offset + 1) + 1), offset + 3);
			}
			case WapiBinaryStreamCodec.TOKEN_INT16 ->
			{
				require(bytes, offset, 3);
				yield new ParsedValue(WapiValue.ofInt((short) WapiBinaryStreamCodec.readWord(bytes, offset + 1)), offset + 3);
			}
			case WapiBinaryStreamCodec.TOKEN_INT32 ->
			{
				require(bytes, offset, 5);
				yield new ParsedValue(WapiValue.ofInt(WapiBinaryStreamCodec.readInt32(bytes, offset + 1)), offset + 5);
			}
			case WapiBinaryStreamCodec.TOKEN_FLOAT32, WapiBinaryStreamCodec.TOKEN_RAW_FLOAT32 ->
			{
				require(bytes, offset, 5);
				yield new ParsedValue(WapiValue.ofFloat(WapiBinaryStreamCodec.readFloat32(bytes, offset + 1)), offset + 5);
			}
			case WapiBinaryStreamCodec.TOKEN_NODE_HASH ->
			{
				require(bytes, offset, 5);
				yield new ParsedValue(WapiValue.ofNode(ByteUtil.readSignedInt(bytes, offset + 1)), offset + 5);
			}
			default -> throw new WapiException("Unsupported WAPI event value token: " + String.format("0x%02X", token), WapiError.WTOKEN);
		};
	}

	private ParsedValue stringValue(final byte[] bytes, final int offset, final int length)
	{
		require(bytes, offset, length + 1);
		final var value = ByteUtil.toAscii(bytes, offset + 1, length);
		return new ParsedValue(WapiValue.ofString(value), offset + length + 1);
	}

	private static void require(final byte[] bytes, final int offset, final int length)
	{
		if (offset < 0 || length < 0 || offset > bytes.length - length)
		{
			throw new WapiException("Incomplete WAPI event value");
		}
	}

	/** A decoded value and the offset of the token after it. */
	private record ParsedValue(WapiValue value, int nextOffset)
	{
	}

	/**
	 * One complete record of the stream: an event, a node definition or an end-of-data marker, with
	 * its raw bytes. Two records are equal when their kind, event and bytes are equal.
	 *
	 * @param kind the kind of record
	 * @param event decoded event, or {@code null} when this is not an event
	 * @param rawBytes the whole event, node definition or end-of-data byte; copied in and out
	 */
	public record StreamRecord(Kind kind, WapiEvent event, byte[] rawBytes)
	{
		/** The kind of record. */
		public enum Kind
		{
			/** A {@code 0xd7}/hash/value record. */
			EVENT,
			/** An end-of-data token, {@code 0xde}. */
			END_OF_DATA,
			/** A node definition response, {@code 0xdf}. */
			NODE_DEFINITION
		}

		private static final StreamRecord END_OF_DATA = new StreamRecord(Kind.END_OF_DATA, null, new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_END_DATA });

		/**
		 * Copies the raw bytes in.
		 *
		 * @param kind the kind of record
		 * @param event decoded event, or {@code null} when this is not an event
		 * @param rawBytes the whole event, node definition or end-of-data byte; copied in and out
		 * @throws NullPointerException if {@code rawBytes} is {@code null}
		 */
		public StreamRecord
		{
			rawBytes = rawBytes.clone();
		}

		/** {@return whether this is a {@code 0xd7}/hash/value event} */
		public boolean isEvent()
		{
			return kind == Kind.EVENT;
		}

		/** {@return whether this is an end-of-data marker} */
		public boolean isEndOfData()
		{
			return kind == Kind.END_OF_DATA;
		}

		/** {@return whether this is a node-definition response ({@code 0xdf})} */
		public boolean isNodeDefinition()
		{
			return kind == Kind.NODE_DEFINITION;
		}

		/** {@return a copy of the raw bytes} */
		@Override
		public byte[] rawBytes()
		{
			return rawBytes.clone();
		}

		@Override
		public boolean equals(final Object other)
		{
			return other instanceof StreamRecord streamRecord && kind == streamRecord.kind && Objects.equals(event, streamRecord.event)
					&& Arrays.equals(rawBytes, streamRecord.rawBytes);
		}

		@Override
		public int hashCode()
		{
			return 31 * Objects.hash(kind, event) + Arrays.hashCode(rawBytes);
		}

		@Override
		public String toString()
		{
			return "StreamRecord[" + kind + ", " + WapiBinaryDataCodec.encodeHex(rawBytes) + "]";
		}
	}
}
