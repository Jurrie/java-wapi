package org.jurr.java.wapi.client.meter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class WapiMeterReceiverTest
{
	@Test
	void receivesAndDecodesUdpReport() throws Exception
	{
		try (var receiver = new WapiMeterReceiver(0);
				var sender = new DatagramSocket())
		{
			final var report = new byte[] { 0, 0, 0, 7, (byte) 0xFC, 0 };
			sender.send(new DatagramPacket(report, report.length,
					InetAddress.getLoopbackAddress(), receiver.getPort()));

			final var data = receiver.receive(Duration.ofSeconds(1));
			assertEquals(7, data.requestId());
			assertEquals(-4.0F, data.decibelValues().get(0), 0.001F);
		}
	}

	@Test
	void receiveWithoutTimeoutWaitsForReport() throws Exception
	{
		try (var receiver = new WapiMeterReceiver(0);
				var sender = new DatagramSocket())
		{
			final var report = new byte[] { 0, 0, 0, 7, (byte) 0xFC, 0 };
			sender.send(new DatagramPacket(report, report.length, InetAddress.getLoopbackAddress(), receiver.getPort()));

			assertEquals(7, receiver.receive().requestId());
		}
	}

	@Test
	void zeroTimeoutReturnsAtOnceWithoutReport()
	{
		try (var receiver = new WapiMeterReceiver(0))
		{
			assertTimeoutPreemptively(Duration.ofMillis(200), () -> assertNull(receiver.receive(Duration.ZERO)));
		}
	}

	@Test
	void rejectsNegativeAndMissingTimeouts()
	{
		try (var receiver = new WapiMeterReceiver(0))
		{
			final var negativeDuration = Duration.ofMillis(-1);
			assertThrows(IllegalArgumentException.class, () -> receiver.receive(negativeDuration));
			assertThrows(IllegalArgumentException.class, () -> receiver.receive(null));
		}
	}
}
