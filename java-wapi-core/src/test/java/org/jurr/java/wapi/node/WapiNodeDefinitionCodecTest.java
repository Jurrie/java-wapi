package org.jurr.java.wapi.node;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiType;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.junit.jupiter.api.Test;

class WapiNodeDefinitionCodecTest
{
	// Definitions captured from a WING console for the children of ch.1 (parent hash 878392cf = "ch.1")
	private static final String IN = "df0015878392cf286742ff000002696e05494e5055540000";
	private static final String NAME = "df0018878392cfc6ad548d0000046e616d65044e414d4500700010";
	private static final String FDR = "df0016878392cf38ae75c20000036664720546414445520031";
	private static final String PAN = "df0020878392cf377bd1d200000370616e0350414e0010c2c8000042c80000000000c9";
	private static final String MUTE = "df001e878392cff50f69f80000046d757465044d55544500400000000000000001";
	private static final String MON = "df0025878392cf127982ac0000036d6f6e074d4f4e204255530050000301410001420003412b4200";
	private static final String PTAP = "df0072878392cf5fe6d5af000004707461700954415020504f494e540050000802494e05494e5055540446494c540646494c5445520133055441502033013405544150203401350554415020350350464c07505245204644520341464c08504f53542046445204504f535409504f53542050524f43";
	private static final String PROC = "df00ab878392cf6796155000000470726f630750524f4345535300500018044745444900044745494400044749454400044947454400044744454900044744494500044749444500044947444500044547444900044547494400044549474400044945474400044544474900044544494700044549444700044945444700044445474900044445494700044449454700044944454700044447454900044447494500044449474500044944474500";
	// Float enum of ch.3.dyn.2 ("ratio"): 17 items from 1.1f ("1.1:1") to 100.0f ("100:1")
	private static final String RATIO = WapiNodeTreeTest.RATIO;
	private static final String ACTUAL_COLOR = "df0022878392cf18675ac000000424636f6c08414354434f4c4f5202400000000100000012";

	@Test
	void parsesCapturedNode()
	{
		final var definition = parse(IN);

		assertEquals("in", definition.name());
		assertEquals(WapiNodeKind.NODE, definition.nodeKind());
		assertNull(definition.metadata());
	}

	@Test
	void parsesCapturedString()
	{
		// flags 0x0070: type 7 in bits 4..7
		final var definition = parse(NAME);

		assertEquals(WapiNodeKind.STRING, definition.nodeKind());
		assertEquals(Integer.valueOf(16), definition.metadata().maxStringLength());
	}

	@Test
	void parsesCapturedFaderWithDecibelUnit()
	{
		// flags 0x0031: type 3 (fader level), unit 1 (dB)
		final var definition = parse(FDR);

		assertEquals(WapiNodeKind.FADER_LEVEL, definition.nodeKind());
		assertEquals(1, definition.unitCode());
		assertFalse(definition.isReadOnly());
	}

	@Test
	void parsesCapturedLinearFloat()
	{
		// flags 0x0010: type 1 (lin float); c2c80000 42c80000 000000c9 = -100.0, 100.0, 201 steps
		final var definition = parse(PAN);

		assertEquals(WapiNodeKind.LIN_FLOAT, definition.nodeKind());
		assertEquals(-100.0F, definition.metadata().minFloat(), 0.0F);
		assertEquals(100.0F, definition.metadata().maxFloat(), 0.0F);
		assertEquals(Long.valueOf(201), definition.metadata().steps());
	}

	@Test
	void parsesCapturedInteger()
	{
		// flags 0x0040: type 4 (integer), range 0..1
		final var definition = parse(MUTE);

		assertEquals(WapiNodeKind.INTEGER, definition.nodeKind());
		assertEquals(Integer.valueOf(0), definition.metadata().minInt());
		assertEquals(Integer.valueOf(1), definition.metadata().maxInt());
	}

	@Test
	void parsesCapturedStringEnum()
	{
		// flags 0x0050: type 5 (string enum) with 3 items: A, B, A+B
		final var definition = parse(MON);

		assertEquals(WapiNodeKind.STRING_ENUM, definition.nodeKind());
		assertEquals(List.of("A", "B", "A+B"), definition.metadata().enumValues());
	}

	@Test
	void parsesCapturedStringEnumValuesWithLabels()
	{
		// flags 0x0050 with 8 items, each with a long name: IN (INPUT), FILT (FILTER), 3 (TAP 3), ...
		final var definition = parse(PTAP);

		assertEquals(List.of("IN", "FILT", "3", "4", "5", "PFL", "AFL", "POST"), definition.metadata().enumValues());
	}

	@Test
	void parsesCapturedStringEnumWithManyItems()
	{
		// flags 0x0050 with 24 items: GEDI, GEID, GIED, ... (order of the processing blocks)
		final var definition = parse(PROC);

		assertEquals(24, definition.metadata().enumValues().size());
		assertEquals("GEDI", definition.metadata().enumValues().get(0));
		assertEquals("IDGE", definition.metadata().enumValues().get(23));
	}

	@Test
	void parsesCapturedFloatEnumValues()
	{
		// flags 0x0060: the values are the floats, not the labels such as "1.1:1"
		final var definition = parse(RATIO);

		assertEquals(WapiNodeKind.FLOAT_ENUM, definition.nodeKind());
		assertEquals(17, definition.metadata().enumValues().size());
		assertEquals("1.1", definition.metadata().enumValues().get(0));
		assertEquals("100.0", definition.metadata().enumValues().get(16));
	}

	@Test
	void exposesStringEnumItemsWithLabels()
	{
		final var ptap = parse(PTAP).metadata().enumItems();
		final var mon = parse(MON).metadata().enumItems();

		assertEquals(WapiType.S32, ptap.get(0).value().getType());
		assertEquals("IN", ptap.get(0).value().getStringValue());
		assertEquals("INPUT", ptap.get(0).longName());
		assertEquals("POST PROC", ptap.get(7).longName());
		assertEquals("A+B", mon.get(2).value().getStringValue());
		assertEquals("", mon.get(2).longName());
	}

	@Test
	void exposesFloatEnumItemsWithLabels()
	{
		final var ratio = parse(RATIO).metadata().enumItems();

		assertEquals(WapiType.F32, ratio.get(0).value().getType());
		assertEquals(1.1F, ratio.get(0).value().getFloatValue(), 0.0F);
		assertEquals("1.1:1", ratio.get(0).longName());
		assertEquals(100.0F, ratio.get(16).value().getFloatValue(), 0.0F);
		assertEquals("100:1", ratio.get(16).longName());
	}

	@Test
	void parsesCapturedReadOnlyInteger()
	{
		// flags 0x0240: read-only (bit 9) integer, range 1..18
		final var definition = parse(ACTUAL_COLOR);

		assertEquals(WapiNodeKind.INTEGER, definition.nodeKind());
		assertTrue(definition.isReadOnly());
		assertEquals(Integer.valueOf(1), definition.metadata().minInt());
		assertEquals(Integer.valueOf(18), definition.metadata().maxInt());
	}

	@Test
	void rejectsNameLongerThanPayload()
	{
		// df 007b 00000000 00000000 0000 70 00.. (126 bytes): name length 0x70 points past the end
		final var response = new byte[126];
		response[0] = (byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE;
		response[2] = 0x7B;
		response[13] = 0x70;

		assertThrows(WapiException.class, () -> WapiNodeDefinitionCodec.parse(response));
	}

	@Test
	void rejectsLongNameLongerThanPayload()
	{
		// df 000e 00000000 00000001 0001 01 49 09 4142
		final var response = new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, 0x00, 0x0E,
				0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01,
				0x01, 'I', 0x09, 'A', 'B'
		};

		assertThrows(WapiException.class, () -> WapiNodeDefinitionCodec.parse(response));
	}

	@Test
	void rejectsTruncatedHeader()
	{
		// df 0005 00000000 0000: the hash is cut off
		final var response = new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, 0x00, 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
		};

		assertThrows(WapiException.class, () -> WapiNodeDefinitionCodec.parse(response));
	}

	@Test
	void rejectsTruncatedFlags()
	{
		// df 0010 00000000 00000001 0001 01 49 00 40: the second flags byte is missing
		final var response = new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, 0x00, 0x10,
				0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01,
				0x01, 'I', 0x00, 0x40
		};

		assertThrows(WapiException.class, () -> WapiNodeDefinitionCodec.parse(response));
	}

	@Test
	void acceptsDefinitionWithoutMetadata()
	{
		// df 0011 00000000 00000001 0001 01 49 00 0040: an integer node whose range is missing
		final var response = new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, 0x00, 0x11,
				0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01,
				0x01, 'I', 0x00, 0x00, 0x40
		};

		final var definition = WapiNodeDefinitionCodec.parse(response);

		assertEquals("I", definition.name());
		assertNull(definition.metadata());
	}

	private static WapiNodeDefinition parse(final String hex)
	{
		return WapiNodeDefinitionCodec.parse(WapiBinaryDataCodec.decodeHex(hex, 1024));
	}

	@Test
	void keepsTheRawBytesOfTheDefinition()
	{
		// Captured: ch.1, followed by the end of data of the reply
		final byte[] definition = WapiBinaryDataCodec.decodeHex("df000f70b10139878392cf00010001310000", 64);
		final byte[] reply = Arrays.copyOf(definition, definition.length + 1);
		reply[definition.length] = (byte) WapiBinaryStreamCodec.TOKEN_END_DATA;

		assertArrayEquals(definition, WapiNodeDefinitionCodec.parse(reply).rawBytes());
		assertArrayEquals(definition, WapiNodeDefinitionCodec.parse(definition).rawBytes());
	}
}
