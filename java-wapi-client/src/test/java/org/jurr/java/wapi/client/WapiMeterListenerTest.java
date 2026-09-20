package org.jurr.java.wapi.client;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.meter.WapiMeterData;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.junit.jupiter.api.Test;

/** Meter listeners get every report as it arrives, on a dispatcher thread of their own. */
class WapiMeterListenerTest
{
	@Test
	void aListenerGetsEachReportInOrder() throws Exception
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()); var sender = new DatagramSocket())
		{
			final var heard = new Recorder(2);
			client.addMeterListener(heard);
			final var port = client.wMeterUDPPort(0);

			// 00000001 0000 | 00000002 0000
			send(sender, port, new byte[] { 0, 0, 0, 1, 0, 0 });
			send(sender, port, new byte[] { 0, 0, 0, 2, 0, 0 });

			assertTrue(heard.await());
			assertEquals(List.of(1, 2), heard.requestIds());
			// wGetMeters still gets them as well
			assertEquals(1, client.wGetMeters(Duration.ofSeconds(1)).orElseThrow().requestId());
		}
	}

	@Test
	void aBlockedEventListenerDoesNotHoldUpMeterListeners() throws Exception
	{
		final var mute = new WapiToken("ch.1.mute");
		final var transport = new ScriptedWapiTransport();
		// d7 <ch.1.mute> 01: an event whose listener blocks
		transport.enqueueResponse(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				WapiBinaryDataCodec.decodeHex("d7" + WapiBinaryDataCodec.encodeHex(mute.hashBytes()) + "01", 16)));
		final var release = new CountDownLatch(1);
		final var blocked = new CountDownLatch(1);
		try (var client = new WapiClient(transport); var sender = new DatagramSocket())
		{
			client.addEventListener(event -> {
				blocked.countDown();
				try
				{
					release.await();
				}
				catch (final InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
			});
			final var heard = new Recorder(1);
			client.addMeterListener(heard);
			client.wOpen("192.168.1.62");
			assertTrue(blocked.await(1, TimeUnit.SECONDS));
			final var port = client.wMeterUDPPort(0);

			// 00000007 0000
			send(sender, port, new byte[] { 0, 0, 0, 7, 0, 0 });

			assertTrue(heard.await());
			release.countDown();
		}
	}

	@Test
	void aClosedSubscriptionIsNotCalled() throws Exception
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()); var sender = new DatagramSocket())
		{
			final var closed = new Recorder(1);
			client.addMeterListener(closed).close();
			final var open = new Recorder(1);
			client.addMeterListener(open);
			final var port = client.wMeterUDPPort(0);

			// 00000007 0000
			send(sender, port, new byte[] { 0, 0, 0, 7, 0, 0 });

			assertTrue(open.await());
			await().during(Duration.ofMillis(100)).atMost(Duration.ofSeconds(1)).until(closed.reports::isEmpty);
		}
	}

	@Test
	void aFailingListenerDoesNotStopTheOthers() throws Exception
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()); var sender = new DatagramSocket())
		{
			client.addMeterListener(report -> {
				throw new IllegalStateException("listener failure");
			});
			final var heard = new Recorder(2);
			client.addMeterListener(heard);
			final var port = client.wMeterUDPPort(0);

			// 00000001 0000 | 00000002 0000
			send(sender, port, new byte[] { 0, 0, 0, 1, 0, 0 });
			send(sender, port, new byte[] { 0, 0, 0, 2, 0, 0 });

			assertTrue(heard.await());
		}
	}

	private static void send(final DatagramSocket sender, final int port, final byte[] report) throws Exception
	{
		sender.send(new DatagramPacket(report, report.length, InetAddress.getLoopbackAddress(), port));
	}

	private static final class Recorder implements WapiMeterListener
	{
		private final List<WapiMeterData> reports = Collections.synchronizedList(new ArrayList<>());
		private final CountDownLatch called;

		Recorder(final int expected)
		{
			called = new CountDownLatch(expected);
		}

		@Override
		public void onMeters(final WapiMeterData report)
		{
			reports.add(report);
			called.countDown();
		}

		boolean await() throws InterruptedException
		{
			return called.await(1, TimeUnit.SECONDS);
		}

		List<Integer> requestIds()
		{
			synchronized (reports)
			{
				return reports.stream().map(WapiMeterData::requestId).toList();
			}
		}
	}
}
