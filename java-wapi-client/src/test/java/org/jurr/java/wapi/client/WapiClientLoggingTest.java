package org.jurr.java.wapi.client;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;
import org.slf4j.event.Level;

import com.github.valfirst.slf4jtest.LoggingEvent;
import com.github.valfirst.slf4jtest.TestLoggerFactory;

/**
 * The client keeps going when a listener fails or a meter datagram is malformed, but logs it, so
 * that the cause can be found.
 */
class WapiClientLoggingTest
{
	private static final WapiToken MUTE = new WapiToken("ch.1.mute");

	@BeforeEach
	void clearLoggedEvents()
	{
		// All threads: the listeners run on the dispatcher threads
		TestLoggerFactory.clearAll();
	}

	@Test
	void aFailingEventListenerIsLogged()
	{
		final var failure = new IllegalStateException("event listener failure");
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(transport))
		{
			client.getTokenRegistry().register(MUTE);
			client.addEventListener(event -> {
				throw failure;
			});
			// d7 <ch.1.mute> 01
			transport.enqueueResponse(frame("d7" + WapiBinaryDataCodec.encodeHex(MUTE.hashBytes()) + "01"));

			client.wOpen("192.168.1.62");

			final var logged = awaitWarning(EventDelivery.class);
			assertSame(failure, logged.getThrowable().orElseThrow());
			assertTrue(logged.getFormattedMessage().contains("ch.1.mute"), logged.getFormattedMessage());
		}
	}

	@Test
	void aFailingConnectionListenerIsLogged()
	{
		final var failure = new IllegalStateException("connection listener failure");
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.addConnectionListener(cause -> {
				throw failure;
			});

			transport.enqueueEndOfStream();

			final var logged = awaitWarning(EventDelivery.class);
			assertSame(failure, logged.getThrowable().orElseThrow());
			assertTrue(logged.getFormattedMessage().contains("Connection closed by the console"), logged.getFormattedMessage());
		}
	}

	@Test
	void aFailingMeterListenerIsLogged() throws Exception
	{
		final var failure = new IllegalStateException("meter listener failure");
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()); var sender = new DatagramSocket())
		{
			client.addMeterListener(report -> {
				throw failure;
			});
			final var port = client.wMeterUDPPort(0);

			// 00000007 0000: report 7
			send(sender, port, new byte[] { 0, 0, 0, 7, 0, 0 });

			final var logged = awaitWarning(Meters.class);
			assertSame(failure, logged.getThrowable().orElseThrow());
			assertTrue(logged.getFormattedMessage().contains("7"), logged.getFormattedMessage());
		}
	}

	@Test
	void aMalformedMeterDatagramIsLogged() throws Exception
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()); var sender = new DatagramSocket())
		{
			final var port = client.wMeterUDPPort(0);

			// 000000: shorter than the 4-byte report id (PDF "Meter Data Packet Structure")
			send(sender, port, new byte[] { 0, 0, 0 });

			final var logged = awaitWarning(MeterReader.class);
			assertTrue(logged.getFormattedMessage().contains("Invalid meter report"), logged.getFormattedMessage());
		}
	}

	@Test
	void closingTheMeterPortIsNotLogged()
	{
		try (var client = new WapiClient(new WapiSession(), new ScriptedWapiTransport()))
		{
			client.wMeterUDPPort(0);
			client.wClose();

			// The reader thread ends with a closed socket, which is expected
			await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1))
					.until(() -> TestLoggerFactory.getTestLogger(MeterReader.class).getAllLoggingEvents().isEmpty());
		}
	}

	/** @return the first warning logged by the class, waiting for it at most one second */
	private static LoggingEvent awaitWarning(final Class<?> type)
	{
		final List<LoggingEvent> events = await("a warning from " + type.getSimpleName()).atMost(Duration.ofSeconds(1))
				.until(() -> TestLoggerFactory.getTestLogger(type).getAllLoggingEvents(), logged -> !logged.isEmpty());
		final var logged = events.get(0);
		assertEquals(Level.WARN, logged.getLevel());
		return logged;
	}

	private static void send(final DatagramSocket sender, final int port, final byte[] report) throws Exception
	{
		sender.send(new DatagramPacket(report, report.length, InetAddress.getLoopbackAddress(), port));
	}

	private static WapiPacket frame(final String hex)
	{
		return new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(hex, 64));
	}
}
