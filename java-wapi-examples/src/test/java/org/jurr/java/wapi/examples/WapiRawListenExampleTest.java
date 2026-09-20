package org.jurr.java.wapi.examples;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;


import com.github.valfirst.slf4jtest.TestLoggerFactory;

import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WapiRawListenExampleTest
{
	@BeforeEach
	void clearLoggedEvents()
	{
		// All threads: the examples also log from their listener threads
		TestLoggerFactory.clearAll();
	}

	@Test
	void keepAliveIsTheClientsDataRequestForConsoleName()
	{
		// d7c9cb20e0dc: the same keep-alive as WapiClient sends by itself
		assertArrayEquals(WapiBinaryDataCodec.decodeHex("d7c9cb20e0dc", 16), WapiRawListenExample.encodeKeepAlive());
	}

	@Test
	void sendsOnlyKeepAliveAndPrintsRawAndDecodedFrames()
	{
		final var transport = new ScriptedWapiTransport();
		// The example sends the keep-alive itself on the raw transport; check it like any request
		transport.setAnswersKeepAlive(false);
		final var channelCodec = new WapiNativeChannelCodec();
		// d7f50f69f801: ch.1.mute = 1
		transport.enqueueResponse(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex("d7f50f69f801", 16)));
		// d7c9cb20e0 8357494e47 de: keep-alive reply "WING"
		transport.enqueueResponse(new WapiPacket(WapiBinaryDataCodec.decodeHex("d7c9cb20e08357494e47de", 16)));
		transport.enqueueEndOfStream();

		WapiRawListenExample.run(new WapiSession(), transport, 5000L);

		final var sent = transport.getSentPackets();
		assertEquals(1, sent.size(), "Only the keep-alive may be sent");
		assertArrayEquals(WapiRawListenExample.encodeKeepAlive(), channelCodec.decode(sent.get(0)).get(0).payload());
		assertArrayEquals(new String[] {
				"tx keep-alive: d7c9cb20e0dc",
				"rx channel 1: d7f50f69f801",
				"      ch.1.mute = 1 [I32]",
				"rx channel 1: d7c9cb20e08357494e47de",
				"      $syscfg.consolename = WING [S32] (keep-alive reply)",
				"end of stream"
		}, lines());
	}

	@Test
	void printsDecodeErrorAndKeepsListening()
	{
		final var transport = new ScriptedWapiTransport();
		// The example sends the keep-alive itself on the raw transport; check it like any request
		transport.setAnswersKeepAlive(false);
		final var channelCodec = new WapiNativeChannelCodec();
		// e0 (unused token, PDF Table 2) | d7f50f69f800: ch.1.mute = 0
		transport.enqueueResponse(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] { (byte) 0xE0 }));
		transport.enqueueResponse(new WapiPacket(WapiBinaryDataCodec.decodeHex("d7f50f69f800", 16)));
		transport.enqueueEndOfStream();

		WapiRawListenExample.run(new WapiSession(), transport, 5000L);

		assertArrayEquals(new String[] {
				"tx keep-alive: d7c9cb20e0dc",
				"rx channel 1: e0",
				"      decode error: Unsupported WAPI stream token: 0xE0",
				"rx channel 1: d7f50f69f800",
				"      ch.1.mute = 0 [I32]",
				"end of stream"
		}, lines());
	}

	/** @return the output lines without their time stamps */
	private static String[] lines()
	{
		return LoggedText.of(WapiRawListenExample.class).trim().replaceAll("\\[\\+ *\\d+ ms\\] ", "").split("\\R");
	}
}
