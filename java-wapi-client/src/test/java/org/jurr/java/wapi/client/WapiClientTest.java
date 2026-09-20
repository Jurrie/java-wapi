package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.meter.WapiMeterGroup;
import org.jurr.java.wapi.meter.WapiMeterRequest;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;

class WapiClientTest
{
	@Test
	void meterSubscriptionUsesMeterChannel()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession("127.0.0.1", 9999), transport))
		{
			final var request = new WapiMeterRequest(2, 14135).addGroup(WapiMeterGroup.CHANNEL, 2);

			client.wSetMetersRequest(request);
			assertEquals(1, transport.getSentPackets().size());
			// dfd3 d33737 d400000002 dca001 de
			assertArrayEquals(new byte[] {
					(byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE, (byte) (WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + WapiNativeChannelCodec.NRP_METER_CHANNEL_ID),
					(byte) WapiBinaryStreamCodec.TOKEN_INT16, 0x37, 0x37,
					(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 2,
					(byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST, (byte) WapiMeterGroup.CHANNEL.getToken(), 0x01, (byte) WapiBinaryStreamCodec.TOKEN_END_DATA
			}, transport.getSentPackets().get(0).payload());
		}
	}

	@Test
	void meterBitmaskRequestUsesThePortOfTheMeterReceiver()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession("127.0.0.1", 9999), transport))
		{
			final var mask = new byte[30];
			mask[0] = (byte) 0x80;
			mask[4] = 0x01;
			final int port = client.wMeterUDPPort(0);
			client.wSetMetersRequest(2, mask);

			final var frames = new WapiNativeChannelCodec().decode(transport.getSentPackets().get(0));
			assertEquals(WapiNativeChannelCodec.NRP_METER_CHANNEL_ID, frames.get(0).channelId());
			assertArrayEquals(WapiMeterRequest.fromBitmask(2, port, mask).encode(), frames.get(0).payload());
		}
	}

	@Test
	void meterBitmaskRequestNeedsMeterReceiver()
	{
		try (var client = new WapiClient(new WapiSession("127.0.0.1", 9999), new ScriptedWapiTransport()))
		{
			assertThrows(WapiException.class, () -> client.wSetMetersRequest(2, new byte[30]));
		}
	}

	@Test
	void meterRenewalPreservesEscapeSensitiveRequestId()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession("127.0.0.1", 9999), transport))
		{
			// wire: dfd3 d401dfded302 (the 0xDF inside the request ID is escaped)
			client.wRenewMeters(0x01DFD302);
			assertEquals(1, transport.getSentPackets().size());
			final var frames = new WapiNativeChannelCodec().decode(transport.getSentPackets().get(0));

			assertEquals(1, frames.size());
			assertEquals(WapiNativeChannelCodec.NRP_METER_CHANNEL_ID, frames.get(0).channelId());
			// d401dfd302
			assertArrayEquals(new byte[] {
					(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0x01, (byte) 0xDF, (byte) 0xD3, 0x02
			}, frames.get(0).payload());
		}
	}

	@Test
	void setNodeSendsHashesOfResolvedNames()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession("127.0.0.1", 9999), transport))
		{
			client.wSetNode("/ch.1.fdr=4,mute=1");

			final var faderHash = new WapiToken("ch.1.fdr").hashBytes();
			final var muteHash = new WapiToken("ch.1.mute").hashBytes();
			// dfd1 d7 <ch.1.fdr> d540800000 d7 <ch.1.mute> d400000001 de
			assertArrayEquals(new byte[] {
					(byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE, (byte) (WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID),
					(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, faderHash[0], faderHash[1], faderHash[2], faderHash[3],
					(byte) WapiBinaryStreamCodec.TOKEN_FLOAT32, 0x40, (byte) 0x80, 0x00, 0x00,
					(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, muteHash[0], muteHash[1], muteHash[2], muteHash[3],
					(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0x00, 0x00, 0x00, 0x01,
					(byte) WapiBinaryStreamCodec.TOKEN_END_DATA
			}, transport.getSentPackets().get(0).payload());
		}
	}

	@Test
	void clientCanOpenAndCloseSession()
	{
		final var session = new WapiSession("127.0.0.1", 9999);
		try (var client = new WapiClient(session, new ScriptedWapiTransport()))
		{
			assertEquals("127.0.0.1", ((InetSocketAddress) client.getSession().socketAddress()).getHostString());
			client.wOpen("127.0.0.1");
			assertTrue(client.isConnected());
			client.wClose();
			assertFalse(client.isConnected());
		}
	}

	@Test
	void openWithEmptyHostIsNotSupported()
	{
		final var session = new WapiSession("127.0.0.1", 2222);
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(session, transport))
		{
			final var exception = assertThrows(WapiException.class, () -> client.wOpen(""));

			assertTrue(exception.getMessage().contains("WapiDiscovery"), exception.getMessage());
			assertEquals(session, client.getSession());
			assertTrue(transport.isConnected());
			assertEquals(List.of("connect 127.0.0.1"), transport.getConnections());
		}
	}

	@Test
	void openWithHostWhileConnectedConnectsAnew()
	{
		final var session = new WapiSession("127.0.0.1", 2223);
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(session, transport))
		{
			client.wOpen("192.168.1.62");
			client.wOpen("192.168.1.62");

			// The host is replaced, the port is kept
			assertEquals(new WapiSession("192.168.1.62", 2222), client.getSession());
			assertTrue(transport.isConnected());
			// A host always opens a new connection; the old one is closed first
			assertEquals(List.of("connect 127.0.0.1", "disconnect", "connect 192.168.1.62", "disconnect", "connect 192.168.1.62"),
					transport.getConnections());
		}
	}

	@Test
	void openWithNullHostReconnectsToTheSessionHost()
	{
		final var session = new WapiSession("127.0.0.1", 2222);
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(session, transport))
		{
			transport.disconnect();

			client.wOpen();

			assertEquals(session, client.getSession());
			assertTrue(transport.isConnected());
		}
	}

	@Test
	void openWithNullHostKeepsAnOpenConnection()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession("127.0.0.1", 2222), transport))
		{
			client.wOpen();

			assertEquals(List.of("connect 127.0.0.1"), transport.getConnections());
		}
	}

	@Test
	void wToggleTokenIntSendsAClickRecordOnChannel1()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var mute = new WapiToken("ch.1.mute");
			final var hash = mute.hashBytes();

			client.wToggleTokenInt(mute);

			// df d1 d7 <ch.1.mute> d8: click (toggle), PDF Table 2
			assertArrayEquals(new byte[] {
					(byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE,
					(byte) (WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID),
					(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3],
					(byte) WapiBinaryStreamCodec.TOKEN_CLICK
			}, transport.getSentPackets().get(0).payload());
			assertEquals(1, transport.getSentPackets().size());
		}
	}

	@Test
	void wClickTokenByteSendsAStepRecordOnChannel1()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var fader = new WapiToken("ch.1.fdr");
			final var hash = fader.hashBytes();

			client.wClickTokenByte(fader, (byte) 5);

			// df d1 d7 <ch.1.fdr> d9 05: step (inc/dec) by 5, PDF Table 2
			assertArrayEquals(new byte[] {
					(byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE,
					(byte) (WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID),
					(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3],
					(byte) WapiBinaryStreamCodec.TOKEN_STEP, 0x05
			}, transport.getSentPackets().get(0).payload());
			assertEquals(1, transport.getSentPackets().size());
		}
	}

	@Test
	void toggleAndClickRejectAMissingTokenWithoutSending()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			assertThrows(WapiException.class, () -> client.wToggleTokenInt(null));
			assertThrows(WapiException.class, () -> client.wClickTokenByte(null, (byte) 5));

			assertTrue(transport.getSentPackets().isEmpty());
		}
	}

	@Test
	void transportTracksItsConnectionState()
	{
		final var transport = new ScriptedWapiTransport();

		assertFalse(transport.isConnected());
		transport.connect(new WapiSession("127.0.0.1", 9999));
		assertTrue(transport.isConnected());

		transport.disconnect();
		assertFalse(transport.isConnected());
	}

	@Test
	void clientReconnectsAfterTheTransportDisconnected()
	{
		final var transport = new ScriptedWapiTransport();
		try (var client = new WapiClient(new WapiSession("127.0.0.1", 9999), transport))
		{
			transport.disconnect();
			assertFalse(client.isConnected());

			client.wOpen("127.0.0.1");
			assertTrue(client.isConnected());
			assertTrue(transport.isConnected());
		}
	}
}
