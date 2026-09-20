package org.jurr.java.wapi.client;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.discovery.WapiConsoleInfo;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;

class WapiLifecycleTest
{
	private static final WapiToken MUTE = new WapiToken("ch.1.mute");

	@Test
	void theUnconnectedConstructorDoesNotConnect()
	{
		final var transport = new ScriptedWapiTransport();

		try (var client = new WapiClient(transport))
		{
			assertTrue(transport.getConnections().isEmpty());
			assertFalse(client.isReading());
			assertInstanceOf(InetSocketAddress.class, client.getSession().socketAddress());
			assertEquals(WapiSession.DEFAULT_PORT, ((InetSocketAddress) client.getSession().socketAddress()).getPort());
			final var exception = assertThrows(WapiException.class, () -> client.wGetTokenInt(MUTE));
			assertTrue(exception.getMessage().contains("not connected"), exception.getMessage());
		}
	}

	@Test
	void listenersAndNamesRegisteredBeforeWOpenGetTheFirstEvent()
	{
		final var transport = new ScriptedWapiTransport();
		// d7 <ch.1.mute> 01: sent by the console right after connecting
		transport.enqueueResponse(frame("d7" + WapiBinaryDataCodec.encodeHex(MUTE.hashBytes()) + "01"));
		try (var client = new WapiClient(transport))
		{
			final var heard = Collections.synchronizedList(new ArrayList<WapiEvent>());
			client.addEventListener(heard::add);
			client.getTokenRegistry().register(MUTE);

			client.wOpen("192.168.1.62");

			assertEquals("connect 192.168.1.62", transport.getConnections().get(0));
			await().atMost(Duration.ofSeconds(1)).until(() -> !heard.isEmpty());
			assertEquals(1, heard.size());
			assertEquals("ch.1.mute", heard.get(0).token().name());
		}
	}

	@Test
	void discoveryThenListenersThenWOpenOfTheConsole() throws Exception
	{
		// As found by WapiDiscovery: the emulator reports 0.0.0.0, the reply came from 192.168.1.20
		final var console = WapiConsoleInfo.fromReply("WING,0.0.0.0,wingemul,ngc-full,0,3.1.1", InetAddress.getByName("192.168.1.20"));
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(transport))
		{
			final var heard = Collections.synchronizedList(new ArrayList<WapiEvent>());
			client.getTokenRegistry().register(MUTE);
			client.addEventListener(heard::add);
			transport.enqueueResponse(frame("d7" + WapiBinaryDataCodec.encodeHex(MUTE.hashBytes()) + "01"));

			client.wOpen(console);

			assertEquals("connect 192.168.1.20", transport.getConnections().get(0));
			assertInstanceOf(InetSocketAddress.class, client.getSession().socketAddress());
			assertEquals("192.168.1.20", ((InetSocketAddress) client.getSession().socketAddress()).getHostString());
			assertEquals(WapiConsoleInfo.PORT, ((InetSocketAddress) client.getSession().socketAddress()).getPort());
			await().atMost(Duration.ofSeconds(1)).until(() -> !heard.isEmpty());
			assertEquals("ch.1.mute", heard.get(0).token().name());
		}
	}

	@Test
	void isConnectedFollowsWOpenAndWClose()
	{
		try (var client = new WapiClient(new ScriptedWapiTransport()))
		{
			assertFalse(client.isConnected());

			client.wOpen("192.168.1.62");
			assertTrue(client.isConnected());

			client.wClose();
			assertFalse(client.isConnected());
		}
	}

	@Test
	void wOpenRequiresAConsole()
	{
		try (var client = new WapiClient(new ScriptedWapiTransport()))
		{
			assertThrows(WapiException.class, () -> client.wOpen((WapiConsoleInfo) null));
		}
	}

	@SuppressWarnings("resource")
	@Test
	void tryWithResourcesClosesTheClient()
	{
		final var transport = new ScriptedWapiTransport();
		final WapiClient leaked;
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			leaked = client;
			assertTrue(client.isReading());
		}

		assertFalse(transport.isConnected());
		assertFalse(leaked.isReading());
	}

	@Test
	void aConnectionClosedByTheConsoleIsDetected()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			transport.enqueueEndOfStream();
			await().atMost(Duration.ofSeconds(1)).until(() -> !client.isConnected());

			assertFalse(client.isConnected());
			assertFalse(transport.isConnected());
			assertFalse(client.isReading());
			final var timeout = Duration.ofSeconds(5);
			final var exception = assertThrows(WapiException.class, () -> client.wGetTokenInt(MUTE, timeout));
			assertTrue(exception.getMessage().contains("not connected"), exception.getMessage());
		}
	}

	private static WapiPacket frame(final String hex)
	{
		return new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(hex, 64));
	}
}
