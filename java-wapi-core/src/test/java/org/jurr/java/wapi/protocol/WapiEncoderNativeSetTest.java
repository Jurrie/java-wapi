package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.jurr.java.wapi.WapiToken;
import org.junit.jupiter.api.Test;

class WapiEncoderNativeSetTest
{
	@Test
	void encodesCStyleIntegerSetRecord()
	{
		final var token = new WapiToken("ch.1.mute");
		final byte[] tokenBytes = token.hashBytes();

		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenBytes[0], tokenBytes[1], tokenBytes[2], tokenBytes[3],
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 1
		}, new WapiEncoder().encodeNativeSetInt(token, 1));
	}
}