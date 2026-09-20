package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WapiBinaryDataCodecTest
{
	@Test
	void decodesSpacedHexBinaryData()
	{
		assertArrayEquals(new byte[] { (byte) 0xD7, 0x01, (byte) 0xDE },
				WapiBinaryDataCodec.decodeHex("d7 01 de", 8));
	}

	@Test
	void encodesBytesAsLowerCaseHex()
	{
		assertEquals("00df7f80ff", WapiBinaryDataCodec.encodeHex(new byte[] { 0, (byte) 0xDF, 0x7F, (byte) 0x80, (byte) 0xFF }));
		assertEquals("", WapiBinaryDataCodec.encodeHex(new byte[0]));
	}
}
