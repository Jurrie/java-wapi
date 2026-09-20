package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.jurr.java.wapi.WapiToken;
import org.junit.jupiter.api.Test;

class WapiEncoderTokenOperationTest
{
	@Test
	void encodesToggleRecord()
	{
		final var token = new WapiToken("ch.1.mute");
		final byte[] tokenHash = token.hashBytes();
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3], (byte) WapiBinaryStreamCodec.TOKEN_CLICK
		}, new WapiEncoder().encodeNativeToggle(token));
	}

	@Test
	void encodesStepRecord()
	{
		final var token = new WapiToken("ch.1.fdr");
		final byte[] tokenHash = token.hashBytes();
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3], (byte) WapiBinaryStreamCodec.TOKEN_STEP, (byte) 0xFE
		}, new WapiEncoder().encodeNativeStep(token, (byte) 0xFE));
	}
}