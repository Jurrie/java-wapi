package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.jurr.java.wapi.node.WapiNodeDefinitionCodec;
import org.jurr.java.wapi.node.WapiNodeKind;
import org.junit.jupiter.api.Test;

class WapiBinaryStreamCodecTest
{

	@Test
	void encodesBooleanAndIntegerTokens()
	{
		assertEquals(0x00, WapiBinaryStreamCodec.encodeBoolean(false));
		assertEquals(0x01, WapiBinaryStreamCodec.encodeBoolean(true));
		assertEquals(0x2A, WapiBinaryStreamCodec.encodeIntValue(42));
	}

	@Test
	void encodesAndDecodesNodeIndexTokens()
	{
		assertEquals(0x42, WapiBinaryStreamCodec.encodeNodeIndex(3));
		assertEquals(3, WapiBinaryStreamCodec.decodeNodeIndex((byte) 0x42));
	}

	@Test
	void parsesNodeDefinitionResponseFrame()
	{
		// df 0017 00000001 00000002 0003 04 41424344 05 48454c4c4f 0001
		final var response = new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, 0x00, 0x17,
				0x00, 0x00, 0x00, 0x01,
				0x00, 0x00, 0x00, 0x02,
				0x00, 0x03,
				0x04, 'A', 'B', 'C', 'D',
				0x05, 'H', 'E', 'L', 'L', 'O',
				0x00, 0x01
		};

		final var definition = WapiNodeDefinitionCodec.parse(response);
		assertEquals(1L, definition.parentHash());
		assertEquals(2L, definition.hash());
		assertEquals(3, definition.index());
		assertEquals("ABCD", definition.name());
		assertEquals("HELLO", definition.longName());
		assertEquals(1, definition.flags());
		assertEquals(WapiNodeKind.NODE, definition.nodeKind());
		assertEquals(1, definition.unitCode());
		assertFalse(definition.isReadOnly());
	}
}
