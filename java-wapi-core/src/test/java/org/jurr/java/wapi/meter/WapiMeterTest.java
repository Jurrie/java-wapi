package org.jurr.java.wapi.meter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;

class WapiMeterTest
{
	@Test
	void requestKeepsItsRequestId()
	{
		assertEquals(7, new WapiMeterRequest(7, 14135).requestId());
	}

	@Test
	void encodesDocumentedMeterRequest()
	{
		final var request = new WapiMeterRequest(2, 10026)
				.addGroup(WapiMeterGroup.CHANNEL, 1)
				.encode();

		// d3272a d400000002 dca000 de
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_INT16, 0x27, 0x2A,
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 2,
				(byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST, (byte) WapiMeterGroup.CHANNEL.getToken(), 0, (byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}, request);
	}

	@Test
	void monitorGroupHasNoIndexByte()
	{
		final var request = new WapiMeterRequest(2, 14135).addGroup(WapiMeterGroup.MONITOR).encode();

		// d33737 d400000002 dca9 de
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_INT16, 0x37, 0x37,
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 2,
				(byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST, (byte) WapiMeterGroup.MONITOR.getToken(), (byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}, request);
	}

	@Test
	void rtaGroupHasNoIndexByte()
	{
		final var request = new WapiMeterRequest(2, 14135).addGroup(WapiMeterGroup.RTA).encode();

		// d33737 d400000002 dcaa de
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_INT16, 0x37, 0x37,
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 2,
				(byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST, (byte) WapiMeterGroup.RTA.getToken(), (byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}, request);
	}

	@Test
	void encodesDocumentedMultiIndexExample()
	{
		// PDF "Example specification": dc a0 00 01 08 a6 04 de requests channels 1, 2, 9 and fx 5
		final var request = new WapiMeterRequest(2, 14135)
				.addGroup(WapiMeterGroup.CHANNEL, 1, 2, 9)
				.addGroup(WapiMeterGroup.FX, 5)
				.encode();

		// d33737 d400000002 dca0000108a604 de
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_INT16, 0x37, 0x37,
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 2,
				(byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST,
				(byte) WapiMeterGroup.CHANNEL.getToken(), 0x00, 0x01, 0x08,
				(byte) WapiMeterGroup.FX.getToken(), 0x04,
				(byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}, request);
	}

	@Test
	void rejectsIndexOutsideGroupRange()
	{
		final var request = new WapiMeterRequest(2, 14135);

		assertThrows(IllegalArgumentException.class, () -> request.addGroup(WapiMeterGroup.MAIN, 5));
		assertThrows(IllegalArgumentException.class, () -> request.addGroup(WapiMeterGroup.CHANNEL, 0));
	}

	@Test
	void rejectsIndexForGroupWithoutIndex()
	{
		final var request = new WapiMeterRequest(2, 14135);

		assertThrows(IllegalArgumentException.class, () -> request.addGroup(WapiMeterGroup.MONITOR, 1));
	}

	@Test
	void rejectsMissingIndexForGroupWithIndex()
	{
		final var request = new WapiMeterRequest(2, 14135);

		assertThrows(IllegalArgumentException.class, () -> request.addGroup(WapiMeterGroup.CHANNEL));
	}

	@Test
	void rejectedGroupLeavesRequestUnchanged()
	{
		final var request = new WapiMeterRequest(2, 14135).addGroup(WapiMeterGroup.CHANNEL, 1);

		assertThrows(IllegalArgumentException.class, () -> request.addGroup(WapiMeterGroup.BUS, 3, 17));

		// d33737 d400000002 dca000 de
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_INT16, 0x37, 0x37,
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 2,
				(byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST, (byte) WapiMeterGroup.CHANNEL.getToken(), 0, (byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}, request.encode());
	}

	@Test
	void fromBitmaskRequestsChannelsOneAndFortyAsInPdf()
	{
		// PDF "wSetMetersRequest": mbits[30] = {0x80, 0, 0, 0, 0x01, 0} requests channels 1 and 40
		final var mask = new byte[30];
		mask[0] = (byte) 0x80;
		mask[4] = 0x01;

		// d33737 d400000002 dca00027 de
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_INT16, 0x37, 0x37,
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 2,
				(byte) WapiBinaryStreamCodec.TOKEN_DATA_REQUEST, (byte) WapiMeterGroup.CHANNEL.getToken(), 0x00, 0x27,
				(byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}, WapiMeterRequest.fromBitmask(2, 14135, mask).encode());
	}

	@Test
	void fromBitmaskUsesTheByteIndexesOfThePdfTable()
	{
		final var mask = new byte[30];
		// The first bit of every group, in table order
		for (final int index : new int[] { 0, 5, 6, 8, 9, 10, 12, 14, 16, 18, 19, 20, 25, 26, 28, 29 })
		{
			mask[index] = (byte) 0x80;
		}
		mask[7] = 0x01; // bus 16
		mask[17] = 0x20; // output 11

		final var expected = new WapiMeterRequest(2, 14135)
				.addGroup(WapiMeterGroup.CHANNEL, 1)
				.addGroup(WapiMeterGroup.AUX, 1)
				.addGroup(WapiMeterGroup.BUS, 1, 16)
				.addGroup(WapiMeterGroup.MAIN, 1)
				.addGroup(WapiMeterGroup.MATRIX, 1)
				.addGroup(WapiMeterGroup.DCA, 1)
				.addGroup(WapiMeterGroup.FX, 1)
				.addGroup(WapiMeterGroup.SOURCE, 1)
				.addGroup(WapiMeterGroup.OUTPUT, 1, 11)
				.addGroup(WapiMeterGroup.MONITOR)
				.addGroup(WapiMeterGroup.RTA)
				.addGroup(WapiMeterGroup.CHANNEL_V2, 1)
				.addGroup(WapiMeterGroup.AUX_V2, 1)
				.addGroup(WapiMeterGroup.BUS_V2, 1)
				.addGroup(WapiMeterGroup.MAIN_V2, 1)
				.addGroup(WapiMeterGroup.MATRIX_V2, 1)
				.encode();
		assertArrayEquals(expected, WapiMeterRequest.fromBitmask(2, 14135, mask).encode());
	}

	@Test
	void fromBitmaskRejectsBitsOutsideTheGroupRange()
	{
		// DCA 9: the bitmask table has room for DCA 1-16, PDF Table 4 allows 1-8
		final var mask1 = maskWith(11, 0x80);
		assertThrows(IllegalArgumentException.class, () -> WapiMeterRequest.fromBitmask(2, 14135, mask1));
		// Main 5
		final var mask2 = maskWith(8, 0x08);
		assertThrows(IllegalArgumentException.class, () -> WapiMeterRequest.fromBitmask(2, 14135, mask2));
		// A second monitor bit
		final var mask3 = maskWith(18, 0x40);
		assertThrows(IllegalArgumentException.class, () -> WapiMeterRequest.fromBitmask(2, 14135, mask3));
	}

	@Test
	void fromBitmaskRequiresThirtyBytes()
	{
		assertThrows(IllegalArgumentException.class, () -> WapiMeterRequest.fromBitmask(2, 14135, new byte[29]));
		assertThrows(IllegalArgumentException.class, () -> WapiMeterRequest.fromBitmask(2, 14135, null));
	}

	private static byte[] maskWith(final int index, final int bits)
	{
		final var mask = new byte[30];
		mask[index] = (byte) bits;
		return mask;
	}

	@Test
	void decodesSignedMeterValues()
	{
		final var data = WapiMeterData.decode(new byte[] {
				0, 0, 0, 2, (byte) 0xFC, 0, 0x01, 0
		});

		assertEquals(2, data.requestId());
		assertEquals(-4.0F, data.decibelValues().get(0), 0.001F);
		assertEquals(1.0F, data.decibelValues().get(1), 0.001F);
	}
}
