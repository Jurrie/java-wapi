package org.jurr.java.wapi.client.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

import com.github.valfirst.slf4jtest.TestLoggerFactory;

/** Discovery over loopback, with fake consoles on 127.0.0.2 and 127.0.0.3. */
class WapiDiscoveryTest
{
	private static final Duration TIMEOUT = Duration.ofMillis(300);

	private final List<DatagramSocket> sockets = new ArrayList<>();

	@AfterEach
	void closeSockets()
	{
		for (final var socket : sockets)
		{
			socket.close();
		}
	}

	@Test
	void sendsWingQuestionAndCollectsEveryReply() throws Exception
	{
		final var first = bind("127.0.0.2", 0);
		final var second = bind("127.0.0.3", first.getLocalPort());
		// As for a broadcast: one request, a reply from each console
		final var request = answer(first, new Reply(first, "WING,192.168.1.62,WING-CLAUDE,ngc-full,S123,3.1.1"),
				new Reply(second, "WING,0.0.0.0,wingemul,ngc-full,0,3.1.1"));

		final var consoles = WapiDiscovery.discover(List.of(address("127.0.0.2")), first.getLocalPort(), TIMEOUT);

		assertEquals("WING?", request.get(1, TimeUnit.SECONDS));
		assertEquals(2, consoles.size());
		assertEquals("WING-CLAUDE", consoles.get(0).consoleName());
		assertEquals(address("127.0.0.2"), consoles.get(0).address());
		assertEquals("wingemul", consoles.get(1).consoleName());
		assertEquals(address("127.0.0.3"), consoles.get(1).address());
	}

	@Test
	void keepsOneReplyPerConsole() throws Exception
	{
		final var console = bind("127.0.0.2", 0);
		answer(console, new Reply(console, "WING,192.168.1.62,WING-CLAUDE,ngc-full,S123,3.1.1"), new Reply(console, "WING,192.168.1.62,WING-CLAUDE,ngc-full,S123,3.1.1"));

		assertEquals(1, WapiDiscovery.discover(List.of(address("127.0.0.2")), console.getLocalPort(), TIMEOUT).size());
	}

	@Test
	void skipsMalformedReplies() throws Exception
	{
		final var first = bind("127.0.0.2", 0);
		final var second = bind("127.0.0.3", first.getLocalPort());
		answer(first, new Reply(second, "BAD"), new Reply(first, "WING,192.168.1.62,WING-CLAUDE,ngc-full,S123,3.1.1"));

		final var consoles = WapiDiscovery.discover(List.of(address("127.0.0.2")), first.getLocalPort(), TIMEOUT);

		assertEquals(1, consoles.size());
		assertEquals("WING-CLAUDE", consoles.get(0).consoleName());
	}

	@Test
	void logsAReplyThatIsNotFromAConsole() throws Exception
	{
		TestLoggerFactory.clearAll();
		final var first = bind("127.0.0.2", 0);
		answer(first, new Reply(first, "BAD"));

		WapiDiscovery.discover(List.of(address("127.0.0.2")), first.getLocalPort(), TIMEOUT);

		final var logged = TestLoggerFactory.getTestLogger(WapiDiscovery.class).getAllLoggingEvents();
		assertEquals(1, logged.size());
		assertEquals(Level.DEBUG, logged.get(0).getLevel());
		assertTrue(logged.get(0).getFormattedMessage().contains("BAD"), logged.get(0).getFormattedMessage());
	}

	@Test
	void returnsNothingWhenNoConsoleAnswers() throws Exception
	{
		final var silent = bind("127.0.0.2", 0);

		assertTrue(WapiDiscovery.discover(List.of(address("127.0.0.2")), silent.getLocalPort(), TIMEOUT).isEmpty());
	}

	@Test
	void sendsToEveryDestination() throws Exception
	{
		final var first = bind("127.0.0.2", 0);
		final var second = bind("127.0.0.3", first.getLocalPort());
		answer(first, new Reply(first, "WING,192.168.1.62,FOH,ngc-full,S1,3.1.1"));
		answer(second, new Reply(second, "WING,192.168.1.63,MON,ngc-full,S2,3.1.1"));

		final var consoles = WapiDiscovery.discover(List.of(address("127.0.0.2"), address("127.0.0.3")), first.getLocalPort(), TIMEOUT);

		assertEquals(2, consoles.size());
	}

	@Test
	void singleAddressIsUsedAsIs() throws Exception
	{
		final var console = bind("127.0.0.2", 0);
		answer(console, new Reply(console, "WING,192.168.1.62,WING-CLAUDE,ngc-full,S123,3.1.1"));

		assertEquals(1, WapiDiscovery.discover("127.0.0.2", console.getLocalPort(), TIMEOUT).size());
	}

	@Test
	void subnetGoesToItsBroadcastAddress() throws Exception
	{
		assertEquals(address("192.168.1.255"), WapiDiscovery.destination("192.168.1.0/24"));
		assertEquals(address("192.168.1.1"), WapiDiscovery.destination("192.168.1.1"));
	}

	@Test
	void rejectsInvalidArguments()
	{
		assertThrows(IllegalArgumentException.class, () -> WapiDiscovery.discover("192.168.1.0/31"));
		assertThrows(IllegalArgumentException.class, () -> WapiDiscovery.discover("192.168.1.1", Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> WapiDiscovery.discover((String) null));
	}

	@Test
	void localBroadcastAddressesAreIpv4()
	{
		for (final var address : WapiDiscovery.localBroadcastAddresses())
		{
			assertTrue(address instanceof Inet4Address, String.valueOf(address));
		}
	}

	private DatagramSocket bind(final String host, final int port) throws Exception
	{
		final var socket = new DatagramSocket(new InetSocketAddress(address(host), port));
		sockets.add(socket);
		return socket;
	}

	/** Receives one request on {@code console} and sends the replies to its sender. */
	private static CompletableFuture<String> answer(final DatagramSocket console, final Reply... replies) throws SocketException
	{
		console.setSoTimeout(2000);
		return CompletableFuture.supplyAsync(() -> {
			try
			{
				final var request = new DatagramPacket(new byte[64], 64);
				console.receive(request);
				for (final var reply : replies)
				{
					final byte[] text = reply.text.getBytes(StandardCharsets.US_ASCII);
					reply.from.send(new DatagramPacket(text, text.length, request.getSocketAddress()));
				}
				return new String(request.getData(), 0, request.getLength(), StandardCharsets.US_ASCII);
			}
			catch (final Exception e)
			{
				throw new IllegalStateException(e);
			}
		});
	}

	private static InetAddress address(final String literal) throws Exception
	{
		return InetAddress.getByName(literal);
	}

	/** A reply of a fake console: the socket it is sent from, and its text. */
	private record Reply(DatagramSocket from, String text)
	{
	}
}
