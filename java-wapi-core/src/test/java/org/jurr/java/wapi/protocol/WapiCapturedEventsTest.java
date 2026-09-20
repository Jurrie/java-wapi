package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiTokenRegistry;
import org.jurr.java.wapi.WapiType;
import org.junit.jupiter.api.Test;

/**
 * Unsolicited events captured from a WING console with WapiRawListenExample, while changing channel 8
 * with WING Edit. Every change arrives as one frame with two bare d7 hash value records, the setting
 * and its read-only $ counterpart, without an end-of-data token.
 */
class WapiCapturedEventsTest
{
	private static final String[] NAMES = {
			"ch.8.mute", "ch.8.$mute", "ch.8.fdr", "ch.8.$fdr", "ch.8.name", "ch.8.$name", "$stat.ppm", "$syscfg.consolename"
	};

	@Test
	void decodesMutePair()
	{
		// d7a8a2d394 01 d7b62bc6da 01 | d7a8a2d394 00 d7b62bc6da 00
		final var on = decode("d7a8a2d39401d7b62bc6da01");
		final var off = decode("d7a8a2d39400d7b62bc6da00");

		assertEvent(on.get(0), "ch.8.mute", WapiType.I32, "1");
		assertEvent(on.get(1), "ch.8.$mute", WapiType.I32, "1");
		assertEvent(off.get(0), "ch.8.mute", WapiType.I32, "0");
		assertEvent(off.get(1), "ch.8.$mute", WapiType.I32, "0");
	}

	@Test
	void decodesFaderBurstInOrder()
	{
		// Three consecutive frames of a fader move, about 13 and 5 ms apart:
		// d73e81b604 d5c2b03f10 d788a484ba d5c2b03f10 | ... d5c2a9ad6b ... | ... d5c2950842 ...
		final var decoder = new WapiEventDecoder(registry());
		final var events = new ArrayList<WapiEvent>();
		events.addAll(decode(decoder, "d73e81b604d5c2b03f10d788a484bad5c2b03f10"));
		events.addAll(decode(decoder, "d73e81b604d5c2a9ad6bd788a484bad5c2a9ad6b"));
		events.addAll(decode(decoder, "d73e81b604d5c2950842d788a484bad5c2950842"));

		assertEquals(6, events.size());
		assertEvent(events.get(0), "ch.8.fdr", WapiType.F32, "-88.12317");
		assertEvent(events.get(1), "ch.8.$fdr", WapiType.F32, "-88.12317");
		assertEvent(events.get(2), "ch.8.fdr", WapiType.F32, "-84.83871");
		assertEvent(events.get(4), "ch.8.fdr", WapiType.F32, "-74.51613");
		assertEvent(events.get(5), "ch.8.$fdr", WapiType.F32, "-74.51613");
	}

	@Test
	void decodesFaderAtMinusInfinityAsMinus144()
	{
		// d73e81b604 d5c3100000 d788a484ba d5c3100000: the PDF gives the fader range as -144..10 (-oo..10)
		final var events = decode("d73e81b604d5c3100000d788a484bad5c3100000");

		assertEvent(events.get(0), "ch.8.fdr", WapiType.F32, "-144.0");
		assertEvent(events.get(1), "ch.8.$fdr", WapiType.F32, "-144.0");
	}

	@Test
	void decodesNamePairAsShortString()
	{
		// d745c10e54 8374657374 d7e0e7b9b1 8374657374: 0x83 is a short string of 4 characters
		final var events = decode("d745c10e548374657374d7e0e7b9b18374657374");

		assertEvent(events.get(0), "ch.8.name", WapiType.S32, "test");
		assertEvent(events.get(1), "ch.8.$name", WapiType.S32, "test");
	}

	@Test
	void decodesClearedNameAsEmptyString()
	{
		// d745c10e54 d0 d7e0e7b9b1 d0: the empty-string token
		final var events = decode("d745c10e54d0d7e0e7b9b1d0");

		assertEvent(events.get(0), "ch.8.name", WapiType.S32, "");
		assertEvent(events.get(1), "ch.8.$name", WapiType.S32, "");
	}

	@Test
	void decodesBackgroundStatusAsInt16()
	{
		// d7d2e47f67 d3ff3b: $stat.ppm (clock ppm, read-only) changes on its own, sent as int16
		final var events = decode("d7d2e47f67d3ff3b");

		assertEvent(events.get(0), "$stat.ppm", WapiType.I32, "-197");
	}

	@Test
	void decodesKeepAliveReplyEndedByEndOfData()
	{
		// d7c9cb20e0 8a57494e472d434c41554445 de: reply to the keep-alive; the console name is
		// anonymized to WING-CLAUDE, which has the same length of 11 characters (0x8a)
		final var decoder = new WapiEventDecoder(registry());
		final var records = decoder.decodeRecords(frame("d7c9cb20e08a57494e472d434c41554445de"));

		assertEquals(2, records.size());
		assertEvent(records.get(0).event(), "$syscfg.consolename", WapiType.S32, "WING-CLAUDE");
		assertTrue(records.get(1).isEndOfData());
	}

	private static List<WapiEvent> decode(final String hex)
	{
		return decode(new WapiEventDecoder(registry()), hex);
	}

	private static List<WapiEvent> decode(final WapiEventDecoder decoder, final String hex)
	{
		return decoder.decode(frame(hex));
	}

	private static WapiNativeChannelCodec.ChannelFrame frame(final String hex)
	{
		final var channelCodec = new WapiNativeChannelCodec();
		return channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(hex, 256))).get(0);
	}

	private static WapiTokenRegistry registry()
	{
		final var registry = new WapiTokenRegistry();
		for (final var name : NAMES)
		{
			registry.register(new WapiToken(name));
		}
		return registry;
	}

	private static void assertEvent(final WapiEvent event, final String name, final WapiType type, final String value)
	{
		assertEquals(name, event.token().name());
		assertEquals(type, event.type());
		assertEquals(value, event.value().toString());
	}
}
