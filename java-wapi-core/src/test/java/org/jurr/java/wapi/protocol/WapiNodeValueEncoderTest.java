package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.List;

import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiTokenValue;
import org.jurr.java.wapi.WapiValue;
import org.junit.jupiter.api.Test;

class WapiNodeValueEncoderTest
{
	@Test
	void encodesCStyleBatchNodeValues()
	{
		final var mute = new WapiToken("ch.1.mute");
		final var fader = new WapiToken("ch.1.fdr");

		final byte[] muteHash = mute.hashBytes();
		final byte[] faderHash = fader.hashBytes();

		// d7 <ch.1.mute> d4 00000001 d7 <ch.1.fdr> d5 3f800000 de
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, muteHash[0], muteHash[1], muteHash[2], muteHash[3], (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 1,
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, faderHash[0], faderHash[1], faderHash[2], faderHash[3], (byte) WapiBinaryStreamCodec.TOKEN_FLOAT32, 0x3F, (byte) 0x80, 0, 0,
				(byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}, new WapiEncoder().encodeNativeNodeValues(
				List.of(
						new WapiTokenValue(mute, WapiValue.ofInt(1)),
						new WapiTokenValue(fader, WapiValue.ofFloat(1.0F)))));
	}
}