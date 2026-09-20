package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiType;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.junit.jupiter.api.Test;

class WapiTimeoutTest
{
	private static final WapiToken FADER = new WapiToken("ch.1.fdr");

	@Test
	void rejectsNegativeAndMissingTimeouts()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			for (final var timeout : new Duration[] { Duration.ofMillis(-1), null })
			{
				assertThrows(IllegalArgumentException.class, () -> client.wGetParsedEvents(1, timeout));
				assertThrows(IllegalArgumentException.class, () -> client.wGetToken(FADER, WapiType.F32, timeout));
				assertThrows(IllegalArgumentException.class, () -> client.wGetTokenFloat(FADER, timeout));
				assertThrows(IllegalArgumentException.class, () -> client.wGetTokenInt(FADER, timeout));
				assertThrows(IllegalArgumentException.class, () -> client.wGetTokenString(FADER, timeout));
				assertThrows(IllegalArgumentException.class, () -> client.wGetBinaryNode(FADER, timeout));
				assertThrows(IllegalArgumentException.class, () -> client.wGetMeters(timeout));
			}
		}
	}

	@Test
	void zeroTimeoutDoesNotWaitForEvents()
	{
		final var transport = new ScriptedWapiTransport();
		// d738ae75c2 d5c0a66668: ch.1.fdr = -5.2 dB, arriving after 500 ms
		transport.enqueueResponse(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				WapiBinaryDataCodec.decodeHex("d738ae75c2d5c0a66668", 16)), 500L);
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			assertTimeoutPreemptively(Duration.ofMillis(200), () -> assertEquals(0, client.wGetParsedEvents(1, Duration.ZERO).size()));
		}
	}

	@Test
	void zeroTimeoutDoesNotWaitForMeters()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			client.wMeterUDPPort(0);
			// Before, a zero timeout waited indefinitely for a meter report
			assertTimeoutPreemptively(Duration.ofMillis(200), () -> assertTrue(client.wGetMeters(Duration.ZERO).isEmpty()));
		}
	}
}
