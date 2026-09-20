package org.jurr.java.wapi.node;

import java.util.ArrayList;
import java.util.Arrays;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiValue;
import org.jurr.java.wapi.protocol.ByteUtil;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;

/** Parses node definition responses, {@code 0xdf} (PDF "Node Definition Response"). */
public final class WapiNodeDefinitionCodec
{
	private WapiNodeDefinitionCodec()
	{
	}

	/**
	 * Parses a node-definition response, including recognized metadata when enough
	 * bytes are present. Unavailable or truncated optional metadata is returned as null.
	 *
	 * @param payload response beginning with {@link WapiBinaryStreamCodec#TOKEN_NODE_DEF_RESPONSE}
	 * @return decoded node definition
	 * @throws WapiException if the payload is null, too short, or has the wrong response token; with
	 *             {@link WapiError#WNODE} if a field up to and including the flags runs past the end
	 */
	public static WapiNodeDefinition parse(final byte[] payload)
	{
		if (payload == null || payload.length < 3)
		{
			throw new WapiException("Node definition payload is too short");
		}

		var offset = 0;
		final var token = payload[offset++] & 0xFF;
		if (token != WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE)
		{
			throw new WapiException("Payload is not a node definition response");
		}

		final int lenWord = ByteUtil.readUnsignedShort(payload, offset);
		offset += 2;

		int totalLength = lenWord;
		if (lenWord == 0)
		{
			require(payload, offset, 4);
			totalLength = ByteUtil.readSignedInt(payload, offset);
			offset += 4;
		}

		require(payload, offset, 4 + 4 + 2 + 1);
		final long parentHash = ByteUtil.readUnsignedInt(payload, offset);
		offset += 4;

		final long hash = ByteUtil.readUnsignedInt(payload, offset);
		offset += 4;

		final int index = ByteUtil.readUnsignedShort(payload, offset);
		offset += 2;

		final var nameLength = payload[offset++] & 0xFF;
		require(payload, offset, nameLength + 1);
		final var name = ByteUtil.toAscii(payload, offset, nameLength);
		offset += nameLength;

		final var longNameLength = payload[offset++] & 0xFF;
		require(payload, offset, longNameLength + 2);
		final var longName = ByteUtil.toAscii(payload, offset, longNameLength);
		offset += longNameLength;

		final int flags = ByteUtil.readUnsignedShort(payload, offset);
		offset += 2;

		final var metadata = parseMetadata(payload, offset, WapiNodeKind.fromFlags(flags));

		final int responseLength = responseLength(payload, 0);
		final byte[] rawBytes = Arrays.copyOf(payload, responseLength < 0 ? payload.length : Math.min(responseLength, payload.length));
		return new WapiNodeDefinition(totalLength, parentHash, hash, index, name, longName, flags, metadata, rawBytes);
	}

	private static void require(final byte[] payload, final int offset, final int length)
	{
		if (offset > payload.length - length)
		{
			throw new WapiException("Node definition is truncated or malformed", WapiError.WNODE);
		}
	}

	/**
	 * Determines the total length of a node-definition response from its length fields:
	 * {@code 3 + len.w}, or {@code 7 + len.l} when {@code len.w} is zero. This reading of the
	 * PDF layout ("Node Definition Response") is not yet verified against a console.
	 *
	 * @param bytes source bytes
	 * @param offset index of the {@link WapiBinaryStreamCodec#TOKEN_NODE_DEF_RESPONSE} token
	 * @return total response length including the token and length fields, or -1 when the
	 *         length fields are not complete yet
	 * @throws WapiException if the response is longer than an array can hold
	 */
	public static int responseLength(final byte[] bytes, final int offset)
	{
		final var remaining = bytes.length - offset;
		if (remaining < 3)
		{
			return -1;
		}
		final int shortLength = ByteUtil.readUnsignedShort(bytes, offset + 1);
		if (shortLength != 0)
		{
			return 3 + shortLength;
		}
		if (remaining < 7)
		{
			return -1;
		}
		final long totalLength = 7 + ByteUtil.readUnsignedInt(bytes, offset + 3);
		if (totalLength > Integer.MAX_VALUE)
		{
			throw new WapiException("Node definition response is too large");
		}
		return (int) totalLength;
	}

	private static WapiNodeMetadata parseMetadata(final byte[] payload, final int offset, final WapiNodeKind kind)
	{
		try
		{
			if (kind == WapiNodeKind.LIN_FLOAT || kind == WapiNodeKind.LOG_FLOAT)
			{
				return new WapiNodeMetadata(readFloat(payload, offset), readFloat(payload, offset + 4),
						ByteUtil.readUnsignedInt(payload, offset + 8), null, null, null, new ArrayList<>());
			}
			if (kind == WapiNodeKind.INTEGER)
			{
				return new WapiNodeMetadata(null, null, null, ByteUtil.readSignedInt(payload, offset),
						ByteUtil.readSignedInt(payload, offset + 4), null, new ArrayList<>());
			}
			if (kind == WapiNodeKind.STRING)
			{
				return new WapiNodeMetadata(null, null, null, null, null,
						ByteUtil.readUnsignedShort(payload, offset), new ArrayList<>());
			}
			if (kind == WapiNodeKind.STRING_ENUM)
			{
				// count.w, [itemlen.b, item, longitemlen.b, longitem] * count
				final int count = ByteUtil.readUnsignedShort(payload, offset);
				var cursor = offset + 2;
				final var items = new ArrayList<WapiEnumItem>();
				for (var i = 0; i < count; i++)
				{
					final var length = payload[cursor++] & 0xFF;
					final var item = ByteUtil.toAscii(payload, cursor, length);
					cursor += length;
					final var longLength = payload[cursor++] & 0xFF;
					items.add(new WapiEnumItem(WapiValue.ofString(item), ByteUtil.toAscii(payload, cursor, longLength)));
					cursor += longLength;
				}
				return new WapiNodeMetadata(null, null, null, null, null, null, items);
			}
			if (kind == WapiNodeKind.FLOAT_ENUM)
			{
				// count.w, [itemval.f, longitemlen.b, longitem] * count
				final int count = ByteUtil.readUnsignedShort(payload, offset);
				var cursor = offset + 2;
				final var items = new ArrayList<WapiEnumItem>();
				for (var i = 0; i < count; i++)
				{
					final float value = readFloat(payload, cursor);
					cursor += 4;
					final var longLength = payload[cursor++] & 0xFF;
					items.add(new WapiEnumItem(WapiValue.ofFloat(value), ByteUtil.toAscii(payload, cursor, longLength)));
					cursor += longLength;
				}
				return new WapiNodeMetadata(null, null, null, null, null, null, items);
			}
		}
		catch (final RuntimeException ignored)
		{
			// Some definitions end immediately after the flags field.
		}
		return null;
	}

	private static float readFloat(final byte[] bytes, final int offset)
	{
		return WapiBinaryStreamCodec.readFloat32(bytes, offset);
	}
}
