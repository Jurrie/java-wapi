package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.jurr.java.wapi.WapiToken;
import org.junit.jupiter.api.Test;

class WapiEncoderNativeWriteTest
{
	@Test
	void encodesCStyleFloatSetRecord()
	{
		final var token = new WapiToken("ch.1.fdr");
		final byte[] tokenHash = token.hashBytes();
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_FLOAT32, 0x3F, (byte) 0x80, 0, 0
		}, new WapiEncoder().encodeNativeSetFloat(token, 1.0F));
	}

	@Test
	void encodesCStyleStringSetRecord()
	{
		final var token = new WapiToken("user.name");
		final byte[] tokenHash = token.hashBytes();
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_STRING_256_BASE, 4, 'H', 'e', 'l', 'l', 'o'
		}, new WapiEncoder().encodeNativeSetString(token, "Hello"));
	}

	@Test
	void encodesEmptyStringSetRecord()
	{
		final var token = new WapiToken("user.name");
		final byte[] tokenHash = token.hashBytes();
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3], (byte) WapiBinaryStreamCodec.TOKEN_EMPTY_STRING
		}, new WapiEncoder().encodeNativeSetString(token, ""));
	}
}
