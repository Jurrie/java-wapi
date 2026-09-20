package org.jurr.java.wapi.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiTokenValue;
import org.jurr.java.wapi.WapiType;
import org.junit.jupiter.api.Test;

class WapiNodeAssignmentCodecTest
{
	@Test
	void parsesDocumentedDottedAssignments()
	{
		final var values = WapiNodeAssignmentCodec.parse("ch.1.fdr=4,mute=1");

		assertEquals(names("ch.1.fdr", "ch.1.mute"), names(values));
		assertEquals(4.0F, values.get(0).value().getFloatValue(), 0.001F);
		assertEquals(1, values.get(1).value().getIntValue().intValue());
	}

	@Test
	void resolvesNamesRelativeToPreviousNodeAndOneLevelUp()
	{
		// PDF, "Node Data": sets fader and mute of channels 1 and 2
		final var values = WapiNodeAssignmentCodec.parse("/ch.1.fdr=-1,mute=0,.2.fdr=0,mute=1");

		assertEquals(names("ch.1.fdr", "ch.1.mute", "ch.2.fdr", "ch.2.mute"), names(values));
	}

	@Test
	void resolvesSlashToRoot()
	{
		// PDF, "Node Data": channel 1 fader and mute, then bus 1 fader
		final var values = WapiNodeAssignmentCodec.parse("/ch.1.fdr=10,mute=1,/bus.1.fdr=5");

		assertEquals(names("ch.1.fdr", "ch.1.mute", "bus.1.fdr"), names(values));
	}

	@Test
	void resolvesWapiReferenceExample()
	{
		// PDF, wSetNode: channel 1 fader and mute, bus 1 and bus 2 faders
		final var values = WapiNodeAssignmentCodec.parse("/ch.1.fdr=8.5,mute=1,/bus.1.fdr=5.0,.2.fdr=0.5");

		assertEquals(names("ch.1.fdr", "ch.1.mute", "bus.1.fdr", "bus.2.fdr"), names(values));
	}

	@Test
	void stripsQuotesAroundStringWithSpace()
	{
		// PDF, "wSetNode": /ch.1.name="space name"
		final var values = WapiNodeAssignmentCodec.parse("/ch.1.name=\"space name\",mute=1");

		assertEquals(names("ch.1.name", "ch.1.mute"), names(values));
		assertEquals(WapiType.S32, values.get(0).value().getType());
		assertEquals("space name", values.get(0).value().getStringValue());
	}

	@Test
	void keepsCommasAndEqualsSignsInsideQuotes()
	{
		final var values = WapiNodeAssignmentCodec.parse("/ch.1.name=\"a,b=c\",mute=1");

		assertEquals(names("ch.1.name", "ch.1.mute"), names(values));
		assertEquals("a,b=c", values.get(0).value().getStringValue());
	}

	@Test
	void keepsQuotedNumberAsString()
	{
		final var values = WapiNodeAssignmentCodec.parse("/ch.1.name=\"1\"");

		assertEquals(WapiType.S32, values.get(0).value().getType());
		assertEquals("1", values.get(0).value().getStringValue());
	}

	@Test
	void parsesEmptyQuotedString()
	{
		// As written by C wGetNode for an empty name
		final var values = WapiNodeAssignmentCodec.parse("/ch.1.name=\"\",mute=1");

		assertEquals(WapiType.S32, values.get(0).value().getType());
		assertEquals("", values.get(0).value().getStringValue());
	}

	@Test
	void rejectsUnclosedQuote()
	{
		assertThrows(WapiException.class, () -> WapiNodeAssignmentCodec.parse("/ch.1.name=\"space name,mute=1"));
	}

	@Test
	void rejectsGoingUpFromRoot()
	{
		assertThrows(WapiException.class, () -> WapiNodeAssignmentCodec.parse(".fdr=1"));
	}

	private static List<String> names(final String... names)
	{
		final var result = new ArrayList<String>();
		for (final var name : names)
		{
			result.add(name);
		}
		return result;
	}

	private static List<String> names(final List<WapiTokenValue> values)
	{
		final var result = new ArrayList<String>();
		for (final var value : values)
		{
			result.add(value.token().name());
		}
		return result;
	}
}
