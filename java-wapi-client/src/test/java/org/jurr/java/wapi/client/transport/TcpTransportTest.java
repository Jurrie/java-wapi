package org.jurr.java.wapi.client.transport;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

import jdk.net.ExtendedSocketOptions;
import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.client.ScenarioTiming;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;

class TcpTransportTest
{
	@Test
	void aFailedKeepAliveOptionIsWskal()
	{
		// Turning on keep-alive fails: WSKAL_NTW_ERROR
		final var transport = new TcpWapiTransport(() -> new Socket()
		{
			@Override
			public void setKeepAlive(final boolean on) throws SocketException
			{
				throw new SocketException("refused");
			}
		});

		final var session = new WapiSession("127.0.0.1", 9);

		final var error = assertThrows(WapiException.class, () -> transport.connect(session));

		assertEquals(WapiError.WSKAL_NTW_ERROR, error.getError());
		assertFalse(transport.isConnected());
	}

	@Test
	void aFailedNoDelayOptionIsWsndl()
	{
		// Turning on TCP_NODELAY fails: WSNDL_NTW_ERROR
		final var transport = new TcpWapiTransport(() -> new Socket()
		{
			@Override
			public void setTcpNoDelay(final boolean on) throws SocketException
			{
				throw new SocketException("refused");
			}
		});

		final var session = new WapiSession("127.0.0.1", 9);

		final var error = assertThrows(WapiException.class, () -> transport.connect(session));

		assertEquals(WapiError.WSNDL_NTW_ERROR, error.getError());
		assertFalse(transport.isConnected());
	}

	@Test
	void findsAnExtendedSocketOptionByName()
	{
		// Looked up by name, so that TcpWapiTransport also loads where jdk.net is missing, such as on Android
		assertEquals(ExtendedSocketOptions.TCP_KEEPIDLE, TcpWapiTransport.extendedOption("TCP_KEEPIDLE").orElseThrow());
	}

	@Test
	void aMissingExtendedSocketOptionIsEmpty()
	{
		assertTrue(TcpWapiTransport.extendedOption("NO_SUCH_OPTION").isEmpty());
	}

	@Test
	void tcpTransportDetectsADeadConnectionWithKeepAlive() throws Exception
	{
		try (var server = new ServerSocket(0))
		{
			final var transport = new TcpWapiTransport();
			transport.connect(new WapiSession("127.0.0.1", server.getLocalPort()));
			try (var accepted = server.accept())
			{
				final var socket = transport.socket();
				assertTrue(socket.getOption(StandardSocketOptions.SO_KEEPALIVE));
				assumeTrue(socket.supportedOptions().contains(ExtendedSocketOptions.TCP_KEEPIDLE), "TCP keep-alive options not supported here");
				// Probes after 1 s of silence, 1 s apart, 3 unanswered: a dead connection is found in about 4 s
				assertEquals(1, socket.getOption(ExtendedSocketOptions.TCP_KEEPIDLE));
				assertEquals(1, socket.getOption(ExtendedSocketOptions.TCP_KEEPINTERVAL));
				assertEquals(3, socket.getOption(ExtendedSocketOptions.TCP_KEEPCOUNT));
			}
			finally
			{
				transport.disconnect();
			}
		}
	}

	@Test
	void tcpTransportRejectsDisconnectedOperation()
	{
		final var transport = new TcpWapiTransport();
		final var packet = new WapiPacket(new byte[] { 1, 2, 3 });
		assertThrows(WapiException.class, () -> transport.sendAndReceive(packet));
	}

	@Test
	void tcpTransportReassemblesPartialAndMultipleFrames() throws Exception
	{
		final var codec = new WapiNativeChannelCodec();
		final byte[] firstFrame = codec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] { 1, 2, 3 }).payload();
		final byte[] secondFrame = codec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] { 2 }).payload();

		try (var server = new ServerSocket(0))
		{
			final var serverThread = new Thread(() -> {
				try (var socket = server.accept(); OutputStream out = socket.getOutputStream())
				{
					final byte[] partial = Arrays.copyOf(firstFrame, firstFrame.length - 1);
					out.write(partial);
					out.flush();
					// The console pauses within a frame and between frames, so they arrive in separate reads
					ScenarioTiming.pause(Duration.ofMillis(50));
					out.write(Arrays.copyOfRange(firstFrame, firstFrame.length - 1, firstFrame.length));
					out.flush();
					ScenarioTiming.pause(Duration.ofMillis(50));
					out.write(secondFrame);
					out.flush();
				}
				catch (final Exception e)
				{
					throw new RuntimeException(e);
				}
			});
			serverThread.start();

			final var session = new WapiSession("127.0.0.1", server.getLocalPort());
			final var transport = new TcpWapiTransport();
			transport.connect(session);

			try
			{
				final var firstChunk = codec.decode(transport.receive(Duration.ofMillis(200))).get(0);
				final var secondChunk = codec.decode(transport.receive(Duration.ofMillis(200))).get(0);
				final var thirdChunk = codec.decode(transport.receive(Duration.ofMillis(200))).get(0);
				assertArrayEquals(new byte[] { 1, 2 }, firstChunk.payload());
				assertArrayEquals(new byte[] { 3 }, secondChunk.payload());
				assertArrayEquals(new byte[] { 2 }, thirdChunk.payload());
			}
			finally
			{
				transport.disconnect();
				serverThread.join();
			}
		}
	}

	@Test
	void receiveReturnsBytesAsReceived() throws Exception
	{
		// dfd1 01 dfde 02: the redundant escape of 0xDF before 0x02 must reach the client unchanged
		final var sent = new byte[] {
				(byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE, (byte) (WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID), 0x01,
				(byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE, (byte) (WapiNativeChannelCodec.NRP_ESCAPE_CODE - 1), 0x02
		};
		try (var server = new ServerSocket(0))
		{
			final var transport = new TcpWapiTransport();
			transport.connect(new WapiSession("127.0.0.1", server.getLocalPort()));
			try (var socket = server.accept())
			{
				socket.getOutputStream().write(sent);
				socket.getOutputStream().flush();

				final var received = new ByteArrayOutputStream();
				while (received.size() < sent.length)
				{
					final var packet = transport.receive(Duration.ofMillis(200));
					if (packet == null || packet.length() == 0)
					{
						break;
					}
					received.write(packet.payload());
				}
				assertArrayEquals(sent, received.toByteArray());
			}
			finally
			{
				transport.disconnect();
			}
		}
	}

	@Test
	void zeroTimeoutReturnsOnlyWhatIsAvailable() throws Exception
	{
		try (var server = new ServerSocket(0))
		{
			final var transport = new TcpWapiTransport();
			transport.connect(new WapiSession("127.0.0.1", server.getLocalPort()));
			try (var socket = server.accept())
			{
				assertTimeoutPreemptively(Duration.ofMillis(200), () -> assertNull(transport.receive(Duration.ZERO)));

				socket.getOutputStream().write(new byte[] { 1, 2, 3 });
				socket.getOutputStream().flush();
				// Received bytes are returned without waiting, once they have arrived
				assertArrayEquals(new byte[] { 1, 2, 3 }, transport.receive(Duration.ofSeconds(1)).payload());
				socket.getOutputStream().write(new byte[] { 4 });
				socket.getOutputStream().flush();
				final var packet = await().atMost(Duration.ofSeconds(1)).until(() -> transport.receive(Duration.ZERO), Objects::nonNull);
				assertArrayEquals(new byte[] { 4 }, packet.payload());
			}
			finally
			{
				transport.disconnect();
			}
		}
	}

	@Test
	void rejectsNegativeAndMissingTimeouts() throws Exception
	{
		try (var server = new ServerSocket(0))
		{
			final var transport = new TcpWapiTransport();
			transport.connect(new WapiSession("127.0.0.1", server.getLocalPort()));
			final var negativeDuration = Duration.ofMillis(-1);
			try
			{
				assertThrows(IllegalArgumentException.class, () -> transport.receive(negativeDuration));
				assertThrows(IllegalArgumentException.class, () -> transport.receive(null));
			}
			finally
			{
				transport.disconnect();
			}
		}
	}

	@Test
	void connectingAgainClosesPreviousSocket() throws Exception
	{
		try (var server = new ServerSocket(0))
		{
			final var session = new WapiSession("127.0.0.1", server.getLocalPort());
			final var transport = new TcpWapiTransport();
			transport.connect(session);
			try (var first = server.accept())
			{
				transport.connect(session);
				try (var second = server.accept())
				{
					first.setSoTimeout(1000);
					assertEquals(-1, first.getInputStream().read(), "The first connection must be closed when connecting again");
				}
			}
			finally
			{
				transport.disconnect();
			}
		}
	}
}
