package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WapiNativeChannelCodecTest
{
	@Test
	void channelFrameEncodesDocumentedEscapingExample()
	{
		final var codec = new WapiNativeChannelCodec();

		final var channelId = 1;
		final var raw = new byte[] { (byte) 0xD7, 0x02, (byte) 0xDF, (byte) 0xAF, 0x0E, 0x02 };
		final var encoded = codec.encode(channelId, raw);

		assertArrayEquals(new byte[] { (byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE, (byte) WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + channelId, (byte) 0xD7, 0x02, (byte) 0xDF, (byte) 0xAF, 0x0E, 0x02 }, encoded.payload());
	}

	@Test
	void channelFrameDecodesDocumentedEscapingExample()
	{
		final var codec = new WapiNativeChannelCodec();

		final var channelId = 1;
		final var raw = new byte[] { (byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE, (byte) WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + channelId, (byte) 0xD7, 0x02, (byte) 0xDF, (byte) 0xAF, 0x0E, 0x02 };
		final var encoded = new WapiPacket(raw);
		final var decoded = codec.decode(encoded).get(0);

		assertEquals(channelId, decoded.channelId());
		assertArrayEquals(new byte[] { (byte) 0xD7, 0x02, (byte) 0xDF, (byte) 0xAF, 0x0E, 0x02 }, decoded.payload());
	}

	@Test
	void roundTripsBytesThatContainEscapeAndMarkerValues()
	{
		final var channelId = 1;
		final var codec = new WapiNativeChannelCodec();
		final var payload = new byte[] { 1, (byte) 0xDF, 3, (byte) 0xD1, 7 };

		final var encoded = codec.encode(channelId, payload);
		final var decoded = codec.decode(encoded).get(0);

		assertEquals(channelId, decoded.channelId());
		assertArrayEquals(payload, decoded.payload());
	}

	@Test
	void roundTripsEndOfDataByteImmediatelyAfterEscape()
	{
		final var channelId = 1;
		final var encoder = new WapiNativeChannelCodec();
		final var decoder = new WapiNativeChannelCodec();
		final var payload = new byte[] { (byte) 0xD7, (byte) 0xDF, (byte) 0xDE };

		final var encoded = encoder.encode(channelId, payload);
		final var decoded = decoder.decode(encoded);

		assertEquals(1, decoded.size());
		assertEquals(channelId, decoded.get(0).channelId());
		assertArrayEquals(payload, decoded.get(0).payload(), "End-of-data byte 0xDE must survive encoding after escape byte 0xDF");
	}

	@Test
	void channelFrameEncodesDocumentedMarkerEscapingExample()
	{
		// PDF "Sample transmit routine": sending d702dfd10e02 to channel 1 (ChID 0) transfers dfd0d702dfded10e02
		final var codec = new WapiNativeChannelCodec();

		final var channelId = 0;
		final var raw = new byte[] { (byte) 0xD7, 0x02, (byte) 0xDF, (byte) 0xD1, 0x0E, 0x02 };
		final var encoded = codec.encode(channelId, raw);

		// dfd0d702dfded10e02
		assertArrayEquals(new byte[] { (byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE, (byte) WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + channelId, (byte) 0xD7, 0x02, (byte) 0xDF, (byte) 0xDE, (byte) 0xD1, 0x0E, 0x02 }, encoded.payload());
	}

	@Test
	void trailingEscapeIsIgnoredRatherThanDecodedAsGarbage()
	{
		final var channelId = 2;
		final var codec = new WapiNativeChannelCodec();
		final var raw = new byte[] { (byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE, (byte) WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + channelId, 0x01, (byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE };
		final var encoded = new WapiPacket(raw);

		final var decoded = codec.decode(encoded).get(0);

		assertEquals(channelId, decoded.channelId());
		assertArrayEquals(new byte[] { 0x01 }, decoded.payload());
	}

	@Test
	void channelMarkerBytesAreDiscardedWhenTheyAppearAsEscapeEscapes()
	{
		final var channelId = 3;
		final var codec = new WapiNativeChannelCodec();
		final var raw = new byte[] { (byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE, (byte) WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + channelId, (byte) WapiNativeChannelCodec.NRP_ESCAPE_CODE, (byte) WapiNativeChannelCodec.NRP_CHANNEL_ID_BASE + 1 };
		final var encoded = new WapiPacket(raw);

		final var decoded = codec.decode(encoded);
		assertTrue(decoded.isEmpty());
	}
}
