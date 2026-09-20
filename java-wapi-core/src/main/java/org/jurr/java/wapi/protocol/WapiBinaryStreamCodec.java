package org.jurr.java.wapi.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Constants and primitive helpers for the WING binary token stream. */
public final class WapiBinaryStreamCodec
{
	/** false; off; 0 (PDF Table 2). */
	public static final int TOKEN_FALSE = 0x00;
	/** true; on; 1 (PDF Table 2). */
	public static final int TOKEN_TRUE = 0x01;
	/** First of the tokens {@code 0x02..0x3f}, which are the integers 2 to 63 themselves (PDF Table 2). */
	public static final int TOKEN_INT_BASE = 0x02;
	/** First of the tokens {@code 0x40..0x7f}: node index 1 to 64 (PDF Table 2). */
	public static final int TOKEN_NODE_INDEX_64_BASE = 0x40;
	/** First of the tokens {@code 0x80..0xbf}: a string of 1 to 64 bytes, the length being token - {@code 0x80} + 1 (PDF Table 2). */
	public static final int TOKEN_STRING_64_BASE = 0x80;
	/** First of the tokens {@code 0xc0..0xcf}: a node name of 1 to 16 bytes, the length being token - {@code 0xc0} + 1 (PDF Table 2). */
	public static final int TOKEN_NODE_NAME_BASE = 0xC0;
	/** Empty string (PDF Table 2). */
	public static final int TOKEN_EMPTY_STRING = 0xD0;
	/**
	 * String of 1 to 256 bytes, after a length byte (PDF Table 2). The PDF does not say how the byte
	 * holds 1 to 256; the decoder reads it as the length - 1.
	 */
	public static final int TOKEN_STRING_256_BASE = 0xD1;
	/** Node index 1 to 65536, followed by a word (PDF Table 2). */
	public static final int TOKEN_NODE_INDEX_65536_BASE = 0xD2;
	/** 16-bit integer, followed by a word (PDF Table 2). */
	public static final int TOKEN_INT16 = 0xD3;
	/** 32-bit integer, followed by a long (PDF Table 2). */
	public static final int TOKEN_INT32 = 0xD4;
	/** 32-bit float, followed by a long (PDF Table 2). */
	public static final int TOKEN_FLOAT32 = 0xD5;
	/** Raw 32-bit float from 0.0 to 1.0, followed by a long (PDF Table 2). */
	public static final int TOKEN_RAW_FLOAT32 = 0xD6;
	/** Node hash, followed by a long (PDF Table 2). */
	public static final int TOKEN_NODE_HASH = 0xD7;
	/** Click (toggle) (PDF Table 2). */
	public static final int TOKEN_CLICK = 0xD8;
	/** Step (increment or decrement), followed by a byte (PDF Table 2). */
	public static final int TOKEN_STEP = 0xD9;
	/** Node tree: go to the root node (PDF Table 2). */
	public static final int TOKEN_ROOT = 0xDA;
	/** Node tree: one level up (PDF Table 2). */
	public static final int TOKEN_PARENT = 0xDB;
	/** Data request (PDF Table 2). */
	public static final int TOKEN_DATA_REQUEST = 0xDC;
	/** Request the node definition of the current node (PDF Table 2). */
	public static final int TOKEN_REQUEST_NODE_DEF = 0xDD;
	/** End of data or of a definition request (PDF Table 2). */
	public static final int TOKEN_END_DATA = 0xDE;
	/** Node definition response, followed by a word with the data length in bytes (PDF Table 2). */
	public static final int TOKEN_NODE_DEF_RESPONSE = 0xDF;

	private WapiBinaryStreamCodec()
	{
	}

	/**
	 * {@return {@link #TOKEN_TRUE} or {@link #TOKEN_FALSE}}
	 *
	 * @param value boolean value to encode
	 */
	public static int encodeBoolean(final boolean value)
	{
		return value ? TOKEN_TRUE : TOKEN_FALSE;
	}

	/**
	 * Encodes a small integer represented directly by its token byte.
	 *
	 * @param value integer from 2 through 63
	 * @return the token value, unchanged
	 * @throws IllegalArgumentException if the value is outside 2..63
	 */
	public static int encodeIntValue(final int value)
	{
		if (value < 2 || value > 0x3F)
		{
			throw new IllegalArgumentException("Int token range is 2..63");
		}
		return value;
	}

	/**
	 * Encodes a one-based node index as a node-index token.
	 *
	 * @param value node index from 1 through 64
	 * @return encoded token byte as an integer
	 * @throws IllegalArgumentException if the index is outside 1..64
	 */
	public static int encodeNodeIndex(final int value)
	{
		if (value < 1 || value > 64)
		{
			throw new IllegalArgumentException("Node index range is 1..64");
		}
		return TOKEN_NODE_INDEX_64_BASE + value - 1;
	}

	/**
	 * Decodes a node-index token to its one-based index.
	 *
	 * @param token token byte in the node-index range
	 * @return decoded index from 1 through 64
	 * @throws IllegalArgumentException if the byte is not a node-index token
	 */
	public static int decodeNodeIndex(final byte token)
	{
		final var value = token & 0xFF;
		if (value < TOKEN_NODE_INDEX_64_BASE || value > TOKEN_NODE_INDEX_64_BASE + 63)
		{
			throw new IllegalArgumentException("Not a node index token: " + String.format("0x%02X", value));
		}
		return value - TOKEN_NODE_INDEX_64_BASE + 1;
	}

	/** {@return one-byte request for node-definition data} */
	public static byte[] encodeNodeDefinitionRequest()
	{
		return new byte[] { (byte) TOKEN_REQUEST_NODE_DEF };
	}

	/**
	 * Reads an unsigned 16-bit big-endian word.
	 *
	 * @param bytes source array with at least two bytes from {@code offset}
	 * @param offset starting index
	 * @return unsigned value from 0 through 65535
	 */
	public static int readWord(final byte[] bytes, final int offset)
	{
		return ByteUtil.readUnsignedShort(bytes, offset);
	}

	/**
	 * Reads a signed 32-bit big-endian integer.
	 *
	 * @param bytes source array with at least four bytes from {@code offset}
	 * @param offset starting index
	 * @return decoded signed integer
	 */
	public static int readInt32(final byte[] bytes, final int offset)
	{
		return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
	}

	/**
	 * Reads a 32-bit IEEE-754 big-endian float.
	 *
	 * @param bytes source array with at least four bytes from {@code offset}
	 * @param offset starting index
	 * @return decoded float
	 */
	public static float readFloat32(final byte[] bytes, final int offset)
	{
		return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getFloat();
	}
}
