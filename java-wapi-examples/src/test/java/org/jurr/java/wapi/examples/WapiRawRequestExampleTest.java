package org.jurr.java.wapi.examples;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;

import com.github.valfirst.slf4jtest.TestLoggerFactory;

import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WapiRawRequestExampleTest
{
	@BeforeEach
	void clearLoggedEvents()
	{
		// All threads: the examples also log from their listener threads
		TestLoggerFactory.clearAll();
	}

	private static final WapiToken DAW_ON = new WapiToken("$ctl.daw.$on");

	@Test
	void dataModeSendsOnlyDataRequests()
	{
		final var transport = new ScriptedWapiTransport();
		final var channelCodec = new WapiNativeChannelCodec();
		// PDF example reply, once for the raw capture and once for wGetBinaryNode: d7beefaeab01de
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex("d7beefaeab01de", 16)));
		transport.enqueueResponse(new WapiPacket(new byte[0]));
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex("d7beefaeab01de", 16)));

		WapiRawRequestExample.run(new WapiSession(), transport, DAW_ON, WapiRawRequestExample.Mode.DATA);

		final var sent = transport.getSentPackets();
		assertEquals(2, sent.size(), "One data request for the capture, one from wGetBinaryNode");
		for (final var packet : sent)
		{
			// d7beefaeabdc
			assertArrayEquals(WapiBinaryDataCodec.decodeHex("d7beefaeabdc", 16), new WapiNativeChannelCodec().decode(packet).get(0).payload());
		}
		assertArrayEquals(new String[] {
				"token: $ctl.daw.$on (hash 0xBEEFAEAB)",
				"tx: d7beefaeabdc",
				"rx channel 1: d7beefaeab01de",
				"wGetBinaryNode: d7beefaeab01"
		}, LoggedText.of(WapiRawRequestExample.class).trim().split("\\R"));
	}

	@Test
	void definitionModeSendsOnlyOneDefinitionRequest()
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueEndOfStream();

		WapiRawRequestExample.run(new WapiSession(), transport, DAW_ON, WapiRawRequestExample.Mode.DEFINITION);

		final var sent = transport.getSentPackets();
		assertEquals(1, sent.size());
		// d7beefaeabdd
		assertArrayEquals(WapiBinaryDataCodec.decodeHex("d7beefaeabdd", 16), new WapiNativeChannelCodec().decode(sent.get(0)).get(0).payload());
	}

	@Test
	void rejectsAnyOtherMode()
	{
		assertThrows(IllegalArgumentException.class, () -> WapiRawRequestExample.Mode.parse("01"));
		assertThrows(IllegalArgumentException.class, () -> WapiRawRequestExample.Mode.parse("DATA"));
		assertThrows(IllegalArgumentException.class, () -> WapiRawRequestExample.main(new String[] { "127.0.0.1", "2222", "ch.1.fdr", "d7beefaeab01" }));
	}

	@Test
	void encodesNavigationPaths()
	{
		// da d7 4e838289 (hash of $ctl.layer.L.$spill) | index or word | dd or dc
		assertEquals("dad74e83828940dd", hex(WapiRawRequestExample.encodePath("root,hash:$ctl.layer.L.$spill,index:1,def")));
		assertEquals("dad74e83828940dc", hex(WapiRawRequestExample.encodePath("root,hash:$ctl.layer.L.$spill,index:1,data")));
		assertEquals("dad74e8382897fdd", hex(WapiRawRequestExample.encodePath("root,hash:$ctl.layer.L.$spill,index:64,def")));
		assertEquals("dbdc", hex(WapiRawRequestExample.encodePath("up,data")));
	}

	@Test
	void rejectsInvalidNavigationPaths()
	{
		// word: (0xd2) is not offered: the console does not handle it as documented, so it cannot be guaranteed read-only
		for (final var invalid : new String[] { "", "root", "def,root", "def,def", "index:0,def", "index:65,def", "index:abc,def",
				"word:64,def", "word:0,def", "hash:,def", "set:1,def", "root,01,def" })
		{
			assertThrows(IllegalArgumentException.class, () -> WapiRawRequestExample.encodePath(invalid), invalid);
		}
		assertThrows(IllegalArgumentException.class, () -> WapiRawRequestExample.main(new String[] { "127.0.0.1", "2222", "nav", "root,01,def" }));
	}

	@Test
	void navigationSendsOnlyThePathRequestAndPrintsTheReply()
	{
		final var transport = new ScriptedWapiTransport();
		final var channelCodec = new WapiNativeChannelCodec();
		// de: an empty reply
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex("de", 4)));
		transport.enqueueEndOfStream();
		final byte[] request = WapiRawRequestExample.encodePath("root,hash:$ctl.layer.L.$spill,index:1,def");

		WapiRawRequestExample.runNavigation(new WapiSession(), transport, "root,hash:$ctl.layer.L.$spill,index:1,def", request);

		final var sent = transport.getSentPackets();
		assertEquals(1, sent.size());
		assertArrayEquals(request, new WapiNativeChannelCodec().decode(sent.get(0)).get(0).payload());
		assertArrayEquals(new String[] {
				"path: root,hash:$ctl.layer.L.$spill,index:1,def",
				"tx: dad74e83828940dd",
				"rx channel 1: de"
		}, LoggedText.of(WapiRawRequestExample.class).trim().split("\\R"));
	}

	private static String hex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}
}
