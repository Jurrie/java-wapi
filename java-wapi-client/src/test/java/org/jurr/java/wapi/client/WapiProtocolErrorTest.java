package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;

/** An unknown or misplaced token is a protocol error that disconnects the client. */
class WapiProtocolErrorTest
{
	private static final WapiToken TOKEN = new WapiToken("ch.1.mute");
	private static final Duration TIMEOUT = Duration.ofSeconds(1);

	@Test
	void eventReadDisconnectsOnUnknownToken()
	{
		final var transport = new ScriptedWapiTransport();
		// d7 <ch.1.mute> 01 e0 (0xE0 is unused in the binary stream protocol, PDF Table 2)
		transport.enqueueResponse(frame((byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash(0), hash(1), hash(2), hash(3), (byte) WapiBinaryStreamCodec.TOKEN_TRUE, (byte) 0xE0));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var error = assertThrows(WapiException.class, () -> client.wGetParsedEvents(1, TIMEOUT));

			assertEquals(WapiError.WTOKEN, error.getError());
			assertFalse(transport.isConnected());
			assertFalse(client.isConnected());
			final var next = assertThrows(WapiException.class, () -> client.wGetParsedEvents(1, TIMEOUT));
			assertTrue(next.getMessage().contains("not connected"), next.getMessage());
		}
	}

	@Test
	void tokenReadDisconnectsOnMisplacedToken()
	{
		final var transport = new ScriptedWapiTransport();
		// d7 <ch.1.mute> d8: a click token is not a value
		transport.enqueueReply(frame((byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash(0), hash(1), hash(2), hash(3), (byte) WapiBinaryStreamCodec.TOKEN_CLICK));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var error = assertThrows(WapiException.class, () -> client.wGetTokenInt(TOKEN));

			assertEquals(WapiError.WTOKEN, error.getError());
			assertFalse(transport.isConnected());
		}
	}

	@Test
	void binaryNodeReadDisconnectsOnUnknownToken()
	{
		final var transport = new ScriptedWapiTransport();
		// e0
		transport.enqueueReply(frame((byte) 0xE0));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var error = assertThrows(WapiException.class, () -> client.wGetBinaryNode(TOKEN, TIMEOUT));

			assertEquals(WapiError.WTOKEN, error.getError());
			assertFalse(transport.isConnected());
		}
	}

	@Test
	void readsAgainAfterReconnect()
	{
		final var transport = new ScriptedWapiTransport();
		// e0 | d7 <ch.1.mute> 01
		transport.enqueueReply(frame((byte) 0xE0));
		transport.enqueueReply(frame((byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash(0), hash(1), hash(2), hash(3), (byte) WapiBinaryStreamCodec.TOKEN_TRUE));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			assertThrows(WapiException.class, () -> client.wGetTokenInt(TOKEN));

			client.wOpen();

			assertTrue(client.isConnected());
			assertEquals(1, client.wGetTokenInt(TOKEN));
		}
	}

	private static byte hash(final int index)
	{
		return TOKEN.hashBytes()[index];
	}

	private static WapiPacket frame(final byte... payload)
	{
		return new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, payload);
	}
}
