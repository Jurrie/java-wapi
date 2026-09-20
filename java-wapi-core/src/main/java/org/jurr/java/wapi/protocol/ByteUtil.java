package org.jurr.java.wapi.protocol;

import java.nio.charset.StandardCharsets;

/** Big-endian integer and ASCII conversion helpers used by WAPI codecs. */
public final class ByteUtil
{
	private ByteUtil()
	{
	}

	/**
	 * Reads a signed big-endian 16-bit value.
	 *
	 * @param data source with at least two bytes starting at {@code offset}
	 * @param offset starting array index
	 * @return value from -32768 through 32767
	 */
	public static short readSignedShort(final byte[] data, final int offset)
	{
		return (short) ((data[offset] & 0xFF) << 8
				| data[offset + 1] & 0xFF);
	}

	/**
	 * Reads an unsigned big-endian 16-bit value.
	 *
	 * @param data source with at least two bytes starting at {@code offset}
	 * @param offset starting array index
	 * @return value from 0 through 65535
	 */
	public static int readUnsignedShort(final byte[] data, final int offset)
	{
		return (data[offset] & 0xFF) << 8
				| data[offset + 1] & 0xFF;
	}

	/**
	 * Reads an unsigned big-endian 32-bit value.
	 *
	 * @param data source with at least four bytes starting at {@code offset}
	 * @param offset starting array index
	 * @return value from 0 through 4294967295
	 */
	public static long readUnsignedInt(final byte[] data, final int offset)
	{
		return (data[offset] & 0xFFL) << 24
				| (data[offset + 1] & 0xFFL) << 16
				| (data[offset + 2] & 0xFFL) << 8
				| data[offset + 3] & 0xFFL;
	}

	/**
	 * Reads four bytes as an unsigned big-endian value represented in an int.
	 * The returned int preserves the bit pattern and may be negative.
	 *
	 * @param data source with at least four bytes starting at {@code offset}
	 * @param offset starting array index
	 * @return four-byte value with its original 32-bit bit pattern
	 */
	public static int readSignedInt(final byte[] data, final int offset)
	{
		return (data[offset] & 0xFF) << 24
				| (data[offset + 1] & 0xFF) << 16
				| (data[offset + 2] & 0xFF) << 8
				| data[offset + 3] & 0xFF;
	}

	/**
	 * {@return four bytes in big-endian order}
	 *
	 * @param value 32-bit value
	 */
	public static byte[] toBytes(final int value)
	{
		return new byte[] {
				(byte) (value >>> 24 & 0xFF),
				(byte) (value >>> 16 & 0xFF),
				(byte) (value >>> 8 & 0xFF),
				(byte) (value & 0xFF)
		};
	}

	/**
	 * {@return two bytes in big-endian order}
	 *
	 * @param value 16-bit value
	 */
	public static byte[] toBytes(final short value)
	{
		return new byte[] {
				(byte) (value >>> 8 & 0xFF),
				(byte) (value & 0xFF)
		};
	}

	/**
	 * Decodes bytes as US-ASCII up to the first zero byte, if present.
	 *
	 * @param bytes non-null source bytes
	 * @return decoded US-ASCII prefix
	 */
	public static String toAscii(final byte[] bytes)
	{
		var length = 0;
		while (length < bytes.length && bytes[length] != 0)
		{
			length++;
		}
		return new String(bytes, 0, length, StandardCharsets.US_ASCII);
	}

	/**
	 * Decodes a range of bytes as US-ASCII, up to the first zero byte in that range, if present.
	 *
	 * @param bytes non-null source bytes
	 * @param offset index of the first byte
	 * @param length number of bytes at most
	 * @return decoded US-ASCII text
	 */
	public static String toAscii(final byte[] bytes, final int offset, final int length)
	{
		var actualLength = 0;
		while (actualLength < length && bytes[offset + actualLength] != 0)
		{
			actualLength++;
		}
		return new String(bytes, offset, actualLength, StandardCharsets.US_ASCII);
	}

	/**
	 * Encodes text as US-ASCII; a character outside US-ASCII becomes {@code ?}.
	 *
	 * @param str non-null text
	 * @return the encoded bytes
	 */
	public static byte[] toAsciiBytes(final String str)
	{
		return str.getBytes(StandardCharsets.US_ASCII);
	}
}
