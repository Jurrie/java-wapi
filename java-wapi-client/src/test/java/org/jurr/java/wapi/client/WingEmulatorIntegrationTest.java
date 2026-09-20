package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.client.transport.TcpWapiTransport;
import org.jurr.java.wapi.node.WapiNodeDefinitionCodec;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class WingEmulatorIntegrationTest
{

	@Test
	void emulatorTcpPortIsReachable() throws IOException
	{
		final var host = host();
		final int port = port();

		try (var socket = connectOrSkip(host, port))
		{
			assertTrue(socket.isConnected());
		}
	}

	@Test
	void wapiClientCanConnectToEmulator()
	{
		final var host = host();
		final int port = port();

		final var transport = new TcpWapiTransport();
		try
		{
			transport.connect(new WapiSession(host, port));
		}
		catch (final WapiException e)
		{
			Assumptions.abort("WING console is not reachable at " + host + ":" + port + ": " + e.getMessage());
		}
		try
		{
			assertTrue(transport.isConnected());
		}
		finally
		{
			transport.disconnect();
		}
	}

	@Test
	void emulatorRespondsToNodeDefinitionRequest() throws IOException
	{
		final var host = host();
		final int port = port();

		final var socket = connectOrSkip(host, port);
		try
		{
			socket.setSoTimeout(1500);
			final var channelCodec = new WapiNativeChannelCodec();
			final var request = channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryStreamCodec.encodeNodeDefinitionRequest());
			socket.getOutputStream().write(request.payload());
			socket.getOutputStream().flush();

			final var response = new byte[256];
			final int read;
			try
			{
				read = socket.getInputStream().read(response);
			}
			catch (final java.net.SocketTimeoutException e)
			{
				Assumptions.assumeTrue(false, "Wing emulator did not return a node definition response: " + e.getMessage());
				return;
			}
			Assumptions.assumeTrue(read > 0, "Wing emulator did not return a node definition response");

			final byte[] frame = Arrays.copyOf(response, read);
			final var packet = new WapiPacket(frame);
			final byte[] decoded = channelCodec.decode(packet).get(0).payload();
			assertTrue(decoded.length > 0, "Decoded WAPI node definition response is empty");
			assertEquals(WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, decoded[0] & 0xFF);

			final var definition = WapiNodeDefinitionCodec.parse(decoded);
			assertNotNull(definition.name());
			assertTrue(!definition.name().isEmpty(), "Node name should be present in the response");
		}
		finally
		{
			socket.close();
		}
	}

	/** Skips the test unless a console or emulator address is given with {@code -Dwing.host}. */
	private static String host()
	{
		final var host = System.getProperty("wing.host");
		Assumptions.assumeTrue(host != null && !host.isEmpty(), "Set -Dwing.host to run the tests against a WING console or emulator");
		return host;
	}

	private static int port()
	{
		return Integer.getInteger("wing.port", 2222);
	}

	/** Connects to the console, or skips the test when it cannot be reached. */
	private static Socket connectOrSkip(final String host, final int port) throws IOException
	{
		final var socket = new Socket();
		try
		{
			socket.connect(new InetSocketAddress(host, port), 1500);
			return socket;
		}
		catch (final IOException e)
		{
			socket.close();
			Assumptions.abort("WING console is not reachable at " + host + ":" + port + ": " + e.getMessage());
			return null;
		}
	}
}
