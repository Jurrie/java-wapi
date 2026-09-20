package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ByteUtilTest
{
	@Test
	void readsSignedShortAtOffset()
	{
		final var bytes = new byte[] { 0x55, (byte) 0x80, 0x01, 0x66 };

		assertEquals(-32767, ByteUtil.readSignedShort(bytes, 1));
		assertEquals(0x5580, ByteUtil.readSignedShort(bytes, 0) & 0xFFFF);
	}

	@Test
	void readsUnsignedShortAtOffset()
	{
		final var bytes = new byte[] { 0x55, (byte) 0xFE, (byte) 0xDC, 0x66 };

		assertEquals(0xFEDC, ByteUtil.readUnsignedShort(bytes, 1));
		assertEquals(0x55FE, ByteUtil.readUnsignedShort(bytes, 0));
	}

	@Test
	void readsUnsignedIntWithoutSignExtension()
	{
		final var bytes = new byte[] { 0x12, (byte) 0xFE, (byte) 0xDC, (byte) 0xBA, (byte) 0x98, 0x34 };

		assertEquals(0xFEDCBA98L, ByteUtil.readUnsignedInt(bytes, 1));
	}

	@Test
	void readsSignedIntPreservingItsBitPattern()
	{
		assertEquals(0xFEDCBA98, ByteUtil.readSignedInt(new byte[] { 0x12, (byte) 0xFE, (byte) 0xDC, (byte) 0xBA, (byte) 0x98, 0x34 }, 1));
		assertEquals(0x12345678, ByteUtil.readSignedInt(new byte[] { 0x12, 0x34, 0x56, 0x78 }, 0));
	}

	@Test
	void encodesShortAndIntInBigEndianOrder()
	{
		assertArrayEquals(new byte[] { (byte) 0xFE, (byte) 0xDC }, ByteUtil.toBytes((short) 0xFEDC));
		assertArrayEquals(new byte[] { (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF }, ByteUtil.toBytes(0x89ABCDEF));
		assertEquals((short) 0xFEDC, ByteUtil.readSignedShort(ByteUtil.toBytes((short) 0xFEDC), 0));
		assertEquals(0x89ABCDEF, ByteUtil.readSignedInt(ByteUtil.toBytes(0x89ABCDEF), 0));
	}

	@Test
	void decodesAsciiUntilFirstZeroByte()
	{
		assertEquals("WING", ByteUtil.toAscii(new byte[] { 'W', 'I', 'N', 'G', 0, 'X' }));
		assertEquals("WING", ByteUtil.toAscii(new byte[] { 'W', 'I', 'N', 'G' }));
		assertEquals("", ByteUtil.toAscii(new byte[] { 0, 'X' }));
	}

	@Test
	void decodesAsciiWithinOffsetAndLength()
	{
		final var bytes = new byte[] { 'X', 'W', 'I', 'N', 'G', 0, 'Y' };

		assertEquals("WING", ByteUtil.toAscii(bytes, 1, 6));
		assertEquals("ING", ByteUtil.toAscii(bytes, 2, 3));
		assertEquals("", ByteUtil.toAscii(bytes, 5, 2));
	}

	@Test
	void encodesStringAsUsAsciiBytes()
	{
		assertArrayEquals(new byte[] { 'W', 'I', 'N', 'G' }, ByteUtil.toAsciiBytes("WING"));
		assertArrayEquals(new byte[0], ByteUtil.toAsciiBytes(""));
		assertArrayEquals(new byte[] { '?' }, ByteUtil.toAsciiBytes("\u00E9"));
	}
}