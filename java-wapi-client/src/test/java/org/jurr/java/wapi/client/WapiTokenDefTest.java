package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.node.WapiNodeKind;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;

class WapiTokenDefTest
{
	private static final WapiToken PAN = new WapiToken("cfg.mon.1.pan");
	private static final byte[] PAN_HASH = PAN.hashBytes();
	/** Integer node definition "I" with range 5..10: df 0017 00000000 <cfg.mon.1.pan> 0001 01 49 00 0040 00000005 0000000a */
	private static final byte[] DEFINITION = {
			(byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, 0x00, 0x17,
			0x00, 0x00, 0x00, 0x00, PAN_HASH[0], PAN_HASH[1], PAN_HASH[2], PAN_HASH[3],
			0x00, 0x01, 0x01, 'I', 0x00, 0x00, 0x40,
			0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x0A
	};
	/** C wGetTokenDef str for ch.1.fdr (captured with libwapi.a): index, name "fdr", long name "FADER", flags, then the end of data. */
	private static final String FDR_STR = "0000036664720546414445520031de";

	@Test
	void returnsDefinitionFromIndexOnLikeC()
	{
		final var transport = new ScriptedWapiTransport();
		// df 0016 <ch.1> <ch.1.fdr> 0000 03666472 054641444552 0031 de
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex("df0016" + hex(new WapiToken("ch.1").hashBytes())
				+ hex(new WapiToken("ch.1.fdr").hashBytes()) + FDR_STR, 64)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var definition = client.wGetTokenDef(new WapiToken("ch.1.fdr"));

			assertEquals(15, definition.num());
			assertArrayEquals(WapiBinaryDataCodec.decodeHex(FDR_STR, 64), definition.str());
			assertEquals("fdr", definition.nodeDefinition().name());
			assertEquals("FADER", definition.nodeDefinition().longName());
		}
	}

	@Test
	void parsesDefinitionSplitOverFrames()
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(frame(Arrays.copyOfRange(DEFINITION, 0, 9)));
		transport.enqueueResponse(frame(Arrays.copyOfRange(DEFINITION, 9, DEFINITION.length)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var definition = client.wGetTokenDef(PAN);

			assertEquals(PAN, definition.token());
			assertArrayEquals(DEFINITION, definition.rawPayload());
			assertEquals(WapiNodeKind.INTEGER, definition.nodeDefinition().nodeKind());
			assertEquals(Integer.valueOf(5), definition.nodeDefinition().metadata().minInt());
			assertEquals(Integer.valueOf(10), definition.nodeDefinition().metadata().maxInt());
		}
	}

	@Test
	void rejectsNodeLikeC()
	{
		final var transport = new ScriptedWapiTransport();
		// df 0010 <ch.1.flt> <ch.1.flt.lc> 0000 02 6c63 00 0000 de: the console answers a node with its children
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex("df0010" + hex(new WapiToken("ch.1.flt").hashBytes())
				+ hex(new WapiToken("ch.1.flt.lc").hashBytes()) + "0000026c63000000de", 64)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var token = new WapiToken("ch.1.flt");
			final var exception = assertThrows(WapiException.class, () -> client.wGetTokenDef(token));

			assertEquals(WapiError.WRECV_ERROR, exception.getError());
		}
	}

	@Test
	void reportsReceiveErrorWithoutDefinitionLikeC()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			final var exception = assertThrows(WapiException.class, () -> client.wGetTokenDef(PAN));

			assertEquals(WapiError.WRECV_ERROR, exception.getError());
		}
	}

	@Test
	void queuesEventsAroundDefinitionInSameChunk()
	{
		final var mute = new WapiToken("ch.2.mute");
		final byte[] muteHash = mute.hashBytes();
		final var solo = new WapiToken("ch.3.solo");
		final byte[] soloHash = solo.hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <ch.2.mute> 01 <definition> d7 <ch.3.solo> 00
		transport.enqueueReply(frame(concat(
				new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, muteHash[0], muteHash[1], muteHash[2], muteHash[3], WapiBinaryStreamCodec.TOKEN_TRUE },
				DEFINITION,
				new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, soloHash[0], soloHash[1], soloHash[2], soloHash[3], WapiBinaryStreamCodec.TOKEN_FALSE })));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getTokenRegistry().register(mute);
			client.getTokenRegistry().register(solo);

			final var definition = client.wGetTokenDef(PAN);

			assertArrayEquals(DEFINITION, definition.rawPayload());
			assertEquals(WapiNodeKind.INTEGER, definition.nodeDefinition().nodeKind());
			final var events = client.wGetParsedEvents(3, Duration.ZERO);
			assertEquals(2, events.size());
			assertEquals(mute, events.get(0).token());
			assertEquals(solo, events.get(1).token());
		}
	}

	@Test
	void completesRecordThatWasHalfReceivedBeforeRequest()
	{
		final var fader = new WapiToken("ch.1.fdr");
		final byte[] faderHash = fader.hashBytes();
		final var transport = new ScriptedWapiTransport();
		// A console sends whole records, so it would not answer the keep-alive in the middle of this one
		transport.setAnswersKeepAlive(false);
		// d7 <ch.1.fdr> d4 00 | 000005 <definition>
		transport.enqueueResponse(frame(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, faderHash[0], faderHash[1], faderHash[2], faderHash[3], (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0x00 }));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getTokenRegistry().register(fader);
			var events = client.wGetParsedEvents(1, Duration.ofMillis(100));
			assertEquals(0, events.size());
			transport.enqueueReply(frame(concat(new byte[] { 0x00, 0x00, 0x05 }, DEFINITION)));

			final var definition = client.wGetTokenDef(PAN);

			assertArrayEquals(DEFINITION, definition.rawPayload());
			events = client.wGetParsedEvents(1, Duration.ZERO);
			assertEquals(1, events.size());
			assertEquals(fader, events.get(0).token());
			assertEquals(5, events.get(0).value().getIntValue().intValue());
		}
	}

	private static WapiPacket frame(final byte[] payload)
	{
		return new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, payload);
	}

	private static String hex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}

	private static byte[] concat(final byte[]... parts)
	{
		var length = 0;
		for (final var part : parts)
		{
			length += part.length;
		}
		final var result = new byte[length];
		var offset = 0;
		for (final var part : parts)
		{
			System.arraycopy(part, 0, result, offset, part.length);
			offset += part.length;
		}
		return result;
	}
}
