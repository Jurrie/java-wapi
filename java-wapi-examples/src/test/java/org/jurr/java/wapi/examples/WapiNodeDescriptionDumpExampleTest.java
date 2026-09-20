package org.jurr.java.wapi.examples;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

import com.github.valfirst.slf4jtest.TestLoggerFactory;

import org.jurr.java.wapi.WapiSession;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.client.WapiClient;
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.protocol.ByteUtil;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WapiNodeDescriptionDumpExampleTest
{
	@BeforeEach
	void clearLoggedEvents()
	{
		// All threads: the examples also log from their listener threads
		TestLoggerFactory.clearAll();
	}

	private static final int CFG_HASH = new WapiToken("cfg").hash();
	private static final int GAIN_HASH = new WapiToken("cfg.gain").hash();
	private static final byte[] GAIN_RANGE = new byte[] { 0, 0, 0, 1, 0, 0, 0, 10 };

	@Test
	void recursivelyReadsDefinitionsUsingOnlyReadOnlyRequests()
	{
		final var transport = new ScriptedWapiTransport();
		final byte[] cfg = nodeDefinition(1, CFG_HASH, 0, "cfg", "Configuration", 0);
		final byte[] gain = nodeDefinition(2, GAIN_HASH, 0, "gain", "Input gain", 0x0040, GAIN_RANGE);
		final byte[] rootResponse = definitionResponse(cfg);
		final var channelCodec = new WapiNativeChannelCodec();
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, Arrays.copyOf(rootResponse, 5)));
		transport.enqueueResponse(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, Arrays.copyOfRange(rootResponse, 5, rootResponse.length)));
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, definitionResponse(gain)));

		try (var client = new WapiClient(new WapiSession(), transport))
		{
			WapiNodeDescriptionDumpExample.dumpAll(client);
		}

		assertArrayEquals(new String[] {
				"cfg [NODE, flags=0x0000, hash=0x" + hex(CFG_HASH) + "] - Configuration",
				"  raw=" + toHex(cfg),
				"  gain [INTEGER, flags=0x0040, hash=0x" + hex(GAIN_HASH) + "] - Input gain",
				"    range=1..10",
				"    raw=" + toHex(gain)
		}, lines());
		final var requests = transport.getSentPackets();
		assertEquals(2, requests.size());
		// da dd
		assertArrayEquals(new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_ROOT,
				(byte) WapiBinaryStreamCodec.TOKEN_REQUEST_NODE_DEF }, channelCodec.decode(requests.get(0)).get(0).payload());
		// d7 <cfg> dd
		final byte[] cfgHash = ByteUtil.toBytes(CFG_HASH);
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, cfgHash[0], cfgHash[1], cfgHash[2], cfgHash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_REQUEST_NODE_DEF }, channelCodec.decode(requests.get(1)).get(0).payload());
	}

	@Test
	void readsDefinitionsWhenChannelSelectorIsSentOnlyOnce()
	{
		final var transport = new ScriptedWapiTransport();
		final byte[] cfg = nodeDefinition(1, CFG_HASH, 0, "cfg", "Configuration", 0);
		final byte[] gain = nodeDefinition(2, GAIN_HASH, 0, "gain", "Input gain", 0x0040, GAIN_RANGE);
		// dfd1 <cfg definition> de | <gain definition> de: like the console, the second response
		// continues on the active channel without repeating the selector
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, definitionResponse(cfg)));
		transport.enqueueReply(new WapiPacket(definitionResponse(gain)));

		try (var client = new WapiClient(new WapiSession(), transport))
		{
			WapiNodeDescriptionDumpExample.dumpAll(client);
		}

		assertArrayEquals(new String[] {
				"cfg [NODE, flags=0x0000, hash=0x" + hex(CFG_HASH) + "] - Configuration",
				"  raw=" + toHex(cfg),
				"  gain [INTEGER, flags=0x0040, hash=0x" + hex(GAIN_HASH) + "] - Input gain",
				"    range=1..10",
				"    raw=" + toHex(gain)
		}, lines());
	}

	@Test
	void ignoresEventsBetweenDefinitions()
	{
		final int gainHash = new WapiToken("gain").hash();
		final byte[] gain = nodeDefinition(1, gainHash, 0, "gain", "Input gain", 0x0040, GAIN_RANGE);
		final var transport = new ScriptedWapiTransport();
		final var response = new ByteArrayOutputStream();
		// d7 00000001 d40000df00: an unsolicited event whose value contains a 0xdf byte
		write(response, new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, 0, 0, 0, 1,
				(byte) WapiBinaryStreamCodec.TOKEN_INT32, 0, 0, (byte) WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE, 0 });
		write(response, definitionResponse(gain));
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, response.toByteArray()));

		try (var client = new WapiClient(new WapiSession(), transport))
		{
			WapiNodeDescriptionDumpExample.dumpAll(client);
		}

		assertArrayEquals(new String[] {
				"gain [INTEGER, flags=0x0040, hash=0x" + hex(gainHash) + "] - Input gain",
				"  range=1..10",
				"  raw=" + toHex(gain)
		}, lines());
	}

	@Test
	void startPathIsRequestedByItsHashAndDepthZeroDoesNotDescend()
	{
		final int inHash = new WapiToken("ch.1.in").hash();
		// flags 0x0000: a node, which the example would descend into without the depth limit
		final byte[] in = nodeDefinition(1, inHash, 0, "in", "INPUT", 0x0000);
		final var transport = new ScriptedWapiTransport();
		final var channelCodec = new WapiNativeChannelCodec();
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, definitionResponse(in)));

		try (var client = new WapiClient(new WapiSession(), transport))
		{
			WapiNodeDescriptionDumpExample.dump(client, "ch.1", 0);
		}

		final var requests = transport.getSentPackets();
		assertEquals(1, requests.size(), "Depth 0 must not request the children of the listed nodes");
		final var ch1Hash = new WapiToken("ch.1").hashBytes();
		// d7 <ch.1> dd
		assertArrayEquals(new byte[] {
				(byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, ch1Hash[0], ch1Hash[1], ch1Hash[2], ch1Hash[3],
				(byte) WapiBinaryStreamCodec.TOKEN_REQUEST_NODE_DEF }, channelCodec.decode(requests.get(0)).get(0).payload());
		assertArrayEquals(new String[] {
				"in [NODE, flags=0x0000, hash=0x" + hex(inHash) + "] - INPUT",
				"  raw=" + toHex(in)
		}, lines());
	}

	@Test
	void namesNumberedNodeByItsIndex()
	{
		// Captured from a console: ch.1 has an empty name, index 1 and long name "1"
		final byte[] ch1 = WapiBinaryDataCodec.decodeHex("df000f70b10139878392cf00010001310000", 256);
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, definitionResponse(ch1)));

		try (var client = new WapiClient(new WapiSession(), transport))
		{
			WapiNodeDescriptionDumpExample.dump(client, "ch", 0);
		}

		assertArrayEquals(new String[] {
				"1 [NODE, flags=0x0000, hash=0x878392CF] - 1",
				"  raw=" + toHex(ch1)
		}, lines());
	}

	@Test
	void namesDynamicParameterByIndexAndModelName()
	{
		// Captured from a console: ch.3.dyn.2 is named "ratio" by the loaded dynamics model
		final byte[] ratio = WapiBinaryDataCodec.decodeHex("df00b5290203c6fef61e36000205726174696f05524154494f006000113f8ccccd05312e313a313f99999a05312e323a313fa6666605312e333a313fc0000005312e353a313fd9999a05312e373a314000000003323a314020000005322e353a314040000003333a314060000005332e353a314080000003343a3140a0000003353a3140c0000003363a314100000003383a31412000000431303a3141a000000432303a31424800000435303a3142c80000053130303a31", 1024);
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, definitionResponse(ratio)));

		try (var client = new WapiClient(new WapiSession(), transport))
		{
			WapiNodeDescriptionDumpExample.dump(client, "ch.3.dyn", 0);
		}

		assertEquals("2 (ratio) [FLOAT_ENUM, flags=0x0060, hash=0xFEF61E36] - RATIO", lines()[0]);
		assertTrue(lines()[1].startsWith("  enum=[1.1 (1.1:1), 1.2 (1.2:1), 1.3 (1.3:1)"), lines()[1]);
	}

	@Test
	void reportsHashThatDiffersFromNameHash()
	{
		final byte[] cfg = nodeDefinition(1, 0x01020304, 0, "cfg", "Configuration", 0);
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, definitionResponse(cfg)));

		try (var client = new WapiClient(new WapiSession(), transport))
		{
			WapiNodeDescriptionDumpExample.dump(client, "", 0);
		}

		assertEquals("cfg [NODE, flags=0x0000, hash=0x01020304] - Configuration (hash of \"cfg\" is 0x" + hex(CFG_HASH) + ")",
				lines()[0]);
	}

	@Test
	void printsUnparsableReplyAndContinuesWithTheNextNode()
	{
		final int busHash = new WapiToken("bus").hash();
		final byte[] cfg = nodeDefinition(0, CFG_HASH, 0, "cfg", "Configuration", 0);
		final byte[] bus = nodeDefinition(0, busHash, 0, "bus", "BUS", 0);
		// df 0007 00000000 0000: the length leaves no room for the hash
		final byte[] malformed = WapiBinaryDataCodec.decodeHex("df000700000000000000", 16);
		final var root = new ByteArrayOutputStream();
		write(root, cfg);
		write(root, definitionResponse(bus));
		final var channelCodec = new WapiNativeChannelCodec();
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, root.toByteArray()));
		// The children of cfg, then those of bus (none)
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, definitionResponse(malformed)));
		transport.enqueueReply(channelCodec.encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_END_DATA }));

		try (var client = new WapiClient(new WapiSession(), transport))
		{
			WapiNodeDescriptionDumpExample.dump(client, "", 1);
		}

		assertArrayEquals(new String[] {
				"cfg [NODE, flags=0x0000, hash=0x" + hex(CFG_HASH) + "] - Configuration",
				"  raw=" + toHex(cfg),
				"  <error: Unparsable node definition: df000700000000000000>",
				"bus [NODE, flags=0x0000, hash=0x" + hex(busHash) + "] - BUS",
				"  raw=" + toHex(bus)
		}, lines());
	}

	private static String[] lines()
	{
		return LoggedText.of(WapiNodeDescriptionDumpExample.class).trim().split("\\R");
	}

	private static String hex(final int hash)
	{
		return String.format("%08X", hash);
	}

	private static String toHex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}

	private static byte[] definitionResponse(final byte[] definition)
	{
		final byte[] response = Arrays.copyOf(definition, definition.length + 1);
		response[response.length - 1] = (byte) WapiBinaryStreamCodec.TOKEN_END_DATA;
		return response;
	}

	private static byte[] nodeDefinition(final int parent, final int hash, final int index,
			final String name, final String longName, final int flags)
	{
		return nodeDefinition(parent, hash, index, name, longName, flags, new byte[0]);
	}

	private static byte[] nodeDefinition(final int parent, final int hash, final int index,
			final String name, final String longName, final int flags, final byte[] metadata)
	{
		final byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
		final byte[] longNameBytes = longName.getBytes(StandardCharsets.US_ASCII);
		final var bodyLength = 14 + nameBytes.length + longNameBytes.length + metadata.length;
		final var bytes = new ByteArrayOutputStream();
		bytes.write(WapiBinaryStreamCodec.TOKEN_NODE_DEF_RESPONSE);
		bytes.write(bodyLength >>> 8);
		bytes.write(bodyLength);
		write(bytes, ByteUtil.toBytes(parent));
		write(bytes, ByteUtil.toBytes(hash));
		write(bytes, ByteUtil.toBytes((short) index));
		bytes.write(nameBytes.length);
		write(bytes, nameBytes);
		bytes.write(longNameBytes.length);
		write(bytes, longNameBytes);
		write(bytes, ByteUtil.toBytes((short) flags));
		write(bytes, metadata);
		return bytes.toByteArray();
	}

	private static void write(final ByteArrayOutputStream output, final byte[] bytes)
	{
		output.write(bytes, 0, bytes.length);
	}

}