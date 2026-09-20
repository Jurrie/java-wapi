package org.jurr.java.wapi.client;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.meter.WapiMeterGroup;
import org.jurr.java.wapi.meter.WapiMeterRequest;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;

/** WING stops sending meters after 5 s (PDF "Channel 3: Metering"); the client renews its requests by itself. */
class WapiMeterRenewalTest
{
	private static final Duration INTERVAL = Duration.ofMillis(100);

	@Test
	void renewsEachRequestByItself()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.setMeterRenewalInterval(INTERVAL);
			client.wSetMetersRequest(new WapiMeterRequest(7, 14135).addGroup(WapiMeterGroup.CHANNEL, 1));
			client.wSetMetersRequest(new WapiMeterRequest(8, 14135).addGroup(WapiMeterGroup.MONITOR));

			await().atMost(Duration.ofSeconds(1)).until(() -> countRenewals(transport, 7) >= 2 && countRenewals(transport, 8) >= 2);
		}
	}

	@Test
	void wStopMetersStopsRenewingThatRequest()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.setMeterRenewalInterval(INTERVAL);
			client.wSetMetersRequest(new WapiMeterRequest(7, 14135).addGroup(WapiMeterGroup.CHANNEL, 1));
			client.wSetMetersRequest(new WapiMeterRequest(8, 14135).addGroup(WapiMeterGroup.MONITOR));

			client.wStopMeters(7);
			final var stopped = countRenewals(transport, 7);
			final var running = countRenewals(transport, 8);
			await().during(Duration.ofMillis(350)).atMost(Duration.ofSeconds(1)).until(() -> countRenewals(transport, 7) == stopped);

			assertTrue(countRenewals(transport, 8) > running);
		}
	}

	@Test
	void wCloseStopsRenewing()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.setMeterRenewalInterval(INTERVAL);
			client.wSetMetersRequest(new WapiMeterRequest(7, 14135).addGroup(WapiMeterGroup.CHANNEL, 1));

			client.wClose();
			final var renewals = countRenewals(transport, 7);
			await().during(Duration.ofMillis(350)).atMost(Duration.ofSeconds(1)).until(() -> countRenewals(transport, 7) == renewals);
		}
	}

	@Test
	void aReconnectSendsTheRequestsAgain()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var request = new WapiMeterRequest(7, 14135).addGroup(WapiMeterGroup.CHANNEL, 1);
			client.wSetMetersRequest(request);
			// The connection is lost
			transport.disconnect();

			client.wOpen();

			// df d3 d33737 d400000007 dca000de, once before and once after the reconnect
			assertEquals(2, countRequests(transport, request));
		}
	}

	@Test
	void aReconnectSendsTheNewestRequestOfEachId()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var first = new WapiMeterRequest(7, 14135).addGroup(WapiMeterGroup.CHANNEL, 1);
			final var second = new WapiMeterRequest(7, 14135).addGroup(WapiMeterGroup.MONITOR);
			client.wSetMetersRequest(first);
			client.wSetMetersRequest(second);
			transport.disconnect();

			client.wOpen();

			assertEquals(1, countRequests(transport, first));
			assertEquals(2, countRequests(transport, second));
		}
	}

	@Test
	void aStoppedRequestIsNotSentAgain()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var request = new WapiMeterRequest(7, 14135).addGroup(WapiMeterGroup.CHANNEL, 1);
			client.wSetMetersRequest(request);
			client.wStopMeters(7);
			transport.disconnect();

			client.wOpen();

			assertEquals(1, countRequests(transport, request));
		}
	}

	/** @return how often {@code request} was sent in full on channel 3 */
	private static int countRequests(final ScriptedWapiTransport transport, final WapiMeterRequest request)
	{
		final var framed = new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_METER_CHANNEL_ID, request.encode()).payload();
		return (int) transport.getSentPackets().stream().filter(packet -> Arrays.equals(framed, packet.payload())).count();
	}

	/** @return the renewals of {@code requestId} sent: df d3 d4 <request id> (PDF Table 4) */
	private static int countRenewals(final ScriptedWapiTransport transport, final int requestId)
	{
		final var renewal = new byte[] {
				(byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE,
				(byte) (WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + WapiNativeChannelCodec.NRP_METER_CHANNEL_ID),
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, (byte) (requestId >>> 24), (byte) (requestId >>> 16), (byte) (requestId >>> 8), (byte) requestId
		};
		return (int) transport.getSentPackets().stream().filter(packet -> Arrays.equals(renewal, packet.payload())).count();
	}
}
