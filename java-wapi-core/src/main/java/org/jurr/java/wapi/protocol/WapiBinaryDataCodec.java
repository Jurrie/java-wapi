package org.jurr.java.wapi.protocol;

import java.util.HexFormat;

import org.jurr.java.wapi.WapiException;

/** Converts between bytes and hexadecimal text, for protocol captures and messages. */
public final class WapiBinaryDataCodec
{
	private WapiBinaryDataCodec()
	{
	}

	/**
	 * Decodes a whitespace-tolerant hexadecimal string into bytes.
	 *
	 * @param value non-null string containing an even number of hexadecimal digits after whitespace is removed
	 * @param maxLength non-negative maximum decoded byte count; input larger than this is rejected, not truncated
	 * @return decoded bytes, including an empty array for an empty/whitespace-only input
	 * @throws WapiException if the input is null, malformed, odd-length, or exceeds {@code maxLength}
	 */
	public static byte[] decodeHex(final String value, final int maxLength)
	{
		if (value == null || maxLength < 0)
		{
			throw new WapiException("Invalid binary data input");
		}
		final var hex = value.replaceAll("\\s+", "");
		if ((hex.length() & 1) != 0 || hex.length() / 2 > maxLength)
		{
			throw new WapiException("Invalid binary data length");
		}
		try
		{
			return HexFormat.of().parseHex(hex);
		}
		catch (final IllegalArgumentException e)
		{
			throw new WapiException("Invalid hexadecimal binary data", e);
		}
	}

	/**
	 * Encodes bytes as lower-case hexadecimal text without separators, the form
	 * {@link #decodeHex(String, int)} reads.
	 *
	 * @param bytes non-null bytes
	 * @return two hexadecimal digits per byte
	 */
	public static String encodeHex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}
}
