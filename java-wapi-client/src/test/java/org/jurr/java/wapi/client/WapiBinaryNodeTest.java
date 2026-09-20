package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiTokenRegistry;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiEventDecoder;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;

class WapiBinaryNodeTest
{
	// Replies captured from a WING console with WapiRawRequestExample
	private static final String FDR_REPLY = "d738ae75c2d5c0a66668de";
	private static final String FLT_REPLY = """
			d7ae9ed8af01d7401e6cf6d541e89b02d7d82e0a59813234d7826f399401d7a0426589d5460a7fa2
			d74b51a9958036d71645ae3b00d7104ec6788354494c54d74bc3ed08d500000000de""";
	/** The parameters below ch.1.flt in reply order; ch.1.flt.1 is the TILT model's parameter, addressed by number. */
	private static final String[] FLT_PARAMETERS = {
			"ch.1.flt.lc", "ch.1.flt.lcf", "ch.1.flt.lcs", "ch.1.flt.hc", "ch.1.flt.hcf", "ch.1.flt.hcs", "ch.1.flt.tf", "ch.1.flt.mdl", "ch.1.flt.1"
	};

	@Test
	void reportsAnUnknownNodeLikeC()
	{
		final var transport = new ScriptedWapiTransport();
		// Captured: the console answers d704afd241dc (ch.1.nosuchthing) with de only; C returns -1 (WNODE)
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_END_DATA }));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var token = new WapiToken("ch.1.nosuchthing");
			final var timeout = Duration.ofSeconds(5);
			final var exception = assertTimeoutPreemptively(Duration.ofSeconds(1),
					() -> assertThrows(WapiException.class, () -> client.wGetBinaryNode(token, timeout)));

			assertEquals(WapiError.WNODE, exception.getError());
		}
	}

	@Test
	void readsWithTheDefaultTimeoutLikeC()
	{
		final var transport = new ScriptedWapiTransport();
		// d738ae75c2 d5c0a66668 de: ch.1.fdr = -5.2 dB
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(FDR_REPLY, 64)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			// d738ae75c2d5c0a66668
			assertArrayEquals(WapiBinaryDataCodec.decodeHex("d738ae75c2d5c0a66668", 64), client.wGetBinaryNode(new WapiToken("ch.1.fdr")));
		}
	}

	@Test
	void readsCapturedParameter()
	{
		final var transport = new ScriptedWapiTransport();
		final var channelCodec = new WapiNativeChannelCodec();
		// d738ae75c2 d5c0a66668 de: ch.1.fdr = -5.2 dB
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(FDR_REPLY, 64)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final byte[] result = client.wGetBinaryNode(new WapiToken("ch.1.fdr"), Duration.ofSeconds(1));

			// d738ae75c2dc
			assertArrayEquals(WapiBinaryDataCodec.decodeHex("d738ae75c2dc", 16), channelCodec.decode(transport.getSentPackets().get(0)).get(0).payload());
			// d738ae75c2d5c0a66668
			assertArrayEquals(WapiBinaryDataCodec.decodeHex("d738ae75c2d5c0a66668", 64), result);
		}
	}

	@Test
	void readsCapturedNodeAsAllParametersBelowIt()
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, WapiBinaryDataCodec.decodeHex(FLT_REPLY, 256)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final byte[] result = client.wGetBinaryNode(new WapiToken("ch.1.flt"), Duration.ofSeconds(1));

			assertArrayEquals(WapiBinaryDataCodec.decodeHex(FLT_REPLY.substring(0, FLT_REPLY.length() - 2), 256), result);
			assertEquals(List.of(FLT_PARAMETERS), parameterNames(result));
		}
	}

	@Test
	void readsCapturedNodeReplySplitInsideRecord()
	{
		final byte[] reply = WapiBinaryDataCodec.decodeHex(FLT_REPLY, 256);
		final var transport = new ScriptedWapiTransport();
		final var channelCodec = new WapiNativeChannelCodec();
		// d7ae9ed8af01 d7401e | 6cf6d541e89b02 ...: split inside the hash of the second record,
		// like the console split its reply for ch.1
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, Arrays.copyOfRange(reply, 0, 8)));
		transport.enqueueResponse(new WapiPacket(Arrays.copyOfRange(reply, 8, reply.length)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final byte[] result = client.wGetBinaryNode(new WapiToken("ch.1.flt"), Duration.ofSeconds(1));

			assertArrayEquals(Arrays.copyOf(reply, reply.length - 1), result);
		}
	}

	@Test
	void assemblesParameterRecordAcrossReceiveChunks()
	{
		final var token = new WapiToken("ch.1.fdr");
		final byte[] hash = token.hashBytes();
		final var transport = new ScriptedWapiTransport();
		final var channelCodec = new WapiNativeChannelCodec();
		// d7 <hash[0..1]> | <hash[2..3]> d5 41 | 48 00 00 de
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1] }));
		transport.enqueueResponse(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { hash[2], hash[3], (byte) WapiBinaryStreamCodec.TOKEN_FLOAT32, 0x41 }));
		transport.enqueueResponse(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				new byte[] { 0x48, 0x00, 0x00, (byte) WapiBinaryStreamCodec.TOKEN_END_DATA }));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			// d7 <hash> d541480000
			assertArrayEquals(new byte[] {
					(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3],
					(byte) WapiBinaryStreamCodec.TOKEN_FLOAT32, 0x41, 0x48, 0x00, 0x00
			}, client.wGetBinaryNode(token, Duration.ofSeconds(1)));
		}
	}

	@Test
	void readsParameterAsInPdfExample()
	{
		// PDF, OSC chapter: request d7beefaeabdc for $ctl.daw.$on, reply d7beefaeab01de
		final var token = new WapiToken("$ctl.daw.$on");
		final var transport = new ScriptedWapiTransport();
		final var channelCodec = new WapiNativeChannelCodec();
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				WapiBinaryDataCodec.decodeHex("d7beefaeab01de", 16)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final byte[] result = client.wGetBinaryNode(token, Duration.ofSeconds(1));

			// d7beefaeabdc
			assertArrayEquals(WapiBinaryDataCodec.decodeHex("d7beefaeabdc", 16),
					channelCodec.decode(transport.getSentPackets().get(0)).get(0).payload());
			// d7beefaeab01
			assertArrayEquals(WapiBinaryDataCodec.decodeHex("d7beefaeab01", 16), result);
		}
	}

	@Test
	void doesNotCutOffValueContainingEndOfDataByte()
	{
		final var token = new WapiToken("ch.1.fdr");
		final byte[] hash = token.hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <hash> d4000000de de: the int32 value 0x000000DE ends with the end-of-data byte
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, (byte) WapiBinaryStreamCodec.TOKEN_END_DATA,
				(byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final byte[] result = client.wGetBinaryNode(token, Duration.ofSeconds(1));

			// d4000000de
			assertContains(result, new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, (byte) WapiBinaryStreamCodec.TOKEN_END_DATA });
		}
	}

	@Test
	void returnsParameterValueWithoutEndOfDataByte()
	{
		// WING ends a parameter reply with de (see readsCapturedParameter); a reply without it,
		// as readNativeToken also accepts, must give the same result.
		final var token = new WapiToken("ch.1.fdr");
		final byte[] hash = token.hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <hash> d400000005
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 5
		}));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final byte[] result = client.wGetBinaryNode(token, Duration.ofSeconds(1));

			// d400000005
			assertContains(result, new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 5 });
		}
	}

	@Test
	void ignoresHashBytesInsideAnotherValue()
	{
		final var token = new WapiToken("ch.1.fdr");
		final byte[] hash = token.hashBytes();
		final var otherHash = new WapiToken("ch.1.name").hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <other hash> 85 d7<hash>de | d7 <hash> d400000007 de
		// The string value of the other record contains d7 <hash> followed by de.
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, otherHash[0], otherHash[1], otherHash[2], otherHash[3],
				(byte) (WapiBinaryStreamCodec.TOKEN_STRING_64_BASE + 5),
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3], (byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}));
		transport.enqueueResponse(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 7,
				(byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final byte[] result = client.wGetBinaryNode(token, Duration.ofSeconds(1));

			// d400000007
			assertContains(result, new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 7 });
		}
	}

	@Test
	void returnsAllRecordsOfNode()
	{
		// A node reply is one d7 <hash> <value> record per parameter below the node, ended by de
		// (confirmed with captures, see readsCapturedNodeAsAllParametersBelowIt).
		final var node = new WapiToken("ch.1");
		final var faderHash = new WapiToken("ch.1.fdr").hashBytes();
		final var muteHash = new WapiToken("ch.1.mute").hashBytes();
		final byte[] faderRecord = {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, faderHash[0], faderHash[1], faderHash[2], faderHash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_FLOAT32, 0x41, 0x48, 0x00, 0x00
		};
		final byte[] muteRecord = {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, muteHash[0], muteHash[1], muteHash[2], muteHash[3],
				WapiBinaryStreamCodec.TOKEN_TRUE
		};
		final var transport = new ScriptedWapiTransport();
		// d7 <fdr hash> d541480000 d7 <mute hash> 01 de
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID,
				concat(faderRecord, muteRecord, new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_END_DATA })));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final byte[] result = client.wGetBinaryNode(node, Duration.ofSeconds(1));

			// Both records, with their d7 <hash> prefix, so the result can be sent back with wSetBinaryNode
			assertContains(result, concat(faderRecord, muteRecord));
		}
	}

	@Test
	void keepsEventsReceivedWhileWaiting()
	{
		final var token = new WapiToken("ch.1.fdr");
		final var other = new WapiToken("ch.1.mute");
		final byte[] hash = token.hashBytes();
		final byte[] otherHash = other.hashBytes();
		final var transport = new ScriptedWapiTransport();
		// d7 <mute hash> 01 | d7 <hash> d400000007 de
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, otherHash[0], otherHash[1], otherHash[2], otherHash[3], WapiBinaryStreamCodec.TOKEN_TRUE
		}));
		transport.enqueueResponse(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, 0, 7,
				(byte) WapiBinaryStreamCodec.TOKEN_END_DATA
		}));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getTokenRegistry().register(other);

			client.wGetBinaryNode(token, Duration.ofSeconds(1));

			final var events = client.wGetParsedEvents(1, Duration.ZERO);
			assertEquals(1, events.size());
			assertEquals(other, events.get(0).token());
			assertEquals(1, events.get(0).value().getIntValue().intValue());
		}
	}

	private static void assertContains(final byte[] actual, final byte[] expected)
	{
		for (var offset = 0; offset <= actual.length - expected.length; offset++)
		{
			var match = true;
			for (var i = 0; i < expected.length && match; i++)
			{
				match = actual[offset + i] == expected[i];
			}
			if (match)
			{
				return;
			}
		}
		fail("Expected " + toHex(expected) + " in " + toHex(actual));
	}

	private static byte[] concat(final byte[]... parts)
	{
		var length = 0;
		for (final var part : parts)
		{
			length += part.length;
		}
		final var result = new byte[length];
		var offset = 0;
		for (final var part : parts)
		{
			System.arraycopy(part, 0, result, offset, part.length);
			offset += part.length;
		}
		return result;
	}

	private static String toHex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}

	/** Decodes the records of a wGetBinaryNode result and returns their token names. */
	private static List<String> parameterNames(final byte[] records)
	{
		final var registry = new WapiTokenRegistry();
		for (final var name : FLT_PARAMETERS)
		{
			registry.register(new WapiToken(name));
		}
		final var channelCodec = new WapiNativeChannelCodec();
		final var names = new ArrayList<String>();
		for (final var event : new WapiEventDecoder(registry).decode(
				channelCodec.decode(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, records)).get(0)))
		{
			names.add(event.token().name());
		}
		return names;
	}
}
