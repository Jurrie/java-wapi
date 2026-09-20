package org.jurr.java.wapi.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiTokenRegistry;
import org.jurr.java.wapi.WapiType;
import org.junit.jupiter.api.Test;

class WapiEventDecoderTest
{
	@Test
	void decodesCStyleHashRecordsAndValues()
	{
		final var registry = new WapiTokenRegistry();
		final var ch1MuteToken = new WapiToken("ch.1.mute");
		final var ch1FaderToken = new WapiToken("ch.1.fdr");
		registry.register(ch1MuteToken);
		registry.register(ch1FaderToken);

		final byte[] ch1MuteTokenHash = ch1MuteToken.hashBytes();
		final byte[] ch1FaderTokenHash = ch1FaderToken.hashBytes();
		final var decoder = new WapiEventDecoder(registry);
		final var payload = new byte[] {
				(byte) WapiEventDecoder.TOKEN_EVENT, ch1MuteTokenHash[0], ch1MuteTokenHash[1], ch1MuteTokenHash[2], ch1MuteTokenHash[3], WapiBinaryStreamCodec.TOKEN_TRUE,
				(byte) WapiEventDecoder.TOKEN_EVENT, ch1FaderTokenHash[0], ch1FaderTokenHash[1], ch1FaderTokenHash[2], ch1FaderTokenHash[3], (byte) WapiBinaryStreamCodec.TOKEN_FLOAT32, 0x41, 0x48, 0x00, 0x00
		};

		final var events = decoder.decodeFromByteArray(payload);

		assertEquals(2, events.size());
		assertEquals("ch.1.mute", events.get(0).token().name());
		assertEquals(1, events.get(0).value().getIntValue().intValue());
		assertEquals("ch.1.fdr", events.get(1).token().name());
		assertEquals(12.5F, events.get(1).value().getFloatValue(), 0.001F);
	}

	@Test
	void createsOpaqueTokenForUnknownHash()
	{
		final var registry = new WapiTokenRegistry();
		final var decoder = new WapiEventDecoder(registry);
		final var events = decoder.decodeFromByteArray(
				new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, 0 });

		assertEquals(1, events.size());
		assertEquals(1, events.get(0).token().hash());
		assertFalse(events.get(0).token().hasKnownName());
		assertSame(events.get(0).token(), registry.findByHash(1));
		assertEquals(WapiType.I32, events.get(0).type());
	}

	@Test
	void doesNotDecodeD7InsideUnknownEventStringAsAnotherEvent()
	{
		final var registry = new WapiTokenRegistry();
		final var embeddedEventToken = new WapiToken(0x01020304);
		registry.register(embeddedEventToken);
		final var unknownHash = 0x11121314;
		// d7 11121314 85 d7 01
		final var payloadPrefix = new byte[] {
				(byte) WapiEventDecoder.TOKEN_EVENT, 0x11, 0x12, 0x13, 0x14,
				(byte) (WapiBinaryStreamCodec.TOKEN_STRING_64_BASE + 5), (byte) WapiEventDecoder.TOKEN_EVENT, 0x01
		};
		final var payloadRemainder = new byte[] { 0x02, 0x03, 0x04, 0x01 };
		final var channelCodec = new WapiNativeChannelCodec();
		final var decoder = new WapiEventDecoder(registry);
		final var first = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, payloadPrefix)).get(0);
		final var second = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, payloadRemainder)).get(0);

		assertEquals(0, decoder.decode(first).size());
		final var events = decoder.decode(second);

		assertEquals(1, events.size());
		assertEquals(unknownHash, events.get(0).token().hash());
		assertFalse(events.get(0).token().hasKnownName());
		assertEquals(WapiType.S32, events.get(0).type());
		assertEquals(6, events.get(0).value().getStringValue().length());
		assertSame(embeddedEventToken, registry.findByHash(embeddedEventToken.hash()));
	}

	@Test
	void rejectsIncompleteValues()
	{
		final var registry = new WapiTokenRegistry();
		final var token = new WapiToken("ch.1.fdr");
		registry.register(token);

		final byte[] tokenHash = token.hashBytes();

		final var decoder = new WapiEventDecoder(registry);

		assertThrows(WapiException.class, () -> decoder.decodeFromByteArray(new byte[] {
				(byte) WapiEventDecoder.TOKEN_EVENT, tokenHash[0], tokenHash[1], tokenHash[2], tokenHash[3], (byte) WapiBinaryStreamCodec.TOKEN_FLOAT32, 0, 0 // We miss two bytes here
		}));
	}

	@Test
	void retainsAnEventSplitAcrossChannelDataChunks()
	{
		final var registry = new WapiTokenRegistry();
		final var token = new WapiToken("ch.1.mute");
		registry.register(token);
		final byte[] hash = token.hashBytes();
		final var channelCodec = new WapiNativeChannelCodec();
		final var decoder = new WapiEventDecoder(registry);
		final var first = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, hash[0], hash[1] })).get(0);
		final var second = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { hash[2], hash[3], 0x01 })).get(0);

		assertEquals(0, decoder.decode(first).size());
		final var events = decoder.decode(second);
		assertEquals(1, events.size());
		assertEquals(token, events.get(0).token());
		assertEquals(1, events.get(0).value().getIntValue().intValue());
	}

	@Test
	void decodesMixedValueKindsInOnePayload()
	{
		final var registry = new WapiTokenRegistry();
		final var ch1MuteToken = new WapiToken("ch.1.mute");
		final var ch1NameToken = new WapiToken("ch.1.name");
		final var ch1FaderToken = new WapiToken("ch.1.fdr");
		registry.register(ch1MuteToken);
		registry.register(ch1NameToken);
		registry.register(ch1FaderToken);

		final byte[] ch1MuteTokenHash = ch1MuteToken.hashBytes();
		final byte[] ch1NameTokenHash = ch1NameToken.hashBytes();
		final byte[] ch1FaderTokenHash = ch1FaderToken.hashBytes();
		final var decoder = new WapiEventDecoder(registry);

		final var payload = new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, ch1MuteTokenHash[0], ch1MuteTokenHash[1], ch1MuteTokenHash[2], ch1MuteTokenHash[3], 0x01,
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, ch1NameTokenHash[0], ch1NameTokenHash[1], ch1NameTokenHash[2], ch1NameTokenHash[3], (byte) WapiBinaryStreamCodec.TOKEN_STRING_64_BASE + 1, 'A', 'B',
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, ch1FaderTokenHash[0], ch1FaderTokenHash[1], ch1FaderTokenHash[2], ch1FaderTokenHash[3], (byte) WapiBinaryStreamCodec.TOKEN_FLOAT32, 0x41, 0x48, 0x00, 0x00
		};

		final var events = decoder.decodeFromByteArray(payload);

		assertEquals(3, events.size());
		assertEquals(1, events.get(0).value().getIntValue().intValue());
		assertEquals("AB", events.get(1).value().getStringValue());
		assertEquals(12.5F, events.get(2).value().getFloatValue(), 0.001F);
	}

	@Test
	void decodesOneCharacterShortString()
	{
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		// d7 00000001 80 41
		final var events = decoder.decodeFromByteArray(new byte[] {
				(byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, (byte) WapiBinaryStreamCodec.TOKEN_STRING_64_BASE, 'A'
		});

		assertEquals(1, events.size());
		assertEquals(WapiType.S32, events.get(0).type());
		assertEquals("A", events.get(0).value().getStringValue());
	}

	@Test
	void decodes64CharacterShortString()
	{
		final var payload = new byte[6 + 64];
		payload[0] = (byte) WapiEventDecoder.TOKEN_EVENT;
		payload[4] = 1;
		payload[5] = (byte) (WapiBinaryStreamCodec.TOKEN_STRING_64_BASE + 0x3F); // bf: 64-character string
		Arrays.fill(payload, 6, payload.length, (byte) 'A');
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());

		final var events = decoder.decodeFromByteArray(payload);

		assertEquals(1, events.size());
		assertEquals(WapiType.S32, events.get(0).type());
		assertEquals(ByteUtil.toAscii(payload, 6, 64), events.get(0).value().getStringValue());
	}

	@Test
	void decodesStringProducedByNativeSetStringEncoder()
	{
		final var token = new WapiToken("ch.1.name");
		final var registry = new WapiTokenRegistry();
		registry.register(token);
		final var payload = new WapiEncoder().encodeNativeSetString(token, "AB");
		final var decoder = new WapiEventDecoder(registry);

		final var events = decoder.decodeFromByteArray(payload);

		assertEquals(1, events.size());
		assertSame(token, events.get(0).token());
		assertEquals(WapiType.S32, events.get(0).type());
		assertEquals("AB", events.get(0).value().getStringValue());
	}

	@Test
	void retainsShortStringUntilLastCharacterArrives()
	{
		final var channelCodec = new WapiNativeChannelCodec();
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		// d7 00000001 81 41 | 42
		final var first = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, (byte) (WapiBinaryStreamCodec.TOKEN_STRING_64_BASE + 1), 'A' })).get(0);
		final var second = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { 'B' })).get(0);

		assertEquals(0, decoder.decode(first).size());
		final var events = decoder.decode(second);
		assertEquals(1, events.size());
		assertEquals("AB", events.get(0).value().getStringValue());
	}

	@Test
	void retainsLongStringUntilLengthAndAllCharactersArrive()
	{
		final var channelCodec = new WapiNativeChannelCodec();
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		// d7 00000001 d1 | 01 41 | 42
		final var first = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, (byte) WapiBinaryStreamCodec.TOKEN_STRING_256_BASE })).get(0);
		final var second = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { 0x01, 'A' })).get(0);
		final var third = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { 'B' })).get(0);

		assertEquals(0, decoder.decode(first).size());
		assertEquals(0, decoder.decode(second).size());
		final var events = decoder.decode(third);
		assertEquals(1, events.size());
		assertEquals(WapiType.S32, events.get(0).type());
		assertEquals("AB", events.get(0).value().getStringValue());
	}

	@Test
	void rejectsNodeNameAsEventValue()
	{
		final var channelCodec = new WapiNativeChannelCodec();
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		// d7 00000001 c1 41 42
		final var frame = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, (byte) (WapiBinaryStreamCodec.TOKEN_NODE_NAME_BASE + 1), 'A', 'B' })).get(0);

		assertEquals(WapiError.WTOKEN, assertThrows(WapiException.class, () -> decoder.decode(frame)).getError());
	}

	@Test
	void rejectsUnknownTokenWithTokenError()
	{
		final var channelCodec = new WapiNativeChannelCodec();
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		// d7 00000001 01 e0 (0xE0 is unused in the binary stream protocol, PDF Table 2)
		final var frame = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, WapiBinaryStreamCodec.TOKEN_TRUE, (byte) 0xE0 })).get(0);

		assertEquals(WapiError.WTOKEN, assertThrows(WapiException.class, () -> decoder.decode(frame)).getError());
	}

	@Test
	void rejectsNonValueTokenAfterHashWithTokenError()
	{
		final var channelCodec = new WapiNativeChannelCodec();
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		// d7 00000001 d8: a click token is not a value
		final var frame = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, (byte) WapiBinaryStreamCodec.TOKEN_CLICK })).get(0);

		assertEquals(WapiError.WTOKEN, assertThrows(WapiException.class, () -> decoder.decodeRecords(frame)).getError());
	}

	@Test
	void doesNotDecodeD7InsideLongStringAsAnotherEvent()
	{
		final var channelCodec = new WapiNativeChannelCodec();
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		// d7 00000001 d1 05 d7 01 02 03 04 01
		final var frame = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, (byte) WapiBinaryStreamCodec.TOKEN_STRING_256_BASE, 0x05,
				(byte) WapiEventDecoder.TOKEN_EVENT, 0x01, 0x02, 0x03, 0x04, 0x01
		})).get(0);

		final var events = decoder.decode(frame);

		assertEquals(1, events.size());
		assertEquals(1, events.get(0).token().hash());
		assertEquals(WapiType.S32, events.get(0).type());
		assertEquals(6, events.get(0).value().getStringValue().length());
	}

	@Test
	void doesNotCreateHashEventFromD7InsideSkippedInteger()
	{
		final var registry = new WapiTokenRegistry();
		final var decoder = new WapiEventDecoder(registry);
		final var channelCodec = new WapiNativeChannelCodec();
		// da 40 d4 0102d704 01 02 03 01 d7 00000005 01
		final var frame = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] {
						(byte) WapiBinaryStreamCodec.TOKEN_ROOT, WapiBinaryStreamCodec.TOKEN_NODE_INDEX_64_BASE, (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0x01, 0x02, (byte) WapiEventDecoder.TOKEN_EVENT, 0x04,
						0x01, 0x02, 0x03, 0x01,
						(byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 5, WapiBinaryStreamCodec.TOKEN_TRUE
				})).get(0);

		final var events = decoder.decode(frame);

		assertNull(registry.findByHash(0x04010203), "The D7 byte inside an int32 must not create a fabricated hash token");
		assertFalse(events.isEmpty());
		assertEquals(5, events.get(events.size() - 1).token().hash());
		assertEquals(1, events.get(events.size() - 1).value().getIntValue().intValue());
	}

	@Test
	void doesNotReplayPendingPrefixAfterUnsupportedValue()
	{
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		final var channelCodec = new WapiNativeChannelCodec();
		// d7 00000001 d4 01 | 02 03 04 d7 00000002 e0 | 05 06 07 | d7 00000003 01
		// 0xE0 is unused in the binary stream protocol (PDF Table 2: 0xe0..0xff not used)
		final var first = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0x01 })).get(0);
		final var invalid = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { 0x02, 0x03, 0x04, (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 2, (byte) 0xE0 })).get(0);
		final var remainder = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { 0x05, 0x06, 0x07 })).get(0);
		final var fresh = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 3, WapiBinaryStreamCodec.TOKEN_TRUE })).get(0);

		assertEquals(0, decoder.decode(first).size());
		assertThrows(WapiException.class, () -> decoder.decode(invalid));
		final var resumed = decoder.decode(remainder);
		assertFalse(resumed.stream().anyMatch(event -> event.token().hash() == 1
				&& Integer.valueOf(0x01050607).equals(event.value().getIntValue())),
				"Already-consumed pending bytes must not be combined with unrelated bytes after an exception");

		final var events = decoder.decode(fresh);
		assertEquals(1, events.size());
		assertEquals(3, events.get(0).token().hash());
		assertEquals(1, events.get(0).value().getIntValue().intValue());
	}

	@Test
	void retainsSkippedNodeNameAcrossChunksWithoutCreatingHashEvent()
	{
		final var registry = new WapiTokenRegistry();
		final var decoder = new WapiEventDecoder(registry);
		final var channelCodec = new WapiNativeChannelCodec();
		// da c5 d7 01 | 02 03 04 01 d7 00000009 01 (c5 = node name of 6 bytes: d7 01 02 03 04 01)
		final var first = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_ROOT, (byte) (WapiBinaryStreamCodec.TOKEN_NODE_NAME_BASE + 5),
						(byte) WapiEventDecoder.TOKEN_EVENT, 0x01 })).get(0);
		final var second = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { 0x02, 0x03, 0x04, 0x01,
						(byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 9, WapiBinaryStreamCodec.TOKEN_TRUE })).get(0);

		assertEquals(0, decoder.decode(first).size());
		final var events = decoder.decode(second);

		assertEquals(1, events.size());
		assertEquals(9, events.get(0).token().hash());
		assertEquals(1, events.get(0).value().getIntValue().intValue());
		assertNull(registry.findByHash(0x01020304));
	}

	@Test
	void consumesD7InsideRecognizedIntegerValue()
	{
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		final var channelCodec = new WapiNativeChannelCodec();
		// d7 00000001 d4 0102d704
		final var frame = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1,
						(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0x01, 0x02, (byte) WapiEventDecoder.TOKEN_EVENT, 0x04 })).get(0);

		final var events = decoder.decode(frame);

		assertEquals(1, events.size());
		assertEquals(1, events.get(0).token().hash());
		assertEquals(WapiType.I32, events.get(0).type());
		assertEquals(0x0102D704, events.get(0).value().getIntValue().intValue());
	}

	@Test
	void skipsNodeDefinitionResponseBeforeEvent()
	{
		final var registry = new WapiTokenRegistry();
		final var decoder = new WapiEventDecoder(registry);
		final var channelCodec = new WapiNativeChannelCodec();
		// df 0017 00000000 d7000001 0001 01 49 00 0040 00000005 0000000a d7 00000009 01
		final var frame = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				concat(nodeDefinitionResponse(), new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 9, WapiBinaryStreamCodec.TOKEN_TRUE }))).get(0);

		final var events = decoder.decode(frame);

		assertEquals(1, events.size());
		assertEquals(9, events.get(0).token().hash());
		assertEquals(1, events.get(0).value().getIntValue().intValue());
	}

	@Test
	void retainsNodeDefinitionResponseSplitAcrossChunks()
	{
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		final var channelCodec = new WapiNativeChannelCodec();
		final byte[] payload = concat(nodeDefinitionResponse(), new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 9, WapiBinaryStreamCodec.TOKEN_TRUE });
		// df 0017 00000000 d7 | 000001 0001 01 49 00 0040 00000005 0000000a d7 00000009 01
		final var first = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				Arrays.copyOfRange(payload, 0, 8))).get(0);
		final var second = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				Arrays.copyOfRange(payload, 8, payload.length))).get(0);

		assertEquals(0, decoder.decode(first).size());
		final var events = decoder.decode(second);

		assertEquals(1, events.size());
		assertEquals(9, events.get(0).token().hash());
	}

	@Test
	void skipsNodeDefinitionResponseWithExtendedLength()
	{
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		final var channelCodec = new WapiNativeChannelCodec();
		// df 0000 00000003 d7 01 02 d7 00000009 01 (len.w == 0, so len.l gives the length)
		final var frame = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] {
						(byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, 0, 0, 0, 0, 0, 3, (byte) WapiEventDecoder.TOKEN_EVENT, 0x01, 0x02,
						(byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 9, WapiBinaryStreamCodec.TOKEN_TRUE
				})).get(0);

		final var events = decoder.decode(frame);

		assertEquals(1, events.size());
		assertEquals(9, events.get(0).token().hash());
	}

	@Test
	void decodeRecordsReturnsRawBytesAndEndOfDataMarkers()
	{
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		final var channelCodec = new WapiNativeChannelCodec();
		// d7 00000001 01 de d7 00000002 d4000000de
		final var frame = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] {
						(byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, WapiBinaryStreamCodec.TOKEN_TRUE,
						(byte) WapiBinaryStreamCodec.TOKEN_END_DATA,
						(byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 2, (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, (byte) WapiBinaryStreamCodec.TOKEN_END_DATA
				})).get(0);

		final var records = decoder.decodeRecords(frame);

		assertEquals(3, records.size());
		assertEquals(1, records.get(0).event().token().hash());
		// d7 00000001 01
		assertArrayEquals(new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, WapiBinaryStreamCodec.TOKEN_TRUE }, records.get(0).rawBytes());
		assertTrue(records.get(1).isEndOfData());
		assertEquals(2, records.get(2).event().token().hash());
		// d7 00000002 d4000000de: the de inside the value is not an end-of-data marker
		assertArrayEquals(new byte[] {
				(byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 2, (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, (byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}, records.get(2).rawBytes());
	}

	@Test
	void decodesLongNodeIndexLikeShortNodeIndex()
	{
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		// d7 00000001 40 d7 00000002 d20000: node index 1 in the short and the long form
		final var events = decoder.decodeFromByteArray(new byte[] {
				(byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, WapiBinaryStreamCodec.TOKEN_NODE_INDEX_64_BASE,
				(byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 2, (byte) WapiBinaryStreamCodec.TOKEN_NODE_INDEX_65536_BASE, 0x00, 0x00
		});

		assertEquals(2, events.size());
		assertEquals(WapiType.NODE, events.get(0).type());
		assertEquals(1, events.get(0).value().getIntValue().intValue());
		assertEquals(WapiType.NODE, events.get(1).type());
		assertEquals(1, events.get(1).value().getIntValue().intValue());
	}

	@Test
	void decodesHighestLongNodeIndex()
	{
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		// d7 00000001 d2ffff: node index 65536 (PDF Table 2: 0xd2 word = node index 1..65536)
		final var events = decoder.decodeFromByteArray(new byte[] {
				(byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 1, (byte) WapiBinaryStreamCodec.TOKEN_NODE_INDEX_65536_BASE, (byte) 0xFF, (byte) 0xFF
		});

		assertEquals(1, events.size());
		assertEquals(WapiType.NODE, events.get(0).type());
		assertEquals(65536, events.get(0).value().getIntValue().intValue());
	}

	@Test
	void decodeRecordsReturnsNodeDefinitionWithRawBytes()
	{
		final var decoder = new WapiEventDecoder(new WapiTokenRegistry());
		final var channelCodec = new WapiNativeChannelCodec();
		// df 0017 00000000 d7000001 0001 01 49 00 0040 00000005 0000000a d7 00000009 01
		final var frame = channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				concat(nodeDefinitionResponse(), new byte[] { (byte) WapiEventDecoder.TOKEN_EVENT, 0, 0, 0, 9, WapiBinaryStreamCodec.TOKEN_TRUE }))).get(0);

		final var records = decoder.decodeRecords(frame);

		assertEquals(2, records.size());
		assertEquals(WapiEventDecoder.StreamRecord.Kind.NODE_DEFINITION, records.get(0).kind());
		assertArrayEquals(nodeDefinitionResponse(), records.get(0).rawBytes());
		assertEquals(WapiEventDecoder.StreamRecord.Kind.EVENT, records.get(1).kind());
		assertEquals(9, records.get(1).event().token().hash());
	}

	/** Integer node definition "I" (range 5..10) whose hash field starts with a 0xD7 byte. */
	private static byte[] nodeDefinitionResponse()
	{
		// df 0017 00000000 d7000001 0001 01 49 00 0040 00000005 0000000a
		return new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, 0x00, 0x17,
				0x00, 0x00, 0x00, 0x00, (byte) WapiEventDecoder.TOKEN_EVENT, 0x00, 0x00, 0x01,
				0x00, 0x01, 0x01, 'I', 0x00, 0x00, 0x40,
				0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, 0x0A
		};
	}

	private static byte[] concat(final byte[] first, final byte[] second)
	{
		final byte[] result = Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, result, first.length, second.length);
		return result;
	}
}
