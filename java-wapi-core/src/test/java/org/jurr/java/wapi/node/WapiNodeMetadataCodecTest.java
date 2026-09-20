package org.jurr.java.wapi.node;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.junit.jupiter.api.Test;

class WapiNodeMetadataCodecTest
{
	@Test
	void parsesIntegerRangeMetadata()
	{
		// df 001f 00000000 00000001 0001 01 49 00 0040 00000005 0000000a
		final var response = new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, 0, 0x1F,
				0, 0, 0, 0, 0, 0, 0, 1, 0, 1,
				1, 'I', 0, 0x00, 0x40,
				0, 0, 0, 5, 0, 0, 0, 10
		};

		final var definition = WapiNodeDefinitionCodec.parse(response);
		assertEquals(WapiNodeKind.INTEGER, definition.nodeKind());
		assertEquals(Integer.valueOf(5), definition.metadata().minInt());
		assertEquals(Integer.valueOf(10), definition.metadata().maxInt());
	}

	@Test
	void parsesStringMetadata()
	{
		// df 001c 00000001 00000002 0003 04 41424344 05 48454c4c4f 0070 0010
		final var response = new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, 0x00, 0x1C,
				0x00, 0x00, 0x00, 0x01,
				0x00, 0x00, 0x00, 0x02,
				0x00, 0x03,
				0x04, 'A', 'B', 'C', 'D',
				0x05, 'H', 'E', 'L', 'L', 'O',
				0x00, 0x70, 0x00, 0x10
		};

		final var definition = WapiNodeDefinitionCodec.parse(response);
		assertEquals(WapiNodeKind.STRING, definition.nodeKind());
		assertEquals(Integer.valueOf(16), definition.metadata().maxStringLength());
	}
}
