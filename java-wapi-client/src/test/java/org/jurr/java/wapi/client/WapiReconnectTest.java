package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.junit.jupiter.api.Test;

class WapiReconnectTest
{
	@Test
	void reconnectDiscardsHalfReceivedRecordOfOldConnection()
	{
		final var fader = new WapiToken("ch.1.fdr");
		final byte[] faderHash = fader.hashBytes();
		final var mute = new WapiToken("ch.2.mute");
		final byte[] muteHash = mute.hashBytes();
		final var channelCodec = new WapiNativeChannelCodec();
		final var transport = new ScriptedWapiTransport();
		// A console sends whole records, so it would not answer the keep-alive in the middle of this one
		transport.setAnswersKeepAlive(false);
		// old connection: d7 <ch.1.fdr> d4 00 (half an int32 record)
		transport.enqueueResponse(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, faderHash[0], faderHash[1], faderHash[2], faderHash[3], (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0
		}));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getTokenRegistry().register(fader);
			client.getTokenRegistry().register(mute);
			var events = client.wGetParsedEvents(2, Duration.ofMillis(100));
			assertEquals(0, events.size());

			client.wClose();
			transport.setAnswersKeepAlive(true);
			client.wOpen();
			// new connection: d7 <ch.2.mute> 01
			transport.enqueueResponse(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
					(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, muteHash[0], muteHash[1], muteHash[2], muteHash[3], WapiBinaryStreamCodec.TOKEN_TRUE
			}));

			events = client.wGetParsedEvents(2, Duration.ofSeconds(1));
			assertEquals(1, events.size());
			assertEquals(mute, events.get(0).token());
			assertEquals(1, events.get(0).value().getIntValue().intValue());
		}
	}
}
