package org.jurr.java.wapi.meter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.junit.jupiter.api.Test;

/**
 * Meter reports captured from a WING console with WapiMeterListenExample, request ID 1. Channel 28 carries
 * an oscillator with mono pink noise, so its in L and in R are always equal: a marker for the position of
 * its block. A value of -32768 (0x8000) means no signal.
 */
class WapiCapturedMeterReportsTest
{
	private static final int NO_SIGNAL = -32768;

	@Test
	void decodesSingleChannel()
	{
		// ch28: 00000001 | f43a f43a 8000 8000 f3d2 0000 d3ad 0000
		final var values = decode("00000001f43af43a80008000f3d20000d3ad0000", 8);

		assertEquals(-3014, values.get(0).intValue());
		assertEquals(values.get(0), values.get(1));
		assertEquals(NO_SIGNAL, values.get(2).intValue());
		assertEquals(-11.77F, WapiMeterData.decode(WapiBinaryDataCodec.decodeHex("00000001f43af43a80008000f3d20000d3ad0000", 64)).decibelValues().get(0), 0.01F);
	}

	@Test
	void decodesMixedGroupsInRequestOrderWithTheirValueCounts()
	{
		// mon,ch28,ch1,fx1,dca1: 6 + 8 + 8 + 10 + 4 = 36 values
		final var values = decode("00000001"
				+ "800080008000800080008000" // mon: solo L/R, mon1 L/R, mon2 L/R
				+ "f16af16a80008000f3820000d4b20000" // ch28: oscillator, in L == in R
				+ "c464c4648b8b8b8b800001008fcb0000" // ch1: quiet input, gate closed (gate gain 256)
				+ "b342b342acddaa4c000000000000000000000000" // fx1: in L/R, out L/R, state meters 1..6
				+ "8000800080008000", 36); // dca1: pre L/R, post L/R

		for (var i = 0; i < 6; i++)
		{
			assertEquals(NO_SIGNAL, values.get(i).intValue(), "monitor value " + (i + 1));
		}
		assertEquals(-3734, values.get(6).intValue());
		assertEquals(values.get(6), values.get(7));
		assertEquals(-15260, values.get(14).intValue());
		assertEquals(256, values.get(19).intValue(), "ch1 gate gain");
		for (var i = 26; i < 32; i++)
		{
			assertEquals(0, values.get(i).intValue(), "fx1 state meter " + (i - 25));
		}
		for (var i = 32; i < 36; i++)
		{
			assertEquals(NO_SIGNAL, values.get(i).intValue(), "dca1 value " + (i - 31));
		}
	}

	@Test
	void followsRequestOrderRatherThanChannelNumber()
	{
		// ch1,ch28: the oscillator block (in L == in R) comes second
		final var values = decode("00000001"
				+ "b9bdb9bd800080008000010083e80000" // ch1
				+ "f27df27d80008000f3ff0000d6880000", 16); // ch28

		assertEquals(-17987, values.get(0).intValue());
		assertEquals(-3459, values.get(8).intValue());
		assertEquals(values.get(8), values.get(9));
	}

	@Test
	void decodesChannelV2WithElevenValues()
	{
		// ch28v2: in L/R, out L/R, gate key, gate gain, gate led, dyn key, dyn gain, dyn state, automix gain
		final var values = decode("00000001f24df24d80008000f24d00000000d387000000000000", 11);

		assertEquals(-3507, values.get(0).intValue());
		assertEquals(values.get(0), values.get(1));
		assertEquals(0, values.get(6).intValue(), "gate led");
		assertEquals(0, values.get(9).intValue(), "dyn state");
	}

	private static List<Integer> decode(final String hex, final int expectedValues)
	{
		final var report = WapiMeterData.decode(WapiBinaryDataCodec.decodeHex(hex, 256));
		assertEquals(1, report.requestId());
		assertEquals(expectedValues, report.rawValues().size());
		return report.rawValues();
	}
}
