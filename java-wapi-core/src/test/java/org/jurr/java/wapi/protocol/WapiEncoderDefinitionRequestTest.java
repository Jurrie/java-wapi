package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.jurr.java.wapi.WapiToken;
import org.junit.jupiter.api.Test;

class WapiEncoderDefinitionRequestTest
{
	@Test
	void requestsDefinitionsOfNodeByItsHash()
	{
		// Captured: d7 ea0b9382 dd (ch.1.flt) gives the same reply as da d7 <ch> d7 <ch.1> d7 <ch.1.flt> dd
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, (byte) 0xEA, 0x0B, (byte) 0x93, (byte) 0x82, (byte) WapiBinaryStreamCodec.TOKEN_REQUEST_NODE_DEF
		}, new WapiEncoder().encodeNativeDefinitionRequest(new WapiToken("ch.1.flt")));
	}

	@Test
	void requestsTopLevelDefinitionsFromTheRoot()
	{
		// Captured: da dd gives the definitions of the 17 top-level nodes
		assertArrayEquals(new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_ROOT, (byte) WapiBinaryStreamCodec.TOKEN_REQUEST_NODE_DEF },
				new WapiEncoder().encodeRootDefinitionRequest());
	}
}
