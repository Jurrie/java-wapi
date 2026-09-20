package org.jurr.java.wapi.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.jurr.java.wapi.client.transport.ScriptedWapiTransport;
import org.jurr.java.wapi.node.WapiNodeDefinition;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiBinaryStreamCodec;
import org.jurr.java.wapi.protocol.WapiEncoder;
import org.jurr.java.wapi.protocol.WapiNativeChannelCodec;
import org.jurr.java.wapi.protocol.WapiPacket;

class WapiNodeDefinitionsTest
{
	private static final WapiToken FLT = new WapiToken("ch.1.flt");
	/** Captured reply to d7 ea0b9382 dd: the definitions of the children of ch.1.flt, then de. */
	private static final String FLT_REPLY = """
			df001fea0b9382ae9ed8af0000026c63074c4f572043555400400000000000000001
			df0024ea0b9382401e6cf60000036c6366074c432046524551002441a0000044fa000100000281
			df004dea0b9382d82e0a590000036c6373084c4320534c4f504500500004013608362064422f4f63740231320931322064422f4f63740231380931382064422f4f63740232340932342064422f4f6374
			df0020ea0b9382826f3994000002686308484947482043555400400000000000000001
			df0024ea0b9382a04265890000036863660748432046524551002442480000469c400100000341
			df0033ea0b93824b51a99500000368637308484320534c4f504500500002013608362064422f4f63740231320931322064422f4f6374
			df0021ea0b93821645ae3b000002746609544f4f4c2046494c5400400000000000000001
			df0048ea0b9382104ec6780000036d646c09544f4f4c2054595045005000040454494c540754494c54204551034d4158054d41584552034150310541502039300341503206415020313830
			df0022ea0b93824bc3ed0800010474696c740454494c540011c0c0000040c0000000000031de""";
	/** Captured reply to da dd: the definitions of the top-level nodes, then de. */
	private static final String ROOT_REPLY = """
			df00180000000097a0043900000524737461740553544154450000df001100000000edca7af9000003636667000000
			df001500000000f89818a600000724737973636667000000df001300000000294f7794000002696f03492f4f0000
			df00170000000070b101390000026368074348414e4e454c0000df001c000000008fa3078d0000036175780b415558204348414e4e454c0000
			df001400000000f46c185e000003627573034255530000df00160000000004d3a3a80000046d61696e044d41494e0000
			df001700000000f82a5af20000036d7478064d41545249580000df001400000000e313aeff000003646361034443410000
			df001c00000000d252398b0000046d6772700a4d5554452047524f55500000df001700000000473c9134000002667807454646454354530000
			df002200000000b4296fc900000563617264730f455850414e53494f4e2043415244530000df00180000000057297a28000004706c617906504c415945520000
			df001900000000fab1762c000003726563085245434f524445520000df001900000000cbb951430000042463746c07434f4e54524f4c0000
			df001600000000d75f364200000824676c6f62616c73000000de""";

	@Test
	void returnsTheChildrenOfACapturedNode()
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex(FLT_REPLY, 1024)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var definitions = client.getNodeDefinitions(FLT);

			assertEquals(List.of("lc", "lcf", "lcs", "hc", "hcf", "hcs", "tf", "mdl", "tilt"), names(definitions));
			for (final var definition : definitions)
			{
				assertEquals(Integer.toUnsignedLong(FLT.hash()), definition.parentHash());
			}
			// d7 ea0b9382 dd
			assertArrayEquals(new WapiEncoder().encodeNativeDefinitionRequest(FLT), sent(transport, 0));
		}
	}

	@Test
	void returnsTheTopLevelNodes()
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex(ROOT_REPLY, 1024)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var definitions = client.getNodeDefinitions();

			assertEquals(List.of("$stat", "cfg", "$syscfg", "io", "ch", "aux", "bus", "main", "mtx", "dca", "mgrp", "fx", "cards", "play", "rec",
					"$ctl", "$globals"), names(definitions));
			assertEquals(0L, definitions.get(0).parentHash());
			// da dd
			assertArrayEquals(new WapiEncoder().encodeRootDefinitionRequest(), sent(transport, 0));
		}
	}

	@Test
	void readsAReplySplitAcrossPacketsWithTheChannelSelectorSentOnce()
	{
		final byte[] reply = WapiBinaryDataCodec.decodeHex(FLT_REPLY, 1024);
		final var transport = new ScriptedWapiTransport();
		// dfd1 <first 40 bytes> | <next 100 bytes> | <rest>: like the console, only the first packet selects the channel
		transport.enqueueReply(frame(Arrays.copyOfRange(reply, 0, 40)));
		transport.enqueueResponse(new WapiPacket(Arrays.copyOfRange(reply, 40, 140)));
		transport.enqueueResponse(new WapiPacket(Arrays.copyOfRange(reply, 140, reply.length)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			assertEquals(9, client.getNodeDefinitions(FLT).size());
		}
	}

	@Test
	void deliversEventsReceivedBetweenDefinitions()
	{
		final var mute = new WapiToken("ch.2.mute");
		final byte[] hash = mute.hashBytes();
		final byte[] reply = WapiBinaryDataCodec.decodeHex(FLT_REPLY, 1024);
		final var transport = new ScriptedWapiTransport();
		// <first definition> d7 <ch.2.mute> 01 <other definitions>
		final var withEvent = new byte[reply.length + 6];
		System.arraycopy(reply, 0, withEvent, 0, 34);
		System.arraycopy(new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_NODE_HASH, hash[0], hash[1], hash[2], hash[3], WapiBinaryStreamCodec.TOKEN_TRUE }, 0, withEvent, 34, 6);
		System.arraycopy(reply, 34, withEvent, 40, reply.length - 34);
		transport.enqueueReply(frame(withEvent));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getTokenRegistry().register(mute);

			assertEquals(9, client.getNodeDefinitions(FLT).size());

			final var events = client.wGetParsedEvents(2, Duration.ZERO);
			assertEquals(1, events.size());
			assertEquals(mute, events.get(0).token());
		}
	}

	@Test
	void returnsNothingForANodeWithoutChildren()
	{
		final var transport = new ScriptedWapiTransport();
		// de
		transport.enqueueReply(frame(new byte[] { (byte) WapiBinaryStreamCodec.TOKEN_END_DATA }));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			assertTrue(client.getNodeDefinitions(FLT).isEmpty());
		}
	}

	@Test
	void rejectsAParameterLikeWGetNodeToTVArray()
	{
		final var fader = new WapiToken("ch.1.fdr");
		final var transport = new ScriptedWapiTransport();
		// df 0016 <ch.1> <ch.1.fdr> 0000 03 666472 05 4641444552 0031 de: the console answers with the parameter's own definition
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex("df0016" + hex(new WapiToken("ch.1").hashBytes()) + hex(fader.hashBytes())
				+ "0000036664720546414445520031de", 64)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var exception = assertThrows(WapiException.class, () -> client.getNodeDefinitions(fader));

			assertEquals(WapiError.WNODE, exception.getError());
		}
	}

	@Test
	void reportsAnUnparsableDefinitionWithItsBytes()
	{
		// df 0007 00000000 0000: the length leaves no room for the hash
		final var malformed = "df000700000000000000";
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex(malformed + "de", 64)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var exception = assertThrows(WapiException.class, () -> client.getNodeDefinitions(FLT));

			assertEquals(WapiError.WNODE, exception.getError());
			assertTrue(exception.getMessage().contains(malformed), exception.getMessage());
		}
	}

	@Test
	void reportsAReplyWithoutEndOfData()
	{
		final byte[] reply = WapiBinaryDataCodec.decodeHex(FLT_REPLY, 1024);
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(frame(Arrays.copyOf(reply, reply.length - 1)));
		transport.enqueueTimeout();
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var timeout = Duration.ofMillis(50);
			final var exception = assertThrows(WapiException.class, () -> client.getNodeDefinitions(FLT, timeout));

			assertEquals(WapiError.WRECV_ERROR, exception.getError());
		}
	}

	@Test
	void registersTheFullNamesOfTheChildren()
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex(FLT_REPLY, 1024)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getNodeDefinitions(FLT);

			assertEquals("ch.1.flt.lc", client.getTokenRegistry().findByHash(new WapiToken("ch.1.flt.lc").hash()).name());
			// The tilt parameter is addressed by its index
			assertEquals("ch.1.flt.1", client.getTokenRegistry().findByHash(new WapiToken("ch.1.flt.1").hash()).name());
		}
	}

	@Test
	void registersTheTopLevelNames()
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex(ROOT_REPLY, 1024)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getNodeDefinitions();

			assertEquals("ch", client.getTokenRegistry().findByHash(new WapiToken("ch").hash()).name());
			assertEquals("$globals", client.getTokenRegistry().findByHash(new WapiToken("$globals").hash()).name());
		}
	}

	@Test
	void findsTheNameOfAParentGivenByHashInTheRegistry()
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex(FLT_REPLY, 1024)));
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex(FLT_REPLY, 1024)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			final var byHash = new WapiToken(FLT.hash());

			// Unknown parent name: nothing is registered
			client.getNodeDefinitions(byHash);
			assertNull(client.getTokenRegistry().findByHash(new WapiToken("ch.1.flt.lc").hash()));

			// As during a walk, where the parent was registered one level up
			client.getTokenRegistry().register(FLT);
			client.getNodeDefinitions(byHash);
			assertEquals("ch.1.flt.lc", client.getTokenRegistry().findByHash(new WapiToken("ch.1.flt.lc").hash()).name());
		}
	}

	@Test
	void doesNotRegisterANameWhoseHashDiffers()
	{
		final var transport = new ScriptedWapiTransport();
		// df 0011 00000000 01020304 0000 03 636667 00 0000 de: "cfg" with a hash that is not the hash of "cfg"
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex("df00110000000001020304000003636667000000de", 64)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getNodeDefinitions();

			assertNull(client.getTokenRegistry().findByHash(0x01020304));
			assertNull(client.getTokenRegistry().findByName("cfg"));
		}
	}

	@Test
	void namesTheValuesOfWGetNodeAfterAWalk()
	{
		final var transport = new ScriptedWapiTransport();
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex(FLT_REPLY, 1024)));
		// Captured reply to the data request for ch.1.flt (see WapiGetNodeTest)
		transport.enqueueReply(frame(WapiBinaryDataCodec.decodeHex("d7ae9ed8af01d7401e6cf6d541e89b02d7d82e0a59813234d7826f399401d7a0426589d5460a7fa2"
				+ "d74b51a9958036d71645ae3b00d7104ec6788354494c54d74bc3ed08d500000000de", 256)));
		try (var client = new WapiClient(new WapiSession(), transport))
		{
			client.getNodeDefinitions(FLT);

			// The output of C wGetNode for the same console state, without registering any names by hand
			assertEquals(".lc=1,lcf=29.08,lcs=24,hc=1,hcf=8863.91,hcs=6,tf=0,mdl=TILT,1=0.00,\n", client.wGetNode(FLT));
		}
	}

	private static WapiPacket frame(final byte[] payload)
	{
		return new WapiNativeChannelCodec().encode(WapiNativeChannelCodec.NRP_AUDIO_ENGINE_CHANNEL_ID, payload);
	}

	private static byte[] sent(final ScriptedWapiTransport transport, final int index)
	{
		return new WapiNativeChannelCodec().decode(transport.getSentPackets().get(index)).get(0).payload();
	}

	private static List<String> names(final List<WapiNodeDefinition> definitions)
	{
		final var names = new ArrayList<String>();
		for (final var definition : definitions)
		{
			names.add(definition.name());
		}
		return names;
	}

	private static String hex(final byte[] bytes)
	{
		return HexFormat.of().formatHex(bytes);
	}
}
