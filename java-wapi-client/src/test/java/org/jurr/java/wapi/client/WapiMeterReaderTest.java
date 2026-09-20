package org.jurr.java.wapi.client;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;

/** Meter reports are received on a background thread and queued for {@code wGetMeters}. */
class WapiMeterReaderTest
{
	@Test
	void aMalformedReportIsSkipped() throws Exception
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()); var sender = new DatagramSocket())
		{
			final var port = client.wMeterUDPPort(0);
			// 000000: shorter than the 4-byte report id (PDF "Meter Data Packet Structure")
			send(sender, port, new byte[] { 0, 0, 0 });
			// 00000007 fc00: report 7, one value of -4 dB
			send(sender, port, new byte[] { 0, 0, 0, 7, (byte) 0xFC, 0 });

			assertEquals(7, client.wGetMeters(Duration.ofSeconds(1)).orElseThrow().requestId());
		}
	}

	@Test
	void reportsAreReturnedOldestFirst() throws Exception
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()); var sender = new DatagramSocket())
		{
			final var port = client.wMeterUDPPort(0);
			for (var id = 1; id <= 3; id++)
			{
				// 0000000<id> 0000
				send(sender, port, new byte[] { 0, 0, 0, (byte) id, 0, 0 });
			}

			for (var id = 1; id <= 3; id++)
			{
				assertEquals(id, client.wGetMeters(Duration.ofSeconds(1)).orElseThrow().requestId());
			}
		}
	}

	@Test
	void aZeroTimeoutReturnsAReceivedReportWithoutWaiting() throws Exception
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()); var sender = new DatagramSocket())
		{
			// A meter listener is called once the report is queued
			final var received = new AtomicBoolean();
			client.addMeterListener(report -> received.set(true));
			final var port = client.wMeterUDPPort(0);
			// 00000007 0000
			send(sender, port, new byte[] { 0, 0, 0, 7, 0, 0 });
			await().atMost(Duration.ofSeconds(1)).untilTrue(received);

			assertTimeoutPreemptively(Duration.ofMillis(200), () -> assertTrue(client.wGetMeters(Duration.ZERO).isPresent()));
		}
	}

	@Test
	void aWaitingCallEndsWhenTheClientCloses()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			client.wMeterUDPPort(0);
			final var waiter = new Thread(() -> client.wGetMeters(Duration.ofSeconds(10)));
			waiter.start();
			await().atMost(Duration.ofSeconds(1)).until(() -> waiter.getState() == Thread.State.TIMED_WAITING);

			client.wClose();

			// wGetMeters must not keep waiting after wClose
			await().atMost(Duration.ofSeconds(1)).until(() -> !waiter.isAlive());
			assertThrows(WapiException.class, () -> client.wGetMeters(Duration.ZERO));
		}
	}

	private static void send(final DatagramSocket sender, final int port, final byte[] report) throws Exception
	{
		sender.send(new DatagramPacket(report, report.length, InetAddress.getLoopbackAddress(), port));
	}
}
