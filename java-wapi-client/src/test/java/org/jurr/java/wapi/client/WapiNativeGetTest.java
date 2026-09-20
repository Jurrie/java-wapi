package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;

import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiType;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.junit.jupiter.api.Test;

class WapiNativeGetTest
{
	@Test
	void readsMatchingNativeResponseAndQueuesUnrelatedEvents()
	{
		final var target = new WapiToken("ch.1.mute");
		final byte[] targetHash = target.hashBytes();
		final var other = new WapiToken("ch.2.mute");
		final byte[] otherHash = other.hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <ch.2.mute> 00 d7 <ch.1.mute> 01: an unrelated event, then the reply
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, otherHash[0], otherHash[1], otherHash[2], otherHash[3], 0,
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, targetHash[0], targetHash[1], targetHash[2], targetHash[3], 1
		}));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getTokenRegistry().register(other);
			assertEquals(1, client.wGetToken(target, WapiType.I32).getIntValue().intValue());
			final var events = client.wGetParsedEvents(1, Duration.ofSeconds(1));
			assertEquals(1, events.size());
			assertEquals(other, events.get(0).token());
		}
	}

	@Test
	void queuesEventsThatFollowRequestedValueInSameChunk() throws Exception
	{
		final var target = new WapiToken("ch.1.mute");
		final byte[] targetHash = target.hashBytes();
		final var second = new WapiToken("ch.2.mute");
		final byte[] secondHash = second.hashBytes();
		final var third = new WapiToken("ch.3.mute");
		final byte[] thirdHash = third.hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <ch.1.mute> 01 d7 <ch.2.mute> 00 d7 <ch.3.mute> 01
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, targetHash[0], targetHash[1], targetHash[2], targetHash[3], WapiBinaryStreamCodec.TOKEN_TRUE,
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, secondHash[0], secondHash[1], secondHash[2], secondHash[3], WapiBinaryStreamCodec.TOKEN_FALSE,
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, thirdHash[0], thirdHash[1], thirdHash[2], thirdHash[3], WapiBinaryStreamCodec.TOKEN_TRUE
		}));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getTokenRegistry().register(second);
			client.getTokenRegistry().register(third);
			final var heard = new ArrayList<WapiEvent>();
			client.addEventListener(heard::add);

			assertEquals(1, client.wGetTokenInt(target));

			final var events = client.wGetParsedEvents(3, Duration.ZERO);
			assertEquals(2, events.size());
			assertEquals(second, events.get(0).token());
			assertEquals(0, events.get(0).value().getIntValue().intValue());
			assertEquals(third, events.get(1).token());
			assertEquals(1, events.get(1).value().getIntValue().intValue());
			client.awaitListeners();
			assertEquals(2, heard.size(), "Listeners must also receive the events after the requested value");
		}
	}

	@Test
	void queuesEventSplitAcrossChunksAfterRequestedValue()
	{
		final var target = new WapiToken("ch.1.mute");
		final byte[] targetHash = target.hashBytes();
		final var other = new WapiToken("ch.2.mute");
		final byte[] otherHash = other.hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <ch.1.mute> 01 d7 <ch.2.mute[0..1]> | <ch.2.mute[2..3]> 00
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, targetHash[0], targetHash[1], targetHash[2], targetHash[3], WapiBinaryStreamCodec.TOKEN_TRUE,
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, otherHash[0], otherHash[1]
		}));
		transport.enqueueResponse(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				otherHash[2], otherHash[3], WapiBinaryStreamCodec.TOKEN_FALSE
		}));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getTokenRegistry().register(other);

			assertEquals(1, client.wGetTokenInt(target));

			final var events = client.wGetParsedEvents(1, Duration.ofSeconds(1));
			assertEquals(1, events.size());
			assertEquals(other, events.get(0).token());
			assertEquals(0, events.get(0).value().getIntValue().intValue());
		}
	}

	@Test
	void matchesResponseByHashRegardlessOfDeclaredType()
	{
		final int hash = new WapiToken("ch.1.fdr").hash();
		final var hashBytes = new WapiToken(hash).hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <hash> d541480000
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hashBytes[0], hashBytes[1], hashBytes[2], hashBytes[3],
				(byte) WapiBinaryStreamCodec.TOKEN_FLOAT32, 0x41, 0x48, 0x00, 0x00
		}));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			// The registry already holds a hash-only token without a declared type
			client.getTokenRegistry().register(new WapiToken(hash));

			assertEquals(12.5F, client.wGetTokenFloat(new WapiToken(hash, WapiType.F32), Duration.ofSeconds(1)), 0.001F);
		}
	}
}
