package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.Optional;

import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.junit.jupiter.api.Test;

/** Lookups that may find nothing return an Optional instead of null. */
class WapiLookupTest
{
	private static final WapiToken MUTE = new WapiToken("ch.1.mute");

	@Test
	void findsARegisteredTokenByHash()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			client.getTokenRegistry().register(MUTE);

			assertEquals(Optional.of(MUTE), client.wGetTokenFromHash(MUTE.hash()));
			assertEquals(Optional.of("ch.1.mute"), client.wGetNameFromHash(MUTE.hash()));
		}
	}

	@Test
	void findsNothingForAnUnknownHash()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			assertTrue(client.wGetTokenFromHash(MUTE.hash()).isEmpty());
			assertTrue(client.wGetNameFromHash(MUTE.hash()).isEmpty());
		}
	}

	@Test
	void returnsAReceivedMeterReport() throws Exception
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()); var console = new DatagramSocket())
		{
			final var port = client.wMeterUDPPort(0);
			// 00000007 fc00: request 7, one value of -4 dB
			final byte[] report = { 0, 0, 0, 7, (byte) 0xFC, 0 };
			console.send(new DatagramPacket(report, report.length, InetAddress.getLoopbackAddress(), port));

			final var received = client.wGetMeters(Duration.ofSeconds(1));

			assertTrue(received.isPresent());
			assertEquals(7, received.get().requestId());
		}
	}
}
