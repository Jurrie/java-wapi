package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiType;
import org.jurr.java.wapi.WapiValue;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;

class WapiEventTest
{

	@Test
	void parsedEventsAreReturnedFifoAndInOneBatch()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			final var first = new WapiEvent(new WapiToken("ch.1.mute"),
					org.jurr.java.wapi.WapiType.I32,
					org.jurr.java.wapi.WapiValue.ofInt(1));
			final var second = new WapiEvent(new WapiToken("ch.1.fdr"),
					org.jurr.java.wapi.WapiType.F32,
					org.jurr.java.wapi.WapiValue.ofFloat(-12.0F));

			client.acceptEvent(first);
			client.acceptEvent(second);

			final var events = client.wGetParsedEvents(2, Duration.ofSeconds(1));
			assertEquals(2, events.size());
			assertEquals(first, events.get(0));
			assertEquals(second, events.get(1));
		}
	}

	@Test
	void timedReadReturnsZeroWhenNoEventArrives()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			assertTimeoutPreemptively(Duration.ofMillis(250), () -> assertEquals(0, client.wGetParsedEvents(1, Duration.ofMillis(1)).size()));
		}
	}

	@Test
	void subscriptionReceivesEventsAndCanBeClosed() throws Exception
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			final var received = new ArrayList<WapiEvent>();
			final var subscription = client.addEventListener(received::add);
			final var event = new WapiEvent(new WapiToken("ch.1.mute"),
					org.jurr.java.wapi.WapiType.I32,
					org.jurr.java.wapi.WapiValue.ofInt(0));

			client.acceptEvent(event);
			subscription.close();
			client.acceptEvent(event);
			client.awaitListeners();

			// The listener gets the events that arrive while it is registered
			assertEquals(1, received.size());
		}
	}

	@Test
	void timedReadDecodesIncomingNativeChannelEvent()
	{
		final var token = new WapiToken("ch.1.mute");
		final byte[] tokenHash = token.hashBytes();
		final var event = new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3], 0x00 };
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getTokenRegistry().register(token);
			// d7 <ch.1.mute> 00: an unsolicited event, after the name is registered
			transport.enqueueResponse(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, event));

			final var events = client.wGetParsedEvents(1, Duration.ofSeconds(1));
			assertEquals(1, events.size());
			assertEquals(token, events.get(0).token());
			assertEquals(0, events.get(0).value().getIntValue().intValue());
		}
	}

	@Test
	void decodesEveryAudioEngineFrameInOnePacket()
	{
		final var first = new WapiToken("ch.1.mute");
		final byte[] firstHash = first.hashBytes();
		final var second = new WapiToken("ch.2.mute");
		final byte[] secondHash = second.hashBytes();
		final var channelCodec = new WapiNativeChannelCodec();
		final byte[] audio1 = channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, firstHash[0], firstHash[1], firstHash[2], firstHash[3], WapiBinaryStreamCodec.TOKEN_TRUE }).payload();
		final byte[] meter = channelCodec.encode(WapiNativeChannelCodec.NRP_METER_CHANNEL_ID, new byte[] { 0x01 }).payload();
		final byte[] audio2 = channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, secondHash[0], secondHash[1], secondHash[2], secondHash[3], WapiBinaryStreamCodec.TOKEN_FALSE }).payload();
		final var transport = new ScriptedWapiTransport();
		// dfd1 d7 <ch.1.mute> 01 dfd3 01 dfd1 d7 <ch.2.mute> 00, all in one packet
		final var packet = new byte[audio1.length + meter.length + audio2.length];
		System.arraycopy(audio1, 0, packet, 0, audio1.length);
		System.arraycopy(meter, 0, packet, audio1.length, meter.length);
		System.arraycopy(audio2, 0, packet, audio1.length + meter.length, audio2.length);
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getTokenRegistry().register(first);
			client.getTokenRegistry().register(second);
			// After the names are registered: events are named when the reader thread decodes them
			transport.enqueueResponse(new WapiPacket(packet));

			final var events = client.wGetParsedEvents(2, Duration.ofSeconds(1));
			assertEquals(2, events.size());
			assertEquals(first, events.get(0).token());
			assertEquals(second, events.get(1).token());
		}
	}

	@Test
	void withoutMaximumReturnsAllQueuedEvents()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			for (var i = 0; i < 3; i++)
			{
				client.acceptEvent(new WapiEvent(new WapiToken("ch.1.mute"), WapiType.I32, WapiValue.ofInt(i)));
			}

			final var events = client.wGetParsedEvents();

			assertEquals(3, events.size());
			assertEquals(0, events.get(0).value().getIntValue().intValue());
			assertEquals(2, events.get(2).value().getIntValue().intValue());
		}
	}

	@Test
	void withMaximumLeavesTheRestQueued()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			for (var i = 0; i < 3; i++)
			{
				client.acceptEvent(new WapiEvent(new WapiToken("ch.1.mute"), WapiType.I32, WapiValue.ofInt(i)));
			}

			assertEquals(2, client.wGetParsedEvents(2).size());
			final var rest = client.wGetParsedEvents(5, Duration.ZERO);
			assertEquals(1, rest.size());
			assertEquals(2, rest.get(0).value().getIntValue().intValue());
		}
	}

	@Test
	void timeoutGivesAnEmptyList()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			assertTrue(client.wGetParsedEvents(Duration.ofMillis(20)).isEmpty());
			assertTrue(client.wGetParsedEvents(0).isEmpty());
		}
	}

	@Test
	void rejectsANegativeMaximum()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			assertThrows(IllegalArgumentException.class, () -> client.wGetParsedEvents(-1));
			assertThrows(IllegalArgumentException.class, () -> client.wGetParsedEvents(-1, Duration.ZERO));
		}
	}
}
