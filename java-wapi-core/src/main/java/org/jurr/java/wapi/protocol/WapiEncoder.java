package org.jurr.java.wapi.protocol;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiTokenValue;
import org.jurr.java.wapi.WapiType;
import org.jurr.java.wapi.WapiValue;

/** Encodes WAPI values and operations into protocol payload bytes. */
public class WapiEncoder
{
	/** Creates an encoder; it keeps no state. */
	public WapiEncoder()
	{
	}

	/**
	 * Encodes a native token write using the integer value token.
	 *
	 * @param token target token; must not be {@code null}
	 * @param value signed 32-bit value
	 * @return native token/hash/value bytes in network byte order
	 * @throws WapiException if token is {@code null}
	 */
	public byte[] encodeNativeSetInt(final WapiToken token, final int value)
	{
		if (token == null)
		{
			throw new WapiException("Token is required");
		}
		final byte[] tokenHash = token.hashBytes();
		final byte[] valueBytes = ByteUtil.toBytes(value);
		return new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, valueBytes[0], valueBytes[1], valueBytes[2], valueBytes[3]
		};
	}

	/**
	 * Encodes a native request for the current value of a token.
	 *
	 * @param token token to request; must not be {@code null}
	 * @return native token/hash/data-request bytes
	 * @throws WapiException if token is {@code null}
	 */
	public byte[] encodeNativeGet(final WapiToken token)
	{
		if (token == null)
		{
			throw new WapiException("Token is required");
		}
		final byte[] hash = token.hashBytes();
		return new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3], (byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST };
	}

	/**
	 * Encodes a native request for node definitions: {@code 0xd7 <hash> 0xdd}. The console answers a
	 * node with the definitions of its children, and a parameter with its own definition. A node's
	 * hash is that of its full name, so no navigation from the root is needed (captured for
	 * {@code ch.1.flt}: the same reply as {@code 0xda 0xd7 <ch> 0xd7 <ch.1> 0xd7 <ch.1.flt> 0xdd}).
	 *
	 * @param token node or parameter; must not be {@code null}
	 * @return native token/hash/definition-request bytes
	 * @throws WapiException if token is {@code null}
	 */
	public byte[] encodeNativeDefinitionRequest(final WapiToken token)
	{
		if (token == null)
		{
			throw new WapiException("Token is required");
		}
		final byte[] hash = token.hashBytes();
		return new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3], (byte) WapiBinaryStreamCodec.TOKEN_REQUEST_NODE_DEF };
	}

	/**
	 * Encodes a native request for the definitions of the top-level nodes: {@code 0xda 0xdd}. The
	 * root has no hash of its own.
	 *
	 * @return root/definition-request bytes
	 */
	public byte[] encodeRootDefinitionRequest()
	{
		return new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_ROOT, (byte) WapiBinaryStreamCodec.TOKEN_REQUEST_NODE_DEF };
	}

	/**
	 * Encodes a native token write using the float value token.
	 *
	 * @param token target token; must not be {@code null}
	 * @param value 32-bit float to encode
	 * @return native token/hash/float bytes in network byte order
	 * @throws WapiException if token is {@code null}
	 */
	public byte[] encodeNativeSetFloat(final WapiToken token, final float value)
	{
		if (token == null)
		{
			throw new WapiException("Token is required");
		}
		final int bits = Float.floatToIntBits(value);
		final byte[] tokenHash = token.hashBytes();
		final byte[] valueBytes = ByteUtil.toBytes(bits);
		return new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_FLOAT32, valueBytes[0], valueBytes[1], valueBytes[2], valueBytes[3]
		};
	}

	/**
	 * Encodes a native token write using the WAPI byte-string representation.
	 * Text is converted to US-ASCII; encoded values are limited to 256 bytes.
	 *
	 * @param token target token; must not be {@code null}
	 * @param value non-null string, at most 256 US-ASCII bytes after encoding
	 * @return native token/hash/string bytes, using the empty-string token when empty
	 * @throws WapiException if either argument is null or the encoded string exceeds 256 bytes
	 */
	public byte[] encodeNativeSetString(final WapiToken token, final String value)
	{
		if (token == null || value == null)
		{
			throw new WapiException("Token and value are required");
		}
		final byte[] tokenHash = token.hashBytes();
		final byte[] stringBytes = ByteUtil.toAsciiBytes(value);
		if (stringBytes.length == 0)
		{
			return new byte[] {
					(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3], (byte) WapiBinaryStreamCodec.TOKEN_EMPTY_STRING
			};
		}
		if (stringBytes.length > 256)
		{
			throw new WapiException("WAPI string value exceeds 256 bytes");
		}
		final var payload = new byte[7 + stringBytes.length];
		payload[0] = (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH;
		payload[1] = tokenHash[0];
		payload[2] = tokenHash[1];
		payload[3] = tokenHash[2];
		payload[4] = tokenHash[3];
		payload[5] = (byte) WapiBinaryStreamCodec.TOKEN_STRING_256_BASE;
		payload[6] = (byte) (stringBytes.length - 1);
		System.arraycopy(stringBytes, 0, payload, 7, stringBytes.length);
		return payload;
	}

	/**
	 * Encodes a native toggle operation for a token.
	 *
	 * @param token target token; must not be {@code null}
	 * @return native token/hash/click bytes
	 * @throws WapiException if token is {@code null}
	 */
	public byte[] encodeNativeToggle(final WapiToken token)
	{
		if (token == null)
		{
			throw new WapiException("Token is required");
		}
		final byte[] tokenHash = token.hashBytes();
		return new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3], (byte) WapiBinaryStreamCodec.TOKEN_CLICK
		};
	}

	/**
	 * Encodes a native step operation for a token.
	 *
	 * @param token target token; must not be {@code null}
	 * @param value one-byte step value
	 * @return native token/hash/step bytes
	 * @throws WapiException if token is {@code null}
	 */
	public byte[] encodeNativeStep(final WapiToken token, final byte value)
	{
		if (token == null)
		{
			throw new WapiException("Token is required");
		}
		final byte[] tokenHash = token.hashBytes();
		return new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3], (byte) WapiBinaryStreamCodec.TOKEN_STEP, value
		};
	}

	/**
	 * Encodes a renewal record for a meter request.
	 *
	 * @param requestId meter request identifier encoded as four bytes
	 * @return unframed native meter renewal payload; the caller must frame it on the meter channel
	 */
	public byte[] encodeMeterRenewal(final int requestId)
	{
		final byte[] requestIdBytes = ByteUtil.toBytes(requestId);
		return new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_INT32,
				requestIdBytes[0], requestIdBytes[1], requestIdBytes[2], requestIdBytes[3]
		};
	}

	/**
	 * Encodes token/value pairs as one native node update terminated by end-data.
	 *
	 * @param values non-null list of non-null pairs with {@code I32}, {@code F32}, or {@code S32} values
	 * @return encoded node update; an empty list produces only the end-data token
	 * @throws WapiException if the list is null or contains an unsupported value type
	 */
	public byte[] encodeNativeNodeValues(final List<WapiTokenValue> values)
	{
		if (values == null)
		{
			throw new WapiException("Token values are required");
		}
		final var output = new ByteArrayOutputStream();
		for (final var entry : values)
		{
			final var token = entry.token();
			final var value = entry.value();
			output.write(WapiBinaryStreamCodec.TOKEN_NODE_HASH);
			writeInt32(output, token.hash());
			appendNativeValue(output, value);
		}
		output.write(WapiBinaryStreamCodec.TOKEN_END_DATA);
		return output.toByteArray();
	}

	private static void appendNativeValue(final ByteArrayOutputStream output, final WapiValue value)
	{
		if (value.getType() == WapiType.I32)
		{
			output.write(WapiBinaryStreamCodec.TOKEN_INT32);
			writeInt32(output, value.getIntValue());
		}
		else if (value.getType() == WapiType.F32)
		{
			output.write(WapiBinaryStreamCodec.TOKEN_FLOAT32);
			writeInt32(output, Float.floatToIntBits(value.getFloatValue()));
		}
		else if (value.getType() == WapiType.S32)
		{
			final byte[] bytes = ByteUtil.toAsciiBytes(value.getStringValue());
			if (bytes.length == 0)
			{
				output.write(WapiBinaryStreamCodec.TOKEN_EMPTY_STRING);
			}
			else
			{
				output.write(WapiBinaryStreamCodec.TOKEN_STRING_256_BASE);
				output.write(bytes.length - 1);
				output.write(bytes, 0, bytes.length);
			}
		}
		else
		{
			throw new WapiException("Unsupported node array value type: " + value.getType());
		}
	}

	private static void writeInt32(final ByteArrayOutputStream output, final int value)
	{
		output.write(value >>> 24 & 0xFF);
		output.write(value >>> 16 & 0xFF);
		output.write(value >>> 8 & 0xFF);
		output.write(value & 0xFF);
	}
}
